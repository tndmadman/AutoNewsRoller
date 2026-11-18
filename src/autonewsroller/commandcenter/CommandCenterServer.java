package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.config.SourceConfig;
import autonewsroller.orchestration.NewsPipeline;
import autonewsroller.audit.VideoAudit;
import autonewsroller.safety.ContentSafetyService;
import autonewsroller.safety.SafetyPython;
import autonewsroller.safety.SafetyScanProgress;
import autonewsroller.safety.PersistentSafetyScanner;
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
    private final CommandCenterAssistant assistant;
    private final ScheduledExecutorService scheduler=Executors.newScheduledThreadPool(2);
    private final ExecutorService requestExecutor=Executors.newCachedThreadPool();
    private final CopyOnWriteArrayList<BlockingQueue<String>>subscribers=new CopyOnWriteArrayList<>();
    private final AtomicBoolean scanInFlight=new AtomicBoolean();
    // Fixed stripes avoid duplicate expensive controller scans on concurrent
    // completion retries without creating unbounded per-job lock state.
    private final Object[] completionLocks=java.util.stream.IntStream.range(0,64)
            .mapToObj(i->new Object()).toArray();
    private HttpServer server;
    private volatile Map<String,Object> cachedSafetyHealth=Map.of();
    private volatile long safetyHealthCheckedAt=0;

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
                cfg.getInt("commandCenterFailureRetryDelaySeconds",20),
                cfg.getInt("commandCenterLeaseRecoveryMaxRetries",3),
                cfg.getBool("commandCenterPairFramingVideos",false)
        );
        this.store.configureMeme(cfg);
        try{this.assistant=new CommandCenterAssistant(root,cfg,this.store);}
        catch(IOException e){throw new IllegalStateException("Unable to load saved assistant conversation",e);}
    }

    public int start() throws Exception {
        server=HttpServer.create(new InetSocketAddress(host,requestedPort),0);
        server.setExecutor(requestExecutor);
        server.createContext("/api/health",this::health);
        server.createContext("/api/safety/health",this::safetyHealth);
        server.createContext("/api/safety/progress",this::safetyProgress);
        server.createContext("/api/state",this::state);
        server.createContext("/api/board/stories",this::boardStories);
        server.createContext("/api/assistant",this::assistant);
        server.createContext("/api/events",this::events);
        server.createContext("/api/scan",this::scanNow);
        server.createContext("/api/settings/auto-video",this::autoVideoSetting);
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
        scheduler.shutdownNow();if(server!=null)server.stop(1);requestExecutor.shutdownNow();
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

    private void safetyProgress(HttpExchange x)throws IOException{
        if(!api(x))return;
        if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        json(x,200,SafetyScanProgress.snapshot());
    }

    private void safetyHealth(HttpExchange x)throws IOException{
        if(!api(x))return;
        if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        try{
            Map<String,Object> health;
            synchronized(this){
                if(System.currentTimeMillis()-safetyHealthCheckedAt>300000||cachedSafetyHealth.isEmpty()){
                    Map<String,Object> anatomy=probeSafetyModel("tools/content_safety_scan.py","NudeNet","3.4.2");
                    Map<String,Object> context=probeSafetyModel("tools/content_context_scan.py","OpenNSFW2","0.19.0");
                    cachedSafetyHealth=Map.of("status","READY","detector","NudeNet + OpenNSFW2",
                            "version","3.4.2 + 0.19.0","policyVersion",ContentSafetyService.POLICY,
                            "models",List.of(anatomy,context));
                    safetyHealthCheckedAt=System.currentTimeMillis();
                }
                health=cachedSafetyHealth;
            }
            json(x,200,health);
        }catch(Exception e){
            json(x,503,Map.of("error","Mandatory safety model unavailable: "+safe(e.getMessage())));
        }
    }

    private Map<String,Object> probeSafetyModel(String script,String detector,String version)throws Exception{
        if(cfg.getBool("contentSafetyScannerPersistent",true)){
            Map<String,Object>live=PersistentSafetyScanner.health(root,cfg,script);
            if(!"READY".equals(live.get("status"))||!detector.equals(live.get("detector"))
                    ||!version.equals(live.get("version"))
                    ||!ContentSafetyService.POLICY.equals(live.get("policyVersion")))
                throw new IOException(detector+" resident model health mismatch");
            return live;
        }
        String python=SafetyPython.resolve(root,cfg);
        Path scanner=root.resolve(script).toAbsolutePath();
        if(!Files.isRegularFile(scanner))throw new IOException("Missing "+detector+" scanner");
        Path stderr=Files.createTempFile("anr-safety-health-", ".log");
        try{
            ProcessBuilder builder=new ProcessBuilder(python,scanner.toString(),"--health");
            builder.directory(root.toFile());
            builder.environment().put("TF_CPP_MIN_LOG_LEVEL","2");
            builder.redirectError(stderr.toFile());
            Process process=builder.start();
            if(!process.waitFor(150,TimeUnit.SECONDS)){
                process.destroyForcibly();
                throw new IOException(detector+" health timeout");
            }
            if(process.exitValue()!=0)throw new IOException(detector+" health failed");
            Map<String,Object> result=Json.object(Json.parse(
                    new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8)));
            if(!"READY".equals(result.get("status"))||!detector.equals(result.get("detector"))
                    ||!version.equals(result.get("version"))
                    ||!ContentSafetyService.POLICY.equals(result.get("policyVersion")))
                throw new IOException(detector+" did not validate the current safety policy");
            return result;
        }finally{Files.deleteIfExists(stderr);}
    }

    private void state(HttpExchange x)throws IOException{
        if(!api(x))return;
        if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        boolean includeStories=!"false".equalsIgnoreCase(query(x.getRequestURI()).getOrDefault("includeStories","true"));
        json(x,200,store.snapshot(includeStories));
    }

    private void boardStories(HttpExchange x)throws IOException{
        if(!api(x))return;
        if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        Map<String,String>q=query(x.getRequestURI());
        try{
            int page=boardInt(q.getOrDefault("page","1"),1);
            int pageSize=boardInt(q.getOrDefault("pageSize","25"),25);
            int ageHours=boardInt(q.getOrDefault("ageHours","0"),0);
            String status=q.getOrDefault("status","ALL").trim().toUpperCase(Locale.ROOT);
            String memeFilter=q.getOrDefault("memeFilter","ALL_ANALYZED").trim().toUpperCase(Locale.ROOT);
            String search=q.getOrDefault("search","").trim();
            if(search.length()>500){json(x,400,Map.of("error","search is too long"));return;}
            json(x,200,store.boardStories(page,pageSize,status,search,ageHours,memeFilter));
        }catch(IllegalArgumentException e){
            json(x,400,Map.of("error","invalid board query parameters"));
        }
    }

    private static int boardInt(String raw,int fallback){
        if(raw==null||raw.isBlank())return fallback;
        try{return Integer.parseInt(raw);}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid integer",e);}
    }

    private void assistant(HttpExchange x)throws IOException{
        if(!api(x))return;
        // The assistant can show real production state and prompt source.
        // Unlike the public dashboard, it may not run open to the LAN.
        if(!isLoopbackHost(host)&&token.isBlank()){
            json(x,403,Map.of("error","Set AUTONEWS_TOKEN before enabling LAN assistant access."));
            return;
        }
        String method=x.getRequestMethod().toUpperCase(Locale.ROOT);
        String suffix=x.getRequestURI().getPath().substring("/api/assistant".length());
        try{
            switch(suffix){
                case "", "/"->{
                    if(!"GET".equals(method)){method(x);return;}
                    json(x,200,assistant.history());
                }
                case "/chat"->{
                    if(!"POST".equals(method)){method(x);return;}
                    byte[]bytes=x.getRequestBody().readNBytes(16_385);
                    if(bytes.length>16_384){json(x,413,Map.of("error","Message too large"));return;}
                    Map<String,Object>body;
                    try{body=Json.object(Json.parse(new String(bytes,StandardCharsets.UTF_8)));}
                    catch(Exception e){json(x,400,Map.of("error","Invalid message JSON"));return;}
                    if(!(body.get("message") instanceof String)){
                        json(x,400,Map.of("error","message string required"));return;
                    }
                    json(x,200,assistant.ask((String)body.get("message")));
                }
                case "/history"->{
                    if(!"DELETE".equals(method)){method(x);return;}
                    assistant.clear();json(x,200,Map.of("ok",true,"messages",List.of()));
                }
                case "/prompts"->{
                    if(!"GET".equals(method)){method(x);return;}
                    json(x,200,Map.of("prompts",assistant.promptCatalog(),
                            "note","Static source templates are not fully interpolated per-story Ollama requests."));
                }
                case "/prompt"->{
                    if(!"GET".equals(method)){method(x);return;}
                    json(x,200,assistant.promptSource(query(x.getRequestURI()).getOrDefault("id","")));
                }
                default->json(x,404,Map.of("error","Unknown assistant operation"));
            }
        }catch(IllegalArgumentException e){json(x,400,Map.of("error",safe(e.getMessage())));}
        catch(IOException e){json(x,500,Map.of("error","Assistant storage/source unavailable; check logs."));}
        catch(Exception e){
            System.err.println("Command Center assistant error: "+e.getClass().getSimpleName()+" "+safe(e.getMessage()));
            json(x,502,Map.of("error","Local Ollama assistant unavailable or returned an invalid response. Verify the configured Ollama endpoint and model."));
        }
    }

    private void scanNow(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        if(scanInFlight.get()){json(x,202,Map.of("ok",true,"status","already-scanning"));return;}
        scheduler.submit(this::scanSafe);json(x,202,Map.of("ok",true,"status","scan-started"));
    }

    private void autoVideoSetting(HttpExchange x)throws IOException{
        if(!api(x))return;
        if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        Map<String,Object>body=bodyObject(x);
        if(!(body.get("enabled") instanceof Boolean enabled)){json(x,400,Map.of("error","enabled boolean required"));return;}
        try{json(x,200,store.setAutoQueue(enabled));}
        catch(UncheckedIOException e){json(x,500,Map.of("error","Unable to save auto-video setting"));}
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
                Map<String,Object>body=bodyObject(x);String action=String.valueOf(body.getOrDefault("action",""));
                if(action.toUpperCase(Locale.ROOT).contains("FRAMING_EXPOSURE")&&!cfg.getBool("politicalExposureEnabled",true))
                    throw new IllegalStateException("Political framing exposure experiment is disabled.");
                if(action.toUpperCase(Locale.ROOT).contains("MEME")&&!cfg.getBool("memePipelineEnabled",true))
                    throw new IllegalStateException("Meme production is disabled.");
                json(x,200,store.action(id,action,String.valueOf(body.getOrDefault("operationId",""))));return;
            }
            if(p.length>=2&&"upload-status".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>body=bodyObject(x);
                boolean uploaded=Boolean.TRUE.equals(body.get("uploaded"));
                if(uploaded&&!Boolean.TRUE.equals(body.get("aigcLabelConfirmed"))){
                    json(x,409,Map.of("error","AIGC label confirmation required; verify the label on the actual platform post"));
                    return;
                }
                String platform=String.valueOf(body.getOrDefault("platform",""));
                String note=String.valueOf(body.getOrDefault("note",""));
                String versionId=String.valueOf(body.getOrDefault("versionId",""));
                json(x,200,store.setUploadStatus(id,versionId,uploaded,platform,note,String.valueOf(body.getOrDefault("operationId",""))));return;
            }
            if(p.length>=2&&"scrap-status".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>body=bodyObject(x);
                boolean scrapped=Boolean.TRUE.equals(body.get("scrapped"));
                String reason=String.valueOf(body.getOrDefault("reason",""));
                String versionId=String.valueOf(body.getOrDefault("versionId",""));
                json(x,200,store.setScrapStatus(id,versionId,scrapped,reason,String.valueOf(body.getOrDefault("operationId",""))));return;
            }
            if(p.length>=2&&"analyze-meme".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                if(!cfg.getBool("memeWorthinessEnabled",true))throw new IllegalStateException("Meme analysis disabled");
                Map<String,Object>b=bodyObject(x);
                json(x,202,store.queueMemeAnalysis(id,String.valueOf(b.getOrDefault("operationId",""))));return;
            }
            if(p.length>=2&&"meme-decision".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>b=bodyObject(x);
                json(x,200,store.memeDecision(id,String.valueOf(b.getOrDefault("decision","")),
                        String.valueOf(b.getOrDefault("operationId",""))));return;
            }
            if(p.length>=2&&"analyze-bias".equalsIgnoreCase(p[1])&&"POST".equalsIgnoreCase(x.getRequestMethod())){
                Map<String,Object>b=bodyObject(x);json(x,202,store.queuePoliticalAnalysis(id,String.valueOf(b.getOrDefault("operationId",""))));return;
            }
            method(x);
        }catch(UncheckedIOException e){json(x,500,Map.of("error","State save failed; check server logs."));}
        catch(IllegalStateException e){json(x,409,Map.of("error",safe(e.getMessage())));}
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
                    store.requireActiveClaim(id,String.valueOf(b.getOrDefault("workerId","")),
                            String.valueOf(b.getOrDefault("claimId","")));
                    autonewsroller.orchestration.PipelineStage stage;
                    try{stage=autonewsroller.orchestration.PipelineStage.valueOf(String.valueOf(b.getOrDefault("stage","VERIFY")));}catch(Exception e){stage=autonewsroller.orchestration.PipelineStage.VERIFY;}
                    store.progress(id,new autonewsroller.orchestration.WorkerState(1,1,stage,String.valueOf(b.getOrDefault("detail","")),Instant.now()),String.valueOf(b.getOrDefault("workerId","")),String.valueOf(b.getOrDefault("claimId","")));
                    json(x,200,Map.of("ok",true));
                }
                case "video"->{
                    if(!"PUT".equalsIgnoreCase(x.getRequestMethod())&&!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    String workerId=String.valueOf(x.getRequestHeaders().getFirst("X-Worker-Id"));
                    String claimId=String.valueOf(x.getRequestHeaders().getFirst("X-Claim-Id"));
                    String epoch=String.valueOf(x.getRequestHeaders().getFirst("X-Production-Epoch"));
                    String sha=String.valueOf(x.getRequestHeaders().getFirst("X-Media-Sha256")).toLowerCase(Locale.ROOT);
                    store.authorizeVideoTransfer(id,workerId,claimId,epoch,sha);
                    Path dir=root.resolve("output/command_center/videos");Files.createDirectories(dir);
                    // Stable media name: repeat requests never allocate another version/file.
                    String name="job-"+autonewsroller.util.Hashing.sha256(id).substring(0,20)+"_"+
                            epoch.replaceAll("[^A-Za-z0-9_-]","")+"_"+
                            sha+".mp4";
                    Path out=dir.resolve(name);
                    Path staging=Files.createTempFile(dir,".incoming-",".part");
                    try{
                        long bytes=0;
                        try(InputStream in=x.getRequestBody();OutputStream os=Files.newOutputStream(staging)){
                            byte[] buffer=new byte[65536];int count;
                            while((count=in.read(buffer))!=-1){
                                bytes+=count;
                                if(bytes>2147483647L)throw new IllegalArgumentException("Video exceeds the signed 2 GiB upload limit");
                                os.write(buffer,0,count);
                            }
                        }
                        if(bytes<=0||!sha.equals(ContentSafetyService.hash(staging)))
                            throw new IllegalStateException("Uploaded bytes do not match approved media SHA-256");
                        Map<String,Object>authorized=store.authorizeVideoTransfer(id,workerId,claimId,epoch,sha);
                        if(Boolean.TRUE.equals(authorized.get("completed"))){
                            Map<String,Object>result=new LinkedHashMap<>(authorized);
                            result.put("ok",true);json(x,200,result);break;
                        }
                        if(Files.exists(out)){
                            if(!Files.isRegularFile(out)||Files.isSymbolicLink(out)
                                    ||!sha.equals(ContentSafetyService.hash(out)))
                                throw new IllegalStateException("Existing upload identity has different bytes");
                        }else{
                            try{Files.move(staging,out);}
                            catch(FileAlreadyExistsException collision){
                                if(!Files.isRegularFile(out)||!sha.equals(ContentSafetyService.hash(out)))
                                    throw new IllegalStateException("Concurrent upload bytes differ",collision);
                            }
                        }
                        try(java.nio.channels.FileChannel durable=java.nio.channels.FileChannel.open(
                                out,StandardOpenOption.WRITE)){durable.force(true);}
                        Map<String,Object>result=new LinkedHashMap<>(
                                store.recordVideoTransfer(id,out,workerId,claimId,epoch,sha,bytes));
                        result.put("ok",true);json(x,200,result);
                    }finally{Files.deleteIfExists(staging);}
                }
                case "complete"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    synchronized(completionLocks[Math.floorMod(id.hashCode(),completionLocks.length)]){
                    Map<String,Object>b=new LinkedHashMap<>(bodyObject(x));
                    if(!Boolean.TRUE.equals(b.get("dryRun"))){
                        Path uploaded=store.pendingMediaForControllerApproval(id,b);
                        if(uploaded!=null){
                            Map<String,Object>sidecar=new LinkedHashMap<>(Json.object(b.get("sidecar")));
                            Map<String,Object>workerManifest=Json.object(sidecar.get("contentSafety"));
                            String expectedSha=String.valueOf(workerManifest.get("videoSha256"));
                            Map<String,Object>controllerManifest;
                            try(SafetyScanProgress.Scope scope=SafetyScanProgress.open(id)){
                                try{
                                    controllerManifest=approveOnController(uploaded,expectedSha);
                                }catch(Exception denied){
                                    SafetyScanProgress.finished(id,false);
                                    throw denied;
                                }
                            }
                            sidecar.put("contentSafety",controllerManifest);
                            b.put("sidecar",sidecar);
                        }
                    }
                    boolean committed=store.completeAuthorized(id,b);
                    if(!Boolean.TRUE.equals(b.get("dryRun"))){
                        // Use the controller's committed provenance, never replay-supplied data.
                        Map<String,Object>output=store.committedOutput(id,b);
                        Json.write(Path.of(String.valueOf(output.get("path"))+".json"),output.get("sidecar"));
                    }
                    json(x,200,Map.of("ok",true,"alreadyCompleted",!committed));
                    }
                }
                case "fail"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    store.requireActiveClaim(id,String.valueOf(b.getOrDefault("workerId","")),
                            String.valueOf(b.getOrDefault("claimId","")));
                    store.fail(id,String.valueOf(b.getOrDefault("error","worker failure")),String.valueOf(b.getOrDefault("workerId","")),String.valueOf(b.getOrDefault("claimId","")),String.valueOf(b.getOrDefault("failureCode","")));json(x,200,Map.of("ok",true));
                }
                case "meme-complete"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    if(!(b.get("result") instanceof Map<?,?>)||!(b.get("candidate") instanceof Map<?,?>))
                        throw new IllegalArgumentException("Missing meme result or prepared candidate");
                    store.memeAnalysisComplete(id,Json.object(b.get("result")),
                            String.valueOf(b.getOrDefault("workerId","")),
                            String.valueOf(b.getOrDefault("claimId","")),
                            String.valueOf(b.getOrDefault("analysisInputFingerprint","")),
                            NewsPipeline.Candidate.fromMap(Json.object(b.get("candidate"))));
                    json(x,200,Map.of("ok",true));
                }
                case "meme-fail"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    store.memeAnalysisFail(id,String.valueOf(b.getOrDefault("error","meme analysis failure")),
                            String.valueOf(b.getOrDefault("workerId","")),
                            String.valueOf(b.getOrDefault("claimId","")),
                            String.valueOf(b.getOrDefault("analysisInputFingerprint","")));
                    json(x,200,Map.of("ok",true));
                }
                case "bias-complete"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);
                    Map<String,Object>result=b.get("result") instanceof Map<?,?>?Json.object(b.get("result")):b;
                    if(!(b.get("candidate") instanceof Map<?,?>))
                        throw new IllegalStateException("Missing enriched analysis candidate");
                    NewsPipeline.Candidate prepared=NewsPipeline.Candidate.fromMap(Json.object(b.get("candidate")));
                    store.politicalAnalysisComplete(id,result,
                            String.valueOf(b.getOrDefault("workerId","")),prepared,
                            String.valueOf(b.getOrDefault("analysisInputFingerprint","")));
                    json(x,200,Map.of("ok",true));
                }
                case "bias-fail"->{
                    if(!"POST".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
                    Map<String,Object>b=bodyObject(x);store.politicalAnalysisFail(id,String.valueOf(b.getOrDefault("error","analysis failure")),
                            String.valueOf(b.getOrDefault("workerId","")));json(x,200,Map.of("ok",true));
                }
                default->json(x,404,Map.of("error","unknown job operation"));
            }
        }catch(IllegalArgumentException e){json(x,404,Map.of("error",safe(e.getMessage())));}
        catch(IllegalStateException e){json(x,409,Map.of("error",safe(e.getMessage())));}
        catch(Exception e){json(x,500,Map.of("error",safe(e.getMessage())));}
    }

    /**
     * Mandatory, independent controller trust boundary. An upload and a worker
     * manifest remain non-publishable until both actual local detectors and
     * the encoded-stream audit succeed against the exact received bytes.
     */
    private Map<String,Object> approveOnController(Path media,String expectedSha)throws Exception{
        if(!expectedSha.equals(ContentSafetyService.hash(media)))
            throw new IllegalStateException("CONTENT_SAFETY: controller received different bytes");
        // Reject malformed, silent or nonconforming media before spending time
        // on two potentially slow detector scans.
        SafetyScanProgress.stage("CONTROLLER_AUDIT");
        Map<String,Object>encoded=new VideoAudit(cfg.get("ffprobeCommand","ffprobe"),
                cfg.get("ffmpegCommand","ffmpeg")).auditUploaded(media,
                cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),
                cfg.getInt("videoFps",30));
        Map<String,Object>scan=new ContentSafetyService(root,cfg).inspectVideo(media);
        if(!"SAFE".equals(scan.get("status")))
            throw new IllegalStateException("CONTENT_SAFETY: controller scan blocked approval: "+
                    safe(String.valueOf(scan.getOrDefault("reason",scan.get("status")))));
        if(!expectedSha.equals(ContentSafetyService.hash(media)))
            throw new IllegalStateException("CONTENT_SAFETY: media changed during controller approval");
        Map<String,Object>approved=new LinkedHashMap<>();
        approved.put("status","APPROVED_FOR_EXPORT");
        approved.put("policyVersion",ContentSafetyService.POLICY);
        approved.put("videoSha256",expectedSha);
        approved.put("finalVideoAudit",scan);
        approved.put("inputs",List.of());
        approved.put("createdAt",Instant.now().toString());
        approved.put("approvalOrigin","CONTROLLER_INDEPENDENT_RESCAN");
        approved.put("controllerEncodedAudit",encoded);
        ContentSafetyService.verify(media,approved,false);
        SafetyScanProgress.finished(SafetyScanProgress.current(),true);
        return approved;
    }

    private void videos(HttpExchange x)throws IOException{
        if(!api(x))return;if(!"GET".equalsIgnoreCase(x.getRequestMethod())){method(x);return;}
        String name=sanitizeFilename(decode(x.getRequestURI().getPath().substring("/videos/".length())));
        if(name.isBlank()){json(x,404,Map.of("error","video not found"));return;}
        Path file=root.resolve("output/command_center/videos").resolve(name).normalize();
        Path dir=root.resolve("output/command_center/videos").normalize();
        if(!file.startsWith(dir)||!Files.isRegularFile(file)){json(x,404,Map.of("error","video not found"));return;}
        // Uploaded, historical, replaced, or tampered bytes are never served as publishable MP4.
        if(!name.toLowerCase(Locale.ROOT).endsWith(".json")){
            try{
                Path sidecar=file.resolveSibling(file.getFileName()+".json");
                Map<String,Object>provenance=Json.object(Json.read(sidecar));
                Map<String,Object>manifest=Json.object(provenance.get("contentSafety"));
                if(!"CONTROLLER_INDEPENDENT_RESCAN".equals(manifest.get("approvalOrigin")))
                    throw new IllegalStateException("CONTENT_SAFETY: legacy worker evidence is not controller approval");
                ContentSafetyService.verify(file,manifest,false);
            }catch(Exception denied){
                json(x,409,Map.of("error","CONTENT_SAFETY: video unverified or stale; do not repost"));
                return;
            }
        }
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
