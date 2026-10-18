package autonewsroller.commandcenter;

import autonewsroller.config.SourceConfig;
import autonewsroller.model.Article;
import autonewsroller.model.PoliticalVisualData;
import autonewsroller.orchestration.NewsPipeline;
import autonewsroller.orchestration.ProductionMode;
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
    private boolean autoQueue;
    private final double autoThreshold;
    private final int maxQueued;
    private final int leaseSeconds;
    private final double softWorthThreshold;
    private final double singleSourceAutoQueueThreshold;
    private final int failureMaxRetries;
    private final int failureRetryDelaySeconds;
    private final int leaseRecoveryMaxRetries;
    private final boolean pairFramingAfterStandard;
    private final Consumer<Map<String,Object>>eventSink;
    private final Map<String,Map<String,Object>>stories=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>feeds=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>workers=new LinkedHashMap<>();
    private final Map<String,Map<String,Object>>videos=new LinkedHashMap<>();
    private final Map<String,String>processedOperations=new LinkedHashMap<>();
    private long lastHeartbeatSave;
    private boolean stateDirty;
    private Map<String,Object>lastScan=new LinkedHashMap<>();
    private boolean scanning;

    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,180,0.74,0.82,3,20,3);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,0.74,0.82,3,20,3);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,softWorthThreshold,singleSourceAutoQueueThreshold,3,20,3);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold,int failureMaxRetries,int failureRetryDelaySeconds){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,softWorthThreshold,singleSourceAutoQueueThreshold,failureMaxRetries,failureRetryDelaySeconds,3);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold,int failureMaxRetries,int failureRetryDelaySeconds,int leaseRecoveryMaxRetries){
        this(statePath,bias,autoQueue,autoThreshold,maxQueued,eventSink,leaseSeconds,softWorthThreshold,
                singleSourceAutoQueueThreshold,failureMaxRetries,failureRetryDelaySeconds,leaseRecoveryMaxRetries,false);
    }
    public CommandCenterStore(Path statePath,BiasRegistry bias,boolean autoQueue,double autoThreshold,int maxQueued,Consumer<Map<String,Object>>eventSink,int leaseSeconds,double softWorthThreshold,double singleSourceAutoQueueThreshold,int failureMaxRetries,int failureRetryDelaySeconds,int leaseRecoveryMaxRetries,boolean pairFramingAfterStandard){
        this.pairFramingAfterStandard=pairFramingAfterStandard;
        this.statePath=statePath;this.bias=bias;this.autoQueue=autoQueue;this.autoThreshold=autoThreshold;this.maxQueued=Math.max(1,maxQueued);this.eventSink=eventSink;this.leaseSeconds=Math.max(30,leaseSeconds);
        this.softWorthThreshold=Math.max(0.0,Math.min(1.0,softWorthThreshold));
        this.singleSourceAutoQueueThreshold=Math.max(this.softWorthThreshold,Math.min(1.0,singleSourceAutoQueueThreshold));
        this.failureMaxRetries=Math.max(0,failureMaxRetries);
        this.failureRetryDelaySeconds=Math.max(0,failureRetryDelaySeconds);
        this.leaseRecoveryMaxRetries=Math.max(0,leaseRecoveryMaxRetries);
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
            boolean politicalCandidate=PoliticalFramingAnalyzer.likelyPolitical(item.cluster())||
                    Boolean.TRUE.equals(m.get("manualFramingRequested"));
            m.put("politicalCandidate",politicalCandidate);
            String framingInputHash=Hashing.sha256(item.cluster().articles.stream().map(a->a.id()+"|"+a.title()+"|"+a.description()+"|"+a.bodyText()).sorted().reduce("",(a,b)->a+"|"+b));
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
                markStandardPairQueued(m);
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
        return action(storyId,action,"");
    }
    public synchronized Map<String,Object> action(String storyId,String action,String operationId){
        Map<String,Object>m=requireStory(storyId);
        String a=action==null?"":action.trim().toUpperCase(Locale.ROOT);
        // The retry button on a failed SECOND video should retry ONLY the framing
        // review. Never overwrite/re-render an already successful first video.
        if("FAILED".equals(m.get("status"))&&"FRAMING_FAILED".equals(m.get("pairedVideoState"))
                &&Set.of("MAKE","QUEUE","WORTH","WORTH_IT","WORTH-IT").contains(a))
            return action(storyId,"MAKE_FRAMING_EXPOSURE",operationId);
        String identity="action:"+storyId+":"+a;
        if(alreadyHandled(operationId,identity))return publicStory(m);
        String status=String.valueOf(m.getOrDefault("status",""));
        if(("MAKE".equals(a)||"QUEUE".equals(a)||"WORTH".equals(a)||"WORTH_IT".equals(a)||"WORTH-IT".equals(a))
                &&("QUEUED".equals(status)||"PRODUCING".equals(status)))return publicStory(m);
        if(("MAKE".equals(a)||"QUEUE".equals(a)||"WORTH".equals(a)||"WORTH_IT".equals(a)||"WORTH-IT".equals(a))
                &&("COMPLETE".equals(status)||"COMPLETE_HISTORY".equals(status)))
            throw new IllegalStateException("Completed video cannot be queued again with MAKE/WORTH; use RE-MAKE.");
        if("PRODUCING".equals(status))
            throw new IllegalStateException("Job is being produced. Wait for completion before changing its decision.");
        switch(a){
            case "MAKE","QUEUE"->{
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","MAKE");m.put("status","QUEUED");m.put("productionMode","STANDARD");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);resetFailureRetryState(m);markStandardPairQueued(m);
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
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","WORTH");m.put("status","QUEUED");m.put("productionMode","STANDARD");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);m.remove("error");clearLease(m);resetFailureRetryState(m);markStandardPairQueued(m);
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
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision","REMAKE");m.put("productionMode","STANDARD");
                m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);
                m.put("remakeCount",integer(m.get("remakeCount"))+1);m.put("remakeQueuedAt",Instant.now().toString());
                m.put("detail","Re-make queued; previous completed video retained in archive");
                m.remove("error");clearLease(m);resetFailureRetryState(m);clearCurrentPublicationState(m);clearCurrentMediaResult(m);
                markStandardPairQueued(m);
            }
            case "MAKE_FRAMING_EXPOSURE","REMAKE_FRAMING_EXPOSURE"->{
                if("QUEUED".equals(status))throw new IllegalStateException("Another video is already queued.");
                if(!(m.get("candidate") instanceof Map<?,?>))
                    throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: No source article available.");
                NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(m.get("candidate")));
                if(candidate.cluster().articles.stream().noneMatch(article->
                        !article.title().isBlank()&&!article.publisher().isBlank()&&!article.url().isBlank()))
                    throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: An attributed article headline and URL are required.");
                if("REMAKE_FRAMING_EXPOSURE".equals(a)&&!"COMPLETE".equals(status))
                    throw new IllegalStateException("Only completed videos can be remade.");
                if("COMPLETE".equals(status)&&currentVideo(m)==null)
                    throw new IllegalStateException("Existing version missing; cannot archive.");
                m.put("manualWorth",true);m.put("worthy",true);m.put("decision",a);
                m.put("manualFramingRequested",true);m.put("politicalCandidate",true);
                if("FRAMING_FAILED".equals(m.get("pairedVideoState")))
                    m.put("pairedVideoState","FRAMING_QUEUED");
                m.put("productionMode",ProductionMode.FRAMING_EXPOSURE.name());
                m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("progress",15);
                m.remove("error");clearLease(m);resetFailureRetryState(m);
                m.remove("manualVerificationOverride");m.remove("softVerificationOverride");
                if("COMPLETE".equals(status)){
                    m.put("remakeCount",integer(m.get("remakeCount"))+1);
                    clearCurrentPublicationState(m);clearCurrentMediaResult(m);
                }
                String inputHash=String.valueOf(m.getOrDefault("biasAnalysisInputHash",""));
                String analyzedHash=String.valueOf(m.getOrDefault("framingInputHash",""));
                if(!"COMPLETE".equals(String.valueOf(m.get("biasAnalysisStatus")))||!inputHash.equals(analyzedHash)){
                    if(!"ANALYZING".equals(String.valueOf(m.get("biasAnalysisStatus")))){
                        m.put("biasAnalysisStatus","QUEUED");
                        m.put("biasAnalysisQueuedAt",Instant.now().toString());m.remove("biasAnalysisError");
                    }
                    m.put("stage","WAITING_FOR_FRAMING_ANALYSIS");
                    m.put("detail","Waiting for political framing analysis");
                }else{
                    m.put("stage","QUEUED");m.put("detail","Waiting for compatible framing worker");
                }
            }
            case "HOLD"->{
                if("FRAMING_QUEUED".equals(m.get("pairedVideoState")))
                    m.put("pairedVideoState","FRAMING_HELD");
                m.put("decision","HOLD");m.put("status","HOLD");m.put("stage","HOLD");m.put("progress",10);
                m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
            }
            case "SKIP","NOT_WORTH","NOT-WORTH"->{
                if("FRAMING_QUEUED".equals(m.get("pairedVideoState")))
                    m.put("pairedVideoState","FRAMING_SKIPPED");
                m.put("manualWorth",false);m.put("worthy",false);m.put("decision","SKIP");m.put("status","SKIPPED");m.put("stage","SKIPPED");m.put("progress",0);
                m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
            }
            case "AUTO"->{
                m.put("manualWorth",false);m.put("decision","AUTO");m.put("productionMode","STANDARD");m.remove("error");resetFailureRetryState(m);m.remove("manualVerificationOverride");m.remove("softVerificationOverride");m.remove("verificationOverrideReason");
                boolean verified=Boolean.TRUE.equals(m.get("verified"));
                boolean soft=Boolean.TRUE.equals(m.get("softWorthy"));
                boolean worthy=verified||soft;
                m.put("worthy",worthy);
                if(Boolean.TRUE.equals(m.get("previouslyGenerated")))m.put("status","COMPLETE_HISTORY");
                else if(autoQueue&&queueDepth()<maxQueued&&((verified&&number(m.get("score"))>=autoThreshold)||(soft&&number(m.get("score"))>=singleSourceAutoQueueThreshold))){
                    m.put("status","QUEUED");m.put("queuedAt",Instant.now().toString());m.put("stage","QUEUED");m.put("progress",15);
                    markStandardPairQueued(m);
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
        m.put("updatedAt",Instant.now().toString());remember(operationId,identity);persistRequired();Map<String,Object>pub=publicStory(m);emit("story",pub);return pub;
    }

    public synchronized Map<String,Object> claim(String workerId,Map<String,Object>settings){
        recoverExpiredLeases(true);
        recoverExpiredBiasLeases(true);

        Optional<Map<String,Object>>bestVideo=stories.values().stream()
                .filter(x->"QUEUED".equals(String.valueOf(x.get("status"))))
                .filter(CommandCenterStore::retryReady)
                .filter(x->claimEligible(x,workerId))
                .sorted((a,b)->{
                    int retryCmp=Integer.compare(integer(a.get("failureCount")),integer(b.get("failureCount")));
                    return retryCmp!=0?retryCmp:Double.compare(number(b.get("score")),number(a.get("score")));
                })
                .findFirst();
        if(bestVideo.isPresent()){
            Map<String,Object>m=bestVideo.get();
            Instant claimedAt=Instant.now();
            if("STANDARD_QUEUED".equals(m.get("pairedVideoState"))
                    &&ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.STANDARD)
                m.put("pairedVideoState","STANDARD_PRODUCING");
            if("FRAMING_QUEUED".equals(m.get("pairedVideoState"))
                    &&ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.FRAMING_EXPOSURE)
                m.put("pairedVideoState","FRAMING_PRODUCING");
            m.put("claimId",UUID.randomUUID().toString());m.put("status","PRODUCING");m.put("assignedWorker",workerId);m.put("jobStartedAt",claimedAt.toString());m.put("stage","VERIFY");m.put("progress",18);m.put("detail","Claimed by "+workerId);m.remove("error");m.remove("retryNotBefore");
            int claimCount=integer(m.get("claimCount"))+1;m.put("claimCount",claimCount);m.remove("failureType");
            appendProductionAttempt(m,claimedAt,Map.of("event","CLAIMED","attempt",claimCount,"worker",workerId,"stage","VERIFY"));
            renewLease(m);
            persistQuiet();emit("story",publicStory(m));
            Map<String,Object>job=new LinkedHashMap<>();
            job.put("jobType","VIDEO");job.put("productionMode",ProductionMode.fromPersisted(m.get("productionMode")).name());job.put("jobId",m.get("id"));job.put("candidate",m.get("candidate"));job.put("settings",new LinkedHashMap<>(settings));job.put("topic",m.get("topic"));job.put("leaseSeconds",leaseSeconds);
            job.put("claimId",m.get("claimId"));job.put("manualVerificationOverride",Boolean.TRUE.equals(m.get("manualVerificationOverride")));job.put("softVerificationOverride",Boolean.TRUE.equals(m.get("softVerificationOverride")));job.put("worthy",Boolean.TRUE.equals(m.get("worthy")));
            Map<String,Object>sourceMix=m.get("sourceMix") instanceof Map<?,?>?Json.object(m.get("sourceMix")):Map.of();
            Map<String,Object>framing=m.get("framingAnalysis") instanceof Map<?,?>?Json.object(m.get("framingAnalysis")):Map.of();
            PoliticalVisualData political=PoliticalVisualData.from(
                    Boolean.TRUE.equals(m.get("politicalCandidate")),
                    sourceMix,
                    framing,
                    String.valueOf(m.getOrDefault("biasAnalysisStatus","")),
                    String.valueOf(m.getOrDefault("biasAnalysisError",""))
            );
            job.put("politicalVisualData",political.toMap());
            if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.FRAMING_EXPOSURE){
                job.put("framingContentFingerprint",m.getOrDefault("biasAnalysisInputHash",""));
                job.put("framingAnalysis",new LinkedHashMap<>(framing));
            }
            return job;
        }

        Optional<Map<String,Object>>bestBias=stories.values().stream()
                .filter(x->"QUEUED".equals(String.valueOf(x.get("biasAnalysisStatus"))))
                .filter(x->x.get("candidate") instanceof Map<?,?>)
                .sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed())
                .findFirst();
        if(bestBias.isEmpty())return null;
        Map<String,Object>m=bestBias.get();
        m.put("biasAnalysisStatus","ANALYZING");m.put("biasAssignedWorker",workerId);
        m.put("biasClaimInputHash",m.getOrDefault("biasAnalysisInputHash",""));
        m.put("biasAnalysisStartedAt",Instant.now().toString());
        m.put("biasAnalysisLeaseUntil",Instant.now().plusSeconds(leaseSeconds).toString());m.remove("biasAnalysisError");
        persistQuiet();emit("story",publicStory(m));
        Map<String,Object>job=new LinkedHashMap<>();
        job.put("jobType","POLITICAL_ANALYSIS");job.put("jobId",m.get("id"));job.put("candidate",m.get("candidate"));job.put("topic",m.get("topic"));job.put("leaseSeconds",leaseSeconds);
        return job;
    }

    public synchronized void progress(String jobId,WorkerState state){progress(jobId,state,"","");}
    public synchronized void progress(String jobId,WorkerState state,String workerId){progress(jobId,state,workerId,"");}
    public synchronized void progress(String jobId,WorkerState state,String workerId,String claimId){
        Map<String,Object>m=stories.get(jobId);
        if(m==null||!workerOwnsActiveJob(m,workerId,claimId))return;
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
            if(story!=null&&"PRODUCING".equals(String.valueOf(story.get("status")))&&workerId.equals(String.valueOf(story.get("assignedWorker")))
                    &&claimMatches(story,String.valueOf(payload.getOrDefault("claimId","")))){
                renewLease(story);
            }else if(story!=null&&"ANALYZING".equals(String.valueOf(story.get("biasAnalysisStatus")))&&workerId.equals(String.valueOf(story.get("biasAssignedWorker")))){
                story.put("biasAnalysisLeaseUntil",Instant.now().plusSeconds(leaseSeconds).toString());
            }
        }
        if(System.currentTimeMillis()-lastHeartbeatSave>=5000){persistQuiet();lastHeartbeatSave=System.currentTimeMillis();}
        emit("worker",publicCopy(m));
    }

    public synchronized Map<String,Object> queuePoliticalAnalysis(String storyId){return queuePoliticalAnalysis(storyId,"");}
    public synchronized Map<String,Object> queuePoliticalAnalysis(String storyId,String operationId){
        Map<String,Object>m=requireStory(storyId);
        String identity="bias:"+storyId;
        if(alreadyHandled(operationId,identity))return publicStory(m);
        if("QUEUED".equals(m.get("biasAnalysisStatus"))||"ANALYZING".equals(m.get("biasAnalysisStatus")))return publicStory(m);
        m.put("politicalCandidate",true);m.put("biasAnalysisStatus","QUEUED");m.put("biasAnalysisQueuedAt",Instant.now().toString());
        m.remove("biasAnalysisError");m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
        remember(operationId,identity);persistRequired();Map<String,Object>pub=publicStory(m);emit("story",pub);return pub;
    }

    public synchronized void politicalAnalysisComplete(String storyId,Map<String,Object>result,String workerId){
        Map<String,Object>m=requireStory(storyId);
        m.put("framingAnalysis",deepCopyMap(result));
        m.put("framingInputHash",String.valueOf(m.getOrDefault("biasClaimInputHash",m.getOrDefault("biasAnalysisInputHash",""))));
        m.remove("biasClaimInputHash");
        m.put("biasAnalysisStatus",String.valueOf(m.getOrDefault("framingInputHash",""))
                .equals(String.valueOf(m.getOrDefault("biasAnalysisInputHash","")))?"COMPLETE":"QUEUED");m.put("biasAnalyzedAt",Instant.now().toString());m.put("biasAnalyzedBy",workerId);
        m.remove("biasAnalysisError");m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized void politicalAnalysisFail(String storyId,String error){
        Map<String,Object>m=requireStory(storyId);
        m.put("biasAnalysisStatus","FAILED");m.put("biasAnalysisError",safe(error));m.put("biasAnalysisFailedAt",Instant.now().toString());
        if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.FRAMING_EXPOSURE&&"QUEUED".equals(m.get("status"))){m.put("stage","WAITING_FOR_FRAMING_ANALYSIS");m.put("detail","Analysis failed. Retry analysis to continue.");}
        m.remove("biasAssignedWorker");m.remove("biasAnalysisLeaseUntil");m.remove("biasClaimInputHash");
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized void maintenance(){
        int recovered=recoverExpiredLeases(true)+recoverExpiredBiasLeases(true);
        if(recovered>0)persistQuiet();
    }

    public synchronized void videoUploaded(String jobId,Path path,String filename,long bytes){videoUploaded(jobId,path,filename,bytes,"","");}
    public synchronized void videoUploaded(String jobId,Path path,String filename,long bytes,String workerId){videoUploaded(jobId,path,filename,bytes,workerId,"");}
    public synchronized void videoUploaded(String jobId,Path path,String filename,long bytes,String workerId,String claimId){
        Map<String,Object>m=requireStory(jobId);
        if(!workerOwnsActiveJob(m,workerId,claimId))throw new IllegalStateException("Video arrived from an inactive or superseded worker claim.");
        m.put("serverVideo",path.toString());m.put("videoFilename",filename);m.put("videoBytes",bytes);m.put("stage","UPLOAD");m.put("progress",99);
        emit("story",publicStory(m));
    }

    public synchronized void complete(String jobId,Map<String,Object>metadata){
        Map<String,Object>m=requireStory(jobId);
        if(!workerOwnsActiveJob(m,String.valueOf(metadata.getOrDefault("workerId","")),
                String.valueOf(metadata.getOrDefault("claimId",""))))
            throw new IllegalStateException("Completion rejected: inactive or superseded worker claim.");
        ProductionMode completedMode=ProductionMode.fromPersisted(m.get("productionMode"));
        if(metadata.get("sidecar") instanceof Map<?,?> sidecarMode){
            String declared=String.valueOf(Json.object(sidecarMode).getOrDefault("productionMode","STANDARD"));
            if(!completedMode.name().equals(declared))throw new IllegalStateException("Completion mode mismatch: "+declared);
        }else if(completedMode==ProductionMode.FRAMING_EXPOSURE)
            throw new IllegalStateException("Experimental video requires a provenance sidecar.");
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
        Map<String,Object>v=new LinkedHashMap<>();v.put("versionId",versionId);v.put("productionMode",completedMode.name());v.put("storyId",jobId);v.put("jobId",jobId);v.put("version",version);v.put("current",true);v.put("topic",m.get("topic"));v.put("filename",m.getOrDefault("videoFilename",""));v.put("path",m.getOrDefault("serverVideo",""));v.put("completedAt",m.get("completedAt"));v.put("ttsEngine",m.getOrDefault("ttsEngine","unknown"));v.put("ttsVoice",m.getOrDefault("ttsVoice",""));v.put("visualMode",m.getOrDefault("visualMode","unknown"));v.put("comfyCheckpoint",m.getOrDefault("comfyCheckpoint",""));v.put("comfyImages",m.getOrDefault("comfyImages",0));v.put("metadata",new LinkedHashMap<>(metadata));copyPublicationState(m,v);videos.put(versionId,v);
        // Create the second job only AFTER the normal video has been fully recorded.
        // The standard version keeps its own id, path, metadata and uploaded flag.
        if(completedMode==ProductionMode.STANDARD&&pairFramingAfterStandard){
            queueFollowupFraming(m,versionId);
        }else if(completedMode==ProductionMode.FRAMING_EXPOSURE&&
                ("FRAMING_QUEUED".equals(m.get("pairedVideoState"))||
                 "FRAMING_PRODUCING".equals(m.get("pairedVideoState")))){
            m.put("pairedFramingVersionId",versionId);
            m.put("pairedVideoState","COMPLETE");
            m.put("detail","Both videos complete: original report and political framing review");
        }
        persistQuiet();emit("story",publicStory(m));emit("video",publicCopy(v));
    }

    /**
     * Durable sequential pair: never queue a review from an unsuccessful normal
     * render. Store the first version before clearing its current-media fields.
     */
    private void queueFollowupFraming(Map<String,Object>m,String standardVersionId){
        m.put("pairedStandardVersionId",standardVersionId);
        m.remove("pairedFramingVersionId");
        m.put("manualFramingRequested",true);
        m.put("politicalCandidate",true); // user/auto request applies even to nonpolitical stories
        if(!(m.get("candidate") instanceof Map<?,?>)){
            m.put("pairedVideoState","FRAMING_SKIPPED");
            m.put("detail","Normal video complete; missing article evidence for follow-up");
            return;
        }
        NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(m.get("candidate")));
        if(candidate.cluster().articles.stream().noneMatch(a->
                !a.title().isBlank()&&!a.publisher().isBlank()&&!a.url().isBlank())){
            m.put("pairedVideoState","FRAMING_SKIPPED");
            m.put("detail","Normal video complete; source attribution is unavailable for follow-up");
            return;
        }
        m.put("pairedVideoState","FRAMING_QUEUED");
        m.put("productionMode",ProductionMode.FRAMING_EXPOSURE.name());
        m.put("status","QUEUED");
        m.put("progress",15);
        m.put("queuedAt",Instant.now().toString());
        m.put("updatedAt",Instant.now().toString());
        clearLease(m);resetFailureRetryState(m);
        // Reset the story's in-flight media result, never the archived version.
        clearCurrentMediaResult(m);clearCurrentPublicationState(m);
        String requested=String.valueOf(m.getOrDefault("biasAnalysisInputHash",""));
        String analyzed=String.valueOf(m.getOrDefault("framingInputHash",""));
        if(!"COMPLETE".equals(String.valueOf(m.get("biasAnalysisStatus")))
                ||requested.isBlank()||!requested.equals(analyzed)){
            if(!"ANALYZING".equals(String.valueOf(m.get("biasAnalysisStatus")))){
                m.put("biasAnalysisStatus","QUEUED");
                m.put("biasAnalysisQueuedAt",Instant.now().toString());
                m.remove("biasAnalysisError");
            }
            m.put("stage","WAITING_FOR_FRAMING_ANALYSIS");
            m.put("detail","Normal video archived; preparing the framing review");
        }else{
            m.put("stage","QUEUED");
            m.put("detail","Normal video archived; framing review ready for compatible worker");
        }
    }

    private void markStandardPairQueued(Map<String,Object>m){
        if(!pairFramingAfterStandard)return;
        m.put("pairedVideoState","STANDARD_QUEUED");
        m.remove("pairedStandardVersionId");
        m.remove("pairedFramingVersionId");
    }

    public synchronized Map<String,Object> setUploadStatus(String storyId,boolean uploaded,String platform,String note){
        return setUploadStatus(storyId,"",uploaded,platform,note);
    }

    public synchronized Map<String,Object> setUploadStatus(String storyId,String versionId,boolean uploaded,String platform,String note){
        return setUploadStatus(storyId,versionId,uploaded,platform,note,"");
    }
    public synchronized Map<String,Object> setUploadStatus(String storyId,String versionId,boolean uploaded,String platform,String note,String operationId){
        Map<String,Object>m=requireStory(storyId);
        String identity="upload:"+storyId+":"+versionId+":"+uploaded+":"+safeText(platform,80)+":"+safeText(note,500);
        if(alreadyHandled(operationId,identity))return publicStory(m);
        Map<String,Object>v=resolveVideo(storyId,versionId);
        if(v==null)throw new IllegalStateException("No completed local video version is available.");
        if(uploaded&&Boolean.TRUE.equals(v.get("scrapped")))
            throw new IllegalStateException("A scrapped video cannot be marked uploaded until it is restored.");
        if(uploaded==Boolean.TRUE.equals(v.get("uploaded"))
                &&(!uploaded||(Objects.equals(safeText(platform,80),String.valueOf(v.getOrDefault("uploadedPlatform","")))
                &&Objects.equals(safeText(note,500),String.valueOf(v.getOrDefault("uploadNote",""))))))
            return publicStory(m);

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

        remember(operationId,identity);persistRequired();
        Map<String,Object>pub=publicStory(m);emit("story",pub);emit("video",publicCopy(v));
        return pub;
    }

    public synchronized Map<String,Object> setScrapStatus(String storyId,String versionId,boolean scrapped,String reason){
        return setScrapStatus(storyId,versionId,scrapped,reason,"");
    }
    public synchronized Map<String,Object> setScrapStatus(String storyId,String versionId,boolean scrapped,String reason,String operationId){
        Map<String,Object>m=requireStory(storyId);
        String identity="scrap:"+storyId+":"+versionId+":"+scrapped+":"+safeText(reason,500);
        if(alreadyHandled(operationId,identity))return publicStory(m);
        Map<String,Object>v=resolveVideo(storyId,versionId);
        if(v==null)throw new IllegalStateException("No completed local video version is available.");
        if(scrapped==Boolean.TRUE.equals(v.get("scrapped")))return publicStory(m);

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

        remember(operationId,identity);persistRequired();
        Map<String,Object>pub=publicStory(m);emit("story",pub);emit("video",publicCopy(v));
        return pub;
    }

    public synchronized void fail(String jobId,String error){fail(jobId,error,"","");}
    public synchronized void fail(String jobId,String error,String workerId){fail(jobId,error,workerId,"");}
    public synchronized void fail(String jobId,String error,String workerId,String claimId){
        Map<String,Object>m=stories.get(jobId);
        if(m==null||!workerOwnsActiveJob(m,workerId,claimId))return;
        Instant now=Instant.now();
        int failures=integer(m.get("failureCount"))+1;
        String message=safe(error);
        String failedStage=String.valueOf(m.getOrDefault("stage","UNKNOWN"));
        String worker=String.valueOf(m.getOrDefault("assignedWorker","unknown"));
        String failureType=failureTypeForStage(failedStage,message);
        m.put("failureCount",failures);
        m.put("lastFailure",message);
        m.put("lastFailedAt",now.toString());
        m.put("failureType",failureType);
        m.put("lastAssignedWorker",worker);
        m.put("error",message);
        m.put("updatedAt",now.toString());
        appendProductionAttempt(m,now,Map.of(
                "event","FAILED",
                "attempt",Math.max(1,integer(m.get("claimCount"))),
                "worker",worker,
                "stage",failedStage,
                "failureType",failureType,
                "error",message
        ));
        m.remove("assignedWorker");m.remove("jobStartedAt");clearLease(m);

        if(failures<=failureMaxRetries){
            Instant retryAt=now.plusSeconds(failureRetryDelaySeconds);
            m.put("status","QUEUED");m.put("stage","QUEUED");m.put("progress",15);m.put("queuedAt",now.toString());
            m.put("retryNotBefore",retryAt.toString());
            m.put("detail","Production failed; automatic retry "+failures+"/"+failureMaxRetries+" queued"+(failureRetryDelaySeconds>0?" in "+failureRetryDelaySeconds+"s":""));
        }else{
            m.put("status","FAILED");m.put("stage","FAILED");m.put("progress",0);m.remove("retryNotBefore");
            m.put("detail","Production failed after "+failureMaxRetries+" automatic retries; manual action required");
            if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.FRAMING_EXPOSURE&&
                    String.valueOf(m.getOrDefault("pairedVideoState","")).startsWith("FRAMING_"))
                m.put("pairedVideoState","FRAMING_FAILED");
            else if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.STANDARD&&
                    String.valueOf(m.getOrDefault("pairedVideoState","")).startsWith("STANDARD_"))
                m.put("pairedVideoState","STANDARD_FAILED");
        }
        persistQuiet();emit("story",publicStory(m));
    }

    public synchronized Map<String,Object> storyDetail(String id){
        Map<String,Object>m=stories.get(id);return m==null?null:deepCopyMap(m);
    }

    public synchronized Map<String,Object> setAutoQueue(boolean enabled){
        autoQueue=enabled;
        persistRequired();
        Map<String,Object>payload=Map.of("autoQueue",autoQueue,"changedAt",Instant.now().toString());
        emit("settings",payload);
        return payload;
    }

    public synchronized Map<String,Object> snapshot(){return snapshot(true);}

    // Lightweight snapshots do not deep-copy, sort, or serialize any story.
    public synchronized Map<String,Object> snapshot(boolean includeStories){
        List<Map<String,Object>>ss=includeStories
                ?stories.values().stream().map(this::publicStory)
                    .sorted(Comparator.comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed()).toList()
                :List.of();
        List<Map<String,Object>>ff=feeds.values().stream().map(CommandCenterStore::publicCopy).sorted(Comparator.comparing(x->String.valueOf(x.get("name")))).toList();
        List<Map<String,Object>>ww=workers.values().stream().map(x->{Map<String,Object>c=publicCopy(x);c.put("online",isOnline(x));return c;}).toList();
        List<Map<String,Object>>vv=videos.values().stream().map(CommandCenterStore::publicCopy).toList();
        Map<String,Object>counts=new LinkedHashMap<>();
        counts.put("stories",stories.size());
        counts.put("verified",stories.values().stream().filter(x->Boolean.TRUE.equals(x.get("verified"))).count());
        counts.put("worthy",stories.values().stream().filter(CommandCenterStore::isActionableWorthy).count());
        counts.put("queued",countStatus("QUEUED"));counts.put("producing",countStatus("PRODUCING"));counts.put("complete",countStatus("COMPLETE")+countStatus("COMPLETE_HISTORY"));counts.put("hold",countStatus("HOLD"));counts.put("skipped",countStatus("SKIPPED"));counts.put("failed",countStatus("FAILED"));
        counts.put("toPost",videos.values().stream().filter(v->!Boolean.TRUE.equals(v.get("uploaded"))&&!Boolean.TRUE.equals(v.get("scrapped"))).count());
        counts.put("uploaded",videos.values().stream().filter(v->Boolean.TRUE.equals(v.get("uploaded"))&&!Boolean.TRUE.equals(v.get("scrapped"))).count());
        counts.put("scrapped",videos.values().stream().filter(v->Boolean.TRUE.equals(v.get("scrapped"))).count());
        counts.put("videoVersions",videos.size());
        counts.put("feedsOk",feeds.values().stream().filter(x->"OK".equals(x.get("status"))).count());counts.put("feedsFailed",feeds.values().stream().filter(x->"FAILED".equals(x.get("status"))).count());counts.put("workersOnline",ww.stream().filter(x->Boolean.TRUE.equals(x.get("online"))).count());
        counts.put("biasQueued",stories.values().stream().filter(x->"QUEUED".equals(x.get("biasAnalysisStatus"))).count());
        counts.put("biasAnalyzing",stories.values().stream().filter(x->"ANALYZING".equals(x.get("biasAnalysisStatus"))).count());
        counts.put("biasComplete",stories.values().stream().filter(x->"COMPLETE".equals(x.get("biasAnalysisStatus"))).count());
        Map<String,Object>out=new LinkedHashMap<>();out.put("serverTime",Instant.now().toString());out.put("scanning",scanning);out.put("autoQueue",autoQueue);out.put("autoThreshold",autoThreshold);out.put("softWorthThreshold",softWorthThreshold);out.put("singleSourceAutoQueueThreshold",singleSourceAutoQueueThreshold);out.put("maxQueued",maxQueued);out.put("failureMaxRetries",failureMaxRetries);out.put("leaseRecoveryMaxRetries",leaseRecoveryMaxRetries);out.put("jobLeaseSeconds",leaseSeconds);out.put("counts",counts);out.put("lastScan",new LinkedHashMap<>(lastScan));out.put("feeds",ff);out.put("stories",ss);out.put("workers",ww);out.put("videos",vv);return out;
    }


    private static final Set<String> BOARD_STATUSES=Set.of(
            "ALL","DISCOVERED","WORTHY","VERIFIED","QUEUED","PRODUCING",
            "HOLD","FAILED","COMPLETE","TO_POST","UPLOADED","SCRAPPED","SKIPPED");

    // Search, filtering and pagination operate on raw in-memory records; only the page
    // is deep-copied into public story objects. Stable ID tie-breaking avoids page drift.
    public synchronized Map<String,Object> boardStories(int requestedPage,int pageSize,String status,String search,int ageHours){
        if(requestedPage<1||!Set.of(10,25,50,100).contains(pageSize)
                ||!BOARD_STATUSES.contains(status)||!Set.of(0,1,2,3,4,6,8,12,24,48,72).contains(ageHours))
            throw new IllegalArgumentException("Invalid board pagination or filter parameter");
        String query=search==null?"":search.trim().toLowerCase(Locale.ROOT);
        Instant cutoff=ageHours>0?Instant.now().minus(Duration.ofHours(ageHours)):null;
        List<Map<String,Object>>matches=new ArrayList<>();
        Map<String,Object>facets=new LinkedHashMap<>();
        for(String key:BOARD_STATUSES)facets.put(key,0L);
        for(Map<String,Object>story:stories.values()){
            String rawStatus=String.valueOf(story.getOrDefault("status",""));
            boolean recent=withinBoardAge(story,cutoff);
            // Rail counts ignore search and mirror the existing browser's age
            // behavior. Queue/producing/hold counts are intentionally age-exempt.
            for(String key:BOARD_STATUSES){
                if((recent||isOperationalBoardStatus(key))&&matchesBoardStatus(story,rawStatus,key))
                    facets.put(key,((Long)facets.get(key))+1);
            }
            if(!matchesBoardStatus(story,rawStatus,status))continue;
            if(!isOperationalBoardStatus(status)&&!recent)continue;
            if(!query.isEmpty()&&!boardSearchText(story).contains(query))continue;
            matches.add(story);
        }
        matches.sort(Comparator
                .comparingDouble((Map<String,Object>x)->number(x.get("score"))).reversed()
                .thenComparing(x->String.valueOf(x.getOrDefault("id",""))));
        int total=matches.size();
        int totalPages=Math.max(1,(int)((total+(long)pageSize-1)/pageSize));
        int page=Math.min(requestedPage,totalPages);
        int start=(int)(((long)page-1)*pageSize);
        int end=Math.min(total,start+pageSize);
        List<Map<String,Object>>publicPage=new ArrayList<>(end-start);
        for(int i=start;i<end;i++)publicPage.add(publicStory(matches.get(i)));
        Map<String,Object>out=new LinkedHashMap<>();
        out.put("stories",publicPage);out.put("page",page);out.put("pageSize",pageSize);
        out.put("total",total);out.put("totalPages",totalPages);out.put("facets",facets);
        return out;
    }

    private static boolean isOperationalBoardStatus(String status){
        return "QUEUED".equals(status)||"PRODUCING".equals(status)||"HOLD".equals(status);
    }
    private static boolean matchesBoardStatus(Map<String,Object>m,String rawStatus,String filter){
        return switch(filter){
            case "ALL"->true;
            case "COMPLETE"->rawStatus.startsWith("COMPLETE");
            case "TO_POST"->isToPost(m);
            case "UPLOADED"->Boolean.TRUE.equals(m.get("uploaded"))&&!Boolean.TRUE.equals(m.get("scrapped"));
            case "SCRAPPED"->Boolean.TRUE.equals(m.get("scrapped"));
            case "WORTHY"->isActionableWorthy(m);
            default->rawStatus.equals(filter);
        };
    }
    private static boolean withinBoardAge(Map<String,Object>m,Instant cutoff){
        if(cutoff==null)return true;
        try{
            String published=String.valueOf(m.getOrDefault("latestPublishedAt",""));
            // Future publications were previously clamped to age zero in the UI.
            return !Instant.parse(published).isBefore(cutoff);
        }catch(Exception e){return false;}
    }
    private static String boardSearchText(Map<String,Object>m){
        StringBuilder sb=new StringBuilder();
        for(String key:List.of("topic","category","uploadedPlatform","uploadNote","scrapReason",
                "lastFailure","failureType","assignedWorker","lastAssignedWorker")){
            Object value=m.get(key);
            if(value!=null)sb.append(value).append(' ');
        }
        Object publishers=m.get("publishers");
        if(publishers instanceof Collection<?> collection)
            for(Object publisher:collection)sb.append(publisher).append(' ');
        return sb.toString().toLowerCase(Locale.ROOT);
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
    private boolean claimEligible(Map<String,Object>m,String workerId){
        ProductionMode mode=ProductionMode.fromPersisted(m.get("productionMode"));
        if(mode==ProductionMode.STANDARD)return true;
        Map<String,Object>worker=workers.get(workerId);
        if(worker==null||!(worker.get("capabilities") instanceof List<?> capabilities)
                ||!capabilities.contains("framing_exposure_v1")){
            m.put("stage","WAITING_FOR_COMPATIBLE_WORKER");
            m.put("detail","WAITING FOR COMPATIBLE WORKER");
            return false;
        }
        if(!"COMPLETE".equals(String.valueOf(m.get("biasAnalysisStatus")))
                ||!String.valueOf(m.getOrDefault("biasAnalysisInputHash","")).equals(String.valueOf(m.getOrDefault("framingInputHash","")))){
            m.put("stage","WAITING_FOR_FRAMING_ANALYSIS");return false;
        }
        if(!(m.get("framingAnalysis") instanceof Map<?,?>))return false;
        Map<String,Object>analysis=Json.object(m.get("framingAnalysis"));
        // Absence of political slant is a valid finding, not a reason to refuse analysis.
        // The experimental script must report that conclusion rather than invent a bias.
        return true;
    }
    private static boolean retryReady(Map<String,Object>m){
        Object raw=m.get("retryNotBefore");
        if(raw==null)return true;
        try{return !Instant.parse(String.valueOf(raw)).isAfter(Instant.now());}
        catch(Exception ignored){return true;}
    }
    private static void resetFailureRetryState(Map<String,Object>m){
        m.remove("failureCount");m.remove("retryNotBefore");m.remove("lastFailure");m.remove("lastFailedAt");m.remove("failureType");m.remove("leaseRecoveries");
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

    private static void appendProductionAttempt(Map<String,Object>target,Instant now,Map<String,Object>values){
        Map<String,Object>withMode=new LinkedHashMap<>(values);
        withMode.put("productionMode",ProductionMode.fromPersisted(target.get("productionMode")).name());
        appendStateHistory(target,"productionAttempts",now,withMode);
        Object raw=target.get("productionAttempts");
        if(raw instanceof List<?> list&&list.size()>20)target.put("productionAttempts",new ArrayList<>(list.subList(list.size()-20,list.size())));
    }
    private static String failureTypeForStage(String stage,String message){
        String lower=message==null?"":message.toLowerCase(Locale.ROOT);
        if(lower.contains("upload")||lower.contains("/video"))return "UPLOAD_FAILURE";
        return switch(stage==null?"":stage.toUpperCase(Locale.ROOT)){
            case "VERIFY"->"VERIFICATION_FAILURE";
            case "SCRIPT"->"SCRIPT_FAILURE";
            case "TTS"->"TTS_FAILURE";
            case "VISUALS"->"VISUAL_FAILURE";
            case "RENDER","AUDIT"->"RENDER_FAILURE";
            case "APPROVED"->"POST_PRODUCTION_FAILURE";
            default->"PRODUCTION_FAILURE";
        };
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
            String failedStage=String.valueOf(m.getOrDefault("stage","UNKNOWN"));
            int leaseRecoveries=integer(m.get("leaseRecoveries"))+1;
            m.put("lastAssignedWorker",previous);m.put("leaseRecoveries",leaseRecoveries);
            appendProductionAttempt(m,now,Map.of(
                    "event","LEASE_EXPIRED",
                    "attempt",Math.max(1,integer(m.get("claimCount"))),
                    "worker",previous,
                    "stage",failedStage,
                    "failureType","WORKER_LEASE_EXPIRED"
            ));
            m.remove("assignedWorker");m.remove("jobStartedAt");m.remove("leaseUntil");m.remove("claimId");
            if(leaseRecoveries<=leaseRecoveryMaxRetries){
                m.put("status","QUEUED");m.put("stage","QUEUED");m.put("progress",15);m.put("queuedAt",now.toString());
                m.put("failureType","WORKER_LEASE_EXPIRED");
                m.put("detail","Worker lease expired; automatic recovery "+leaseRecoveries+"/"+leaseRecoveryMaxRetries+" requeued");
            }else{
                m.put("status","FAILED");m.put("stage","FAILED");m.put("progress",0);m.put("failureType","WORKER_LEASE_EXHAUSTED");
                m.put("lastFailure","Worker lease expired "+leaseRecoveries+" times; automatic lease recovery stopped.");
                m.put("lastFailedAt",now.toString());m.put("error",m.get("lastFailure"));
                m.put("detail","Worker lease recovery exhausted; manual retry required");
                if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.FRAMING_EXPOSURE&&
                        String.valueOf(m.getOrDefault("pairedVideoState","")).startsWith("FRAMING_"))
                    m.put("pairedVideoState","FRAMING_FAILED");
                else if(ProductionMode.fromPersisted(m.get("productionMode"))==ProductionMode.STANDARD&&
                        String.valueOf(m.getOrDefault("pairedVideoState","")).startsWith("STANDARD_"))
                    m.put("pairedVideoState","STANDARD_FAILED");
            }
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
    private static boolean claimMatches(Map<String,Object>m,String claimId){
        // Older workers may not send a token. Updated workers always do, so stale
        // callbacks remain distinguishable even when the same worker ID reclaims a job.
        return claimId==null||claimId.isBlank()||claimId.equals(m.get("claimId"));
    }
    private static boolean workerOwnsActiveJob(Map<String,Object>m,String workerId,String claimId){
        return "PRODUCING".equals(m.get("status"))
            &&(workerId==null||workerId.isBlank()||workerId.equals(m.get("assignedWorker")))
            &&claimMatches(m,claimId);
    }
    private static boolean isOnline(Map<String,Object>x){try{return Duration.between(Instant.parse(String.valueOf(x.get("lastSeen"))),Instant.now()).toSeconds()<45;}catch(Exception e){return false;}}
    private Map<String,Object>publicStory(Map<String,Object>m){Map<String,Object>x=new LinkedHashMap<>(m);x.remove("candidate");x=deepCopyMap(x);x.put("actionableWorthy",isActionableWorthy(m));return x;}
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
            if(root.get("operations") instanceof Map<?,?>op)for(var entry:op.entrySet())processedOperations.put(String.valueOf(entry.getKey()),String.valueOf(entry.getValue()));
            if(root.get("autoQueue") instanceof Boolean b)autoQueue=b;
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
                boolean storyHasPublicationState=story.containsKey("uploadedAt")||story.containsKey("uploadedPlatform")||story.containsKey("uploadHistory")
                        ||story.containsKey("scrappedAt")||story.containsKey("scrapReason")||story.containsKey("scrapHistory");
                if(storyHasPublicationState)copyPublicationState(story,v);
                copyPublicationState(v,story);
            }
        }
        return changed;
    }

    private static void copyMap(Object source,Map<String,Map<String,Object>>target){
        if(!(source instanceof Map<?,?>m))return;
        for(var e:m.entrySet())if(e.getValue() instanceof Map<?,?>)target.put(String.valueOf(e.getKey()),new LinkedHashMap<>(Json.object(e.getValue())));
    }

    private boolean alreadyHandled(String operationId,String identity){
        // A previous required write may have failed after mutating memory.
        // Never acknowledge its retry as completed until state is saved.
        if(stateDirty)persistRequired();
        if(operationId==null||operationId.isBlank())return false;
        if(operationId.length()>128)throw new IllegalArgumentException("Operation ID too long.");
        String previous=processedOperations.get(operationId);
        if(previous!=null&&!previous.equals(identity))throw new IllegalArgumentException("Operation ID reused for a different action.");
        return previous!=null;
    }
    private void remember(String operationId,String identity){
        if(operationId==null||operationId.isBlank())return;
        processedOperations.put(operationId,identity);
        while(processedOperations.size()>2000)processedOperations.remove(processedOperations.keySet().iterator().next());
    }
    private Map<String,Object>persistenceState(){
        Map<String,Object>root=new LinkedHashMap<>();
        root.put("stories",stories);root.put("feeds",feeds);root.put("videos",videos);
        root.put("lastScan",lastScan);root.put("autoQueue",autoQueue);root.put("operations",processedOperations);
        return root;
    }
    private void persistRequired(){
        try{Json.write(statePath,persistenceState());stateDirty=false;}
        catch(java.io.IOException e){stateDirty=true;throw new java.io.UncheckedIOException("Command Center state could not be saved",e);}
    }
    private void persistQuiet(){
        try{Json.write(statePath,persistenceState());stateDirty=false;}
        catch(Exception e){stateDirty=true;System.err.println("Command center state save failed: "+e.getMessage());}
    }
}
