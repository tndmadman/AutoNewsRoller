package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.config.SourceConfig;
import autonewsroller.orchestration.NewsPipeline;
import autonewsroller.util.Json;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommandCenterServer {
    private final Path root;
    private final NewsConfig cfg;
    private final String host,token;
    private final int requestedPort,scanMinutes;
    private final boolean scanOnStart;
    private final CommandCenterStore store;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    private final CopyOnWriteArrayList<BlockingQueue<String>>subscribers=new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanInFlight=new AtomicBoolean();
    private HttpServer server;

    public CommandCenterServer(Path root,NewsConfig cfg,String host,int port,int scanMinutes,boolean autoQueue,double autoThreshold,int maxQueued,String token,boolean scanOnStart){
        this.root=root;this.cfg=cfg;this.host=host;this.requestedPort=port;this.scanMinutes=Math.max(1,scanMinutes);this.token=token==null?"":token.trim();this.scanOnStart=scanOnStart;
        BiasRegistry bias=BiasRegistry.load(root.resolve("config/source_bias.json"));
        this.store=new CommandCenterStore(
                root.resolve("data/command_center_state.json"),
                bias,
                autoQueue,
                autoThreshold,
                maxQueued,
                this::broadcast,
                cfg.getInt("commandCenterJobLeaseSeconds",75),
                cfg.getDouble("commandCenterSoftWorthThreshold",0.74),
                cfg.getDouble("commandCenterSingleSourceAutoQueueThreshold",0.82),
                cfg.getInt("commandCenterFailureMaxRetries",3),
                cfg.getInt("commandCenterFailureRetryDelaySeconds",20)
        );
    }

    public int start() throws Exception {
        server=HttpServer.create(new InetSocketAddress(host,requestedPort),0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/health",this::health);
        server.createContext("/api/state",this::state);
        server.createContext("/api/events",this::events);
        server.createContext("/api/scan",this::scanNow);
        server.createContext("/api/stories/",this::stories);
        server.createContext("/api/workers/heartbeat",this::heartbeat);
        server.createContext("/api/jobs/claim",this::claim);
        server.createContext("/api/jobs/",this::jobs);
        server.createContext("/videos/",this::videos);
        server.createContext("/",this::staticFiles);
        server.start();
        int actual=server.getAddress().getPort();
        System.out.println("AutoNewsRoller Command Center listening on http://"+displayHost()+":"+actual);
        if(!isLoopbackHost(host)&&token.isBlank())System.err.println("WARNING: command center is listening beyond localhost without an API token. Set AUTONEWS_TOKEN or --token.");
        scheduler.scheduleWithFixedDelay(this::scanSafe,scanOnStart?0:scanMinutes,scanMinutes,TimeUnit.MINUTES);
        scheduler.scheduleWithFixedDelay(store::maintenance,15,15,TimeUnit.SECONDS);
        return actual;
    }

    public void block() throws InterruptedException {new CountDownLatch(1).await();}

    public void stop(){
        scheduler.shutdownNow();if(server!=null)server.stop(1);
    }

    private void scanSafe(){
        if(!scanInFlight.compareAndSet(false,true))return;
        store.scanStarted();
        try{
            String stamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
            Path batch=root.resolve("output/command_center/scans/scan_"+stamp);
            NewsPipeline p=new NewsPipeline(root,cfg,batch);
            NewsPipeline.DiscoveryObserver observer=new NewsPipeline.DiscoveryObserver(){
                @Override public void feedStarted(SourceConfig source){store.feedStarted(source);}
                @Override public void feedSucceeded(SourceConfig source,int entries){store.feedSucceeded(source,entries);}
                @Override public void feedFailed(SourceConfig source,String reason){store.feedFailed(source,reason);}
            };
            NewsPipeline.DiscoveryResult result=p.discoverDetailed("general",cfg.maxAgeHours(),cfg.minimumIndependentSources(),false,observer);
            store.applyDiscovery(result);
        }catch(Exception e){
            e.printStackTrace();store.scanFailed(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }finally{scanInFlight.set(false);}
    }

    private void health(HttpExchange x)throws IOException{
        json(x,200,Map.of("ok",true,"service","AutoNewsRoller Command Center","time",Instant.now().toString()));
    }

    private void state(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}json(x,200,store.snapshot());
    }

    private void scanNow(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        if(scanInFlight.get()){json(x,202,Map.of("ok",true,"status","already-scanning"));return;}
        scheduler.submit(this::scanSafe);json(x,202,Map.of("ok",true,"status","scan-started"));
    }

    private void stories(HttpExchange x)throws IOException{
        if(!api(x))return;
        String rest=x.getRequestURI().getPath().substring("/api/stories/".length());
        String[]p=rest.split("/");
        if(p.length<1||p[0].isBlank()){json(x,400,Map.of("error","story id required"));return;}
        String id=decode(p[0]);
        try{
            if(p.length==1&&"GET".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>m=store.storyDetail(id);if(m==null){json(x,404,Map.of("error","story not found"));return;}json(x,200,m);return;
            }
            if(p.length>=2&&"action".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>body=bodyObject(x);String action=String.valueOf(body.getOrDefault("action",""));json(x,200,store.action(id,action));return;
            }
            if(p.length>=2&&"analyze-bias".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                json(x,202,store.queuePoliticalAnalysis(id));return;
            }
            method(x);
        }catch(IllegalStateException e){json(x,409,Map.of("error",safe(e.getMessage())));}
        catch(IllegalArgumentException e){json(x,400,Map.of("error",safe(e.getMessage())));}
    }

    private void heartbeat(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        Map<String,Object>body=bodyObject(x);String id=String.valueOf(body.getOrDefault("id","")).trim();
        if(id.isBlank()){json(x,400,Map.of("error","worker id required"));return;}
        store.heartbeat(id,body);json(x,200,Map.of("ok",true));
    }

    private void claim(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        String worker=query(x.getRequestURI()).getOrDefault("worker","").trim();
        if(worker.isBlank()){json(x,400,Map.of("error","worker query parameter required"));return;}
        Map<String,Object>settings=new LinkedHashMap<>();
        settings.put("duration",cfg.duration());settings.put("encoder",cfg.get("videoEncoder","auto"));
        settings.put("useComfy",cfg.getBool("commandCenterUseComfy",true));
        settings.put("requireComfy",cfg.getBool("commandCenterRequireComfy",true));
        settings.put("comfyImages",cfg.getInt("commandCenterComfyImages",3));
        settings.put("dryRun",false);
        Map<String,Object>job=store.claim(worker,settings);
        if(job==null){x.sendResponseHeaders(204,-1);x.close();return;}
        json(x,200,job);
    }

    private void jobs(HttpExchange x)throws IOException{
        if(!api(x))return;
        String rest=x.getRequestURI().getPath().substring("/api/jobs/".length());
        String[]p=rest.split("/");
        if(p.length<2){json(x,400,Map.of("error","job path required"));return;}
        String id=decode(p[0]),op=p[1].toLowerCase(Locale.ROOT);
        try{
            switch(op){
                case "progress"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    autonewsroller.orchestration.PipelineStage stage;
                    try{stage=autonewsroller.orchestration.PipelineStage.valueOf(String.valueOf(b.getOrDefault("stage","VERIFY")));}catch(Exception e){stage=autonewsroller.orchestration.PipelineStage.VERIFY;}
                    store.progress(id,new autonewsroller.orchestration.WorkerState(1,1,stage,String.valueOf(b.getOrDefault("detail","")),Instant.now()));
                    json(x,200,Map.of("ok",true));
                }
                case "video"->{
                    if(!"PUT".equalsIgnoreCase(x.getRequestMethod())&&!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    String filename=sanitizeFilename(x.getRequestHeaders().getFirst("X-Filename"));
                    if(filename.isBlank())filename=id+".mp4";
                    Path dir=root.resolve("output/command_center/videos");Files.createDirectories(dir);
                    Path out=unique(dir,id+"_"+filename);
                    try(InputStream in=x.getRequestBody();OutputStream os=Files.newOutputStream(out,StandardOpenOption.CREATE_NEW)){in.transferTo(os);}
                    long bytes=Files.size(out);store.videoUploaded(id,out,out.getFileName().toString(),bytes);
                    json(x,200,Map.of("ok",true,"filename",out.getFileName().toString(),"bytes",bytes));
                }
                case "complete"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);store.complete(id,b);
                    Object sidecar=b.get("sidecar");if(sidecar!=null){
                        Map<String,Object>d=store.storyDetail(id);String file=String.valueOf(d.getOrDefault("videoFilename",id+".mp4"));
                        Json.write(root.resolve("output/command_center/videos").resolve(file+".json"),sidecar);
                    }
                    json(x,200,Map.of("ok",true));
                }
                case "fail"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);store.fail(id,String.valueOf(b.getOrDefault("error","worker failure")));json(x,200,Map.of("ok",true));
                }
                case "bias-complete"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    Map<String,Object>result=b.get("result") instanceof Map<?,?>?Json.object(b.get("result")):b;
                    store.politicalAnalysisComplete(id,result,String.valueOf(b.getOrDefault("workerId","")));
                    json(x,200,Map.of("ok",true));
                }
                case "bias-fail"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);store.politicalAnalysisFail(id,String.valueOf(b.getOrDefault("error","analysis failure")));json(x,200,Map.of("ok",true));
                }
                default->json(x,404,Map.of("error","unknown job operation"));
            }
        }catch(IllegalArgumentException e){json(x,404,Map.of("error",safe(e.getMessage())));}
        catch(Exception e){json(x,500,Map.of("error",safe(e.getMessage())));}
    }

    private void videos(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        String name=sanitizeFilename(decode(x.getRequestURI().getPath().substring("/videos/".length())));
        if(name.isBlank()){json(x,404,Map.of("error","video not found"));return;}
        Path file=root.resolve("output/command_center/videos").resolve(name).normalize();
        Path dir=root.resolve("output/command_center/videos").normalize();
        if(!file.startsWith(dir)||!Files.isRegularFile(file)){json(x,404,Map.of("error","video not found"));return;}
        x.getResponseHeaders().set("Content-Type",name.toLowerCase(Locale.ROOT).endsWith(".json")?"application/json":"video/mp4");
        x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(200,Files.size(file));
        try(OutputStream out=x.getResponseBody()){Files.copy(file,out);}
    }

    private void events(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        x.getResponseHeaders().set("Content-Type","text/event-stream");x.getResponseHeaders().set("Cache-Control","no-cache");x.getResponseHeaders().set("Connection","keep-alive");
        x.sendResponseHeaders(200,0);
        BlockingQueue<String>q=new LinkedBlockingQueue<>(500);subscribers.add(q);
        try(OutputStream out=x.getResponseBody()){
            writeSse(out,Json.stringify(Map.of("type","connected","timestamp",Instant.now().toString())));
            while(true){
                String msg;
                try{msg=q.poll(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                if(msg==null){out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));out.flush();}
                else writeSse(out,msg);
            }
        }catch(Exception ignored){}finally{subscribers.remove(q);}
    }

    private void staticFiles(HttpExchange x)throws IOException{
        if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        String path=x.getRequestURI().getPath();if(path.equals("/"))path="/index.html";
        Path base=root.resolve("web/command-center").normalize();Path file=base.resolve(path.substring(1)).normalize();
        if(!file.startsWith(base)||!Files.isRegularFile(file)){text(x,404,"Not found","text/plain; charset=utf-8");return;}
        String ct=path.endsWith(".html")?"text/html; charset=utf-8":path.endsWith(".css")?"text/css; charset=utf-8":path.endsWith(".js")?"application/javascript; charset=utf-8":"application/octet-stream";
        byte[]data=Files.readAllBytes(file);x.getResponseHeaders().set("Content-Type",ct);x.getResponseHeaders().set("Cache-Control","no-cache");x.sendResponseHeaders(200,data.length);try(OutputStream out=x.getResponseBody()){out.write(data);}
    }

    private boolean api(HttpExchange x)throws IOException{
        if(token.isBlank())return true;
        String supplied=x.getRequestHeaders().getFirst("X-AutoNews-Token");
        if(supplied==null||supplied.isBlank())supplied=query(x.getRequestURI()).getOrDefault("token","");
        if(token.equals(supplied))return true;
        json(x,401,Map.of("error","authentication required"));return false;
    }

    private void broadcast(Map<String,Object>event){
        String msg=Json.stringify(event);
        for(BlockingQueue<String>q:subscribers){if(!q.offer(msg)){q.poll();q.offer(msg);}}
    }

    private static Map<String,Object>bodyObject(HttpExchange x)throws IOException{
        byte[]data=x.getRequestBody().readAllBytes();if(data.length==0)return new LinkedHashMap<>();
        try{return Json.object(Json.parse(new String(data,StandardCharsets.UTF_8)));}catch(Exception e){throw new IOException("invalid JSON body");}
    }

    private static void json(HttpExchange x,int status,Object body)throws IOException{
        byte[]data=Json.stringify(body).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");x.sendResponseHeaders(status,data.length);try(OutputStream out=x.getResponseBody()){out.write(data);}
    }

    private static void text(HttpExchange x,int status,String body,String contentType)throws IOException{
        byte[]data=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type",contentType);x.sendResponseHeaders(status,data.length);try(OutputStream out=x.getResponseBody()){out.write(data);}
    }

    private static void method(HttpExchange x)throws IOException{json(x,405,Map.of("error","method not allowed"));}
    private static void writeSse(OutputStream out,String json)throws IOException{out.write(("data: "+json.replace("\n"," ")+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    private static Map<String,String>query(URI u){Map<String,String>m=new HashMap<>();String q=u.getRawQuery();if(q==null)return m;for(String p:q.split("&")){int i=p.indexOf('=');String k=i<0?p:p.substring(0,i),v=i<0?"":p.substring(i+1);m.put(decode(k),decode(v));}return m;}
    private static String decode(String x){try{return URLDecoder.decode(x,StandardCharsets.UTF_8);}catch(Exception e){return x;}}
    private static String sanitizeFilename(String x){if(x==null)return "";return x.replaceAll("[^A-Za-z0-9._-]+","_").replaceAll("^\\.+","");}
    private static Path unique(Path dir,String filename){Path p=dir.resolve(filename);if(!Files.exists(p))return p;String base=filename,ext="";int dot=filename.lastIndexOf('.');if(dot>0){base=filename.substring(0,dot);ext=filename.substring(dot);}for(int i=2;;i++){p=dir.resolve(base+"_"+i+ext);if(!Files.exists(p))return p;}}
    private static String safe(String x){return x==null?"":x.length()>1500?x.substring(0,1500):x;}
    private String displayHost(){return host.equals("0.0.0.0")?"localhost":host;}
    private static boolean isLoopbackHost(String h){return h.equals("127.0.0.1")||h.equals("localhost")||h.equals("::1");}
}
