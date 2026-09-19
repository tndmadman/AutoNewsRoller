package autonewsroller.orchestration;

import autonewsroller.audit.*;
import autonewsroller.cluster.*;
import autonewsroller.config.*;
import autonewsroller.history.*;
import autonewsroller.ingest.*;
import autonewsroller.model.*;
import autonewsroller.rank.*;
import autonewsroller.script.*;
import autonewsroller.tts.*;
import autonewsroller.util.*;
import autonewsroller.visuals.*;
import autonewsroller.video.*;
import autonewsroller.verify.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class NewsPipeline {
    private final Path root,batchDir;private final NewsConfig cfg;private final EventLog events;private final RuntimeLog logs;private final StoryHistory history;private final PublishHistory publishHistory;
    public NewsPipeline(Path root,NewsConfig cfg,Path batchDir){this(root,cfg,batchDir,null);}
    public NewsPipeline(Path root,NewsConfig cfg,Path batchDir,Consumer<WorkerState> eventListener){this.root=root;this.batchDir=batchDir;this.cfg=cfg;this.events=new EventLog(batchDir.resolve("runtime/events.jsonl"),eventListener);this.logs=new RuntimeLog(batchDir);this.history=new StoryHistory(root.resolve("data/story_history.jsonl"));this.publishHistory=new PublishHistory(root.resolve("data/publish_history.jsonl"));this.logs.debug("Batch initialized at "+batchDir);}

    public List<Candidate> discover(String category,int maxAge,int minSources,boolean enrichArticles)throws Exception{
        DiscoveryResult detailed=discoverDetailed(category,maxAge,minSources,enrichArticles,DiscoveryObserver.NOOP);
        return detailed.items().stream()
                .filter(x->x.verified()&&!x.previouslyGenerated())
                .map(x->new Candidate(x.cluster(),x.factPackage(),x.score()))
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .toList();
    }

    public DiscoveryResult discoverDetailed(String category,int maxAge,int minSources,boolean enrichArticles,DiscoveryObserver observer)throws Exception{
        if(observer==null)observer=DiscoveryObserver.NOOP;
        events.emit(0,0,PipelineStage.DISCOVER,"scanning all enabled feeds");
        logs.debug("Discovery started category="+category+" maxAgeHours="+maxAge+" minimumIndependentSources="+minSources);

        Map<String,Article>allScanned=new LinkedHashMap<>();
        ArticleHistory ah=new ArticleHistory(root.resolve("data/seen_articles.jsonl"));
        Set<String>seen=ah.ids();
        List<SourceConfig>sources=FeedRegistry.load(root.resolve("config/sources.json"));
        int attemptedFeeds=0,successfulFeeds=0,failedFeeds=0,totalEntries=0,recentEntries=0;

        for(SourceConfig sc:sources){
            if(!sc.enabled()||!"rss".equalsIgnoreCase(sc.type()))continue;
            attemptedFeeds++;
            observer.feedStarted(sc);
            try{
                List<Article>entries=new RssSource(
                        sc,
                        cfg.getInt("feedFetchTimeout",12),
                        cfg.getInt("httpRetries",2),
                        cfg.get("userAgent","AutoNewsRoller/0.1"),
                        root.resolve("data/feed_cache.json")
                ).discover();
                successfulFeeds++;
                totalEntries+=entries.size();
                observer.feedSucceeded(sc,entries.size());

                for(Article a:entries){
                    long age=Math.max(0,Duration.between(a.publishedAt(),Instant.now()).toHours());
                    if(age>maxAge)continue;
                    recentEntries++;
                    Article x=a;
                    if(enrichArticles&&cfg.getBool("articleEnrichmentEnabled",false)&&categoryMatches(category,a.category())&&a.description().length()<120){
                        try{
                            String html=new ArticleFetcher(
                                    cfg.getInt("articleFetchTimeout",30),
                                    cfg.get("userAgent","AutoNewsRoller/0.1")
                            ).fetch(a.url());
                            String body=ArticleParser.extractText(html);
                            x=new Article(a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),a.publishedAt(),a.discoveredAt(),a.author(),a.category(),a.description(),body,a.language(),a.sourceTrustTier(),a.authoritativePrimary());
                        }catch(Exception e){logs.debug("Article extraction failed url="+a.url()+" reason="+e.getMessage());}
                    }
                    allScanned.put(x.id(),x);
                    if(!seen.contains(x.id())){
                        try{ah.append(x);seen.add(x.id());}
                        catch(Exception e){logs.debug("Article history append failed id="+x.id()+" reason="+e.getMessage());}
                    }
                }
            }catch(Exception e){
                failedFeeds++;
                String msg="Feed failed and was skipped: "+sc.name()+" :: "+e.getMessage();
                System.err.println(msg);logs.debug(msg);observer.feedFailed(sc,e.getMessage());
            }
        }

        List<Article>eligible=allScanned.values().stream().filter(a->categoryMatches(category,a.category())).toList();
        String scanSummary="Feed scan complete: attempted="+attemptedFeeds+
                " succeeded="+successfulFeeds+" failed="+failedFeeds+" entries="+totalEntries+
                " recent="+recentEntries+" uniqueRecent="+allScanned.size()+" eligible="+eligible.size();
        System.out.println(scanSummary);logs.debug(scanSummary);

        List<StoryCluster>clusters=new StoryClusterer().cluster(eligible);
        SourceVerifier verifier=new SourceVerifier();
        StoryRanker ranker=new StoryRanker(cfg.ranking());
        List<DiscoveryItem>items=new ArrayList<>();
        for(StoryCluster c:clusters){
            VerificationResult vr=verifier.verify(c,minSources);
            boolean generated=history.seen(c.fingerprint);
            double score=ranker.score(c,vr.factPackage(),Instant.now(),1);
            if(!vr.accepted())logs.debug("Rejected cluster "+c.id+" verification="+vr.reason());
            DiscoveryItem item=new DiscoveryItem(c,vr.factPackage(),vr.accepted(),vr.reason(),score,generated);
            items.add(item);observer.storyEvaluated(item);
        }
        items.sort(Comparator.comparingDouble(DiscoveryItem::score).reversed());
        long verified=items.stream().filter(DiscoveryItem::verified).count();
        logs.debug("Discovery complete articles="+eligible.size()+" clusters="+clusters.size()+" verifiedCandidates="+verified);
        DiscoveryResult result=new DiscoveryResult(List.copyOf(items),attemptedFeeds,successfulFeeds,failedFeeds,totalEntries,recentEntries,allScanned.size(),eligible.size(),Instant.now());
        observer.scanComplete(result);
        return result;
    }

    public List<Candidate> discoverFixtures(int maxAge,int minSources)throws Exception{
        Instant now=Instant.now();List<Article>all=new ArrayList<>();String[] names={"source_a.xml","source_b.xml","source_c.xml","authoritative.xml"};
        for(int i=0;i<names.length;i++){boolean auth=names[i].startsWith("authoritative");SourceConfig sc=new SourceConfig(auth?"Official Agency":"Fixture Publisher "+(char)('A'+i),"rss",auth?"world":"technology","fixture",true,auth?1:Math.min(3,i+1),auth);try(var in=Files.newInputStream(root.resolve("tests/fixtures").resolve(names[i]))){List<Article>parsed=RssSource.parse(in,sc,now);for(Article a:parsed){Instant pub=a.title().startsWith("Old archive")?now.minus(Duration.ofHours(maxAge+48L)):now.minus(Duration.ofMinutes(15L+i*5L));all.add(new Article(a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),pub,now,a.author(),a.category(),a.description(),a.bodyText(),a.language(),a.sourceTrustTier(),a.authoritativePrimary()));}}}
        List<StoryCluster>clusters=new StoryClusterer().cluster(all.stream().filter(a->Duration.between(a.publishedAt(),now).toHours()<=maxAge).toList());SourceVerifier verifier=new SourceVerifier();StoryRanker ranker=new StoryRanker(cfg.ranking());List<Candidate>out=new ArrayList<>();for(StoryCluster c:clusters){VerificationResult vr=verifier.verify(c,minSources);if(!vr.accepted())continue;out.add(new Candidate(c,vr.factPackage(),ranker.score(c,vr.factPackage(),now,1)));}out.sort(Comparator.comparingDouble(Candidate::score).reversed());return out;
    }

    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean dryRun)throws Exception{
        return produce(cand,worker,slot,slotDir,targetSeconds,encoder,useComfy,false,1,dryRun);
    }

    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean requireComfy,int maxComfyImages,boolean dryRun)throws Exception{
        Files.createDirectories(slotDir);
        Candidate prepared=enrichForProduction(cand);
        StoryCluster c=prepared.cluster();
        FactPackage fp=prepared.factPackage();
        int effectiveTarget=Math.max(68,Math.min(75,targetSeconds));
        double minAudio=cfg.getDouble("videoTargetAudioMinSeconds",65);
        double maxAudio=cfg.getDouble("videoTargetAudioMaxSeconds",72);
        double sourceTail=cfg.getDouble("videoSourceTailSeconds",2.5);
        int minScenes=cfg.getInt("videoMinStoryScenes",7),maxScenes=cfg.getInt("videoMaxStoryScenes",9);

        logs.worker(worker,"slot="+slot+" story="+c.topic+" started");
        events.emit(worker,slot,PipelineStage.VERIFY,fp.independentSourceCount()+" independent sources");
        Json.write(slotDir.resolve("story.json"),c.toMap());
        Path articleDir=slotDir.resolve("articles");Files.createDirectories(articleDir);
        int articleIndex=0;for(Article a:c.articles)Json.write(articleDir.resolve(String.format("%02d.json",articleIndex++)),a.toMap());
        Json.write(slotDir.resolve("fact_package.json"),fp.toMap());

        OllamaClient oc=dryRun?null:new OllamaClient(
                cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate"),
                cfg.get("ollamaModel","llama3.1:8b"),
                cfg.get("ollamaKeepAlive","30m"),
                root.resolve("output/runtime/ollama.lock")
        );
        NewsScriptGenerator generator=new NewsScriptGenerator(oc,cfg.getInt("ollamaRetries",3));
        NewsScript script=generator.generate(fp,effectiveTarget,dryRun);
        System.out.println("[Story] Narration words: "+Text.words(script.narration()));
        events.emit(worker,slot,PipelineStage.SCRIPT,"script ready; words="+Text.words(script.narration()));

        if(dryRun){
            VisualPlan dryPlan=new VisualPlanner().plan(script,fp,effectiveTarget,sourceTail,minScenes,maxScenes);
            Json.write(slotDir.resolve("script.json"),script.toMap());
            Json.write(slotDir.resolve("visual_plan.json"),dryPlan.toMap());
            Map<String,Object>audit=new LinkedHashMap<>();audit.put("status","approved-dry-run");audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());audit.put("plannedScenes",dryPlan.items().size());
            Json.write(slotDir.resolve("audit.json"),audit);events.emit(worker,slot,PipelineStage.APPROVED,"dry-run complete");logs.worker(worker,"slot="+slot+" dry-run approved");return slotDir.resolve("script.json");
        }

        VideoRenderer renderer=new VideoRenderer(
                cfg.get("ffmpegCommand","ffmpeg"),cfg.get("ffprobeCommand","ffprobe"),
                cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),cfg.getInt("videoFps",30)
        );

        Path wav=slotDir.resolve("narration/narration.wav");
        NarrationResult nr=null;
        double audioDuration=0;
        int durationAttempts=Math.max(1,cfg.getInt("narrationDurationRetries",3));
        for(int attempt=1;attempt<=durationAttempts;attempt++){
            nr=synthesizeNarration(c,script,worker,slot,wav);
            audioDuration=renderer.probeDuration(nr.wav());
            System.out.printf(Locale.US,"[TTS] Audio duration: %.2f sec (attempt %d/%d)%n",audioDuration,attempt,durationAttempts);
            logs.worker(worker,String.format(Locale.US,"slot=%d TTS audio duration %.2f sec attempt=%d",slot,audioDuration,attempt));
            events.emit(worker,slot,PipelineStage.TTS,String.format(Locale.US,"audio %.2fs",audioDuration));

            if(audioDuration>=minAudio&&audioDuration<=maxAudio)break;
            if(attempt>=durationAttempts)break;
            String reason=audioDuration<minAudio?"too short":"too long";
            System.out.printf(Locale.US,"[Story] Narration audio %s (%.2fs); regenerating toward %d sec%n",reason,audioDuration,effectiveTarget);
            script=generator.regenerateForMeasuredDuration(fp,effectiveTarget,script,audioDuration);
            System.out.println("[Story] Revised narration words: "+Text.words(script.narration()));
        }
        if(audioDuration<minAudio)throw new IllegalStateException(String.format(Locale.US,"narration remains too short after retries: %.2f sec; need >= %.2f",audioDuration,minAudio));
        if(audioDuration>maxAudio)throw new IllegalStateException(String.format(Locale.US,"narration remains too long after retries: %.2f sec; need <= %.2f",audioDuration,maxAudio));

        Json.write(slotDir.resolve("script.json"),script.toMap());
        VisualPlan plan=new VisualPlanner().plan(script,fp,audioDuration,sourceTail,minScenes,maxScenes);
        Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());
        long storySceneCount=plan.items().stream().filter(x->!"SOURCE_CARD".equals(x.type())).count();
        System.out.println("[Scenes] Planned "+storySceneCount+" story scenes + "+(plan.items().size()-storySceneCount)+" source tail scene");
        logs.worker(worker,"slot="+slot+" scenes="+plan.items().size());

        Path visuals=slotDir.resolve("visuals");
        Path generatedDir=visuals.resolve("generated");Files.createDirectories(generatedDir);
        Path cardsDir=visuals.resolve("cards");Files.createDirectories(cardsDir);
        Map<Integer,Path>generatedByScene=new LinkedHashMap<>();
        List<Map<String,Object>>imageSources=new ArrayList<>();
        String usedCheckpoint="";
        int comfyGenerated=0;

        if(requireComfy&&!useComfy)throw new IllegalStateException("ComfyUI is required for this job but useComfy=false");
        if(useComfy){
            events.emit(worker,slot,PipelineStage.VISUALS,"COMFYUI CHECK starting");
            ComfyImageGenerator comfy=new ComfyImageGenerator(root,cfg.get("comfyUrl","http://127.0.0.1:8188"),cfg.get("qwenUrl","http://127.0.0.1:8765"));
            try{
                ComfyRuntime.ensureRunning(root,cfg);
                if(!comfy.reachable())throw new IllegalStateException("ComfyUI is not reachable at "+cfg.get("comfyUrl","http://127.0.0.1:8188"));
                List<String>available=comfy.checkpoints();
                String configured=cfg.get("imageCheckpoint","");
                if(!configured.isBlank()){
                    usedCheckpoint=available.stream().filter(x->checkpointMatches(x,configured)).findFirst().orElse("");
                    if(usedCheckpoint.isBlank())throw new IllegalStateException("configured checkpoint is unavailable: "+configured+"; available="+available);
                }else if(cfg.getBool("comfyAutoPickCheckpoint",true)&&!available.isEmpty())usedCheckpoint=available.get(0);
                else throw new IllegalStateException("no imageCheckpoint configured and ComfyUI returned no usable checkpoints");

                List<Integer>eligible=new ArrayList<>();
                for(int n=0;n<plan.items().size();n++)if(!"SOURCE_CARD".equals(plan.items().get(n).type()))eligible.add(n);
                int limit=Math.min(Math.max(1,maxComfyImages),eligible.size());
                System.out.println("[Images] Need "+limit+" unique SDXL renders for "+eligible.size()+" story scenes");
                for(int k=0;k<limit;k++){
                    int sceneIndex=(int)Math.floor(k*eligible.size()/(double)limit);
                    sceneIndex=eligible.get(Math.min(sceneIndex,eligible.size()-1));
                    VisualPlan.Item chosen=plan.items().get(sceneIndex);
                    String prompt=chosen.prompt();
                    String negative=Text.clean(chosen.negativePrompt()+", "+cfg.get("imageNegative",""));
                    Path generated=generatedDir.resolve(String.format("%02d_scene_%02d.png",k+1,sceneIndex+1));
                    String generating="[ComfyUI] Submitting scene "+(sceneIndex+1)+"/"+eligible.size()+" unique="+(k+1)+"/"+limit;
                    System.out.println(generating);events.emit(worker,slot,PipelineStage.VISUALS,generating);
                    comfy.generate(prompt,negative,usedCheckpoint,generated,cfg.getInt("imageWidth",768),cfg.getInt("imageHeight",1344),cfg.getInt("imageSteps",28),cfg.getDouble("imageCfg",5.5));
                    generatedByScene.put(sceneIndex,generated);
                    Map<String,Object>src=new LinkedHashMap<>();src.put("type","comfyui-generated");src.put("sceneIndex",sceneIndex);src.put("path",generated.toString());src.put("checkpoint",usedCheckpoint);src.put("prompt",prompt);src.put("negativePrompt",negative);imageSources.add(src);
                    comfyGenerated++;
                }
                int minimumUnique=Math.min(6,eligible.size());
                if(requireComfy&&comfyGenerated<minimumUnique)throw new IllegalStateException("ComfyUI produced only "+comfyGenerated+" unique images; need at least "+minimumUnique);
            }catch(Exception e){
                String msg="COMFYUI FAILED: "+e.getMessage();System.err.println(msg);logs.worker(worker,"slot="+slot+" "+msg);events.emit(worker,slot,PipelineStage.VISUALS,msg);usedCheckpoint="";
                if(requireComfy)throw new IllegalStateException(msg,e);
            }
        }else events.emit(worker,slot,PipelineStage.VISUALS,"COMFYUI DISABLED for this job");

        List<Path>uniqueGenerated=new ArrayList<>(generatedByScene.values());
        CardRenderer cards=new CardRenderer();
        List<VideoRenderer.Scene>renderScenes=new ArrayList<>();
        int storyOrdinal=0;
        for(int i=0;i<plan.items().size();i++){
            VisualPlan.Item item=plan.items().get(i);
            Path frame=cardsDir.resolve(String.format("%02d_%s.png",i+1,item.type().toLowerCase(Locale.ROOT)));
            if("SOURCE_CARD".equals(item.type())){
                cards.renderSourceCard(item,frame,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
            }else{
                Path raw=generatedByScene.get(i);
                boolean reused=false;
                if(raw==null&&!uniqueGenerated.isEmpty()){
                    raw=uniqueGenerated.get(Math.min(storyOrdinal,uniqueGenerated.size()-1)%uniqueGenerated.size());
                    reused=true;
                }
                cards.renderPost(item,raw,frame,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),raw!=null);
                if(raw==null){
                    Map<String,Object>src=new LinkedHashMap<>();src.put("type","procedural-card");src.put("sceneIndex",i);src.put("path",frame.toString());imageSources.add(src);
                }else if(reused){
                    Map<String,Object>src=new LinkedHashMap<>();src.put("type","reused-comfy");src.put("sceneIndex",i);src.put("path",raw.toString());imageSources.add(src);
                }
                storyOrdinal++;
            }
            renderScenes.add(new VideoRenderer.Scene(frame,item.duration(),item.transition()));
        }

        Path render=slotDir.resolve("render/video.mp4");
        events.emit(worker,slot,PipelineStage.RENDER,"building post-card scenes");
        VideoRenderer.RenderResult rr=renderer.renderScenes(
                renderScenes,nr.wav(),render,encoder,cfg.get("captions","phrase"),script.narration(),
                cfg.getInt("captionFontSize",44),cfg.getInt("captionMaxWords",9)
        );

        Map<String,Object>audit=new VideoAudit(cfg.get("ffprobeCommand","ffprobe"),cfg.get("ffmpegCommand","ffmpeg")).audit(render,nr.wav(),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
        audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());audit.put("contestedFacts",fp.disputedClaims().size());
        audit.put("ttsEngine",nr.engine());audit.put("encoder",rr.encoder());audit.put("duplicateStoryFingerprint",false);
        audit.put("narrationWords",Text.words(script.narration()));audit.put("narrationAudioDuration",audioDuration);audit.put("storyScenes",storySceneCount);audit.put("totalScenes",plan.items().size());audit.put("uniqueComfyImages",comfyGenerated);audit.put("averageFps",rr.averageFps());
        Json.write(slotDir.resolve("audit.json"),audit);

        Path finalDir=slotDir.getParent().resolve("final_videos");Path finalVideo=FileNames.unique(finalDir,c.topic,".mp4");Files.createDirectories(finalDir);Files.copy(render,finalVideo,StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object>prov=new LinkedHashMap<>();
        prov.put("storyId",c.id);prov.put("storyFingerprint",c.fingerprint);prov.put("script",script.toMap());prov.put("visualPlan",plan.toMap());prov.put("generatedTimestamp",Instant.now().toString());prov.put("sources",fp.sources());
        prov.put("factPackageHash",Hashing.sha256(Json.stringify(fp.toMap())));prov.put("scriptHash",Hashing.sha256(Json.stringify(script.toMap())));
        prov.put("ttsEngineActuallyUsed",nr.engine());prov.put("voice",nr.voice());prov.put("narrationWords",Text.words(script.narration()));prov.put("narrationAudioDuration",audioDuration);
        prov.put("imageSources",imageSources);prov.put("comfyCheckpoint",usedCheckpoint);prov.put("comfyImagesGenerated",comfyGenerated);prov.put("comfyRequired",requireComfy);
        prov.put("videoEncoderRequested",encoder);prov.put("videoEncoderActuallyUsed",rr.encoder());prov.put("finalDuration",rr.finalDuration());prov.put("averageFps",rr.averageFps());prov.put("cfrTarget",cfg.getInt("videoFps",30));
        prov.put("ffmpegVersion",renderer.version());prov.put("ffmpegCommand",rr.command());prov.put("ollamaModel",cfg.get("ollamaModel","llama3.1:8b"));prov.put("autoNewsRollerCommit",commitSha());prov.put("output",finalVideo.toString());
        Json.write(finalVideo.resolveSibling(finalVideo.getFileName()+".json"),prov);

        history.append(c,finalVideo);publishHistory.append(c.id,finalVideo,c.fingerprint);
        events.emit(worker,slot,PipelineStage.APPROVED,finalVideo.getFileName().toString());
        logs.worker(worker,String.format(Locale.US,"slot=%d approved output=%s duration=%.2f fps=%.3f scenes=%d uniqueImages=%d",slot,finalVideo,rr.finalDuration(),rr.averageFps(),plan.items().size(),comfyGenerated));
        return finalVideo;
    }

    private Candidate enrichForProduction(Candidate cand){
        if(!cfg.getBool("productionEnrichmentEnabled",true))return cand;
        StoryCluster c=cand.cluster();List<Article>enriched=new ArrayList<>();boolean changed=false;
        for(Article a:c.articles){
            Article current=a;
            if((a.bodyText()==null||a.bodyText().length()<600)&&a.url()!=null&&a.url().startsWith("http")){
                try{
                    String html=new ArticleFetcher(cfg.getInt("articleFetchTimeout",30),cfg.get("userAgent","AutoNewsRoller/0.1")).fetch(a.url());
                    String body=ArticleParser.extractText(html);
                    if(body.length()>=200){
                        current=new Article(a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),a.publishedAt(),a.discoveredAt(),a.author(),a.category(),a.description(),body,a.language(),a.sourceTrustTier(),a.authoritativePrimary());
                        changed=true;logs.debug("Production enrichment added "+body.length()+" chars from "+a.publisher());
                    }
                }catch(Exception e){logs.debug("Production enrichment failed url="+a.url()+" reason="+e.getMessage());}
            }
            enriched.add(current);
        }
        if(!changed)return cand;
        StoryCluster ec=new StoryCluster(c.id,c.topic,enriched,c.entities,c.fingerprint);
        FactPackage fp=new SourceVerifier().verify(ec,cfg.minimumIndependentSources()).factPackage();
        logs.debug("Production fact package expanded to "+fp.facts().size()+" facts");
        return new Candidate(ec,fp,cand.score());
    }

    private NarrationResult synthesizeNarration(StoryCluster c,NewsScript script,int worker,int slot,Path wav)throws Exception{
        Files.deleteIfExists(wav);
        List<String>kv=cfg.csv("kokoroVoices","af_heart");List<String>qv=cfg.csv("qwenVoices","Ryan");
        String kVoice=kv.get(Math.floorMod(c.id.hashCode(),kv.size()));String qVoice=qv.get(Math.floorMod(c.id.hashCode(),qv.size()));
        NarrationEngine primary=new KokoroNarrator(root),fallback=new QwenNarrator(root,cfg.get("qwenUrl","http://127.0.0.1:8765"));
        events.emit(worker,slot,PipelineStage.TTS,"KOKORO active");
        try{
            NarrationResult nr=primary.narrate(script.narration(),kVoice,wav);
            String used="KOKORO USED voice="+kVoice;System.out.println("[TTS] Engine actually used: Kokoro voice="+kVoice);events.emit(worker,slot,PipelineStage.TTS,used);logs.worker(worker,"slot="+slot+" TTS engine used: Kokoro voice="+kVoice);return nr;
        }catch(Exception e){
            String fail="KOKORO FAILED: "+e.getMessage();System.err.println(fail);logs.worker(worker,"slot="+slot+" "+fail);events.emit(worker,slot,PipelineStage.TTS,fail);
            events.emit(worker,slot,PipelineStage.TTS,"QWEN3 FALLBACK active");
            NarrationResult nr=fallback.narrate(script.narration(),qVoice,wav);
            String used="QWEN3 FALLBACK USED voice="+qVoice;System.out.println("[TTS] Engine actually used: Qwen3 fallback voice="+qVoice);events.emit(worker,slot,PipelineStage.TTS,used);logs.worker(worker,"slot="+slot+" TTS engine used: Qwen3 fallback voice="+qVoice);return nr;
        }
    }

    public void rejected(int worker,int slot,String detail){events.emit(worker,slot,PipelineStage.REJECTED,detail);logs.worker(worker,"slot="+slot+" rejected "+detail);}

    private static boolean checkpointMatches(String available,String configured){
        if(available==null||configured==null)return false;
        String a=available.replace('\\','/').toLowerCase(Locale.ROOT);
        String c=configured.replace('\\','/').toLowerCase(Locale.ROOT);
        return a.equals(c)||a.endsWith("/"+c);
    }

    private static boolean categoryMatches(String requested,String articleCategory){
        return requested==null||requested.isBlank()||"general".equalsIgnoreCase(requested)||requested.equalsIgnoreCase(articleCategory);
    }

    private String commitSha(){String env=System.getenv("GITHUB_SHA");if(env!=null&&!env.isBlank())return env;try{Process p=new ProcessBuilder("git","rev-parse","HEAD").directory(root.toFile()).redirectErrorStream(true).start();if(p.waitFor(5,TimeUnit.SECONDS)&&p.exitValue()==0)return new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();}catch(Exception ignored){}return "unknown";}
    public record Candidate(StoryCluster cluster,FactPackage factPackage,double score){
        public Map<String,Object>toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("cluster",cluster.toMap());m.put("factPackage",factPackage.toMap());m.put("score",score);return m;}
        public static Candidate fromMap(Map<String,Object>m){return new Candidate(StoryCluster.fromMap(Json.object(m.get("cluster"))),FactPackage.fromMap(Json.object(m.get("factPackage"))),m.get("score") instanceof Number n?n.doubleValue():0);}
    }
    public record DiscoveryItem(StoryCluster cluster,FactPackage factPackage,boolean verified,String verificationReason,double score,boolean previouslyGenerated){
        public Map<String,Object>toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("cluster",cluster.toMap());m.put("factPackage",factPackage.toMap());m.put("verified",verified);m.put("verificationReason",verificationReason);m.put("score",score);m.put("previouslyGenerated",previouslyGenerated);return m;}
        public Candidate candidate(){return new Candidate(cluster,factPackage,score);}
    }
    public record DiscoveryResult(List<DiscoveryItem>items,int attemptedFeeds,int successfulFeeds,int failedFeeds,int totalEntries,int recentEntries,int uniqueRecent,int eligibleEntries,Instant completedAt){
        public Map<String,Object>toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("attemptedFeeds",attemptedFeeds);m.put("successfulFeeds",successfulFeeds);m.put("failedFeeds",failedFeeds);m.put("totalEntries",totalEntries);m.put("recentEntries",recentEntries);m.put("uniqueRecent",uniqueRecent);m.put("eligibleEntries",eligibleEntries);m.put("completedAt",completedAt.toString());m.put("stories",items.stream().map(DiscoveryItem::toMap).toList());return m;}
    }
    public interface DiscoveryObserver{
        DiscoveryObserver NOOP=new DiscoveryObserver(){};
        default void feedStarted(SourceConfig source){}
        default void feedSucceeded(SourceConfig source,int entries){}
        default void feedFailed(SourceConfig source,String reason){}
        default void storyEvaluated(DiscoveryItem item){}
        default void scanComplete(DiscoveryResult result){}
    }
}
