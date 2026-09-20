package autonewsroller.commandcenter;

import autonewsroller.config.SourceConfig;
import autonewsroller.model.Article;
import autonewsroller.orchestration.NewsPipeline;
import autonewsroller.orchestration.WorkerState;
import autonewsroller.util.Json;
import autonewsroller.util.Hashing;

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
    private final int failureMaxRetries;
    private final int failureRetryDelaySeconds;
    private final Consumer<Map<String,Object>>eventSink;
    private final Map<String,Map<String,Object>>stories=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>feeds=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>workers=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>videos=new LinkedHashMap<>();
    private Map<String,Object>lastScan=new LinkedHashMap<>();
    private boolean scanning;

    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,75,0.74,0.82,3,20);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,0.74,0.82,3,20);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,softWorthThreshold,singleSourceAutoQueueThreshold,3,20);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold,int failureMaxRetries,int failureRetryDelaySeconds){
        this.statePath=statePath;this.bias=bias;this.autoQueue=autoQueue;this.autoThreshold=autoThreshold;this.maxQueued=Math.max(1,maxQueued);this.eventSink=eventSink;this.leaseSeconds=Math.max(30,leaseSeconds);
        this.softWorthThreshold=Math.max(0.0,Math.min(1.0,softWorthThreshold));
        this.singleSourceAutoQueueThreshold=Math.max(this.softWorthThreshold,Math.min(1.0,singleSourceAutoQueueThreshold));
        this.failureMaxRetries=Math.max(0,failureMaxRetries);
        this.failureRetryDelaySeconds=Math.max(0,failureRetryDelaySeconds);
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
            boolean politicalCandidate=PoliticalFramingAnalyzer.likelyPolitical(item.cluster());
            m.put("politicalCandidate",politicalCandidate);
            String framingInputHash=Hashing.sha256(item.cluster().articles.stream().map(Article::id).sorted().reduce("",(a,b)->a+"|"+b));
            m.put("biasAnalysisInputHash",framingInputHash);
            String analyzedInputHash=String.valueOf(m.getOrDefault("framingInputHash",""));
            if(politicalCandidate&&!framingInputHash.equals(analyzedInputHash)){
                String biasStatus=String.valueOf(m.getOrDefault("biasAnalysisStatus",""));
                if(!"ANALYZING".equals(biasStatus)){
                    m.put("biasAnalysisStatus","QUEUED");
                    m.put("biasAnalysisQueuedAt",Instant.now().toString());
                    m.remove("biasAnalysisError");
                }
            }else if(!politicalCandidate&&!m.containsKey("framingAnalysis")){
                m.put("biasAnalysisStatus","NOT_POLITICAL");
            }
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
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","MAKE");m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);resetFailureRetryState(m);
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
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","WORTH");m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);resetFailureRetryState(m);
                m.remove("softVerificationOverride");
                if(!verified){
                    m.put("manualVerificationOverride",true);
                    m.put("verificationOverrideReason","User marked this story WORTH IT before automatic independent-source verification passed.");
                }else{
                    m.remove("manualVerificationOverride");m.remove("verificationOverrideReason");
                }
            }
            case "REMAKE","RE-MAKE"->{
                if(!"COMPLETE".equals(String.valueOf(m.get("status"))))
                    throw new IllegalStateException("Only a completed video can be re-made.");
                if(currentVideo(m)==null)
                    throw new IllegalStateException("No completed local video version is available to re-make.");
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","REMAKE");
                m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);
                m.put("remakeCount",integer(m.get("remakeCount"))+1);m.put("remakeQueuedAt",Instant.now().toString());
                m.put("detail","Re-make queued; previous completed video retained in archive");
                m.remove("error");clearLease(m);resetFailureRetryState(m);clearCurrentPublicationState(m);clearCurrentMediaResult(m);
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
                m.put("manualWorth",false);m.put("decision","AUTO");m.remove("error");resetFailureRetryState(m);m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
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
        recoverExpiredBiasLeases(true);

        Optional<Map<String,Object>>bestVideo=stories.values().stream()
                .filter(x->"QUEUED".equals(String.valueOf(x.get("status"))))
                .filter(CommandCenterStore::retryReady)
                .sorted((a,b)->{
                    int retryCmp=Integer.compare(integer(a.get("failureCount")),integer(b.get("failureCount")));
                    return retryCmp!=0?retryCmp:Double.compare(number(b.get("score")),number(a.get("score")));
                })
                .findFirst();
        if(bestVideo.isPresent()){
            Map<String,Object>m=bestVideo.get();
            m.put("status","PRODUCING");m.put("assignedWorker",workerId);m.put("jobStartedAt",Instant.now().toString());m.put("stage","VERIFY");m.put("progress",18);m.put("detail","Claimed by "+workerId);m.remove("error");m.remove("retryNotBefore");
            m.put("claimCount",integer(m.get("claimCount"))+1);renewLease(m);
            persistQuiet();emit("story",publicStory(m));
            Map<String,Object>job=new LinkedHashMap<>();
            job.put("jobType","VIDEO");job.put("jobId",m.get("id"));job.put("candidate",m.get("candidate"));job.put("settings",new LinkedHashMap<>(settings));job.put("topic",m.get("topic"));job.put("leaseSeconds",leaseSeconds);
            job.put("manualVerificationOverride",Boolean.TRUE.equals(m.get("manualVerificationOverride")));job.put("softVerificationOverride",Boolean.TRUE.equals(m.get("softVerificationOverride")));job.put("worthy",Boolean.TRUE.equals(m.get("worthy")));
            return job;
        }

        Optional<Map<String,Object>>bestBias=stories.values().stream()
                .filter(x->"QUEUED".equals(String.valueOf(x.get("biasAnalysisStatus"))))
                .filter(x->x.get("candidate") instanceof Map<?,?>)
                .sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed())
                .findFirst();
        if(bestBias.isEmpty())return null;
        Map<String,Object>m=bestBias.get();
        m.put("biasAnalysisStatus","ANALYZING");m.put("biasAssignedWorker",workerId);m.put("biasAnalysisStartedAt",Instant.now().toString());
        m.put("biasAnalysisLeaseUntil",Instant.now().plusSeconds(leaseSeconds).toString());m.remove("biasAnalysisError");
        persistQuiet();emit("story",publicStory(m));
        Map<String,Object>job=new LinkedHashMap<>();
        job.put("jobType","POLITICAL_ANALYSIS");job.put("jobId",m.get("id"));job.put("candidate",m.get("candidate"));job.put("topic",m.get("topic"));job.put("leaseSeconds",leaseSeconds);
        return job;
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
            }else if(story!=null&&"ANALYZING".equals(String.valueOf(story.get("biasAnalysisStatus")))&&workerId.equals(String.valueOf(story.get("biasAssignedWorker")))){
                story.put("biasAnalysisLeaseUntil",Instant.now().plusSeconds(leaseSeconds).toString());
            }
        }
        persistQuiet();emit("worker",publicCopy(m));
    }

    public synchronized Map<String,Object> queuePoliticalAnalysis(String storyId){
        Map<String,Object>m=requireStory(storyId);
        m.put("politicalCandidate",true);m.put("biasAnalysisStatus","QUEUED");m.put("biasAnalysisQueuedAt",Instant.now().toString());
        m.remove("biasAnalysisError");m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
        persistQuiet();Map<String,Object>pub=publicStory(m);emit("story",pub);return pub;
    }

    public synchronized void politicalAnalysisComplete(String storyId,Map<String,Object>result,String workerId){
        Map<String,Object>m=requireStory(storyId);
        m.put("framingAnalysis",deepCopyMap(result));
        m.put("framingInputHash",String.valueOf(m.getOrDefault("biasAnalysisInputHash","")));
        m.put("biasAnalysisStatus","COMPLETE");m.put("biasAnalyzedAt",Instant.now().toString());m.put("biasAnalyzedBy",workerId);
        m.remove("biasAnalysisError");m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized void politicalAnalysisFail(String storyId,String error){
        Map<String,Object>m=requireStory(storyId);
        m.put("biasAnalysisStatus","FAILED");m.put("biasAnalysisError",safe(error));m.put("biasAnalysisFailedAt",Instant.now().toString());
        m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized void maintenance(){
        int recovered=recoverExpiredLeases(true)+recoverExpiredBiasLeases(true);
        if(recovered>0)persistQuiet();
    }

    public synchronized void videoUploaded(String jobId,Path path,String filename,long bytes){
        Map<String,Object>m=requireStory(jobId);m.put("serverVideo",path.toString());m.put("videoFilename",filename);m.put("videoBytes",bytes);m.put("stage","UPLOAD");m.put("progress",99);
        emit("story",publicStory(m));
    }

    public synchronized void complete(String jobId,Map<String,Object>metadata){
        Map<String,Object>m=requireStory(jobId);
        int version=nextVideoVersion(jobId);
        String versionId=videoVersionId(jobId,version);
        m.put("status","COMPLETE");m.put("stage","COMPLETE");m.put("progress",100);clearLease(m);m.put("completedAt",Instant.now().toString());m.put("result",new LinkedHashMap<>(metadata));m.remove("error");
        m.put("videoVersion",version);m.put("currentVideoVersionId",versionId);
        clearCurrentPublicationState(m);m.put("uploaded",false);m.put("scrapped",false);
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
        for(Map<String,Object>existing:videos.values())if(jobId.equals(String.valueOf(existing.get("storyId"))))existing.put("current",false);
        Map<String,Object>v=new LinkedHashMap<>();v.put("versionId",versionId);v.put("storyId",jobId);v.put("jobId",jobId);v.put("version",version);v.put("current",true);v.put("topic",m.get("topic"));v.put("filename",m.getOrDefault("videoFilename",""));v.put("path",m.getOrDefault("serverVideo",""));v.put("completedAt",m.get("completedAt"));v.put("ttsEngine",m.getOrDefault("ttsEngine","unknown"));v.put("ttsVoice",m.getOrDefault("ttsVoice",""));v.put("visualMode",m.getOrDefault("visualMode","unknown"));v.put("comfyCheckpoint",m.getOrDefault("comfyCheckpoint",""));v.put("comfyImages",m.getOrDefault("comfyImages",0));v.put("metadata",new LinkedHashMap<>(metadata));copyPublicationState(m,v);videos.put(versionId,v);
        persistQuiet();emit("story",publicStory(m));emit("video",publicCopy(v));
    }

    public synchronized Map<String,Object> setUploadStatus(String storyId,boolean uploaded,String platform,String note){
        return setUploadStatus(storyId,"",uploaded,platform,note);
    }

    public synchronized Map<String,Object> setUploadStatus(String storyId,String versionId,boolean uploaded,String platform,String note){
        Map<String,Object>m=requireStory(storyId);
        Map<String,Object>v=resolveVideo(storyId,versionId);
        if(v==null)throw new IllegalStateException("No completed local video version is available.");
        if(uploaded&&Boolean.TRUE.equals(v.get("scrapped")))
            throw new IllegalStateException("A scrapped video cannot be marked uploaded until it is restored.");

        Instant now=Instant.now();
        String cleanPlatform=safeText(platform,80);
        String cleanNote=safeText(note,500);

        appendStateHistory(v,"uploadHistory",now,Map.of(
                "uploaded",uploaded,
                "platform",cleanPlatform,
                "note",cleanNote
        ));

        v.put("uploaded",uploaded);
        v.put("uploadUpdatedAt",now.toString());
        if(uploaded){
            v.put("uploadedAt",now.toString());
            if(cleanPlatform.isBlank())v.remove("uploadedPlatform");else v.put("uploadedPlatform",cleanPlatform);
            if(cleanNote.isBlank())v.remove("uploadNote");else v.put("uploadNote",cleanNote);
        }else{
            v.remove("uploadedAt");v.remove("uploadedPlatform");v.remove("uploadNote");
        }

        if(isCurrentVideo(m,v))copyPublicationState(v,m);

        persistQuiet();
        Map<String,Object>pub=publicStory(m);emit("story",pub);emit("video",publicCopy(v));
        return pub;
    }

    public synchronized Map<String,Object> setScrapStatus(String storyId,String versionId,boolean scrapped,String reason){
        Map<String,Object>m=requireStory(storyId);
        Map<String,Object>v=resolveVideo(storyId,versionId);
        if(v==null)throw new IllegalStateException("No completed local video version is available.");

        Instant now=Instant.now();
        String cleanReason=safeText(reason,500);
        Map<String,Object>event=new LinkedHashMap<>();
        event.put("scrapped",scrapped);
        if(!cleanReason.isBlank())event.put("reason",cleanReason);
        appendStateHistory(v,"scrapHistory",now,event);

        v.put("scrapped",scrapped);
        v.put("scrapUpdatedAt",now.toString());
        if(scrapped){
            v.put("scrappedAt",now.toString());
            if(cleanReason.isBlank())v.remove("scrapReason");else v.put("scrapReason",cleanReason);
        }else{
            v.remove("scrappedAt");v.remove("scrapReason");
        }

        if(isCurrentVideo(m,v))copyPublicationState(v,m);

        persistQuiet();
        Map<String,Object>pub=publicStory(m);emit("story",pub);emit("video",publicCopy(v));
        return pub;
    }

    public synchronized void fail(String jobId,String error){
        Map<String,Object>m=stories.get(jobId);if(m==null)return;
        Instant now=Instant.now();
        int failures=integer(m.get("failureCount"))+1;
        String message=safe(error);
        m.put("failureCount",failures);
        m.put("lastFailure",message);
        m.put("lastFailedAt",now.toString());
        m.put("error",message);
        m.put("updatedAt",now.toString());
        m.remove("assignedWorker");m.remove("jobStartedAt");clearLease(m);

        if(failures<=failureMaxRetries){
            Instant retryAt=now.plusSeconds(failureRetryDelaySeconds);
            m.put("status","QUEUED");m.put("stage","QUEUED");m.put("progress",15);m.put("queuedAt",now.toString());
            m.put("retryNotBefore",retryAt.toString());
            m.put("detail","Production failed; automatic retry "+failures+"/"+failureMaxRetries+" queued"+(failureRetryDelaySeconds>0?" in "+failureRetryDelaySeconds+"s":""));
        }else{
            m.put("status","FAILED");m.put("stage","FAILED");m.put("progress",0);m.remove("retryNotBefore");
            m.put("detail","Production failed after "+failureMaxRetries+" automatic retries; manual action required");
        }
        persistQuiet();emit("story",publicStory(m));
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
        counts.put("worthy",stories.values().stream().filter(CommandCenterStore::isActionableWorthy).count());
        counts.put("queued",countStatus("QUEUED"));counts.put("producing",countStatus("PRODUCING"));counts.put("complete",countStatus("COMPLETE")+countStatus("COMPLETE_HISTORY"));counts.put("hold",countStatus("HOLD"));counts.put("skipped",countStatus("SKIPPED"));counts.put("failed",countStatus("FAILED"));
        counts.put("toPost",stories.values().stream().filter(CommandCenterStore::isToPost).count());
        counts.put("uploaded",stories.values().stream().filter(x->Boolean.TRUE.equals(x.get("uploaded"))&&!Boolean.TRUE.equals(x.get("scrapped"))).count());
        counts.put("scrapped",stories.values().stream().filter(x->Boolean.TRUE.equals(x.get("scrapped"))).count());
        counts.put("videoVersions",videos.size());
        counts.put("feedsOk",feeds.values().stream().filter(x->"OK".equals(x.get("status"))).count());counts.put("feedsFailed",feeds.values().stream().filter(x->"FAILED".equals(x.get("status"))).count());counts.put("workersOnline",ww.stream().filter(x->Boolean.TRUE.equals(x.get("online"))).count());
        counts.put("biasQueued",stories.values().stream().filter(x->"QUEUED".equals(x.get("biasAnalysisStatus"))).count());
        counts.put("biasAnalyzing",stories.values().stream().filter(x->"ANALYZING".equals(x.get("biasAnalysisStatus"))).count());
        counts.put("biasComplete",stories.values().stream().filter(x->"COMPLETE".equals(x.get("biasAnalysisStatus"))).count());
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
    private static boolean retryReady(Map<String,Object>m){
        Object raw=m.get("retryNotBefore");
        if(raw==null)return true;
        try{return !Instant.parse(String.valueOf(raw)).isAfter(Instant.now());}
        catch(Exception ignored){return true;}
    }
    private static void resetFailureRetryState(Map<String,Object>m){
        m.remove("failureCount");m.remove("retryNotBefore");m.remove("lastFailure");m.remove("lastFailedAt");
    }
    private static boolean isToPost(Map<String,Object>m){
        return "COMPLETE".equals(String.valueOf(m.get("status")))
                &&!Boolean.TRUE.equals(m.get("uploaded"))
                &&!Boolean.TRUE.equals(m.get("scrapped"));
    }
    private Map<String,Object>currentVideo(Map<String,Object>story){
        String current=String.valueOf(story.getOrDefault("currentVideoVersionId",""));
        if(!current.isBlank()&&videos.containsKey(current))return videos.get(current);
        String storyId=String.valueOf(story.getOrDefault("id",""));
        return videos.values().stream()
                .filter(v->storyId.equals(String.valueOf(v.get("storyId"))))
                .max(Comparator.comparingInt(v->integer(v.get("version"))))
                .orElse(null);
    }
    private Map<String,Object>resolveVideo(String storyId,String versionId){
        if(versionId!=null&&!versionId.isBlank()){
            Map<String,Object>v=videos.get(versionId);
            return v!=null&&storyId.equals(String.valueOf(v.get("storyId")))?v:null;
        }
        return currentVideo(requireStory(storyId));
    }
    private static boolean isCurrentVideo(Map<String,Object>story,Map<String,Object>video){
        return String.valueOf(video.getOrDefault("versionId","")).equals(String.valueOf(story.getOrDefault("currentVideoVersionId","")));
    }
    private int nextVideoVersion(String storyId){
        return videos.values().stream()
                .filter(v->storyId.equals(String.valueOf(v.get("storyId"))))
                .mapToInt(v->integer(v.get("version"))).max().orElse(0)+1;
    }
    private static String videoVersionId(String storyId,int version){return storyId+"#v"+version;}
    private static void copyPublicationState(Map<String,Object>from,Map<String,Object>to){
        to.put("uploaded",Boolean.TRUE.equals(from.get("uploaded")));
        to.put("scrapped",Boolean.TRUE.equals(from.get("scrapped")));
        for(String key:List.of("uploadedAt","uploadedPlatform","uploadNote","uploadUpdatedAt","uploadHistory","scrappedAt","scrapReason","scrapUpdatedAt","scrapHistory"))
            copyOrRemove(from,to,key);
    }
    private static void clearCurrentPublicationState(Map<String,Object>m){
        m.put("uploaded",false);m.put("scrapped",false);
        for(String key:List.of("uploadedAt","uploadedPlatform","uploadNote","uploadUpdatedAt","uploadHistory","scrappedAt","scrapReason","scrapUpdatedAt","scrapHistory"))m.remove(key);
    }
    private static void clearCurrentMediaResult(Map<String,Object>m){
        for(String key:List.of("serverVideo","videoFilename","videoBytes","completedAt","result","ttsEngine","ttsVoice","visualMode","comfyCheckpoint","comfyImages","proceduralImages","comfyStatus","kokoroFailure"))m.remove(key);
    }
    private static void appendStateHistory(Map<String,Object>target,String key,Instant now,Map<String,Object>values){
        List<Object>history=new ArrayList<>();
        Object existing=target.get(key);
        if(existing instanceof List<?>list)history.addAll(list);
        Map<String,Object>event=new LinkedHashMap<>();event.put("at",now.toString());
        for(var e:values.entrySet())if(e.getValue()!=null&&!String.valueOf(e.getValue()).isBlank())event.put(e.getKey(),e.getValue());
        if(values.containsKey("uploaded"))event.put("uploaded",values.get("uploaded"));
        if(values.containsKey("scrapped"))event.put("scrapped",values.get("scrapped"));
        history.add(event);while(history.size()>50)history.remove(0);target.put(key,history);
    }
    private static void copyOrRemove(Map<String,Object>from,Map<String,Object>to,String key){
        if(from.containsKey(key))to.put(key,from.get(key));else to.remove(key);
    }
    private static String safeText(String x,int max){
        if(x==null)return "";
        String s=x.replaceAll("\\s+"," ").trim();
        return s.length()<=max?s:s.substring(0,max);
    }
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
    private int recoverExpiredBiasLeases(boolean announce){
        int recovered=0;Instant now=Instant.now();
        for(Map<String,Object>m:stories.values()){
            if(!"ANALYZING".equals(String.valueOf(m.get("biasAnalysisStatus"))))continue;
            boolean expired=true;Object raw=m.get("biasAnalysisLeaseUntil");
            if(raw!=null)try{expired=!Instant.parse(String.valueOf(raw)).isAfter(now);}catch(Exception ignored){}
            if(!expired)continue;
            String previous=String.valueOf(m.getOrDefault("biasAssignedWorker","unknown"));
            m.put("biasLastAssignedWorker",previous);m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
            m.put("biasAnalysisStatus","QUEUED");m.put("biasAnalysisQueuedAt",now.toString());
            m.put("biasAnalysisError","Analysis worker lease expired; automatically requeued.");
            m.put("biasLeaseRecoveries",integer(m.get("biasLeaseRecoveries"))+1);
            recovered++;if(announce)emit("story",publicStory(m));
        }
        return recovered;
    }
    private static String valueAfter(String text,String marker){int i=text.indexOf(marker);return i<0?"":text.substring(i+marker.length()).trim();}
    private static String valueAfter(String text,String marker,String until){int i=text.indexOf(marker);if(i<0)return "";String tail=text.substring(i+marker.length());int j=tail.indexOf(until);return (j<0?tail:tail.substring(0,j)).trim();}
    private static String safe(String x){return x==null?"":x.length()>1000?x.substring(0,1000):x;}
    private static boolean isOnline(Map<String,Object>x){try{return Duration.between(Instant.parse(String.valueOf(x.get("lastSeen"))),Instant.now()).toSeconds()<45;}catch(Exception e){return false;}}
    private Map<String,Object>publicStory(Map<String,Object>m){Map<String,Object>x=deepCopyMap(m);x.remove("candidate");x.put("actionableWorthy",isActionableWorthy(m));return x;}
    private static boolean isActionableWorthy(Map<String,Object>m){
        if(!Boolean.TRUE.equals(m.get("worthy")))return false;
        String status=String.valueOf(m.getOrDefault("status",""));
        return Set.of("DISCOVERED","VERIFIED","WORTHY").contains(status);
    }
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
            boolean migrated=migrateVideoArchive();
            if(migrated||recoverExpiredLeases(false)+recoverExpiredBiasLeases(false)>0)persistQuiet();
        }catch(Exception e){System.err.println("Command center state load failed: "+e.getMessage());}
    }

    private boolean migrateVideoArchive(){
        boolean changed=false;
        Map<String,Map<String,Object>>rebuilt=new LinkedHashMap<>();
        for(var entry:videos.entrySet()){
            Map<String,Object>v=new LinkedHashMap<>(entry.getValue());
            String storyId=String.valueOf(v.getOrDefault("storyId",v.getOrDefault("jobId",entry.getKey())));
            int version=integer(v.get("version"));
            if(version<=0){
                Map<String,Object>story=stories.get(storyId);
                version=story==null?1:Math.max(1,integer(story.get("videoVersion")));
                changed=true;
            }
            String versionId=String.valueOf(v.getOrDefault("versionId",videoVersionId(storyId,version)));
            if(!v.containsKey("storyId")||!v.containsKey("versionId")||!v.containsKey("version"))changed=true;
            v.put("storyId",storyId);v.put("jobId",storyId);v.put("version",version);v.put("versionId",versionId);
            v.putIfAbsent("scrapped",false);v.putIfAbsent("uploaded",false);
            rebuilt.put(versionId,v);
        }
        if(!rebuilt.keySet().equals(videos.keySet()))changed=true;
        videos.clear();videos.putAll(rebuilt);

        for(Map<String,Object>story:stories.values()){
            String storyId=String.valueOf(story.getOrDefault("id",""));
            Optional<Map<String,Object>>latest=videos.values().stream()
                    .filter(v->storyId.equals(String.valueOf(v.get("storyId"))))
                    .max(Comparator.comparingInt(v->integer(v.get("version"))));
            if(latest.isEmpty())continue;
            Map<String,Object>v=latest.get();
            int version=integer(v.get("version"));
            String versionId=String.valueOf(v.get("versionId"));
            if(integer(story.get("videoVersion"))!=version||!versionId.equals(String.valueOf(story.getOrDefault("currentVideoVersionId",""))))changed=true;
            story.put("videoVersion",version);story.put("currentVideoVersionId",versionId);
            for(Map<String,Object>x:videos.values())if(storyId.equals(String.valueOf(x.get("storyId"))))x.put("current",x==v);
            if("COMPLETE".equals(String.valueOf(story.get("status")))){
                if(story.containsKey("uploadHistory")&&!v.containsKey("uploadHistory"))v.put("uploadHistory",story.get("uploadHistory"));
                copyPublicationState(story,v);copyPublicationState(v,story);
            }
        }
        return changed;
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
