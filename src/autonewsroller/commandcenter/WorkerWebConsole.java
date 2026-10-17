package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.util.Json;
import com.sun.net.httpserver.*;
import java.awt.Desktop;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loopback-only management server. The worker owns claims, leases, processing and callbacks.
 * The HTTP service never claims a job and survives disconnections and graceful worker stops.
 */
public final class WorkerWebConsole implements AutoCloseable {
    private static final int MAX_BODY=16384,MAX_SUBSCRIBERS=12,MAX_JOBS=100;
    private final Path root,preferencesFile,historyFile;
    private final NewsConfig cfg;
    private final String token,defaultController,defaultWorkerId;
    private final Object lock=new Object();
    private final ExecutorService workers=Executors.newSingleThreadExecutor(r->new Thread(r,"worker-management"));
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"worker-metrics-sampler"));
    private final ExecutorService httpThreads=Executors.newFixedThreadPool(20,r->new Thread(r,"worker-console-http"));
    private final Set<BlockingQueue<String>> subscribers=ConcurrentHashMap.newKeySet();
    private final Semaphore sseSlots=new Semaphore(MAX_SUBSCRIBERS);
    private final Deque<Map<String,Object>> logs=new ArrayDeque<>(),jobs=new ArrayDeque<>();
    private final AtomicBoolean diagnosticRunning=new AtomicBoolean();
    private volatile Map<String,Object> metrics=Map.of("status","measuring"),diagnostics=Map.of("status","NOT CHECKED","results",List.of());
    private volatile long metricsAt=0;
    private volatile Map<String,Object> preferences;
    private volatile Path currentProductionDir;
    private final Map<Path,Long> tailOffsets=new ConcurrentHashMap<>();
    private volatile String lifecycle="STOPPED",lastIssue="";
    private volatile boolean restartRequested;
    private volatile RemoteWorker worker;
    private volatile Thread workerThread;
    private volatile HttpServer server;
    private volatile int port;
    private final Instant startedAt=Instant.now();

    public WorkerWebConsole(Path root,NewsConfig cfg,String controller,String workerId,String token)throws IOException{
        this.root=root;this.cfg=cfg;this.defaultController=controller;this.defaultWorkerId=workerId;
        this.token=token==null?"":token;
        this.preferencesFile=root.resolve("output/runtime/worker-ui-settings.json");
        this.historyFile=root.resolve("output/runtime/worker-ui-jobs.json");
        Map<String,Object> initial=new LinkedHashMap<>();
        initial.put("controllerUrl",controller);initial.put("workerId",workerId);initial.put("port",8788);
        initial.put("autoStart",true);initial.put("autoOpenBrowser",true);
        initial.put("logLimit",500);initial.put("refreshSeconds",5);
        if(Files.isRegularFile(preferencesFile)){
            try{
                Map<String,Object> saved=Json.object(Json.read(preferencesFile));
                validateSettings(saved);
                initial.putAll(saved);
            }catch(Exception e){System.err.println("Invalid worker UI settings ignored: "+e.getMessage());}
        }
        preferences=Map.copyOf(initial);
        if(Files.isRegularFile(historyFile))try{
            for(Object entry:Json.array(Json.read(historyFile))){
                if(entry instanceof Map<?,?>){
                    Map<String,Object> prior=new LinkedHashMap<>(Json.object(entry));
                    if("RUNNING".equals(prior.get("result")))prior.put("result","UNKNOWN");
                    jobs.addLast(prior);
                }
                if(jobs.size()>=MAX_JOBS)break;
            }
        }catch(Exception e){System.err.println("Worker job ledger could not be read: "+e.getMessage());}
    }
    public int start()throws IOException{
        port=((Number)preferences.get("port")).intValue();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",this::handle);
        server.setExecutor(httpThreads);server.start();
        log("INFO","console","Listening at http://127.0.0.1:"+port);
        sampler.scheduleWithFixedDelay(()->{
            try{metrics=SystemMetrics.snapshot();metricsAt=System.currentTimeMillis();tailProductionLogs();}
            catch(Throwable t){log("WARN","telemetry","Sampling failed: "+t.getClass().getSimpleName());}
        },0,6,TimeUnit.SECONDS);
        if(Boolean.TRUE.equals(preferences.get("autoStart")))control("start");
        if(Boolean.TRUE.equals(preferences.get("autoOpenBrowser")))openBrowser();
        return port;
    }
    private void openBrowser(){
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return;
        try{if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(URI.create("http://127.0.0.1:"+port));}
        catch(Exception e){log("WARN","console","Browser could not be opened automatically; use the printed local URL");}
    }
    public void block()throws InterruptedException{new CountDownLatch(1).await();}
    @Override public void close(){
        synchronized(lock){restartRequested=false;if(worker!=null)worker.drain();}
        if(server!=null)server.stop(1);
        sampler.shutdownNow();workers.shutdown();httpThreads.shutdownNow();
        // Do not interrupt an owned render. A live non-daemon worker thread will finish its claim.
    }
    public int port(){return port;}

    private Map<String,Object> workerStatus(){
        RemoteWorker current=worker;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("lifecycle",lifecycle);out.put("uptimeSeconds",Duration.between(startedAt,Instant.now()).getSeconds());
        out.put("controllerUrl",current==null?preferences.get("controllerUrl"):current.snapshot().get("controllerUrl"));
        out.put("workerId",current==null?preferences.get("workerId"):current.snapshot().get("workerId"));
        out.put("lastIssue",lastIssue);out.put("pendingRestart",restartRequested);
        out.put("controllerCredentialConfigured",!token.isBlank());
        if(current!=null){
            Map<String,Object> s=current.snapshot();out.putAll(s);
            if("DRAINING".equals(lifecycle))out.put("lifecycle","DRAINING");
            else if(current.isPaused())out.put("lifecycle","PAUSED");
            else if(Boolean.TRUE.equals(s.get("claimActive")))out.put("lifecycle","POLITICAL_ANALYSIS".equals(s.get("jobType"))?"RUNNING_ANALYSIS":"RUNNING_VIDEO");
            else if(!Boolean.TRUE.equals(s.get("connected")))out.put("lifecycle","DISCONNECTED");
            else if("STARTING".equals(lifecycle))out.put("lifecycle","CONNECTING");
            else out.put("lifecycle","IDLE");
        }else{
            out.put("connected",false);out.put("running",false);out.put("paused",false);
            out.put("draining",false);out.put("currentJob","");out.put("currentTopic","");
        }
        return out;
    }
    private Map<String,Object> metricsStatus(){
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("sample",metrics);out.put("sampledAt",metricsAt==0?null:Instant.ofEpochMilli(metricsAt).toString());
        out.put("ageSeconds",metricsAt==0?null:(System.currentTimeMillis()-metricsAt)/1000);
        out.put("stale",metricsAt==0||System.currentTimeMillis()-metricsAt>18000);
        return out;
    }
    private Map<String,Object> status(){
        return Map.of("worker",workerStatus(),"metrics",metricsStatus(),
                "diagnostics",diagnostics.getOrDefault("status","NOT CHECKED"),
                "controllerUiUrl",String.valueOf(workerStatus().get("controllerUrl")));
    }
    public Map<String,Object> control(String action){
        synchronized(lock){
            boolean alive=workerThread!=null&&workerThread.isAlive();
            switch(action){
                case "start"->{
                    if(alive)throw new IllegalStateException("Worker is already running");
                    restartRequested=false;launch();return Map.of("accepted",true,"state","STARTING");
                }
                case "pause"->{
                    if(!alive||worker==null||worker.isDraining())throw new IllegalStateException("Worker not available for pause");
                    worker.pause();lifecycle="PAUSED";return Map.of("accepted",true,"state","PAUSED");
                }
                case "resume"->{
                    if(!alive||worker==null||worker.isDraining())throw new IllegalStateException("Worker not available for resume");
                    worker.resume();lifecycle="CONNECTING";return Map.of("accepted",true,"state","RESUMING");
                }
                case "stop","drain"->{
                    if(!alive||worker==null)return Map.of("accepted",true,"state","STOPPED");
                    restartRequested=false;worker.drain();lifecycle="DRAINING";
                    return Map.of("accepted",true,"state","DRAINING");
                }
                case "restart"->{
                    if(!alive){launch();return Map.of("accepted",true,"state","STARTING");}
                    restartRequested=true;worker.drain();lifecycle="DRAINING";
                    return Map.of("accepted",true,"state","DRAINING","next","RESTART");
                }
                default->throw new IllegalArgumentException("Unknown worker control");
            }
        }
    }
    private void launch(){
        String endpoint=String.valueOf(preferences.get("controllerUrl"));
        String id=String.valueOf(preferences.get("workerId"));
        worker=new RemoteWorker(root,cfg,endpoint,id,token,this::onWorkerEvent,()->metrics);
        RemoteWorker instance=worker;
        lifecycle="STARTING";lastIssue="";
        workerThread=new Thread(()->{
            try{instance.runForever();}
            catch(Throwable e){lastIssue=safe(e.getMessage());lifecycle="ERROR";log("ERROR","worker","Lifecycle crashed: "+lastIssue);}
            finally{
                synchronized(lock){
                    if(worker==instance){worker=null;workerThread=null;
                        if(!"ERROR".equals(lifecycle))lifecycle="STOPPED";
                        if(restartRequested){restartRequested=false;launch();}
                    }
                }
            }
        },"remote-gpu-worker");
        workerThread.start();
    }
    private void onWorkerEvent(Map<String,Object> e){
        String type=String.valueOf(e.get("type"));
        String detail=String.valueOf(e.getOrDefault("detail",e.getOrDefault("message",type)));
        if("job-dir".equals(type)){
            try{
                Path base=root.resolve("output/remote_worker").toAbsolutePath().normalize();
                Path dir=Path.of(String.valueOf(e.get("dir"))).toAbsolutePath().normalize();
                if(dir.startsWith(base)){currentProductionDir=dir;tailOffsets.clear();}
            }catch(Exception ignored){}
        }else if("job-start".equals(type)){
            Map<String,Object> entry=new LinkedHashMap<>(e);
            entry.put("startedAt",e.get("at"));entry.put("result","RUNNING");entry.remove("claimIdPresent");
            synchronized(jobs){jobs.addFirst(entry);while(jobs.size()>MAX_JOBS)jobs.removeLast();}
            log("INFO","claim",String.valueOf(e.get("jobId"))+" "+e.get("topic"));
        }else if("job-finish".equals(type)){
            synchronized(jobs){
                for(Map<String,Object> j:jobs){
                    if(Objects.equals(j.get("jobId"),e.get("jobId"))&&"RUNNING".equals(j.get("result"))){
                        j.put("result",e.get("result"));j.put("endedAt",e.get("at"));
                        j.put("lastStage",e.getOrDefault("stage",""));j.put("lastError",e.getOrDefault("error",""));
                        j.put("outputPath",e.getOrDefault("outputPath",""));break;
                    }
                }
                try{Json.write(historyFile,new ArrayList<>(jobs));}catch(Exception ex){log("WARN","history","Could not persist worker job ledger");}
            }
            log("INFO","job",String.valueOf(e.get("jobId"))+" "+e.get("result"));
        }else if("error".equals(type)){
            lastIssue=detail;log("ERROR",String.valueOf(e.getOrDefault("component","worker")),detail);
        }else if("progress".equals(type)){
            synchronized(jobs){
                for(Map<String,Object> j:jobs){
                    if("RUNNING".equals(j.get("result"))){
                        j.put("lastStage",e.getOrDefault("stage",""));
                        j.put("lastDetail",detail);break;
                    }
                }
            }
            log("INFO",String.valueOf(e.getOrDefault("stage","pipeline")),detail);
        }
        else if("lifecycle".equals(type))log("INFO","lifecycle",String.valueOf(e.get("state")));
        broadcast(e);
    }
    /** Incremental, bounded reads of two explicitly worker-owned production log files. */
    private void tailProductionLogs(){
        Path dir=currentProductionDir;
        if(dir==null)return;
        for(Path file:List.of(dir.resolve("debug.log"),dir.resolve("runtime/worker_001.log"))){
            if(!Files.isRegularFile(file))continue;
            try(RandomAccessFile input=new RandomAccessFile(file.toFile(),"r")){
                long length=input.length(),start=tailOffsets.getOrDefault(file,0L);
                if(start>length)start=0;
                // Bound each read, even after a worker crashes and leaves a large log.
                if(length-start>32768)start=length-32768;
                long count=Math.min(16384,length-start);
                if(count<=0)continue;
                byte[] bytes=new byte[(int)count];
                input.seek(start);input.readFully(bytes);
                tailOffsets.put(file,start+count);
                String chunk=new String(bytes,StandardCharsets.UTF_8);
                String[] lines=chunk.split("\\R",-1);
                int from=Math.max(0,lines.length-40);
                for(int i=from;i<lines.length;i++){
                    String line=lines[i].trim();
                    if(!line.isEmpty())log("INFO",file.getFileName().toString(),line);
                }
            }catch(Exception e){log("WARN","log-tail","Production log unavailable: "+e.getClass().getSimpleName());}
        }
    }
    private void log(String level,String component,String message){
        Map<String,Object> entry=Map.of("at",Instant.now().toString(),"severity",level,
                "component",component,"message",safe(message));
        synchronized(logs){
            logs.addLast(entry);
            int max=((Number)preferences.get("logLimit")).intValue();
            while(logs.size()>max)logs.removeFirst();
        }
        broadcast(Map.of("type","log","entry",entry));
    }
    private static String safe(String x){
        if(x==null)return "";
        String s=x.replaceAll("(?i)(token|password|authorization|api[-_]?key)\\s*[:=]\\s*\\S+","$1=[REDACTED]").replaceAll("(?i)Bearer\\s+[A-Za-z0-9._-]+","Bearer [REDACTED]");
        return s.length()>1300?s.substring(0,1300):s;
    }
    private void broadcast(Map<String,Object> event){
        String data=Json.stringify(event);
        for(BlockingQueue<String> q:subscribers){if(!q.offer(data)){q.poll();q.offer(data);}}
    }
    private void handle(HttpExchange x)throws IOException{
        try{
            String host=x.getRequestHeaders().getFirst("Host");
            String valid="127.0.0.1:"+port,alt="localhost:"+port;
            if(host==null||!(host.equalsIgnoreCase(valid)||host.equalsIgnoreCase(alt))){
                json(x,403,Map.of("error","Invalid local host"));return;
            }
            String origin=x.getRequestHeaders().getFirst("Origin");
            if(origin!=null&&!(origin.equalsIgnoreCase("http://"+valid)||origin.equalsIgnoreCase("http://"+alt))){
                json(x,403,Map.of("error","Origin not permitted"));return;
            }
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(path.equals("/api/worker/events")){if(!method.equals("GET")){json(x,405,Map.of("error","GET only"));return;}events(x);return;}
            if(path.startsWith("/api/worker/")){
                if(method.equals("GET")){switch(path){
                    case "/api/worker/health"->json(x,200,Map.of("ok",true,"service","worker-console"));
                    case "/api/worker/status"->json(x,200,status());
                    case "/api/worker/metrics"->json(x,200,metricsStatus());
                    case "/api/worker/jobs"->{List<Map<String,Object>> copy; synchronized(jobs){copy=new ArrayList<>(jobs);}json(x,200,Map.of("jobs",copy));}
                    case "/api/worker/logs"->{List<Map<String,Object>> copy; synchronized(logs){copy=new ArrayList<>(logs);}json(x,200,Map.of("logs",copy));}
                    case "/api/worker/diagnostics"->json(x,200,diagnostics);
                    case "/api/worker/settings"->json(x,200,settingsView());
                    default->json(x,404,Map.of("error","No endpoint"));
                }return;}
                if(!method.equals("POST")){json(x,405,Map.of("error","Method not allowed"));return;}
                if(!"1".equals(x.getRequestHeaders().getFirst("X-Worker-Request"))||
                   !String.valueOf(x.getRequestHeaders().getFirst("Content-Type")).startsWith("application/json")){
                    json(x,403,Map.of("error","Invalid local request"));return;
                }
                byte[] body=x.getRequestBody().readNBytes(MAX_BODY+1);
                if(body.length>MAX_BODY){json(x,413,Map.of("error","Body too large"));return;}
                Map<String,Object> payload;
                try{payload=Json.object(Json.parse(new String(body,StandardCharsets.UTF_8)));}
                catch(Exception ex){json(x,400,Map.of("error","Invalid JSON"));return;}
                switch(path){
                    case "/api/worker/control"->{
                        try{json(x,202,control(String.valueOf(payload.getOrDefault("action",""))));}
                        catch(IllegalArgumentException ex){json(x,400,Map.of("error",ex.getMessage()));}
                        catch(IllegalStateException ex){json(x,409,Map.of("error",ex.getMessage()));}
                    }
                    case "/api/worker/settings"->{
                        try{updateSettings(payload);json(x,200,settingsView());}
                        catch(IllegalArgumentException ex){json(x,400,Map.of("error",ex.getMessage()));}
                    }
                    case "/api/worker/diagnostics/run"->{
                        String mode=String.valueOf(payload.getOrDefault("mode","quick"));
                        if(!Set.of("quick","deep").contains(mode)){json(x,400,Map.of("error","Unknown diagnostic mode"));return;}
                        if(!diagnosticRunning.compareAndSet(false,true)){json(x,409,Map.of("error","Diagnostics already running"));return;}
                        diagnostics=Map.of("status","RUNNING","mode",mode,"results",List.of());
                        workers.execute(()->{
                            try{diagnose(mode.equals("deep"));}
                            catch(Throwable failure){diagnostics=Map.of("status","ERROR","error",safe(failure.getMessage()),"results",List.of());log("ERROR","diagnostics","Check failed");}
                            finally{diagnosticRunning.set(false);}
                        });
                        json(x,202,Map.of("accepted",true,"state","RUNNING","mode",mode));
                    }
                    default->json(x,404,Map.of("error","No endpoint"));
                }
                return;
            }
            if(!method.equals("GET")){json(x,405,Map.of("error","GET only"));return;}
            staticFile(x,path);
        }catch(Throwable t){
            log("ERROR","http",t.getClass().getSimpleName());
            try{json(x,500,Map.of("error","Internal worker console error"));}catch(Exception ignored){}
        }finally{x.close();}
    }
    private Map<String,Object> settingsView(){
        Map<String,Object> view=new LinkedHashMap<>(preferences);
        view.put("controllerTokenConfigured",!token.isBlank());
        view.put("controllerInheritedEncoder",cfg.get("videoEncoder","auto"));
        view.put("controllerComfy",cfg.getBool("commandCenterUseComfy",true));
        view.put("controllerRequireComfy",cfg.getBool("commandCenterRequireComfy",true));
        view.put("ttsPrimary",cfg.get("ttsPrimary","kokoro"));view.put("ttsFallback",cfg.get("ttsFallback","qwen3"));
        view.put("effectiveWorkerId",worker==null?preferences.get("workerId"):worker.snapshot().get("workerId"));
        view.put("effectiveControllerUrl",worker==null?preferences.get("controllerUrl"):worker.snapshot().get("controllerUrl"));
        view.put("portChangeNeedsAppRestart",true);
        return view;
    }
    private static void validateSettings(Map<String,Object> body){
        Set<String> allowed=Set.of("controllerUrl","workerId","port","autoStart","autoOpenBrowser","logLimit","refreshSeconds");
        for(var e:body.entrySet()){
            if(!allowed.contains(e.getKey()))throw new IllegalArgumentException("Unsupported setting: "+e.getKey());
            Object v=e.getValue();
            switch(e.getKey()){
                case "controllerUrl"->{
                    if(!(v instanceof String s)||s.length()>255)throw new IllegalArgumentException("Invalid controller URL");
                    try{URI u=URI.create(s);
                        if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||
                           u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||
                           (u.getRawPath()!=null&&!u.getRawPath().isBlank()&&!u.getRawPath().equals("/")))
                            throw new Exception();
                    }catch(Exception ex){throw new IllegalArgumentException("Controller URL must be http(s) host[:port]");}
                }
                case "workerId"->{if(!(v instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new IllegalArgumentException("Invalid worker ID");}
                case "port","logLimit","refreshSeconds"->{
                    if(!(v instanceof Number n)||n.doubleValue()!=n.intValue())throw new IllegalArgumentException("Invalid numeric setting: "+e.getKey());
                    int num=n.intValue();
                    int min=e.getKey().equals("port")?1024:e.getKey().equals("logLimit")?100:2;
                    int max=e.getKey().equals("port")?65535:e.getKey().equals("logLimit")?2000:60;
                    if(num<min||num>max)throw new IllegalArgumentException(e.getKey()+" outside "+min+"-"+max);
                }
                case "autoStart","autoOpenBrowser"->{if(!(v instanceof Boolean))throw new IllegalArgumentException("Expected boolean: "+e.getKey());}
                default->{}
            }
        }
    }
    private void updateSettings(Map<String,Object> updates){
        validateSettings(updates);
        Map<String,Object> next=new LinkedHashMap<>(preferences);next.putAll(updates);
        try{Json.write(preferencesFile,next);}
        catch(IOException e){throw new IllegalArgumentException("Settings could not be saved");}
        preferences=Map.copyOf(next);
        log("INFO","settings","Preferences saved. Worker connection changes require graceful restart; port changes require application relaunch.");
    }
    private void staticFile(HttpExchange x,String path)throws IOException{
        String name=path.equals("/")?"index.html":path.substring(1);
        if(!Set.of("index.html","app.js","styles.css").contains(name)){json(x,404,Map.of("error","Not found"));return;}
        Path file=root.resolve("web/worker-console").resolve(name);
        if(!Files.isRegularFile(file)){json(x,404,Map.of("error","Worker UI asset missing"));return;}
        byte[] data=Files.readAllBytes(file);
        String ct=name.endsWith(".html")?"text/html":name.endsWith(".css")?"text/css":"application/javascript";
        x.getResponseHeaders().set("Content-Type",ct+"; charset=utf-8");
        x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'");
        x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,data.length);
        try(OutputStream out=x.getResponseBody()){out.write(data);}
    }
    private void events(HttpExchange x)throws IOException{
        if(!sseSlots.tryAcquire()){json(x,429,Map.of("error","Too many event subscribers"));return;}
        ArrayBlockingQueue<String> q=new ArrayBlockingQueue<>(128);subscribers.add(q);
        try{
            x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Accel-Buffering","no");
            x.sendResponseHeaders(200,0);
            try(OutputStream out=x.getResponseBody()){
                while(!Thread.currentThread().isInterrupted()){
                    String msg=q.poll(15,TimeUnit.SECONDS);
                    String line=msg==null?": ping\n\n":"data: "+msg+"\n\n";
                    out.write(line.getBytes(StandardCharsets.UTF_8));out.flush();
                }
            }
        }catch(Exception ignored){}finally{subscribers.remove(q);sseSlots.release();}
    }
    private static void json(HttpExchange x,int status,Object data)throws IOException{
        byte[] body=Json.stringify(data).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
        x.sendResponseHeaders(status,body.length);try(OutputStream out=x.getResponseBody()){out.write(body);}
    }

    private void diagnose(boolean deep){
        List<Map<String,Object>> checks=new ArrayList<>();
        checks.add(check("Java 21",Runtime.version().feature()>=21?"PASS":"FAIL",System.getProperty("java.version")));
        checks.add(command("NVIDIA / nvidia-smi","nvidia-smi","-L"));
        checks.add(command("FFmpeg",cfg.get("ffmpegCommand","ffmpeg"),"-version"));
        checks.add(command("FFprobe",cfg.get("ffprobeCommand","ffprobe"),"-version"));
        checks.add(endpoint("Ollama",cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate"),"/api/tags"));
        checks.add(endpoint("ComfyUI",cfg.get("comfyUrl","http://127.0.0.1:8188"),"/system_stats"));
        checks.add(endpoint("Qwen service",cfg.get("qwenUrl","http://127.0.0.1:8765"),"/health"));
        checks.add(endpoint("Controller",String.valueOf(preferences.get("controllerUrl")),"/api/health"));
        checks.add(check("Kokoro environment",Files.isRegularFile(root.resolve(".venv-kokoro/Scripts/python.exe"))||Files.isRegularFile(root.resolve(".venv-kokoro/bin/python"))?"PASS":"WARN","Local Kokoro Python environment"));
        checks.add(check("Qwen environment",Files.isRegularFile(root.resolve(".venv-qwen3-tts/Scripts/python.exe"))||Files.isRegularFile(root.resolve(".venv-qwen3-tts/bin/python"))?"PASS":"WARN","Local Qwen Python environment"));
        checks.add(check("Kokoro helper",Files.isRegularFile(root.resolve("tools/kokoro_tts.py"))?"PASS":"FAIL","tools/kokoro_tts.py"));
        checks.add(check("Qwen helper",Files.isRegularFile(root.resolve("tools/qwen3_tts.py"))?"PASS":"FAIL","tools/qwen3_tts.py"));
        checks.add(check("Qwen service helper",Files.isRegularFile(root.resolve("tools/qwen3_tts_server.py"))?"PASS":"FAIL","tools/qwen3_tts_server.py"));
        if(deep){
            checks.add(cudaVisibility());
            checks.add(ollamaModel());
            checks.add(comfyCheckpoint());
        }
        try{
            Path output=root.resolve("output");Files.createDirectories(output);
            Path f=Files.createTempFile(output,".worker-test-",".tmp");Files.deleteIfExists(f);
            checks.add(check("Output directory","PASS","Writable"));
            checks.add(check("Free disk","PASS",String.valueOf(Files.getFileStore(output).getUsableSpace()/1048576L)+" MB available"));
        }catch(Exception ex){checks.add(check("Output directory","FAIL",safe(ex.getMessage())));}
        diagnostics=Map.of("status","DONE","mode",deep?"deep":"quick","checkedAt",Instant.now().toString(),"results",checks);
        broadcast(Map.of("type","diagnostics","status","DONE"));log("INFO","diagnostics","Dependency check complete");
    }
    private Map<String,Object> cudaVisibility(){
        Path exe=root.resolve(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?
                ".venv-qwen3-tts/Scripts/python.exe":".venv-qwen3-tts/bin/python");
        if(!Files.isRegularFile(exe))return check("PyTorch CUDA","NOT CHECKED","Qwen Python environment not installed");
        Process p=null;
        try{
            p=new ProcessBuilder(exe.toString(),"-c","import torch; print(torch.cuda.is_available())")
                    .redirectErrorStream(true).start();
            if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();return check("PyTorch CUDA","WARN","Visibility probe timed out");}
            String output=new String(p.getInputStream().readNBytes(512),StandardCharsets.UTF_8).trim();
            return check("PyTorch CUDA",p.exitValue()==0&&output.endsWith("True")?"PASS":"WARN",
                    p.exitValue()==0?"torch.cuda.is_available() = "+output:"Python probe failed");
        }catch(Exception e){return check("PyTorch CUDA","WARN","Visibility check unavailable");}
        finally{if(p!=null&&p.isAlive())p.destroyForcibly();}
    }
    private Map<String,Object> ollamaModel(){
        String model=cfg.get("ollamaModel","llama3.1:8b");
        try{
            String base=cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate");
            Map<String,Object> response=jsonProbe(base,"/api/tags");
            for(Object o:Json.array(response.getOrDefault("models",List.of())))
                if(o instanceof Map<?,?> m&&model.equals(String.valueOf(m.get("name"))))
                    return check("Ollama model","PASS",model+" present");
            return check("Ollama model","WARN",model+" not reported in local model list");
        }catch(Exception e){return check("Ollama model","WARN","Model inventory unavailable");}
    }
    private Map<String,Object> comfyCheckpoint(){
        String checkpoint=cfg.get("imageCheckpoint","");
        if(checkpoint.isBlank())return check("Comfy checkpoint","NOT CHECKED","No configured checkpoint");
        try{
            Map<String,Object> response=jsonProbe(cfg.get("comfyUrl","http://127.0.0.1:8188"),"/object_info/CheckpointLoaderSimple");
            Map<String,Object> loader=Json.object(response.getOrDefault("CheckpointLoaderSimple",Map.of()));
            Map<String,Object> input=Json.object(loader.getOrDefault("input",Map.of()));
            Map<String,Object> required=Json.object(input.getOrDefault("required",Map.of()));
            List<Object> choices=Json.array(required.getOrDefault("ckpt_name",List.of()));
            for(Object entry:choices){
                if(entry instanceof List<?> available&&available.contains(checkpoint))
                    return check("Comfy checkpoint","PASS",checkpoint+" present");
            }
            return check("Comfy checkpoint","WARN",checkpoint+" not found in reported checkpoints");
        }catch(Exception e){return check("Comfy checkpoint","WARN","Checkpoint inventory unavailable");}
    }
    private Map<String,Object> jsonProbe(String base,String path)throws Exception{
        URI rootUrl=URI.create(base);
        URI target=URI.create(rootUrl.getScheme()+"://"+rootUrl.getRawAuthority()+path);
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(4)).GET().build();
        HttpResponse<InputStream> response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        if(response.statusCode()!=200)throw new IOException("HTTP "+response.statusCode());
        try(InputStream in=response.body()){
            byte[] bytes=in.readNBytes(262145);
            if(bytes.length>262144)throw new IOException("Inventory response exceeds 256KiB");
            return Json.object(Json.parse(new String(bytes,StandardCharsets.UTF_8)));
        }
    }
    private static Map<String,Object> check(String name,String state,String detail){
        return Map.of("name",name,"status",state,"detail",safe(detail));
    }
    private Map<String,Object> command(String name,String... args){
        Process p=null;
        try{
            p=new ProcessBuilder(args).redirectErrorStream(true).start();
            if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();return check(name,"WARN","Timed out");}
            return check(name,p.exitValue()==0?"PASS":"FAIL","Exit "+p.exitValue());
        }catch(Exception ex){return check(name,"WARN","Not available: "+ex.getClass().getSimpleName());}
        finally{if(p!=null&&p.isAlive())p.destroyForcibly();}
    }
    private Map<String,Object> endpoint(String name,String base,String path){
        try{
            URI u=URI.create(base);
            if(u.getHost()==null)return check(name,"WARN","Invalid configured URL");
            String url=u.getScheme()+"://"+u.getRawAuthority()+path;
            HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET();
            if(name.equals("Controller")&&!token.isBlank())builder.header("X-AutoNews-Token",token);
            HttpResponse<Void> r=client.send(builder.build(),HttpResponse.BodyHandlers.discarding());
            return check(name,r.statusCode()==200?"PASS":"WARN","HTTP "+r.statusCode());
        }catch(Exception ex){return check(name,"WARN","Unreachable ("+ex.getClass().getSimpleName()+")");}
    }
}
