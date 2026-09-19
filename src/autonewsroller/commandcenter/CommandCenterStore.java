package autonewsroller.commandcenter;

import autonewsroller.config.SourceConfig;
import autonewsroller.model.Article;
import autonewsroller.orchestration.NewsPipeline;
import autonewsroller.orchestration.WorkerState;
import autonewsroller.util.Json;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

public final class CommandCenterStore {
    private final Path statePath;
    private final BiasRegistry bias;
    private final boolean autoQueue;
    private final double autoThreshold;
    private final int maxQueued;
    private final int leaseSeconds;
    private final double softWorthThreshold;
    private final double singleSourceAutoQueueThreshold;
    private final Consumer<Map<String,Object>>eventSink;
    private final Map<String,Map<String,Object>>stories=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>feeds=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>workers=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>videos=new LinkedHashMap<>();
    private Map<String,Object>lastScan=new LinkedHashMap<>();
    private boolean scanning;

    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,75,0.74,0.82);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,0.74,0.82);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold){
        this.statePath=statePath;this.bias=bias;this.autoQueue=autoQueue;this.autoThreshold=autoThreshold;this.maxQueued=Math.max(1,maxQueued);this.eventSink=eventSink;this.leaseSeconds=Math.max(30,leaseSeconds);
        this.softWorthThreshold=Math.max(0.0,Math.min(1.0,softWorthThreshold));
        this.singleSourceAutoQueueThreshold=Math.max(this.softWorthThreshold,Math.min(1.0,singleSourceAutoQueueThreshold));
        load();
    }

    public synchronized void scanStarted(){
        scanning=true;
        lastScan=new LinkedHashMap<>(lastScan);
        lastScan.put("startedAt",Instant.now().toString());
        lastScan.put("status","SCANNING");
        emit("scan",Map.of("status","SCANNING"));
    }

    public synchronized void scanFailed(String error){
        scanning=false;lastScan.put("status","FAILED");lastScan.put("error",safe(error));lastScan.put("completedAt",Instant.now().toString());persistQuiet();
        emit("scan",Map.of("status","FAILED","error",safe(error)));
    }

    public synchronized void feedStarted(SourceConfig s){
        Map<String,Object>m=feed(s);m.put("status","SCANNING");m.put("startedAt",Instant.now().toString());m.remove("error");
        emit("feed",publicCopy(m));
    }

    public synchronized void feedSucceeded(SourceConfig s,int entries){
        Map<String,Object>m=feed(s);m.put("status","OK");m.put("entries",entries);m.put("lastSuccess",Instant.now().toString());m.remove("error");
        emit("feed",publicCopy(m));
    }

    public synchronized void feedFailed(SourceConfig s,String reason){
        Map<String,Object>m=feed(s);m.put("status","FAILED");m.put("error",safe(reason));m.put("lastFailure",Instant.now().toString());
        emit("feed",publicCopy(m));
    }

    public synchronized void applyDiscovery(NewsPipeline.DiscoveryResult result){
        scanning=false;
        lastScan=new LinkedHashMap<>(result.toMap());
        lastScan.remove("stories");
        lastScan.put("status","COMPLETE");

        for(NewsPipeline.DiscoveryItem item:result.items()){
            String id=item.cluster().id;
            Map<String,Object>m=stories.computeIfAbsent(id,k->new LinkedHashMap<>());
            String priorStatus=String.valueOf(m.getOrDefault("status",""));
            String decision=String.valueOf(m.getOrDefault("decision","AUTO"));

            m.put("id",id);
            m.put("fingerprint",item.cluster().fingerprint);
            m.put("topic",item.cluster().topic);
            m.put("score",item.score());
            m.put("verified",item.verified());
            m.put("verificationReason",item.verificationReason());
            if(item.verified()){m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");}
            m.put("previouslyGenerated",item.previouslyGenerated());
            m.put("independentSources",item.factPackage().independentSourceCount());
            m.put("sourceCount",item.factPackage().sourceCount());
            m.put("confidence",item.factPackage().confidence());
            boolean softWorthy=!item.previouslyGenerated()
                    &&item.factPackage().independentSourceCount()>=1
                    &&item.score()>=softWorthThreshold;
            boolean manualWorth=Boolean.TRUE.equals(m.get("manualWorth"))||"WORTH".equals(decision)||"MAKE".equals(decision);
            boolean suppressed="SKIP".equals(decision);
            m.put("softWorthy",softWorthy&&!item.verified());
            m.put("worthy",!suppressed&&(item.verified()||softWorthy||manualWorth));
            m.put("publishers",new ArrayList<>(item.cluster().publishers()));
            m.put("category",item.cluster().articles.isEmpty()?"general":item.cluster().articles.get(0).category());
            m.put("sourceUrls",item.cluster().articles.stream().map(Article::url).distinct().toList());
            m.put("latestPublishedAt",item.cluster().articles.stream().map(Article::publishedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).map(Instant::toString).orElse(""));
            m.put("candidate",item.candidate().toMap());
            m.put("sourceMix",bias.mix(item.cluster().publishers()));
            m.put("decision",decision);
            m.put("lastSeen",Instant.now().toString());

            if(priorStatus.isBlank()){
                if(item.previouslyGenerated())m.put("status","COMPLETE_HISTORY");
                else if(item.verified())m.put("status","VERIFIED");
                else if(softWorthy)m.put("status","WORTHY");
                else m.put("status","DISCOVERED");
                m.put("stage","DISCOVERED");m.put("progress",item.verified()?12:softWorthy?10:6);
                m.put("firstSeen",Instant.now().toString());
            }else if(!terminalOrManual(priorStatus)){
                if(item.previouslyGenerated())m.put("status","COMPLETE_HISTORY");
                else if(item.verified())m.put("status","VERIFIED");
                else if(softWorthy)m.put("status","WORTHY");
                else m.put("status","DISCOVERED");
            }
        }

        if(autoQueue){
            int depth=queueDepth();
            List<Map<String,Object>>eligible=stories.values().stream()
                    .filter(x->"AUTO".equals(String.valueOf(x.getOrDefault("decision","AUTO"))))
                    .filter(x->!Boolean.TRUE.equals(x.get("previouslyGenerated")))
                    .filter(x->{
                        boolean verified=Boolean.TRUE.equals(x.get("verified"));
                        boolean soft=Boolean.TRUE.equals(x.get("softWorthy"));
                        double score=number(x.get("score"));
                        return (verified&&"VERIFIED".equals(String.valueOf(x.get("status")))&&score>=autoThreshold)
                                ||(soft&&"WORTHY".equals(String.valueOf(x.get("status")))&&score>=singleSourceAutoQueueThreshold);
                    })
                    .sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed())
                    .toList();
            for(Map<String,Object>m:eligible){
                if(depth>=maxQueued)break;
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);
                if(!verified){
                    m.put("softVerificationOverride",true);
                    m.put("verificationOverrideReason","High-scoring single-source story auto-queued under the relaxed Command Center rule.");
                }
                depth++;emit("story",publicStory(m));
            }
        }
        persistQuiet();
        emit("scan",new LinkedHashMap<>(lastScan));
    }

    public synchronized Map<String,Object> action(String storyId,String action){
        Map<String,Object>m=requireStory(storyId);
        String a=action==null?"":action.trim().toUpperCase(Locale.ROOT);
        switch(a){
            case "MAKE","QUEUE"->{
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","MAKE");m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);
                m.remove("softVerificationOverride");
                if(!verified){
                    m.put("manualVerificationOverride",true);
                    m.put("verificationOverrideReason","User explicitly selected MAKE VIDEO before automatic independent-source verification passed.");
                }else{
                    m.remove("manualVerificationOverride");m.remove("verificationOverrideReason");
                }
            }
            case "WORTH","WORTH_IT","WORTH-IT"->{
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","WORTH");m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);
                m.remove("softVerificationOverride");
                if(!verified){
                    m.put("manualVerificationOverride",true);
                    m.put("verificationOverrideReason","User marked this story WORTH IT before automatic independent-source verification passed.");
                }else{
                    m.remove("manualVerificationOverride");m.remove("verificationOverrideReason");
                }
            }
            case "HOLD"->{
                m.put("decision","HOLD");m.put("status","HOLD");m.put("stage","HOLD");m.put("progress",10);
                m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
            }
            case "SKIP","NOT_WORTH","NOT-WORTH"->{
                m.put("manualWorth",false);m.put("worthy",false);m.put("decision","SKIP");m.put("status","SKIPPED");m.put("stage","SKIPPED");m.put("progress",0);
                m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
            }
            case "AUTO"->{
                m.put("manualWorth",false);m.put("decision","AUTO");m.remove("error");m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                boolean soft=Boolean.TRUE.equals(m.get("softWorthy"));
                boolean worthy=verified||soft;
                m.put("worthy",worthy);
                if(Boolean.TRUE.equals(m.get("previouslyGenerated")))m.put("status","COMPLETE_HISTORY");
                else if(autoQueue&&queueDepth()<maxQueued&&((verified&&number(m.get("score"))>=autoThreshold)||(soft&&number(m.get("score"))>=singleSourceAutoQueueThreshold))){
                    m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);
                    if(!verified){
                        m.put("softVerificationOverride",true);
                        m.put("verificationOverrideReason","High-scoring single-source story auto-queued under the relaxed Command Center rule.");
                    }
                }else if(verified){m.put("status","VERIFIED");m.put("stage","DISCOVERED");m.put("progress",12);}
                else if(soft){m.put("status","WORTHY");m.put("stage","DISCOVERED");m.put("progress",10);}
                else {m.put("status","DISCOVERED");m.put("stage","DISCOVERED");m.put("progress",6);}
            }
            default->throw new IllegalArgumentException("Unknown action: "+action);
        }
        m.put("updatedAt",Instant.now().toString());persistQuiet();Map<String,Object>pub=publicStory(m);emit("story",pub);return pub;
    }

    public synchronized Map<String,Object> claim(String workerId,Map<String,Object>settings){
        recoverExpiredLeases(true);
        Optional<Map<String,Object>>best=stories.values().stream()
                .filter(x->"QUEUED".equals(String.valueOf(x.get("status"))))
                .sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed())
                .findFirst();
        if(best.isEmpty())return null;
        Map<String,Object>m=best.get();
        m.put("status","PRODUCING");m.put("assignedWorker",workerId);m.put("jobStartedAt",Instant.now().toString());m.put("stage","VERIFY");m.put("progress",18);m.put("detail","Claimed by "+workerId);m.remove("error");
        m.put("claimCount",integer(m.get("claimCount"))+1);renewLease(m);
        persistQuiet();emit("story",publicStory(m));
        Map<String,Object>job=new LinkedHashMap<>();job.put("jobId",m.get("id"));job.put("candidate",m.get("candidate"));job.put("settings",new LinkedHashMap<>(settings));job.put("topic",m.get("topic"));job.put("leaseSeconds",leaseSeconds);job.put("manualVerificationOverride",Boolean.TRUE.equals(m.get("manualVerificationOverride")));job.put("softVerificationOverride",Boolean.TRUE.equals(m.get("softVerificationOverride")));job.put("worthy",Boolean.TRUE.equals(m.get("worthy")));return job;
    }

    public synchronized void progress(String jobId,WorkerState state){
        Map<String,Object>m=stories.get(jobId);if(m==null)return;
        String detail=state.detail()==null?"":state.detail();
        m.put("stage",state.stage().name());m.put("detail",detail);m.put("progress",Math.max(number(m.get("progress")),stageProgress(state.stage().name())));m.put("updatedAt",Instant.now().toString());renewLease(m);
        if(detail.startsWith("KOKORO USED")){m.put("ttsEngine","Kokoro");m.put("ttsVoice",valueAfter(detail,"voice="));}
        else if(detail.startsWith("QWEN3 FALLBACK USED")){m.put("ttsEngine","Qwen3 fallback");m.put("ttsVoice",valueAfter(detail,"voice="));}
        else if(detail.startsWith("KOKORO FAILED"))m.put("kokoroFailure",detail);
        if(detail.startsWith("COMFYUI USED")){m.put("visualMode","ComfyUI + procedural cards");m.put("comfyCheckpoint",valueAfter(detail,"checkpoint="," image="));}
        else if(detail.startsWith("COMFYUI FALLBACK")||detail.startsWith("COMFYUI SKIPPED")||detail.startsWith("COMFYUI FAILED")){m.put("visualMode","ComfyUI failed");m.put("comfyStatus",detail);}
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized void heartbeat(String workerId,Map<String,Object>payload){
        Map<String,Object>m=new LinkedHashMap<>(payload);m.put("id",workerId);m.put("lastSeen",Instant.now().toString());workers.put(workerId,m);
        String currentJob=String.valueOf(payload.getOrDefault("currentJob",""));
        if(!currentJob.isBlank()){
            Map<String,Object>story=stories.get(currentJob);
            if(story!=null&&"PRODUCING".equals(String.valueOf(story.get("status")))&&workerId.equals(String.valueOf(story.get("assignedWorker")))){
                renewLease(story);
            }
        }
        persistQuiet();emit("worker",publicCopy(m));
    }

    public synchronized void maintenance(){
        if(recoverExpiredLeases(true)>0)persistQuiet();
    }

    public synchronized void videoUploaded(String jobId,Path path,String filename,long bytes){
        Map<String,Object>m=requireStory(jobId);m.put("serverVideo",path.toString());m.put("videoFilename",filename);m.put("videoBytes",bytes);m.put("stage","UPLOAD");m.put("progress",99);
        emit("story",publicStory(m));
    }

    public synchronized void complete(String jobId,Map<String,Object>metadata){
        Map<String,Object>m=requireStory(jobId);m.put("status","COMPLETE");m.put("stage","COMPLETE");m.put("progress",100);clearLease(m);m.put("completedAt",Instant.now().toString());m.put("result",new LinkedHashMap<>(metadata));m.remove("error");
        Object sidecarObj=metadata.get("sidecar");
        if(sidecarObj instanceof Map<?,?>){
            Map<String,Object>sidecar=Json.object(sidecarObj);
            m.put("ttsEngine",String.valueOf(sidecar.getOrDefault("ttsEngineActuallyUsed",m.getOrDefault("ttsEngine","unknown"))));
            m.put("ttsVoice",String.valueOf(sidecar.getOrDefault("voice",m.getOrDefault("ttsVoice",""))));
            String checkpoint=String.valueOf(sidecar.getOrDefault("comfyCheckpoint",""));
            if(!checkpoint.isBlank()&&!checkpoint.equals("null"))m.put("comfyCheckpoint",checkpoint);
            int comfyImages=0,proceduralImages=0;
            Object images=sidecar.get("imageSources");
            if(images instanceof List<?>list){
                for(Object image:list){
                    if(!(image instanceof Map<?,?>))continue;
                    String type=String.valueOf(Json.object(image).getOrDefault("type",""));
                    if("comfyui-generated".equals(type))comfyImages++;
                    if("procedural-card".equals(type))proceduralImages++;
                }
            }
            m.put("comfyImages",comfyImages);m.put("proceduralImages",proceduralImages);
            m.put("visualMode",comfyImages>0?"ComfyUI + procedural cards":"Procedural cards");
        }
        Map<String,Object>v=new LinkedHashMap<>();v.put("jobId",jobId);v.put("topic",m.get("topic"));v.put("filename",m.getOrDefault("videoFilename",""));v.put("path",m.getOrDefault("serverVideo",""));v.put("completedAt",m.get("completedAt"));v.put("ttsEngine",m.getOrDefault("ttsEngine","unknown"));v.put("ttsVoice",m.getOrDefault("ttsVoice",""));v.put("visualMode",m.getOrDefault("visualMode","unknown"));v.put("comfyCheckpoint",m.getOrDefault("comfyCheckpoint",""));v.put("comfyImages",m.getOrDefault("comfyImages",0));v.put("metadata",new LinkedHashMap<>(metadata));videos.put(jobId,v);
        persistQuiet();emit("story",publicStory(m));emit("video",publicCopy(v));
    }

    public synchronized void fail(String jobId,String error){
        Map<String,Object>m=stories.get(jobId);if(m==null)return;m.put("status","FAILED");m.put("stage","FAILED");m.put("error",safe(error));clearLease(m);m.put("progress",0);m.put("updatedAt",Instant.now().toString());persistQuiet();emit("story",publicStory(m));
    }

    public synchronized Map<String,Object> storyDetail(String id){
        Map<String,Object>m=stories.get(id);return m==null?null:deepCopyMap(m);
    }

    public synchronized Map<String,Object> snapshot(){
        List<Map<String,Object>>ss=stories.values().stream().map(this::publicStory).sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed()).toList();
        List<Map<String,Object>>ff=feeds.values().stream().map(CommandCenterStore::publicCopy).sorted(Comparator.comparing(x->String.valueOf(x.get("name")))).toList();
        List<Map<String,Object>>ww=workers.values().stream().map(x->{Map<String,Object>c=publicCopy(x);c.put("online",isOnline(x));return c;}).toList();
        List<Map<String,Object>>vv=videos.values().stream().map(CommandCenterStore::publicCopy).toList();
        Map<String,Object>counts=new LinkedHashMap<>();
        counts.put("stories",stories.size());
        counts.put("verified",stories.values().stream().filter(x->Boolean.TRUE.equals(x.get("verified"))).count());
        counts.put("worthy",stories.values().stream().filter(x->Boolean.TRUE.equals(x.get("worthy"))).filter(x->!"SKIPPED".equals(String.valueOf(x.get("status")))).count());
        counts.put("queued",countStatus("QUEUED"));counts.put("producing",countStatus("PRODUCING"));counts.put("complete",countStatus("COMPLETE")+countStatus("COMPLETE_HISTORY"));counts.put("hold",countStatus("HOLD"));counts.put("skipped",countStatus("SKIPPED"));counts.put("failed",countStatus("FAILED"));
        counts.put("feedsOk",feeds.values().stream().filter(x->"OK".equals(x.get("status"))).count());counts.put("feedsFailed",feeds.values().stream().filter(x->"FAILED".equals(x.get("status"))).count());counts.put("workersOnline",ww.stream().filter(x->Boolean.TRUE.equals(x.get("online"))).count());
        Map<String,Object>out=new LinkedHashMap<>();out.put("serverTime",Instant.now().toString());out.put("scanning",scanning);out.put("autoQueue",autoQueue);out.put("autoThreshold",autoThreshold);out.put("softWorthThreshold",softWorthThreshold);out.put("singleSourceAutoQueueThreshold",singleSourceAutoQueueThreshold);out.put("maxQueued",maxQueued);out.put("counts",counts);out.put("lastScan",new LinkedHashMap<>(lastScan));out.put("feeds",ff);out.put("stories",ss);out.put("workers",ww);out.put("videos",vv);return out;
    }

    private Map<String,Object>feed(SourceConfig s){
        String key=s.url();Map<String,Object>m=feeds.computeIfAbsent(key,k->new LinkedHashMap<>());m.put("name",s.name());m.put("url",s.url());m.put("category",s.category());return m;
    }
    private Map<String,Object>requireStory(String id){Map<String,Object>m=stories.get(id);if(m==null)throw new IllegalArgumentException("Unknown story: "+id);return m;}
    private long countStatus(String status){return stories.values().stream().filter(x->status.equals(x.get("status"))).count();}
    private int queueDepth(){return (int)stories.values().stream().filter(x->{String s=String.valueOf(x.get("status"));return s.equals("QUEUED")||s.equals("PRODUCING");}).count();}
    private static boolean terminalOrManual(String s){return Set.of("HOLD","SKIPPED","QUEUED","PRODUCING","COMPLETE","COMPLETE_HISTORY","FAILED").contains(s);}
    private static int stageProgress(String s){return switch(s){case "VERIFY"->20;case "SCRIPT"->34;case "TTS"->52;case "VISUALS"->68;case "RENDER"->84;case "AUDIT"->96;case "APPROVED"->99;case "REJECTED"->0;default->25;};}
    private static double number(Object x){return x instanceof Number n?n.doubleValue():0;}
    private static int integer(Object x){return x instanceof Number n?n.intValue():0;}
    private void renewLease(Map<String,Object>m){if("PRODUCING".equals(String.valueOf(m.get("status"))))m.put("leaseUntil",Instant.now().plusSeconds(leaseSeconds).toString());}
    private static void clearLease(Map<String,Object>m){m.remove("leaseUntil");}
    private int recoverExpiredLeases(boolean announce){
        int recovered=0;Instant now=Instant.now();
        for(Map<String,Object>m:stories.values()){
            if(!"PRODUCING".equals(String.valueOf(m.get("status"))))continue;
            boolean expired=true;Object raw=m.get("leaseUntil");
            if(raw!=null)try{expired=!Instant.parse(String.valueOf(raw)).isAfter(now);}catch(Exception ignored){}
            if(!expired)continue;
            String previous=String.valueOf(m.getOrDefault("assignedWorker","unknown"));
            m.put("lastAssignedWorker",previous);m.remove("assignedWorker");m.remove("jobStartedAt");m.remove("leaseUntil");
            m.put("status","QUEUED");m.put("stage","QUEUED");m.put("progress",15);m.put("queuedAt",now.toString());
            m.put("detail","Worker lease expired; automatically requeued");m.put("leaseRecoveries",integer(m.get("leaseRecoveries"))+1);
            recovered++;if(announce)emit("story",publicStory(m));
        }
        return recovered;
    }
    private static String valueAfter(String text,String marker){int i=text.indexOf(marker);return i<0?"":text.substring(i+marker.length()).trim();}
    private static String valueAfter(String text,String marker,String until){int i=text.indexOf(marker);if(i<0)return "";String tail=text.substring(i+marker.length());int j=tail.indexOf(until);return (j<0?tail:tail.substring(0,j)).trim();}
    private static String safe(String x){return x==null?"":x.length()>1000?x.substring(0,1000):x;}
    private static boolean isOnline(Map<String,Object>x){try{return Duration.between(Instant.parse(String.valueOf(x.get("lastSeen"))),Instant.now()).toSeconds()<45;}catch(Exception e){return false;}}
    private Map<String,Object>publicStory(Map<String,Object>m){Map<String,Object>x=deepCopyMap(m);x.remove("candidate");return x;}
    private static Map<String,Object>publicCopy(Map<String,Object>m){return new LinkedHashMap<>(m);}
    @SuppressWarnings("unchecked") private static Map<String,Object>deepCopyMap(Map<String,Object>m){return Json.object(Json.parse(Json.stringify(m)));}

    private void emit(String type,Map<String,Object>payload){
        if(eventSink==null)return;Map<String,Object>e=new LinkedHashMap<>();e.put("type",type);e.put("timestamp",Instant.now().toString());e.put("payload",payload);try{eventSink.accept(e);}catch(Exception ignored){}
    }

    private void load(){
        if(!Files.isRegularFile(statePath))return;
        try{
            Map<String,Object>root=Json.object(Json.read(statePath));copyMap(root.get("stories"),stories);copyMap(root.get("feeds"),feeds);copyMap(root.get("videos"),videos);
            Object scan=root.get("lastScan");if(scan instanceof Map<?,?>)lastScan=new LinkedHashMap<>(Json.object(scan));
            if(recoverExpiredLeases(false)>0)persistQuiet();
        }catch(Exception e){System.err.println("Command center state load failed: "+e.getMessage());}
    }

    private static void copyMap(Object source,Map<String,Map<String,Object>>target){
        if(!(source instanceof Map<?,?>m))return;
        for(var e:m.entrySet())if(e.getValue() instanceof Map<?,?>)target.put(String.valueOf(e.getKey()),new LinkedHashMap<>(Json.object(e.getValue())));
    }

    private void persistQuiet(){
        try{
            Map<String,Object>root=new LinkedHashMap<>();root.put("stories",stories);root.put("feeds",feeds);root.put("videos",videos);root.put("lastScan",lastScan);Json.write(statePath,root);
        }catch(Exception e){System.err.println("Command center state save failed: "+e.getMessage());}
    }
}
