package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.meme.MemeWorthinessAnalyzer;
import autonewsroller.model.PoliticalVisualData;
import autonewsroller.orchestration.*;
import autonewsroller.util.Json;
import autonewsroller.util.Hashing;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One claim loop and one lease-renewing heartbeat scheduler. Never interrupt an owned render on drain. */
public final class RemoteWorker {
    private final Path root;
    private final NewsConfig cfg;
    private final String controller,workerId,token;
    private final HttpClient http;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"worker-heartbeat"));
    private final AtomicBoolean launched=new AtomicBoolean(false);
    private FileChannel identityChannel;
    private FileLock identityLock;
    private final Consumer<Map<String,Object>> listener;
    private final Supplier<Map<String,Object>> metrics;
    private volatile String state="IDLE",currentJob="",currentTopic="",currentClaimId="";
    private volatile String jobType="",stage="",detail="",jobStartedAt="",lastError="";
    private volatile String actualEncoder="",actualTts="",actualVoice="",comfyActivity="";
    private volatile Instant lastContact,lastHeartbeat,lastErrorAt;
    private volatile int failures;
    private volatile boolean running=true,paused=false,draining=false;

    public RemoteWorker(Path root,NewsConfig cfg,String controller,String workerId,String token){
        this(root,cfg,controller,workerId,token,null,null);
    }
    public RemoteWorker(Path root,NewsConfig cfg,String controller,String workerId,String token,
                        Consumer<Map<String,Object>> listener,Supplier<Map<String,Object>> metrics){
        this.root=root;this.cfg=cfg;this.controller=controller.replaceAll("/+$","");this.workerId=workerId;
        this.token=token==null?"":token;this.listener=listener==null?e->{}:listener;
        this.metrics=metrics==null?SystemMetrics::snapshot:metrics;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }
    public void runForever(){
        if(!launched.compareAndSet(false,true))throw new IllegalStateException("RemoteWorker is single-use");
        // An OS-held file lock prevents headless and UI workers in this installation
        // from using the same worker ID, even if launched from different CMD windows.
        Path lockPath=root.resolve("output/runtime/worker-lock-"+safeName(workerId)+".lck");
        try{
            Files.createDirectories(lockPath.getParent());
            identityChannel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try{identityLock=identityChannel.tryLock();}
            catch(OverlappingFileLockException conflict){identityLock=null;}
            if(identityLock==null){
                identityChannel.close();
                throw new IllegalStateException("Worker ID already active in this installation: "+workerId);
            }
        }catch(IOException e){throw new IllegalStateException("Could not lock worker ID: "+workerId,e);}
        System.out.println("AutoNewsRoller GPU worker '"+workerId+"' -> "+controller);
        event("lifecycle",Map.of("state","CONNECTING"));
        heartbeat.scheduleWithFixedDelay(this::heartbeatSafe,0,10,TimeUnit.SECONDS);
        try{
            while(running){
                try{
                    if(draining){running=false;break;}
                    if(paused){Thread.sleep(250);continue;}
                    // Claim may already be in flight when Pause or Drain arrives.
                    // Always complete any returned claim, regardless of the new control state.
                    Map<String,Object> job=claim();
                    if(job==null){Thread.sleep(1500);continue;}
                    runJob(job);
                }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                catch(Exception e){
                    error("controller",e);
                    try{Thread.sleep(2500);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}
                }
            }
        }finally{
            heartbeat.shutdownNow();
            try{heartbeat.awaitTermination(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            running=false;event("lifecycle",Map.of("state","STOPPED"));
            try{if(identityLock!=null&&identityLock.isValid())identityLock.release();}
            catch(IOException e){System.err.println("Worker identity lock release failed");}
            try{if(identityChannel!=null)identityChannel.close();}
            catch(IOException e){System.err.println("Worker identity channel close failed");}
        }
    }
    public void pause(){if(!draining){paused=true;event("lifecycle",Map.of("state","PAUSED"));}}
    public void resume(){if(!draining){paused=false;event("lifecycle",Map.of("state","RESUMED"));}}
    public void drain(){draining=true;paused=false;event("lifecycle",Map.of("state","DRAINING"));}
    public void stop(){drain();}
    public boolean isPaused(){return paused;}
    public boolean isDraining(){return draining;}
    public Map<String,Object> snapshot(){
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("workerId",workerId);m.put("controllerUrl",controller);
        m.put("running",launched.get()&&running);m.put("paused",paused);m.put("draining",draining);
        m.put("state",state);m.put("currentJob",currentJob);m.put("currentTopic",currentTopic);
        m.put("claimActive",!currentJob.isBlank());m.put("jobType",jobType);m.put("stage",stage);m.put("detail",detail);
        m.put("jobStartedAt",jobStartedAt);
        m.put("actualEncoder",actualEncoder);m.put("actualTts",actualTts);m.put("actualVoice",actualVoice);
        m.put("comfyActivity",comfyActivity);m.put("connected",lastContact!=null&&Duration.between(lastContact,Instant.now()).getSeconds()<35);
        m.put("lastContact",lastContact==null?null:lastContact.toString());
        m.put("lastHeartbeat",lastHeartbeat==null?null:lastHeartbeat.toString());
        m.put("connectionFailures",failures);m.put("lastError",lastError);
        m.put("lastErrorAt",lastErrorAt==null?null:lastErrorAt.toString());return m;
    }
    private void event(String type,Map<String,Object> fields){
        Map<String,Object> m=new LinkedHashMap<>(fields);
        m.put("type",type);m.put("at",Instant.now().toString());
        try{listener.accept(m);}catch(RuntimeException ignored){}
    }
    private void error(String component,Exception e){
        failures++;lastError=safe(e.getMessage());lastErrorAt=Instant.now();
        event("error",Map.of("component",component,"message",lastError));
    }
    private Map<String,Object> claim()throws Exception{
        HttpRequest req=request("/api/jobs/claim?worker="+URLEncoder.encode(workerId,StandardCharsets.UTF_8)).GET().timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()==204){lastContact=Instant.now();return null;}
        if(r.statusCode()!=200)throw new IllegalStateException("claim HTTP "+r.statusCode());
        lastContact=Instant.now();return Json.object(Json.parse(r.body()));
    }
    private void runJob(Map<String,Object> job){
        String jobId=String.valueOf(job.get("jobId"));
        String type=String.valueOf(job.getOrDefault("jobType","VIDEO")).toUpperCase(Locale.ROOT);
        currentJob=jobId;currentClaimId=String.valueOf(job.getOrDefault("claimId",""));
        currentTopic=String.valueOf(job.getOrDefault("topic",""));
        state="POLITICAL_ANALYSIS".equals(type)?"ANALYZING_BIAS":"MEME_ANALYSIS".equals(type)?"ANALYZING_MEME":"PRODUCING";
        jobType=type;stage="POLITICAL_ANALYSIS".equals(type)||"MEME_ANALYSIS".equals(type)?"ANALYSIS":"CLAIMED";
        detail="Controller job claimed";jobStartedAt=Instant.now().toString();
        actualEncoder="";actualTts="";actualVoice="";comfyActivity="";
        event("job-start",Map.of("jobId",jobId,"jobType",type,"topic",currentTopic,"stage",stage,"workerId",workerId));
        try{
            if("POLITICAL_ANALYSIS".equals(type)){runPoliticalAnalysis(job);return;}
            if("MEME_ANALYSIS".equals(type)){runMemeAnalysis(job);return;}
            ProductionMode productionMode=ProductionMode.fromPersisted(job.get("productionMode"));
            if(productionMode==ProductionMode.FRAMING_EXPOSURE&&!cfg.getBool("politicalExposureEnabled",true))
                throw new IllegalStateException("Worker does not support framing exposure v1.");
            if(productionMode==ProductionMode.MEME_RAGEBAIT&&!cfg.getBool("memePipelineEnabled",true))
                throw new IllegalStateException("Worker does not support meme_ragebait_v1.");
            NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(job.get("candidate")));
            if(productionMode==ProductionMode.FRAMING_EXPOSURE){
                String expected=String.valueOf(job.getOrDefault("framingContentFingerprint",""));
                String current=Hashing.sha256(candidate.cluster().articles.stream()
                        .map(a->a.id()+"|"+a.title()+"|"+a.description()+"|"+a.bodyText()).sorted()
                        .reduce("",(a,b)->a+"|"+b));
                if(expected.isBlank()||!expected.equals(current))
                    throw new IllegalStateException("STALE_FRAMING_EVIDENCE: Article text changed since analysis.");
            }
            Map<String,Object> settings=job.get("settings") instanceof Map<?,?>?Json.object(job.get("settings")):Map.of();
            int requestedDuration=settings.get("duration") instanceof Number n?n.intValue():cfg.duration();
            int duration=Math.max(cfg.getInt("commandCenterMinimumTargetSeconds",70),requestedDuration);
            if(productionMode==ProductionMode.FRAMING_EXPOSURE)
                duration=Math.max(duration,cfg.getInt("politicalExposureDefaultDurationSeconds",75));
            else if(productionMode==ProductionMode.MEME_RAGEBAIT)
                duration=Math.max(1,cfg.getInt("memeDurationHintSeconds",30));
            String encoder=String.valueOf(settings.getOrDefault("encoder",cfg.get("videoEncoder","auto")));
            boolean controllerComfy=settings.containsKey("useComfy")?bool(settings.get("useComfy")):true;
            boolean useComfy=cfg.getBool("commandCenterUseComfy",true)||controllerComfy;
            boolean requireComfy=cfg.getBool("commandCenterRequireComfy",true)||(settings.containsKey("requireComfy")&&bool(settings.get("requireComfy")));
            int comfyImages=settings.get("comfyImages") instanceof Number n?Math.max(1,n.intValue()):cfg.getInt("commandCenterComfyImages",3);
            boolean dryRun=bool(settings.get("dryRun"));
            if(productionMode==ProductionMode.FRAMING_EXPOSURE){
                useComfy=cfg.getBool("politicalExposureRequireComfy",false);
                requireComfy=useComfy;
            }else if(productionMode==ProductionMode.MEME_RAGEBAIT){
                useComfy=cfg.getBool("memeUseComfy",true);
                requireComfy=cfg.getBool("memeRequireComfy",true);
                comfyImages=Math.max(1,cfg.getInt("memeComfyImages",3));
            }
            event("progress",Map.of("stage","CLAIMED","detail","mode="+productionMode.name()+
                    "; durationTarget="+duration+"s; encoder="+encoder+"; comfy="+useComfy));
            String stamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
            Path batch=root.resolve("output/remote_worker").resolve(safeName(jobId)+"_"+stamp);
            event("job-dir",Map.of("dir",batch.toString()));
            final String claimId=currentClaimId;
            NewsPipeline pipeline=new NewsPipeline(root,cfg,batch,e->progressSafe(jobId,claimId,e));
            PoliticalVisualData political=job.get("politicalVisualData") instanceof Map<?,?>?PoliticalVisualData.fromMap(Json.object(job.get("politicalVisualData"))):null;
            Map<String,Object>framingAnalysis=job.get("framingAnalysis") instanceof Map<?,?>?Json.object(job.get("framingAnalysis")):Map.of();
            Path output=pipeline.produce(candidate,1,1,batch.resolve("slot_001"),duration,encoder,useComfy,requireComfy,comfyImages,political,productionMode,framingAnalysis,dryRun);
            stage="UPLOAD";detail="Local output ready; awaiting controller acknowledgement";
            event("progress",Map.of("stage",stage,"detail",detail));
            if(!dryRun&&output.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")){
                Map<String,Object> upload=uploadVideo(jobId,claimId,output);
                event("progress",Map.of("stage","UPLOAD","detail","Video uploaded; final completion pending"));
                Map<String,Object> complete=new LinkedHashMap<>();
                complete.put("workerId",workerId);complete.put("claimId",claimId);complete.put("localPath",output.toString());complete.put("uploaded",upload);
                complete.put("completedAt",Instant.now().toString());
                Path sidecar=output.resolveSibling(output.getFileName()+".json");
                if(Files.isRegularFile(sidecar))try{complete.put("sidecar",Json.read(sidecar));}catch(Exception ignored){}
                post("/api/jobs/"+enc(jobId)+"/complete",complete);
            }else{
                post("/api/jobs/"+enc(jobId)+"/complete",Map.of("workerId",workerId,"claimId",claimId,"dryRun",true,"localPath",output.toString(),"completedAt",Instant.now().toString()));
            }
            event("job-finish",Map.of("jobId",jobId,"result","COMPLETE","stage","COMPLETE","outputPath",output.toString()));
            System.out.println("Worker completed: "+currentTopic);
        }catch(Exception e){
            if("MEME_ANALYSIS".equals(type)&&supersededMemeAnalysis(e)){
                // Discovery invalidated this claim while analysis was running.
                // Controller already owns the newer evidence/claim; do not send
                // an obsolete failure callback that would return HTTP 409 again.
                event("job-finish",Map.of("jobId",jobId,"result","SUPERSEDED",
                        "stage","MEME_ANALYSIS","detail","Claim superseded by refreshed evidence"));
                return;
            }
            error("job",e);e.printStackTrace();
            boolean acknowledged=false;
            try{
                if("POLITICAL_ANALYSIS".equals(type))
                    post("/api/jobs/"+enc(jobId)+"/bias-fail",Map.of("workerId",workerId,"error",safe(e.getMessage()),"failedAt",Instant.now().toString()));
                else if("MEME_ANALYSIS".equals(type))
                    post("/api/jobs/"+enc(jobId)+"/meme-fail",Map.of("workerId",workerId,"claimId",currentClaimId,
                            "analysisInputFingerprint",String.valueOf(job.getOrDefault("analysisInputFingerprint","")),
                            "error",safe(e.getMessage()),"failedAt",Instant.now().toString()));
                else
                    post("/api/jobs/"+enc(jobId)+"/fail",Map.of("workerId",workerId,"claimId",currentClaimId,"error",safe(e.getMessage()),"failedAt",Instant.now().toString()));
                acknowledged=true;
            }
            catch(Exception callback){error("failure-callback",callback);}
            event("job-finish",Map.of("jobId",jobId,"result",acknowledged?"FAILED":"UNKNOWN","error",safe(e.getMessage()),"stage",stage));
        }finally{
            state="IDLE";currentJob="";currentTopic="";currentClaimId="";jobType="";stage="";detail="";jobStartedAt="";
        }
    }
    /** A stale scoring claim is normal when an RSS refresh replaces its evidence. */
    public static boolean supersededMemeAnalysis(Exception e){
        String message=e==null?"":String.valueOf(e.getMessage());
        return message.startsWith("STALE_MEME_ANALYSIS:")
                ||message.matches("(?s)^/api/jobs/[^ ]+/meme-complete HTTP 409.*");
    }

    private void runMemeAnalysis(Map<String,Object>job)throws Exception{
        if(!cfg.getBool("memeWorthinessEnabled",true))
            throw new IllegalStateException("Worker meme_analysis_v1 is disabled");
        NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(job.get("candidate")));
        String hash=String.valueOf(job.getOrDefault("analysisInputFingerprint",""));
        if(hash.isBlank()||!hash.equals(MemeWorthinessAnalyzer.fingerprint(candidate.cluster())))
            throw new IllegalStateException("STALE_MEME_ANALYSIS: Candidate evidence changed");
        String id=String.valueOf(job.get("jobId"));
        NewsPipeline preparation=new NewsPipeline(root,cfg,
                root.resolve("output/remote_worker/meme_analysis_"+safeName(id)));
        NewsPipeline.Candidate prepared=new NewsPipeline.Candidate(
                preparation.enrichForFraming(candidate.cluster()),candidate.factPackage(),candidate.score());
        Map<String,Object>result=new MemeWorthinessAnalyzer(root,cfg).analyze(prepared.cluster());
        post("/api/jobs/"+enc(id)+"/meme-complete",
                Map.of("workerId",workerId,"claimId",currentClaimId,"result",result,
                        "candidate",prepared.toMap(),"analysisInputFingerprint",hash));
        event("job-finish",Map.of("jobId",id,"result","COMPLETE","stage","MEME_ANALYSIS"));
    }

    private void runPoliticalAnalysis(Map<String,Object>job)throws Exception{
        NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(job.get("candidate")));
        String jobId=String.valueOf(job.get("jobId"));
        String claimedHash=String.valueOf(job.getOrDefault("analysisInputFingerprint",""));
        if(claimedHash.isBlank())throw new IllegalStateException("Missing political analysis input fingerprint");
        // Fetch the selected article once, BEFORE computing the analysis.
        // Submit the enriched candidate and analysis in one controller transaction,
        // so the later video worker sees precisely the version being analyzed.
        NewsPipeline preparation=new NewsPipeline(root,cfg,
                root.resolve("output/remote_worker/analysis_"+safeName(jobId)));
        NewsPipeline.Candidate prepared=new NewsPipeline.Candidate(
                preparation.enrichForFraming(candidate.cluster()),candidate.factPackage(),candidate.score());
        PoliticalFramingAnalyzer analyzer=new PoliticalFramingAnalyzer(root,cfg);
        Map<String,Object> result=analyzer.analyze(prepared.cluster());
        post("/api/jobs/"+enc(jobId)+"/bias-complete",
                Map.of("workerId",workerId,"result",result,
                        "candidate",prepared.toMap(),"analysisInputFingerprint",claimedHash));
        event("job-finish",Map.of("jobId",String.valueOf(job.get("jobId")),"result","COMPLETE","stage","POLITICAL_ANALYSIS"));
    }
    private Map<String,Object> uploadVideo(String jobId,String claimId,Path file)throws Exception{
        HttpRequest.Builder b=request("/api/jobs/"+enc(jobId)+"/video").timeout(Duration.ofMinutes(30))
                .header("Content-Type","video/mp4").header("X-Filename",file.getFileName().toString())
                .header("X-Worker-Id",workerId).header("X-Claim-Id",claimId);
        HttpResponse<String> r=http.send(b.PUT(HttpRequest.BodyPublishers.ofFile(file)).build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("video upload HTTP "+r.statusCode());
        lastContact=Instant.now();return Json.object(Json.parse(r.body()));
    }
    private void progressSafe(String jobId,String claimId,WorkerState update){
        stage=update.stage().name();detail=update.detail()==null?"":update.detail();
        if(detail.startsWith("ENCODER ACTUAL="))actualEncoder=detail.substring("ENCODER ACTUAL=".length());
        if(detail.startsWith("KOKORO USED")){actualTts="Kokoro";actualVoice=extract(detail,"voice=");}
        if(detail.startsWith("QWEN3 FALLBACK USED")){actualTts="Qwen3 fallback";actualVoice=extract(detail,"voice=");}
        if(detail.startsWith("COMFYUI "))comfyActivity=detail;
        event("progress",Map.of("stage",stage,"detail",detail,"timestamp",update.timestamp().toString()));
        try{post("/api/jobs/"+enc(jobId)+"/progress",Map.of("stage",stage,"detail",detail,"timestamp",update.timestamp().toString(),"workerId",workerId,"claimId",claimId));}
        catch(Exception e){error("progress-callback",e);}
    }
    private void heartbeatSafe(){
        try{
            Map<String,Object> m=new LinkedHashMap<>();
            m.put("id",workerId);m.put("state",state);m.put("currentJob",currentJob);m.put("currentTopic",currentTopic);
            m.put("claimId",currentClaimId);
            List<String>capabilities=new ArrayList<>();
            if(cfg.getBool("politicalExposureEnabled",true))capabilities.add("framing_exposure_v1");
            if(cfg.getBool("memePipelineEnabled",true))capabilities.add("meme_ragebait_v1");
            if(cfg.getBool("memeWorthinessEnabled",true))capabilities.add("meme_analysis_v1");
            m.put("capabilities",capabilities);
            m.put("metrics",metrics.get());m.put("timestamp",Instant.now().toString());
            post("/api/workers/heartbeat",m);lastHeartbeat=Instant.now();
        }catch(Exception e){error("heartbeat",e);}
    }
    private Map<String,Object> post(String path,Map<String,Object> body)throws Exception{
        HttpRequest req=request(path).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body),StandardCharsets.UTF_8)).build();
        HttpResponse<String> r=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()<200||r.statusCode()>=300)throw new IllegalStateException(path+" HTTP "+r.statusCode());
        lastContact=Instant.now();return r.body().isBlank()?Map.of():Json.object(Json.parse(r.body()));
    }
    private HttpRequest.Builder request(String path){
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(controller+path)).header("User-Agent","AutoNewsRoller-Worker/1.0");
        if(!token.isBlank())b.header("X-AutoNews-Token",token);
        return b;
    }
    private static String extract(String message,String key){
        int start=message.indexOf(key);if(start<0)return "";
        return message.substring(start+key.length()).split("\\s+",2)[0];
    }
    private static boolean bool(Object x){return x instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(x));}
    private static String enc(String x){return URLEncoder.encode(x,StandardCharsets.UTF_8);}
    private static String safeName(String x){return x==null?"job":x.replaceAll("[^A-Za-z0-9._-]+","_");}
    private static String safe(String x){return x==null?"":x.length()>1200?x.substring(0,1200):x;}
}
