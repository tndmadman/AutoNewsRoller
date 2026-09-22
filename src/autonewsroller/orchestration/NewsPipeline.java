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
        StoryCluster c=enrichForProduction(cand.cluster());
        VerificationResult refreshed=new SourceVerifier().verify(c,cfg.minimumIndependentSources());
        FactPackage fp=refreshed.factPackage();
        logs.worker(worker,"slot="+slot+" story="+c.topic+" started facts="+fp.facts().size()+" enrichedBodies="+c.articles.stream().filter(a->a.bodyText()!=null&&!a.bodyText().isBlank()).count());
        events.emit(worker,slot,PipelineStage.VERIFY,fp.independentSourceCount()+" independent sources; "+fp.facts().size()+" narration facts");

        Json.write(slotDir.resolve("story.json"),c.toMap());
        Path articleDir=slotDir.resolve("articles");
        Files.createDirectories(articleDir);
        int articleIndex=0;
        for(Article a:c.articles)Json.write(articleDir.resolve(String.format("%02d.json",articleIndex++)),a.toMap());
        Json.write(slotDir.resolve("fact_package.json"),fp.toMap());

        OllamaClient oc=dryRun?null:new OllamaClient(
                cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate"),
                cfg.get("ollamaModel","llama3.1:8b"),
                cfg.get("ollamaKeepAlive","30m"),
                root.resolve("output/runtime/ollama.lock"),
                cfg.getInt("ollamaContextTokens",8192),
                cfg.getInt("ollamaMaxOutputTokens",2200),
                cfg.getDouble("ollamaTemperature",0.2)
        );
        NewsScriptGenerator scriptGenerator=new NewsScriptGenerator(oc,cfg.getInt("ollamaRetries",5));
        NewsScript script=scriptGenerator.generate(fp,targetSeconds,dryRun);
        Json.write(slotDir.resolve("script.json"),script.toMap());
        events.emit(worker,slot,PipelineStage.SCRIPT,"script ready; words="+Text.words(script.narration())+
                " hookWords="+(script.segments().isEmpty()?0:Text.words(script.segments().get(0).narration()))+
                " hookType="+String.valueOf(script.hook().getOrDefault("type","unknown")));

        if(dryRun){
            VisualPlan plan=new VisualPlanner().plan(script,fp);
            Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());
            Map<String,Object>audit=new LinkedHashMap<>();
            audit.put("status","approved-dry-run");
            audit.put("sourceCount",fp.sourceCount());
            audit.put("independentSources",fp.independentSourceCount());
            audit.put("verifiedFacts",fp.facts().size());
            audit.put("hook",script.hook());
            audit.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());
            audit.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());
            Json.write(slotDir.resolve("audit.json"),audit);
            events.emit(worker,slot,PipelineStage.APPROVED,"dry-run complete");
            logs.worker(worker,"slot="+slot+" dry-run approved");
            return slotDir.resolve("script.json");
        }

        Path wav=slotDir.resolve("narration/narration.wav");
        NarrationResult nr=narrate(c,script,wav,worker,slot);
        double narrationSeconds=audioDuration(nr.wav());
        double minimumFinalSeconds=cfg.getDouble("minimumFinalVideoSeconds",61.0);
        logs.worker(worker,String.format(Locale.ROOT,"slot=%d narration duration=%.2fs words=%d",slot,narrationSeconds,Text.words(script.narration())));

        if(narrationSeconds<minimumFinalSeconds){
            double desiredAudio=Math.max(minimumFinalSeconds+4.0,65.0);
            int adjustedTarget=Math.min(95,Math.max(targetSeconds+5,(int)Math.ceil(targetSeconds*desiredAudio/Math.max(1.0,narrationSeconds))));
            String retry=String.format(Locale.ROOT,"TTS duration %.2fs is below %.2fs; regenerating narration for %ds target before images",narrationSeconds,minimumFinalSeconds,adjustedTarget);
            System.err.println(retry);
            events.emit(worker,slot,PipelineStage.SCRIPT,retry);
            logs.worker(worker,"slot="+slot+" "+retry);

            script=scriptGenerator.generate(fp,adjustedTarget,false);
            Json.write(slotDir.resolve("script.json"),script.toMap());
            nr=narrate(c,script,wav,worker,slot);
            narrationSeconds=audioDuration(nr.wav());
            logs.worker(worker,String.format(Locale.ROOT,"slot=%d repaired narration duration=%.2fs words=%d",slot,narrationSeconds,Text.words(script.narration())));
            if(narrationSeconds<minimumFinalSeconds)
                throw new IllegalStateException(String.format(Locale.ROOT,"Narration audio still too short after repair: %.2fs; minimum is %.2fs",narrationSeconds,minimumFinalSeconds));
        }

        VisualPlan plan=new VisualPlanner().plan(script,fp);
        Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());

        Path visuals=slotDir.resolve("visuals");List<Path>imgs=new ArrayList<>();List<Map<String,Object>>imageSources=new ArrayList<>();CardRenderer cards=new CardRenderer();int i=0;
        for(VisualPlan.Item item:plan.items()){Path p=visuals.resolve(String.format("%02d_%s.png",i++,item.type().toLowerCase(Locale.ROOT)));cards.render(item,p,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));imgs.add(p);imageSources.add(Map.of("type","procedural-card","path",p.toString(),"visualType",item.type()));}
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
                }else if(cfg.getBool("comfyAutoPickCheckpoint",true)&&!available.isEmpty()){
                    usedCheckpoint=available.get(0);
                }else throw new IllegalStateException("no imageCheckpoint configured and ComfyUI returned no usable checkpoints");

                int limit=Math.max(1,maxComfyImages);
                List<Integer>eligible=new ArrayList<>();
                for(int n=0;n<plan.items().size();n++){
                    VisualPlan.Item item=plan.items().get(n);
                    if(item.type().equals("HEADLINE_CARD")||item.type().equals("SOURCE_CARD"))continue;
                    eligible.add(n);
                }

                if(eligible.isEmpty()){
                    throw new IllegalStateException("visual plan contained no replaceable story visuals");
                }

                for(int replace:eligible){
                    if(comfyGenerated>=limit)break;
                    VisualPlan.Item chosen=plan.items().get(replace);
                    String basePrompt=chosen.prompt()==null||chosen.prompt().isBlank()
                            ? (chosen.title()+". "+chosen.body())
                            : chosen.prompt();
                    String visualSuffix="HOOK".equalsIgnoreCase(chosen.type())
                            ?", immediate opening frame, strongest concrete focal subject visible at a glance, tight editorial composition, realistic documentary photograph, no generic newsroom graphics, no text, no logos, no watermark"
                            :", realistic editorial documentary photograph, clear central subject, natural scene depth, portrait composition with clean breathing room, no text, no logos, no watermark";
                    String prompt=(basePrompt+visualSuffix).replaceAll("\\s+"," ").trim();
                    Path generated=visuals.resolve(String.format("%02d_comfy_generated_%02d.png",replace,comfyGenerated+1));
                    String generating="COMFYUI GENERATING "+(comfyGenerated+1)+"/"+Math.min(limit,eligible.size())+" checkpoint="+usedCheckpoint;
                    System.out.println(generating);events.emit(worker,slot,PipelineStage.VISUALS,generating);
                    try{
                        comfy.generate(prompt,cfg.get("imageNegative","text, watermark, logo, captions, low quality, distorted"),usedCheckpoint,generated,cfg.getInt("imageWidth",768),cfg.getInt("imageHeight",1344),cfg.getInt("imageSteps",24),Double.parseDouble(cfg.get("imageCfg","5.0")));
                    }catch(Exception imageError){
                        if(!ComfyImageGenerator.looksLikeCudaOom(imageError))throw imageError;
                        String recovery="COMFYUI OOM RECOVERY: freeing VRAM once and retrying current image";
                        System.err.println(recovery);events.emit(worker,slot,PipelineStage.VISUALS,recovery);logs.worker(worker,"slot="+slot+" "+recovery);
                        comfy.recoverFromOom();
                        comfy.generate(prompt,cfg.get("imageNegative","text, watermark, logo, captions, low quality, distorted"),usedCheckpoint,generated,cfg.getInt("imageWidth",768),cfg.getInt("imageHeight",1344),cfg.getInt("imageSteps",24),Double.parseDouble(cfg.get("imageCfg","5.0")));
                    }
                    Path composed=visuals.resolve(String.format("%02d_post_%02d.png",replace,comfyGenerated+1));
                    cards.renderWithImage(chosen,generated,composed,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
                    imgs.set(replace,composed);
                    imageSources.set(replace,Map.of("type","comfyui-generated","path",composed.toString(),"generatedPath",generated.toString(),"checkpoint",usedCheckpoint,"prompt",prompt,"visualType",chosen.type()));
                    comfyGenerated++;
                    String comfyUsed="COMFYUI USED checkpoint="+usedCheckpoint+" image="+generated.getFileName()+" count="+comfyGenerated;
                    System.out.println(comfyUsed);events.emit(worker,slot,PipelineStage.VISUALS,comfyUsed);
                    logs.worker(worker,"slot="+slot+" "+comfyUsed);
                }

                if(comfyGenerated==0)throw new IllegalStateException("ComfyUI produced zero images");
            }catch(Exception e){
                String msg="COMFYUI FAILED: "+e.getMessage();
                System.err.println(msg);
                logs.worker(worker,"slot="+slot+" "+msg);
                events.emit(worker,slot,PipelineStage.VISUALS,msg);
                usedCheckpoint="";
                if(requireComfy)throw new IllegalStateException(msg,e);
            }
        }else{
            events.emit(worker,slot,PipelineStage.VISUALS,"COMFYUI DISABLED for this job");
        }

        Path render=slotDir.resolve("render/video.mp4");events.emit(worker,slot,PipelineStage.RENDER,"ffmpeg");VideoRenderer renderer=new VideoRenderer(cfg.get("ffmpegCommand","ffmpeg"),cfg.get("ffprobeCommand","ffprobe"),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),cfg.getInt("videoFps",30));List<Double>sceneWeights=plan.items().stream().map(VisualPlan.Item::duration).toList();VideoRenderer.RenderResult rr=renderer.render(imgs,sceneWeights,nr.wav(),render,encoder,cfg.get("captions","sentence"),script.narration());
        Map<String,Object>audit=new VideoAudit(cfg.get("ffprobeCommand","ffprobe"),cfg.get("ffmpegCommand","ffmpeg")).audit(render,nr.wav(),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));double finalSeconds=((Number)audit.get("videoDuration")).doubleValue();if(finalSeconds<minimumFinalSeconds)throw new IllegalStateException(String.format(Locale.ROOT,"Rendered video too short: %.2fs; minimum is %.2fs",finalSeconds,minimumFinalSeconds));audit.put("minimumDurationRequired",minimumFinalSeconds);audit.put("sceneCount",imgs.size());audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());audit.put("contestedFacts",fp.disputedClaims().size());audit.put("ttsEngine",nr.engine());audit.put("encoder",rr.encoder());audit.put("duplicateStoryFingerprint",false);audit.put("hook",script.hook());audit.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());audit.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());Json.write(slotDir.resolve("audit.json"),audit);
        Path finalDir=slotDir.getParent().resolve("final_videos");Path finalVideo=FileNames.unique(finalDir,c.topic,".mp4");Files.createDirectories(finalDir);Files.copy(render,finalVideo,StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object>prov=new LinkedHashMap<>();prov.put("storyId",c.id);prov.put("storyFingerprint",c.fingerprint);prov.put("script",script.toMap());prov.put("hook",script.hook());prov.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());prov.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());prov.put("generatedTimestamp",Instant.now().toString());prov.put("sources",fp.sources());prov.put("factPackageHash",Hashing.sha256(Json.stringify(fp.toMap())));prov.put("scriptHash",Hashing.sha256(Json.stringify(script.toMap())));prov.put("ttsEngineActuallyUsed",nr.engine());prov.put("voice",nr.voice());prov.put("imageSources",imageSources);prov.put("comfyCheckpoint",usedCheckpoint);prov.put("comfyImagesGenerated",comfyGenerated);prov.put("comfyRequired",requireComfy);prov.put("videoEncoderRequested",encoder);prov.put("videoEncoderActuallyUsed",rr.encoder());prov.put("ffmpegVersion",renderer.version());prov.put("ffmpegCommand",rr.command());prov.put("ollamaModel",cfg.get("ollamaModel","llama3.1:8b"));prov.put("autoNewsRollerCommit",commitSha());prov.put("output",finalVideo.toString());Json.write(finalVideo.resolveSibling(finalVideo.getFileName()+".json"),prov);
        history.append(c,finalVideo);publishHistory.append(c.id,finalVideo,c.fingerprint);events.emit(worker,slot,PipelineStage.APPROVED,finalVideo.getFileName().toString());logs.worker(worker,"slot="+slot+" approved output="+finalVideo);return finalVideo;
    }

    public void rejected(int worker,int slot,String detail){events.emit(worker,slot,PipelineStage.REJECTED,detail);logs.worker(worker,"slot="+slot+" rejected "+detail);}

    private StoryCluster enrichForProduction(StoryCluster original){
        if(!cfg.getBool("articleEnrichmentEnabled",true))return original;

        ArticleFetcher fetcher=new ArticleFetcher(
                cfg.getInt("articleFetchTimeout",30),
                cfg.get("userAgent","AutoNewsRoller/0.1")
        );
        List<Article>enriched=new ArrayList<>();
        boolean changed=false;

        for(Article a:original.articles){
            if(a.bodyText()!=null&&!a.bodyText().isBlank()){
                enriched.add(a);
                continue;
            }
            String url=a.url()==null?"":a.url().trim();
            if(!(url.startsWith("http://")||url.startsWith("https://"))){
                enriched.add(a);
                continue;
            }
            try{
                String html=fetcher.fetch(url);
                String body=ArticleParser.extractText(html);
                if(body.length()>20000)body=body.substring(0,20000);
                if(body.isBlank()){
                    enriched.add(a);
                    continue;
                }
                enriched.add(new Article(
                        a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),
                        a.publishedAt(),a.discoveredAt(),a.author(),a.category(),
                        a.description(),body,a.language(),a.sourceTrustTier(),a.authoritativePrimary()
                ));
                changed=true;
                logs.debug("Production article enrichment succeeded publisher="+a.publisher()+" bodyChars="+body.length());
            }catch(Exception e){
                enriched.add(a);
                logs.debug("Production article enrichment failed url="+url+" reason="+e.getMessage());
            }
        }

        return changed
                ?new StoryCluster(original.id,original.topic,enriched,original.entities,original.fingerprint)
                :original;
    }

    private NarrationResult narrate(StoryCluster c,NewsScript script,Path wav,int worker,int slot)throws Exception{
        List<String>kv=cfg.csv("kokoroVoices","af_heart");
        List<String>qv=cfg.csv("qwenVoices","Ryan");
        String kVoice=kv.get(Math.floorMod(c.id.hashCode(),kv.size()));
        String qVoice=qv.get(Math.floorMod(c.id.hashCode(),qv.size()));
        NarrationEngine primary=new KokoroNarrator(root,cfg.getDouble("kokoroSpeed",0.92));
        NarrationEngine fallback=new QwenNarrator(root,cfg.get("qwenUrl","http://127.0.0.1:8765"));
        events.emit(worker,slot,PipelineStage.TTS,"KOKORO active");

        try{
            NarrationResult nr=primary.narrate(script.narration(),kVoice,wav);
            String used="KOKORO USED voice="+kVoice;
            System.out.println(used);
            events.emit(worker,slot,PipelineStage.TTS,used);
            logs.worker(worker,"slot="+slot+" TTS engine used: Kokoro voice="+kVoice);
            return nr;
        }catch(Exception e){
            String fail="KOKORO FAILED: "+e.getMessage();
            System.err.println(fail);
            logs.worker(worker,"slot="+slot+" "+fail);
            events.emit(worker,slot,PipelineStage.TTS,fail);
            events.emit(worker,slot,PipelineStage.TTS,"QWEN3 FALLBACK active");
            NarrationResult nr=fallback.narrate(script.narration(),qVoice,wav);
            String used="QWEN3 FALLBACK USED voice="+qVoice;
            System.out.println(used);
            events.emit(worker,slot,PipelineStage.TTS,used);
            logs.worker(worker,"slot="+slot+" TTS engine used: Qwen3 fallback voice="+qVoice);
            return nr;
        }
    }

    private double audioDuration(Path wav)throws Exception{
        String dur=FfmpegRunner.run(List.of(
                cfg.get("ffprobeCommand","ffprobe"),
                "-v","error",
                "-show_entries","format=duration",
                "-of","default=noprint_wrappers=1:nokey=1",
                wav.toString()
        ),30).trim();
        if(dur.isBlank())throw new IllegalStateException("ffprobe returned no narration duration");
        double seconds=Double.parseDouble(dur.split("\\R")[0]);
        if(seconds<=0)throw new IllegalStateException("invalid narration duration: "+seconds);
        return seconds;
    }

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
