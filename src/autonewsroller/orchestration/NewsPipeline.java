package autonewsroller.orchestration;

import autonewsroller.audit.*;
import autonewsroller.cluster.*;
import autonewsroller.commandcenter.BiasRegistry;
import autonewsroller.commandcenter.PoliticalFramingAnalyzer;
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
    private static final Set<String> KOKORO_VOICE_BLACKLIST=Set.of("af_nicole");
    private static final List<String> SAFE_KOKORO_VOICES=List.of("af_sarah","am_michael");

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
        return produce(cand,worker,slot,slotDir,targetSeconds,encoder,useComfy,requireComfy,maxComfyImages,null,dryRun);
    }

    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean requireComfy,int maxComfyImages,PoliticalVisualData suppliedPolitical,boolean dryRun)throws Exception{
        return produce(cand,worker,slot,slotDir,targetSeconds,encoder,useComfy,requireComfy,maxComfyImages,suppliedPolitical,ProductionMode.STANDARD,dryRun);
    }
    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean requireComfy,int maxComfyImages,PoliticalVisualData suppliedPolitical,ProductionMode mode,boolean dryRun)throws Exception{
        return produce(cand,worker,slot,slotDir,targetSeconds,encoder,useComfy,requireComfy,maxComfyImages,suppliedPolitical,mode,Map.of(),dryRun);
    }
    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean requireComfy,int maxComfyImages,PoliticalVisualData suppliedPolitical,ProductionMode mode,Map<String,Object>rawAnalysis,boolean dryRun)throws Exception{
        if(mode==null)throw new IllegalArgumentException("Production mode required");
        Files.createDirectories(slotDir);
        StoryCluster c=mode==ProductionMode.FRAMING_EXPOSURE?cand.cluster():enrichForProduction(cand.cluster());
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

        PoliticalVisualData political=mode==ProductionMode.MEME_RAGEBAIT
                ?PoliticalVisualData.empty():resolvePoliticalVisualData(c,suppliedPolitical,dryRun);
        Json.write(slotDir.resolve("political_analysis.json"),political.toMap());

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
        MemeEvidenceBuilder.Evidence memeEvidence=null;
        MemeScriptGenerator memeGenerator=null;
        if(mode==ProductionMode.MEME_RAGEBAIT){
            if(!cfg.getBool("memePipelineEnabled",true))throw new IllegalStateException("Meme pipeline disabled");
            String editorialRisk=MemeEditorialGuard.reason(c);
            if(!editorialRisk.isBlank()){
                Json.write(slotDir.resolve("meme_script_diagnostics.json"),Map.of(
                        "storyId",fp.storyId(),"productionMode","MEME_RAGEBAIT",
                        "worker",worker,"timestamp",Instant.now().toString(),
                        "errorCategory","MEME_EDITORIAL_REVIEW_REQUIRED",
                        "finalDisposition","FAILED","reason",editorialRisk));
                throw new IllegalStateException("MEME_EDITORIAL_REVIEW_REQUIRED: "+editorialRisk);
            }
            memeEvidence=new MemeEvidenceBuilder().build(c,fp);
            Json.write(slotDir.resolve("meme_evidence.json"),memeEvidence.toMap());
            memeGenerator=new MemeScriptGenerator(oc,cfg.getInt("ollamaRetries",5),
                    cfg.getBool("memeComedyReviewEnabled",true),
                    cfg.getInt("memeComedyMinimumScore",7),
                    cfg.getBool("memeComedyStrictGate",false));
        }
        PoliticalFramingEvidenceBuilder.Evidence evidence=null;
        PoliticalFramingScriptGenerator exposureGenerator=null;
        if(mode==ProductionMode.FRAMING_EXPOSURE){
            if(!cfg.getBool("politicalExposureEnabled",true))throw new IllegalStateException("Framing experiment disabled");
            evidence=new PoliticalFramingEvidenceBuilder().build(c,fp,political);
            evidence=new PoliticalFramingEvidenceBuilder().withArticleAnalysis(evidence,rawAnalysis);
            Json.write(slotDir.resolve("framing_evidence.json"),evidence.toMap());
            exposureGenerator=new PoliticalFramingScriptGenerator(oc,cfg.getInt("ollamaRetries",5),
                    cfg.getInt("politicalExposureMaxDurationSeconds",180));
        }
        // Report the stage BEFORE invoking Ollama. Failed generation is not source verification.
        events.emit(worker,slot,PipelineStage.SCRIPT,
                "Generating "+(mode==ProductionMode.FRAMING_EXPOSURE?"narrative review":
                    mode==ProductionMode.MEME_RAGEBAIT?"source-grounded meme":"standard news")+" narration");
        NewsScript script=mode==ProductionMode.FRAMING_EXPOSURE
                ?exposureGenerator.generate(fp,evidence,targetSeconds,dryRun)
                :mode==ProductionMode.MEME_RAGEBAIT
                    ?memeGenerator.generate(fp,memeEvidence,dryRun,
                            slotDir.resolve("meme_script_diagnostics.json"))
                    :scriptGenerator.generate(fp,targetSeconds,dryRun);
        Json.write(slotDir.resolve("script.json"),script.toMap());
        if(mode==ProductionMode.FRAMING_EXPOSURE)
            logs.worker(worker,"slot="+slot+" framing duration: requestedMinimum="+targetSeconds+
                    "s selectedTarget="+(int)Math.round(script.estimatedDuration())+
                    "s words="+Text.words(script.narration()));
        events.emit(worker,slot,PipelineStage.SCRIPT,"script ready; words="+Text.words(script.narration())+
                " hookWords="+(script.segments().isEmpty()?0:Text.words(script.segments().get(0).narration()))+
                " hookType="+String.valueOf(script.hook().getOrDefault("type","unknown")));

        if(dryRun){
            GroundedVisualPromptGenerator.Result visualPrompts=mode==ProductionMode.MEME_RAGEBAIT
                    ?new GroundedVisualPromptGenerator.Result(List.of(),0,0,0,"meme-script-scene-prompts")
                    :generateVisualPrompts(script,fp,c,true,worker,slot);
            Json.write(slotDir.resolve("visual_prompt_plan.json"),visualPrompts.toMap());
            VisualPlan plan=mode==ProductionMode.FRAMING_EXPOSURE
                ?new PoliticalFramingVisualPlanner().plan(script,evidence)
                :mode==ProductionMode.MEME_RAGEBAIT?new MemeVisualPlanner().plan(script)
                :new VisualPlanner().plan(script,fp,visualPrompts.scenes(),political,cfg.getDouble("politicalGraphicsMinimumRelevance",0.60),cfg.getBool("politicalGraphicsShowOnUncertain",true),cfg.getDouble("politicalGraphicsSceneSeconds",5.0),cfg.getBool("politicalGraphicsShowConfidence",true),cfg.getBool("politicalGraphicsShowSourceMix",true));
            Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());

            SpeechTiming.Alignment dryTiming=SpeechTiming.align(script.narration(),script.segments(),null,Math.max(1,script.estimatedDuration()));
            Storyboard dryBoard=new VisualDirector().direct(plan,script,dryTiming,Math.max(1,script.estimatedDuration()));
            Json.write(slotDir.resolve("visual_storyboard.json"),dryBoard.toMap());
            Json.write(slotDir.resolve("visual_quality_report.json"),Map.of("status","not-rendered-dry-run","storyboardValid",true));

            Map<String,Object>audit=new LinkedHashMap<>();
            audit.put("status","approved-dry-run");
            audit.put("sourceCount",fp.sourceCount());
            audit.put("independentSources",fp.independentSourceCount());
            audit.put("verifiedFacts",fp.facts().size());
            audit.put("hook",script.hook());
            audit.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());
            audit.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());
            putVisualPromptAudit(audit,visualPrompts);
            putPoliticalAudit(audit,political,plan);
            Json.write(slotDir.resolve("audit.json"),audit);
            events.emit(worker,slot,PipelineStage.APPROVED,"dry-run complete");
            logs.worker(worker,"slot="+slot+" dry-run approved");
            return slotDir.resolve("script.json");
        }

        Path wav=slotDir.resolve("narration/narration.wav");
        NarrationResult nr=narrate(c,script,wav,worker,slot);
        double narrationSeconds=audioDuration(nr.wav());
        // Standard videos retain their established floor. Narrative reviews must
        // actually meet the requested minimum in measured TTS audio, not just
        // the nominal 2.5-words/second writing estimate.
        double minimumFinalSeconds=mode==ProductionMode.MEME_RAGEBAIT?0.5:
                cfg.getDouble("minimumFinalVideoSeconds",61.0);
        if(mode==ProductionMode.FRAMING_EXPOSURE)
            minimumFinalSeconds=Math.max(minimumFinalSeconds,targetSeconds);
        logs.worker(worker,String.format(Locale.ROOT,"slot=%d narration duration=%.2fs words=%d",slot,narrationSeconds,Text.words(script.narration())));

        if(mode!=ProductionMode.MEME_RAGEBAIT&&narrationSeconds<minimumFinalSeconds){
            double desiredAudio=Math.max(minimumFinalSeconds+4.0,65.0);
            int durationCeiling=mode==ProductionMode.FRAMING_EXPOSURE
                    ?Math.max(targetSeconds,cfg.getInt("politicalExposureMaxDurationSeconds",180))
                    :95;
            int adjustedTarget=Math.min(durationCeiling,Math.max(targetSeconds+5,
                    (int)Math.ceil(targetSeconds*desiredAudio/Math.max(1.0,narrationSeconds))));
            String retry=String.format(Locale.ROOT,"TTS duration %.2fs is below %.2fs; regenerating narration for %ds target before images",narrationSeconds,minimumFinalSeconds,adjustedTarget);
            System.err.println(retry);
            events.emit(worker,slot,PipelineStage.SCRIPT,retry);
            logs.worker(worker,"slot="+slot+" "+retry);

            script=mode==ProductionMode.FRAMING_EXPOSURE?exposureGenerator.generate(fp,evidence,adjustedTarget,false):scriptGenerator.generate(fp,adjustedTarget,false);
            Json.write(slotDir.resolve("script.json"),script.toMap());
            nr=narrate(c,script,wav,worker,slot);
            narrationSeconds=audioDuration(nr.wav());
            logs.worker(worker,String.format(Locale.ROOT,"slot=%d repaired narration duration=%.2fs words=%d",slot,narrationSeconds,Text.words(script.narration())));
            if(narrationSeconds<minimumFinalSeconds)
                throw new IllegalStateException(String.format(Locale.ROOT,"Narration audio still too short after repair: %.2fs; minimum is %.2fs",narrationSeconds,minimumFinalSeconds));
        }

        GroundedVisualPromptGenerator.Result visualPrompts=mode==ProductionMode.MEME_RAGEBAIT
                ?new GroundedVisualPromptGenerator.Result(List.of(),0,0,0,"meme-script-scene-prompts")
                :generateVisualPrompts(script,fp,c,false,worker,slot);
        Json.write(slotDir.resolve("visual_prompt_plan.json"),visualPrompts.toMap());
        VisualPlan plan=mode==ProductionMode.FRAMING_EXPOSURE
                ?new PoliticalFramingVisualPlanner().plan(script,evidence)
                :mode==ProductionMode.MEME_RAGEBAIT?new MemeVisualPlanner().plan(script)
                :new VisualPlanner().plan(script,fp,visualPrompts.scenes(),political,cfg.getDouble("politicalGraphicsMinimumRelevance",0.60),cfg.getBool("politicalGraphicsShowOnUncertain",true),cfg.getDouble("politicalGraphicsSceneSeconds",5.0),cfg.getBool("politicalGraphicsShowConfidence",true),cfg.getBool("politicalGraphicsShowSourceMix",true));
        Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());

        Path timingSidecar=nr.wav().resolveSibling(nr.wav().getFileName().toString().replaceFirst("\\.wav$",".timing.tsv"));
        SpeechTiming.Alignment alignment=SpeechTiming.align(script.narration(),script.segments(),
                cfg.getBool("visualUseExactTtsTiming",true)?timingSidecar:null,narrationSeconds);
        if(mode==ProductionMode.FRAMING_EXPOSURE){
            List<Double>parts=alignment.segmentDurations();
            if(parts.size()!=8)throw new IllegalStateException("Framing segment duration alignment unavailable.");
            double analysisDuration=0,totalDuration=0;
            for(int i=0;i<parts.size();i++){
                totalDuration+=parts.get(i);
                if(i>=3)analysisDuration+=parts.get(i);
            }
            double actualShare=analysisDuration/Math.max(0.001,totalDuration);
            if(actualShare<cfg.getDouble("politicalExposureRequiredAnalysisFraction",0.65))
                throw new IllegalStateException(String.format(Locale.ROOT,
                    "Framing analysis occupies %.1f%% of narration; minimum %.1f%%",
                    actualShare*100,cfg.getDouble("politicalExposureRequiredAnalysisFraction",0.65)*100));
        }
        Storyboard storyboard=new VisualDirector().direct(plan,script,alignment,narrationSeconds);
        Json.write(slotDir.resolve("visual_storyboard.json"),storyboard.toMap());

        Path visuals=slotDir.resolve("visuals");
        List<Path>imgs=new ArrayList<>();
        List<Path>renderBackgrounds=new ArrayList<>();
        List<Path>renderOverlays=new ArrayList<>();
        List<Boolean>renderMotion=new ArrayList<>();
        List<Map<String,Object>>imageSources=new ArrayList<>();
        boolean classic="classic".equalsIgnoreCase(cfg.get("visualQualityPreset","enhanced"));
        CardRenderer cards=mode==ProductionMode.MEME_RAGEBAIT?new MemeCardRenderer():
                classic?new ClassicCardRenderer():new CardRenderer();int i=0;
        for(VisualPlan.Item item:plan.items()){
            Path p=visuals.resolve(String.format("%02d_%s.png",i++,item.type().toLowerCase(Locale.ROOT)));
            if(mode==ProductionMode.FRAMING_EXPOSURE)
                new PoliticalFramingCardRenderer().render(item,p,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),classic);
            else cards.render(item,p,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
            imgs.add(p);renderBackgrounds.add(p);renderOverlays.add(null);renderMotion.add(false);
            imageSources.add(Map.of("type","procedural-card","path",p.toString(),"visualType",item.type()));
            if(!"classic".equalsIgnoreCase(cfg.get("visualQualityPreset","enhanced"))
                    &&CardRenderer.supportsGraphicAnimation(item.type())){
                Path base=visuals.resolve(String.format("%02d_graphic_base.png",item.index()));
                Path overlay=visuals.resolve(String.format("%02d_graphic_reveal.png",item.index()));
                cards.renderGraphicLayers(p,base,overlay,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
                renderBackgrounds.set(renderBackgrounds.size()-1,base);
                renderOverlays.set(renderOverlays.size()-1,overlay);
                imageSources.set(imageSources.size()-1,Map.of("type","procedural-animated-graphic",
                        "path",p.toString(),"backgroundPath",base.toString(),
                        "overlayPath",overlay.toString(),"visualType",item.type()));
            }
        }
        List<Map<String,Object>>imageReviews=new ArrayList<>();
        List<Long>acceptedHashes=new ArrayList<>();
        ImageQualityInspector inspector=new ImageQualityInspector();
        OllamaVisionReviewer visionReviewer=new OllamaVisionReviewer(root,cfg.get("visualImageReviewModel",""),
                cfg.get("visualImageReviewOllamaUrl",cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate")));
        String usedCheckpoint="";
        int comfyGenerated=0;
        List<Integer> requiredGeneratedScenes=new ArrayList<>();
        boolean sensitiveTopic=autonewsroller.safety.ContentSafetyPolicy.sensitive(fp.headline()+" "+fp.summary());
        if(sensitiveTopic&&(mode==ProductionMode.MEME_RAGEBAIT||mode==ProductionMode.STANDARD)){
            // Keep sensitive news on attributable evidence graphics rather than
            // fabricate potentially harmful incident photographs.
            useComfy=false;
            requireComfy=false;
            events.emit(worker,slot,PipelineStage.VISUALS,"SENSITIVE TOPIC: procedural evidence graphics only");
        }else if(mode!=ProductionMode.FRAMING_EXPOSURE){
            // ComfyUI illustrations are mandatory for every normal/meme video.
            // No controller setting, batch CLI switch or retry can approve a
            // caption-only / placeholder-card video.
            if(!useComfy)throw new IllegalStateException(
                    "VISUAL_IMAGE_REQUIRED: ComfyUI image generation disabled for "+mode);
            requireComfy=true;
        }
        if(requireComfy&&!useComfy)throw new IllegalStateException("ComfyUI is required for this job but useComfy=false");
        if(useComfy&&mode==ProductionMode.FRAMING_EXPOSURE)
            throw new IllegalStateException("Framing exposure uses verified procedural evidence cards, not fabricated article images.");
        if(useComfy){
            events.emit(worker,slot,PipelineStage.VISUALS,"COMFYUI CHECK starting");
            ComfyImageGenerator comfy=new ComfyImageGenerator(root,cfg.get("comfyUrl","http://127.0.0.1:8188"),cfg.get("qwenUrl","http://127.0.0.1:8765"));
            try{
                ComfyRuntime.ensureRunning(root,cfg);
                if(!comfy.reachable())throw new IllegalStateException("ComfyUI is not reachable at "+cfg.get("comfyUrl","http://127.0.0.1:8188"));
                List<String>available=comfy.checkpoints();
                String memeCheckpoint=mode==ProductionMode.MEME_RAGEBAIT?cfg.get("memeImageCheckpoint",""):"";
                final String configured=memeCheckpoint.isBlank()
                        ?cfg.get("imageCheckpoint",""):memeCheckpoint;
                if(!configured.isBlank()){
                    usedCheckpoint=available.stream().filter(x->checkpointMatches(x,configured)).findFirst().orElse("");
                    if(usedCheckpoint.isBlank())throw new IllegalStateException("configured checkpoint is unavailable: "+configured+"; available="+available);
                }else if(cfg.getBool("comfyAutoPickCheckpoint",true)&&!available.isEmpty()){
                    usedCheckpoint=available.get(0);
                }else throw new IllegalStateException("no imageCheckpoint configured and ComfyUI returned no usable checkpoints");

                int limit=Math.max(1,maxComfyImages);
                List<Integer>secondaryEligible=new ArrayList<>();
                List<Integer>eligible=new ArrayList<>();
                for(int n=0;n<plan.items().size();n++){
                    VisualPlan.Item item=plan.items().get(n);
                    if(mode!=ProductionMode.MEME_RAGEBAIT&&(item.type().equals("HEADLINE_CARD")
                            ||item.type().equals("SOURCE_CARD")||item.type().equals("POLITICAL_CONTEXT")
                            ||item.type().equals("STATISTIC")||item.type().equals("QUOTE")||item.type().equals("TIMELINE")))continue;
                    if(item.type().equals("FACT_HIGHLIGHT"))secondaryEligible.add(n);
                    else eligible.add(n);
                }

                for(int n:secondaryEligible)if(eligible.size()<limit)eligible.add(n);
                eligible.sort(Integer::compareTo);
                if(eligible.size()>limit){
                    // Image scenes are already ordered by narration, so stop after requested count.
                    eligible=new ArrayList<>(eligible.subList(0,limit));
                }
                if(eligible.isEmpty()){
                    throw new IllegalStateException("visual plan contained no replaceable story visuals");
                }
                requiredGeneratedScenes.addAll(eligible);

                int maxRetries=Math.max(0,Math.min(4,cfg.getInt("visualImageReviewRetries",2)));
                int requestedWidth=cfg.getInt("imageWidth",ComfyImageGenerator.DEFAULT_WIDTH),requestedHeight=cfg.getInt("imageHeight",ComfyImageGenerator.DEFAULT_HEIGHT);
                int requestedSteps=cfg.getInt("imageSteps",ComfyImageGenerator.DEFAULT_STEPS);
                double requestedCfg=cfg.getDouble("imageCfg",ComfyImageGenerator.DEFAULT_CFG);
                for(int replace:eligible){
                    if(comfyGenerated>=limit)break;
                    VisualPlan.Item chosen=plan.items().get(replace);
                    boolean memeImage=mode==ProductionMode.MEME_RAGEBAIT;
                    String negativePrompt=ComfyPromptProfiles.negative(cfg,chosen,memeImage);
                    for(int attempt=0;attempt<=maxRetries;attempt++){
                        String prompt=ComfyPromptProfiles.positive(chosen,memeImage,attempt);
                        long seed=Integer.toUnsignedLong(Objects.hash(c.id,replace,usedCheckpoint,attempt));
                        Path generated=visuals.resolve(String.format("%02d_comfy_attempt_%02d.png",replace,attempt+1));
                        String generating="COMFYUI GENERATING "+(comfyGenerated+1)+"/"+Math.min(limit,eligible.size())+" attempt="+(attempt+1)+" checkpoint="+usedCheckpoint;
                        events.emit(worker,slot,PipelineStage.VISUALS,generating);
                        try{
                            comfy.generate(prompt,negativePrompt,usedCheckpoint,generated,requestedWidth,requestedHeight,
                                    requestedSteps,requestedCfg,seed);
                        }catch(Exception imageError){
                            if(!ComfyImageGenerator.looksLikeCudaOom(imageError))throw imageError;
                            events.emit(worker,slot,PipelineStage.VISUALS,"COMFYUI OOM RECOVERY: retrying after VRAM release");
                            comfy.recoverFromOom();
                            comfy.generate(prompt,negativePrompt,usedCheckpoint,generated,requestedWidth,requestedHeight,
                                    requestedSteps,requestedCfg,seed);
                        }
                        ImageQualityInspector.Result inspection=inspector.inspect(generated,requestedWidth,requestedHeight,
                                cfg.getBool("visualPostGenerationChecks",true)?acceptedHashes:List.of());
                        Map<String,Object>review=new LinkedHashMap<>(inspection.toMap());
                        review.put("sceneIndex",replace);review.put("attempt",attempt+1);review.put("seed",seed);
                        review.put("factIds",chosen.data().getOrDefault("factIds",List.of()));
                        if(!inspection.accepted()){
                            imageReviews.add(review);
                            events.emit(worker,slot,PipelineStage.VISUALS,"IMAGE REJECTED scene="+replace+" reason="+inspection.reason());
                            Files.deleteIfExists(generated);
                            continue;
                        }
                        List<String>expected=chosen.data().get("anchorEntities") instanceof List<?> l
                                ?l.stream().map(String::valueOf).toList():List.of();
                        List<String>forbidden=chosen.data().get("mustNotShow") instanceof List<?> l
                                ?l.stream().map(String::valueOf).toList():List.of();
                        OllamaVisionReviewer.Result semantic=visionReviewer.review(generated,chosen.prompt(),expected,forbidden);
                        review.put("vision",semantic.toMap());
                        review.put("accepted",semantic.accepted());
                        imageReviews.add(review);
                        if(!semantic.accepted()){
                            events.emit(worker,slot,PipelineStage.VISUALS,"IMAGE VISION WARNING scene="+replace+" reason="+semantic.reason());
                            Files.deleteIfExists(generated);
                            continue;
                        }
                        acceptedHashes.add(inspection.perceptualHash());
                        Path overlay=visuals.resolve(String.format("%02d_overlay_%02d.png",replace,attempt+1));
                        cards.renderOverlay(chosen,overlay,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
                        Path composed=visuals.resolve(String.format("%02d_post_%02d.png",replace,attempt+1));
                        cards.renderWithImage(chosen,generated,composed,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
                        Path background=visuals.resolve(String.format("%02d_cropped_%02d.png",replace,attempt+1));
                        cards.renderBackground(chosen,generated,background,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
                        imgs.set(replace,composed);
                        renderBackgrounds.set(replace,background);
                        renderOverlays.set(replace,overlay);
                        renderMotion.set(replace,!classic&&!"off".equalsIgnoreCase(cfg.get("visualMotionIntensity","moderate")));
                        Map<String,Object>imageSource=new LinkedHashMap<>();
                        imageSource.put("type","comfyui-generated-ai-illustration");
                        imageSource.put("path",composed.toString());imageSource.put("generatedPath",generated.toString());
                        imageSource.put("backgroundPath",background.toString());imageSource.put("overlayPath",overlay.toString());
                        imageSource.put("motion",storyboard.scenes().get(replace).motion());
                        imageSource.put("checkpoint",usedCheckpoint);imageSource.put("prompt",prompt);imageSource.put("negativePrompt",negativePrompt);
                        imageSource.put("seed",seed);imageSource.put("steps",requestedSteps);imageSource.put("cfg",requestedCfg);
                        imageSource.put("sampler",ComfyImageGenerator.SAMPLER);imageSource.put("scheduler",ComfyImageGenerator.SCHEDULER);
                        imageSource.put("resolution",requestedWidth+"x"+requestedHeight);
                        imageSource.put("inspection",inspection.toMap());imageSource.put("visionReview",semantic.toMap());imageSource.put("retryCount",attempt);
                        imageSource.put("aiIllustrationDisclosure",true);
                        imageSource.put("visualType",chosen.type());imageSource.put("segmentIndex",chosen.data().getOrDefault("segmentIndex",chosen.index()));
                        imageSource.put("visualStrategy",chosen.data().getOrDefault("visualStrategy",chosen.type()));
                        imageSource.put("factIds",chosen.data().getOrDefault("factIds",List.of()));
                        imageSource.put("anchorEntities",chosen.data().getOrDefault("anchorEntities",List.of()));
                        imageSource.put("groundingScore",chosen.data().getOrDefault("groundingScore",0));
                        imageSources.set(replace,imageSource);
                        comfyGenerated++;
                        String comfyUsed="COMFYUI USED checkpoint="+usedCheckpoint+" image="+generated.getFileName()+" count="+comfyGenerated;
                        events.emit(worker,slot,PipelineStage.VISUALS,comfyUsed);
                        logs.worker(worker,"slot="+slot+" "+comfyUsed);
                        break;
                    }
                }

                int requiredCount=requiredGeneratedScenes.size();
                if(requireComfy&&comfyGenerated<requiredCount)
                    throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: ComfyUI accepted "+
                            comfyGenerated+"/"+requiredCount+" requested images; no fallback video will render");
                if(comfyGenerated==0)throw new IllegalStateException("ComfyUI produced zero accepted images");
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

        // This gate precedes all video-render and output promotion paths.
        // Cards are required graphics, but do not count as AI illustrations.
        VisualAssetGate.requireReady(mode,sensitiveTopic,requiredGeneratedScenes,comfyGenerated,
                imageSources,imgs,renderBackgrounds,renderOverlays,
                cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));
        events.emit(worker,slot,PipelineStage.VISUALS,
                "VISUAL ASSET GATE PASSED: ComfyUI "+comfyGenerated+
                "/"+requiredGeneratedScenes.size()+" required; scenes="+imgs.size());
        Path renderAudio=nr.wav();
        // Both formats open with the same original sting. +9.54 dB is 3x waveform
        // amplitude relative to the former -14 dB exposure level, before limiting.
        boolean introEnabled=mode==ProductionMode.FRAMING_EXPOSURE
                ?cfg.getBool("politicalExposureIntroSoundEnabled",true)
                :mode==ProductionMode.MEME_RAGEBAIT?cfg.getBool("memeIntroSoundEnabled",true)
                :cfg.getBool("newsIntroSoundEnabled",true);
        if(mode==ProductionMode.FRAMING_EXPOSURE&&!introEnabled)
            throw new IllegalStateException("Required experimental audio sting disabled");
        double introGainDb=mode==ProductionMode.FRAMING_EXPOSURE
                ?cfg.getDouble("politicalExposureIntroSoundGainDb",-4.46)
                :mode==ProductionMode.MEME_RAGEBAIT?cfg.getDouble("memeIntroSoundGainDb",-10.0)
                :cfg.getDouble("newsIntroSoundGainDb",-4.46);
        String introId=mode==ProductionMode.MEME_RAGEBAIT
                ?ComedyIntroAudioMixer.STING_ID:NewsIntroAudioMixer.STING_ID;
        if(introEnabled)
            renderAudio=mode==ProductionMode.MEME_RAGEBAIT
                    ?new ComedyIntroAudioMixer().mix(nr.wav(),slotDir.resolve("narration/intro_mixed.wav"),
                            cfg.get("ffmpegCommand","ffmpeg"),introGainDb)
                    :new NewsIntroAudioMixer().mix(nr.wav(),slotDir.resolve("narration/intro_mixed.wav"),
                            cfg.get("ffmpegCommand","ffmpeg"),introGainDb);
        Path render=slotDir.resolve("render/video.mp4");events.emit(worker,slot,PipelineStage.RENDER,"ffmpeg-v5-editorial");VideoRenderer renderer=new VideoRenderer(cfg.get("ffmpegCommand","ffmpeg"),cfg.get("ffprobeCommand","ffprobe"),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),cfg.getInt("videoFps",30),classic);List<Double>sceneWeights=!classic&&cfg.getBool("visualNarrationSyncEnabled",true)?storyboard.durations():plan.items().stream().map(VisualPlan.Item::duration).toList();VideoRenderer.RenderResult rr=renderer.render(renderBackgrounds,renderOverlays,renderMotion,sceneWeights,renderAudio,render,encoder,cfg.get("captions","sentence"),script.narration(),cfg.getBool("visualUseExactTtsTiming",true)?timingSidecar:null);
        events.emit(worker,slot,PipelineStage.RENDER,"ENCODER ACTUAL="+rr.encoder());
        Map<String,Object>audit=new VideoAudit(cfg.get("ffprobeCommand","ffprobe"),cfg.get("ffmpegCommand","ffmpeg")).audit(render,renderAudio,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));double finalSeconds=((Number)audit.get("videoDuration")).doubleValue();Map<String,Object>visualQuality=VisualQualityReport.create(cfg.get("ffmpegCommand","ffmpeg"),render,slotDir,finalSeconds,storyboard,cfg.getBool("visualGenerateContactSheet",true),!"off".equalsIgnoreCase(cfg.get("captions","sentence")),classic);
        visualQuality.put("imageInspections",imageReviews);
        visualQuality.put("acceptedComfyImages",comfyGenerated);
        Json.write(slotDir.resolve("visual_quality_report.json"),visualQuality);
        audit.put("visualQuality",visualQuality);audit.put("narrationTiming",alignment.toMap());if(finalSeconds<minimumFinalSeconds)throw new IllegalStateException(String.format(Locale.ROOT,"Rendered video too short: %.2fs; minimum is %.2fs",finalSeconds,minimumFinalSeconds));audit.put("productionMode",mode.name());
        audit.put("introSound",Map.of("enabled",introEnabled,
                "identity",introEnabled?introId:"",
                "gainDb",introGainDb,
                "startSeconds",0));
        if(mode==ProductionMode.FRAMING_EXPOSURE){
            audit.put("framingRequestedTargetSeconds",targetSeconds);
            audit.put("framingSelectedTargetSeconds",script.estimatedDuration());
            audit.put("framingNarrationActualSeconds",narrationSeconds);
            audit.put("framingMaximumAutomaticTargetSeconds",cfg.getInt("politicalExposureMaxDurationSeconds",180));
        }
        audit.put("minimumDurationRequired",minimumFinalSeconds);audit.put("sceneCount",imgs.size());audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());audit.put("contestedFacts",fp.disputedClaims().size());audit.put("ttsEngine",nr.engine());audit.put("encoder",rr.encoder());audit.put("duplicateStoryFingerprint",false);audit.put("hook",script.hook());audit.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());audit.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());audit.put("visualPackage",BroadcastTheme.PACKAGE_ID);audit.put("postLayout","tiktok-footer-220");audit.put("thumbnailStrategy","hook-frame-zero");putVisualPromptAudit(audit,visualPrompts);putPoliticalAudit(audit,political,plan);Json.write(slotDir.resolve("audit.json"),audit);
        Path finalDir=slotDir.getParent().resolve("final_videos");Path finalVideo=FileNames.unique(finalDir,c.topic,".mp4");Files.createDirectories(finalDir);Files.copy(render,finalVideo,StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object>prov=new LinkedHashMap<>();prov.put("productionMode",mode.name());
        prov.put("introSound",Map.of("enabled",introEnabled,
                "identity",introEnabled?introId:"",
                "gainDb",introGainDb,
                "startSeconds",0));
        if(mode==ProductionMode.FRAMING_EXPOSURE){
            prov.put("framingDuration",Map.of(
                    "requestedMinimumSeconds",targetSeconds,
                    "selectedTargetSeconds",script.estimatedDuration(),
                    "actualNarrationSeconds",narrationSeconds,
                    "maximumAutomaticTargetSeconds",cfg.getInt("politicalExposureMaxDurationSeconds",180)));
        }
        prov.put("experimentalFormatVersion",mode==ProductionMode.FRAMING_EXPOSURE?"framing-exposure-v2-single-article":
                mode==ProductionMode.MEME_RAGEBAIT?"meme-ragebait-v1":"");if(memeEvidence!=null){prov.put("memeEvidence",memeEvidence.toMap());
            prov.put("comedyAngle",script.hook().getOrDefault("angle",""));
            prov.put("sourceContentFingerprint",memeEvidence.fingerprint());
            prov.put("measuredNarrationSeconds",narrationSeconds);
            prov.put("actualFinalVideoSeconds",finalSeconds);}
        if(evidence!=null){prov.put("framingEvidence",evidence.toMap());prov.put("lockedHeadline",script.headline());prov.put("lockedTeaser",PoliticalFramingScriptGenerator.TEASER);}prov.put("storyId",c.id);prov.put("storyFingerprint",c.fingerprint);prov.put("script",script.toMap());prov.put("hook",script.hook());prov.put("openingVisualType",plan.items().isEmpty()?"":plan.items().get(0).type());prov.put("openingSceneWeight",plan.items().isEmpty()?0:plan.items().get(0).duration());prov.put("visualPackage",BroadcastTheme.PACKAGE_ID);prov.put("postLayout","tiktok-footer-220");prov.put("motionPackage","editorial-motion-v5");prov.put("narrationTiming",alignment.toMap());prov.put("visualStoryboard",storyboard.toMap());prov.put("visualQuality",visualQuality);prov.put("thumbnailStrategy","hook-frame-zero");prov.put("generatedTimestamp",Instant.now().toString());prov.put("sources",fp.sources());prov.put("factPackageHash",Hashing.sha256(Json.stringify(fp.toMap())));prov.put("scriptHash",Hashing.sha256(Json.stringify(script.toMap())));prov.put("ttsEngineActuallyUsed",nr.engine());prov.put("voice",nr.voice());prov.put("imageSources",imageSources);prov.put("comfyCheckpoint",usedCheckpoint);prov.put("comfyImagesGenerated",comfyGenerated);prov.put("comfyRequired",requireComfy);prov.put("videoEncoderRequested",encoder);prov.put("videoEncoderActuallyUsed",rr.encoder());prov.put("ffmpegVersion",renderer.version());prov.put("ffmpegCommand",rr.command());prov.put("ollamaModel",cfg.get("ollamaModel","llama3.1:8b"));prov.put("visualPromptModel",visualPrompts.model());prov.put("visualPromptPlannerEnabled",cfg.getBool("visualPromptEnabled",true));prov.put("visualPromptFallbackCount",visualPrompts.fallbackCount());prov.put("visualPromptRepairCount",visualPrompts.repairAttempts());prov.put("visualPromptRejectedCount",visualPrompts.rejectedCount());prov.put("politicalContext",political.toMap());prov.put("politicalContextRendered",plan.items().stream().anyMatch(x->"POLITICAL_CONTEXT".equalsIgnoreCase(x.type())));prov.put("autoNewsRollerCommit",commitSha());prov.put("output",finalVideo.toString());Json.write(finalVideo.resolveSibling(finalVideo.getFileName()+".json"),prov);
        history.append(c,finalVideo);publishHistory.append(c.id,finalVideo,c.fingerprint);events.emit(worker,slot,PipelineStage.APPROVED,finalVideo.getFileName().toString());logs.worker(worker,"slot="+slot+" approved output="+finalVideo);return finalVideo;
    }

    private GroundedVisualPromptGenerator.Result generateVisualPrompts(NewsScript script,FactPackage fp,StoryCluster cluster,boolean dryRun,int worker,int slot){
        events.emit(worker,slot,PipelineStage.VISUALS,"VISUAL PROMPT PLANNER starting");
        OllamaClient visualOllama=null;
        if(!dryRun&&cfg.getBool("visualPromptEnabled",true)){
            String url=cfg.get("visualPromptOllamaUrl","");
            if(url.isBlank())url=cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate");
            String model=cfg.get("visualPromptModel","");
            if(model.isBlank())model=cfg.get("ollamaModel","llama3.1:8b");
            visualOllama=new OllamaClient(url,model,cfg.get("ollamaKeepAlive","30m"),root.resolve("output/runtime/ollama.lock"),
                    cfg.getInt("ollamaContextTokens",8192),cfg.getInt("visualPromptMaxOutputTokens",2400),cfg.getDouble("visualPromptTemperature",0.15));
        }
        GroundedVisualPromptGenerator.Result result=new GroundedVisualPromptGenerator(visualOllama,cfg).generate(script,fp,cluster,dryRun);
        events.emit(worker,slot,PipelineStage.VISUALS,"VISUAL PROMPT PLAN accepted scenes="+result.scenes().size()+" repairs="+result.repairAttempts()+" fallbacks="+result.fallbackCount());
        for(VisualPromptPlan scene:result.scenes()){
            String msg="VISUAL PROMPT scene="+scene.segmentIndex()+" score="+Math.round(scene.groundingScore())+" facts="+String.join(",",scene.factIds())+" strategy="+scene.strategy()+(scene.fallback()?" fallback=true":"");
            System.out.println(msg);logs.worker(worker,"slot="+slot+" "+msg);
        }
        return result;
    }

    private static void putVisualPromptAudit(Map<String,Object>audit,GroundedVisualPromptGenerator.Result visualPrompts){
        audit.put("visualPromptPlannerEnabled",true);
        audit.put("visualPromptScenes",visualPrompts.scenes().size());
        audit.put("visualPromptValidatedScenes",visualPrompts.scenes().stream().filter(VisualPromptPlan::valid).count());
        audit.put("visualPromptFallbackScenes",visualPrompts.fallbackCount());
        audit.put("visualPromptRepairAttempts",visualPrompts.repairAttempts());
        audit.put("visualPromptRejectedScenes",visualPrompts.rejectedCount());
        audit.put("visualPromptMinimumGroundingScore",visualPrompts.scenes().stream().mapToDouble(VisualPromptPlan::groundingScore).min().orElse(0));
        audit.put("visualPromptLowestGroundingScore",visualPrompts.lowestScore());
    }

    private PoliticalVisualData resolvePoliticalVisualData(StoryCluster cluster,PoliticalVisualData supplied,boolean dryRun){
        Map<String,Object>sourceMix=BiasRegistry.load(root.resolve("config/source_bias.json")).mix(cluster.publishers());
        boolean candidate=PoliticalFramingAnalyzer.likelyPolitical(cluster);
        if(!cfg.getBool("politicalGraphicsEnabled",true)){
            return PoliticalVisualData.from(candidate,sourceMix,Map.of(),"DISABLED","");
        }
        if(supplied!=null&&supplied.analysisAvailable())return supplied;
        if(!candidate){
            return PoliticalVisualData.from(false,sourceMix,Map.of(),"NOT_POLITICAL","");
        }
        if(dryRun){
            return PoliticalVisualData.from(true,sourceMix,Map.of(),"NOT_ANALYZED","");
        }

        try{
            Map<String,Object>analysis=new PoliticalFramingAnalyzer(root,cfg).analyze(cluster);
            return PoliticalVisualData.from(true,sourceMix,analysis,"COMPLETE","");
        }catch(Exception e){
            String msg="Political framing analysis unavailable; continuing without political graphic: "+e.getMessage();
            System.err.println(msg);
            logs.debug(msg);
            return PoliticalVisualData.from(true,sourceMix,Map.of(),"FAILED",e.getMessage());
        }
    }

    private static void putPoliticalAudit(Map<String,Object>audit,PoliticalVisualData political,VisualPlan plan){
        boolean rendered=plan.items().stream().anyMatch(x->"POLITICAL_CONTEXT".equalsIgnoreCase(x.type()));
        audit.put("politicalContextRendered",rendered);
        audit.put("politicalCandidate",political.politicalCandidate());
        audit.put("politicalAnalysisAvailable",political.analysisAvailable());
        audit.put("politicalRelevance",political.politicalRelevance());
        audit.put("storyFramingClassification",political.storyClassification());
        audit.put("storyFramingConfidence",political.storyConfidence());
        audit.put("politicalRatedSourceCount",political.ratedSourceCount());
        audit.put("politicalUnratedSourceCount",political.sourceUnknown());
    }

    public void rejected(int worker,int slot,String detail){events.emit(worker,slot,PipelineStage.REJECTED,detail);logs.worker(worker,"slot="+slot+" rejected "+detail);}

    /**
     * Enrich the attributed article before political analysis is computed.
     * Only the selected article can be fetched: other outlets must not replace
     * the text whose framing will be reviewed. The controller atomically stores
     * this revised cluster and its source-content fingerprint on completion.
     */
    public StoryCluster enrichForFraming(StoryCluster original){
        int selected=-1;
        for(int i=0;i<original.articles.size();i++){
            Article a=original.articles.get(i);
            if(a.title().isBlank()||a.publisher().isBlank()||a.url().isBlank())continue;
            if(selected<0)selected=i;
            if(a.title().equalsIgnoreCase(original.topic)){selected=i;break;}
        }
        if(selected<0)return original;
        Article current=original.articles.get(selected);
        StoryCluster single=new StoryCluster(original.id,original.topic,List.of(current),
                original.entities,original.fingerprint);
        Article enriched=enrichForProduction(single).articles.get(0);
        if(enriched.equals(current))return original;
        List<Article>revised=new ArrayList<>(original.articles);
        revised.set(selected,enriched);
        return new StoryCluster(original.id,original.topic,revised,original.entities,original.fingerprint);
    }

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
        // The same WAV path is reused after duration-repair and Kokoro may fail.
        // Never reuse a stale Kokoro timing sidecar with a Qwen3 WAV.
        Files.deleteIfExists(wav.resolveSibling(wav.getFileName().toString().replaceFirst("\\.wav$",".timing.tsv")));

        List<String>configuredKokoro=cfg.csv("kokoroVoices","af_sarah,am_michael");
        List<String>kv=allowedKokoroVoices(configuredKokoro);
        if(kv.size()!=configuredKokoro.size()){
            String blacklistMsg="KOKORO VOICE BLACKLIST: af_nicole removed from configured pool";
            System.out.println(blacklistMsg);
            logs.worker(worker,"slot="+slot+" "+blacklistMsg);
        }
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

    public static List<String> allowedKokoroVoices(List<String>configured){
        List<String>safe=new ArrayList<>();
        if(configured!=null){
            for(String voice:configured){
                String clean=voice==null?"":voice.trim();
                if(clean.isBlank())continue;
                String key=clean.toLowerCase(Locale.ROOT).replace('-','_');
                if(KOKORO_VOICE_BLACKLIST.contains(key))continue;
                if(!safe.contains(clean))safe.add(clean);
            }
        }
        if(safe.isEmpty())safe.addAll(SAFE_KOKORO_VOICES);
        return List.copyOf(safe);
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
