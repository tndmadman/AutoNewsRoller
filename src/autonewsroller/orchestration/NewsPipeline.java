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

public final class NewsPipeline {
    private final Path root,batchDir;private final NewsConfig cfg;private final EventLog events;private final RuntimeLog logs;private final StoryHistory history;private final PublishHistory publishHistory;
    public NewsPipeline(Path root,NewsConfig cfg,Path batchDir){this.root=root;this.batchDir=batchDir;this.cfg=cfg;this.events=new EventLog(batchDir.resolve("runtime/events.jsonl"));this.logs=new RuntimeLog(batchDir);this.history=new StoryHistory(root.resolve("data/story_history.jsonl"));this.publishHistory=new PublishHistory(root.resolve("data/publish_history.jsonl"));this.logs.debug("Batch initialized at "+batchDir);}

    public List<Candidate> discover(String category,int maxAge,int minSources,boolean enrichArticles)throws Exception{
        events.emit(0,0,PipelineStage.DISCOVER,"scanning all enabled feeds");
        logs.debug("Discovery started category="+category+" maxAgeHours="+maxAge+" minimumIndependentSources="+minSources);

        List<Article>allScanned=new ArrayList<>();
        ArticleHistory ah=new ArticleHistory(root.resolve("data/seen_articles.jsonl"));
        Set<String>seen=ah.ids();
        List<SourceConfig>sources=FeedRegistry.load(root.resolve("config/sources.json"));
        int attemptedFeeds=0,successfulFeeds=0,failedFeeds=0,totalEntries=0,recentEntries=0;

        // Always refresh every enabled RSS source first. Category selection is
        // applied only after the complete feed scan, so watch/batch runs never
        // stop discovery just because one category or one source had no match.
        for(SourceConfig sc:sources){
            if(!sc.enabled()||!"rss".equalsIgnoreCase(sc.type()))continue;
            attemptedFeeds++;
            try{
                List<Article>entries=new RssSource(
                        sc,
                        cfg.getInt("articleFetchTimeout",30),
                        cfg.get("userAgent","AutoNewsRoller/0.1"),
                        root.resolve("data/feed_cache.json")
                ).discover();
                successfulFeeds++;
                totalEntries+=entries.size();

                for(Article a:entries){
                    long age=Math.max(0,Duration.between(a.publishedAt(),Instant.now()).toHours());
                    if(age>maxAge)continue;
                    recentEntries++;
                    Article x=a;

                    // Pull every feed, but only crawl linked article pages when
                    // the story can actually be used for the selected output
                    // category. General mode enriches all categories.
                    if(enrichArticles&&categoryMatches(category,a.category())&&a.description().length()<120){
                        try{
                            String html=new ArticleFetcher(
                                    cfg.getInt("articleFetchTimeout",30),
                                    cfg.get("userAgent","AutoNewsRoller/0.1")
                            ).fetch(a.url());
                            String body=ArticleParser.extractText(html);
                            x=new Article(a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),a.publishedAt(),a.discoveredAt(),a.author(),a.category(),a.description(),body,a.language(),a.sourceTrustTier(),a.authoritativePrimary());
                        }catch(Exception e){
                            logs.debug("Article extraction failed url="+a.url()+" reason="+e.getMessage());
                        }
                    }

                    allScanned.add(x);
                    if(!seen.contains(x.id())){
                        try{ah.append(x);seen.add(x.id());}
                        catch(Exception e){logs.debug("Article history append failed id="+x.id()+" reason="+e.getMessage());}
                    }
                }
            }catch(Exception e){
                failedFeeds++;
                String msg="Feed failed and was skipped: "+sc.name()+" :: "+e.getMessage();
                System.err.println(msg);
                logs.debug(msg);
            }
        }

        List<Article>eligible=allScanned.stream().filter(a->categoryMatches(category,a.category())).toList();
        String scanSummary="Feed scan complete: attempted="+attemptedFeeds+
                " succeeded="+successfulFeeds+
                " failed="+failedFeeds+
                " entries="+totalEntries+
                " recent="+recentEntries+
                " eligible="+eligible.size();
        System.out.println(scanSummary);
        logs.debug(scanSummary);

        List<StoryCluster>clusters=new StoryClusterer().cluster(eligible);
        SourceVerifier verifier=new SourceVerifier();
        StoryRanker ranker=new StoryRanker(cfg.ranking());
        List<Candidate>out=new ArrayList<>();

        for(StoryCluster c:clusters){
            if(history.seen(c.fingerprint))continue;
            VerificationResult vr=verifier.verify(c,minSources);
            if(!vr.accepted()){
                logs.debug("Rejected cluster "+c.id+" verification="+vr.reason());
                continue;
            }
            double score=ranker.score(c,vr.factPackage(),Instant.now(),1);
            out.add(new Candidate(c,vr.factPackage(),score));
        }

        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        logs.debug("Discovery complete articles="+eligible.size()+" clusters="+clusters.size()+" verifiedCandidates="+out.size());
        return out;
    }

    public List<Candidate> discoverFixtures(int maxAge,int minSources)throws Exception{
        Instant now=Instant.now();List<Article>all=new ArrayList<>();String[] names={"source_a.xml","source_b.xml","source_c.xml","authoritative.xml"};
        for(int i=0;i<names.length;i++){boolean auth=names[i].startsWith("authoritative");SourceConfig sc=new SourceConfig(auth?"Official Agency":"Fixture Publisher "+(char)('A'+i),"rss",auth?"world":"technology","fixture",true,auth?1:Math.min(3,i+1),auth);try(var in=Files.newInputStream(root.resolve("tests/fixtures").resolve(names[i]))){List<Article>parsed=RssSource.parse(in,sc,now);for(Article a:parsed){Instant pub=a.title().startsWith("Old archive")?now.minus(Duration.ofHours(maxAge+48L)):now.minus(Duration.ofMinutes(15L+i*5L));all.add(new Article(a.id(),a.publisher(),a.title(),a.url(),a.canonicalUrl(),pub,now,a.author(),a.category(),a.description(),a.bodyText(),a.language(),a.sourceTrustTier(),a.authoritativePrimary()));}}}
        List<StoryCluster>clusters=new StoryClusterer().cluster(all.stream().filter(a->Duration.between(a.publishedAt(),now).toHours()<=maxAge).toList());SourceVerifier verifier=new SourceVerifier();StoryRanker ranker=new StoryRanker(cfg.ranking());List<Candidate>out=new ArrayList<>();for(StoryCluster c:clusters){VerificationResult vr=verifier.verify(c,minSources);if(!vr.accepted())continue;out.add(new Candidate(c,vr.factPackage(),ranker.score(c,vr.factPackage(),now,1)));}out.sort(Comparator.comparingDouble(Candidate::score).reversed());return out;
    }

    public Path produce(Candidate cand,int worker,int slot,Path slotDir,int targetSeconds,String encoder,boolean useComfy,boolean dryRun)throws Exception{
        Files.createDirectories(slotDir);StoryCluster c=cand.cluster();FactPackage fp=cand.factPackage();logs.worker(worker,"slot="+slot+" story="+c.topic+" started");events.emit(worker,slot,PipelineStage.VERIFY,fp.independentSourceCount()+" independent sources");
        Json.write(slotDir.resolve("story.json"),c.toMap());Path articleDir=slotDir.resolve("articles");Files.createDirectories(articleDir);int articleIndex=0;for(Article a:c.articles)Json.write(articleDir.resolve(String.format("%02d.json",articleIndex++)),a.toMap());Json.write(slotDir.resolve("fact_package.json"),fp.toMap());
        OllamaClient oc=dryRun?null:new OllamaClient(cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate"),cfg.get("ollamaModel","llama3.1:8b"),cfg.get("ollamaKeepAlive","30m"),root.resolve("output/runtime/ollama.lock"));NewsScript script=new NewsScriptGenerator(oc,cfg.getInt("ollamaRetries",3)).generate(fp,targetSeconds,dryRun);Json.write(slotDir.resolve("script.json"),script.toMap());events.emit(worker,slot,PipelineStage.SCRIPT,"script ready");
        VisualPlan plan=new VisualPlanner().plan(script,fp);Json.write(slotDir.resolve("visual_plan.json"),plan.toMap());
        if(dryRun){Map<String,Object>audit=new LinkedHashMap<>();audit.put("status","approved-dry-run");audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());Json.write(slotDir.resolve("audit.json"),audit);events.emit(worker,slot,PipelineStage.APPROVED,"dry-run complete");logs.worker(worker,"slot="+slot+" dry-run approved");return slotDir.resolve("script.json");}

        Path visuals=slotDir.resolve("visuals");List<Path>imgs=new ArrayList<>();List<Map<String,Object>>imageSources=new ArrayList<>();CardRenderer cards=new CardRenderer();int i=0;
        for(VisualPlan.Item item:plan.items()){Path p=visuals.resolve(String.format("%02d_%s.png",i++,item.type().toLowerCase(Locale.ROOT)));cards.render(item,p,cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));imgs.add(p);imageSources.add(Map.of("type","procedural-card","path",p.toString(),"visualType",item.type()));}
        String usedCheckpoint="";
        if(useComfy){
            events.emit(worker,slot,PipelineStage.VISUALS,"ComfyUI optional image");ComfyImageGenerator comfy=new ComfyImageGenerator(root,cfg.get("comfyUrl","http://127.0.0.1:8188"),cfg.get("qwenUrl","http://127.0.0.1:8765"));
            try{
                if(!comfy.reachable())throw new IllegalStateException("ComfyUI is not reachable");List<String>available=comfy.checkpoints();String configured=cfg.get("imageCheckpoint","");if(!configured.isBlank()){if(!available.contains(configured))throw new IllegalStateException("configured checkpoint is unavailable: "+configured);usedCheckpoint=configured;}else if(cfg.getBool("comfyAutoPickCheckpoint",false)&&!available.isEmpty()){usedCheckpoint=available.get(0);}else throw new IllegalStateException("no imageCheckpoint configured and auto-pick is disabled");
                int replace=-1;VisualPlan.Item chosen=null;for(int n=0;n<plan.items().size();n++){VisualPlan.Item item=plan.items().get(n);if(!item.prompt().isBlank()&&!item.type().equals("HEADLINE_CARD")&&!item.type().equals("SOURCE_CARD")){replace=n;chosen=item;break;}}
                if(replace>=0){String prompt=(chosen.prompt()+", editorial news illustration, no text, no logos, vertical composition").trim();Path generated=visuals.resolve(String.format("%02d_comfy_generated.png",replace));comfy.generate(prompt,cfg.get("imageNegative","text, watermark, logo, captions, low quality, distorted"),usedCheckpoint,generated,cfg.getInt("imageWidth",768),cfg.getInt("imageHeight",1344),cfg.getInt("imageSteps",24),Double.parseDouble(cfg.get("imageCfg","5.0")));imgs.set(replace,generated);imageSources.set(replace,Map.of("type","comfyui-generated","path",generated.toString(),"checkpoint",usedCheckpoint,"prompt",prompt));logs.worker(worker,"slot="+slot+" ComfyUI generated "+generated.getFileName()+" checkpoint="+usedCheckpoint);}
            }catch(Exception e){String msg="ComfyUI visual failed; using procedural cards: "+e.getMessage();System.err.println(msg);logs.worker(worker,"slot="+slot+" "+msg);usedCheckpoint="";}
        }

        List<String>kv=cfg.csv("kokoroVoices","af_heart");List<String>qv=cfg.csv("qwenVoices","Ryan");String kVoice=kv.get(Math.floorMod(c.id.hashCode(),kv.size()));String qVoice=qv.get(Math.floorMod(c.id.hashCode(),qv.size()));Path wav=slotDir.resolve("narration/narration.wav");NarrationEngine primary=new KokoroNarrator(root);NarrationEngine fallback=new QwenNarrator(root,cfg.get("qwenUrl","http://127.0.0.1:8765"));events.emit(worker,slot,PipelineStage.TTS,"KOKORO active");NarrationResult nr;
        try{nr=primary.narrate(script.narration(),kVoice,wav);events.emit(worker,slot,PipelineStage.TTS,"KOKORO USED");logs.worker(worker,"slot="+slot+" TTS engine used: Kokoro voice="+kVoice);}catch(Exception e){String fail="Kokoro failed: "+e.getMessage();System.err.println(fail);logs.worker(worker,"slot="+slot+" "+fail);events.emit(worker,slot,PipelineStage.TTS,"QWEN3 FALLBACK active");nr=fallback.narrate(script.narration(),qVoice,wav);events.emit(worker,slot,PipelineStage.TTS,"QWEN3 FALLBACK USED");logs.worker(worker,"slot="+slot+" TTS engine used: Qwen3 fallback voice="+qVoice);}

        Path render=slotDir.resolve("render/video.mp4");events.emit(worker,slot,PipelineStage.RENDER,"ffmpeg");VideoRenderer renderer=new VideoRenderer(cfg.get("ffmpegCommand","ffmpeg"),cfg.get("ffprobeCommand","ffprobe"),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920),cfg.getInt("videoFps",30));VideoRenderer.RenderResult rr=renderer.render(imgs,nr.wav(),render,encoder,cfg.get("captions","sentence"),script.narration());
        Map<String,Object>audit=new VideoAudit(cfg.get("ffprobeCommand","ffprobe"),cfg.get("ffmpegCommand","ffmpeg")).audit(render,nr.wav(),cfg.getInt("videoWidth",1080),cfg.getInt("videoHeight",1920));audit.put("sourceCount",fp.sourceCount());audit.put("independentSources",fp.independentSourceCount());audit.put("verifiedFacts",fp.facts().size());audit.put("contestedFacts",fp.disputedClaims().size());audit.put("ttsEngine",nr.engine());audit.put("encoder",rr.encoder());audit.put("duplicateStoryFingerprint",false);Json.write(slotDir.resolve("audit.json"),audit);
        Path finalDir=slotDir.getParent().resolve("final_videos");Path finalVideo=FileNames.unique(finalDir,c.topic,".mp4");Files.createDirectories(finalDir);Files.copy(render,finalVideo,StandardCopyOption.REPLACE_EXISTING);
        Map<String,Object>prov=new LinkedHashMap<>();prov.put("storyId",c.id);prov.put("storyFingerprint",c.fingerprint);prov.put("script",script.toMap());prov.put("generatedTimestamp",Instant.now().toString());prov.put("sources",fp.sources());prov.put("factPackageHash",Hashing.sha256(Json.stringify(fp.toMap())));prov.put("scriptHash",Hashing.sha256(Json.stringify(script.toMap())));prov.put("ttsEngineActuallyUsed",nr.engine());prov.put("voice",nr.voice());prov.put("imageSources",imageSources);prov.put("comfyCheckpoint",usedCheckpoint);prov.put("videoEncoderRequested",encoder);prov.put("videoEncoderActuallyUsed",rr.encoder());prov.put("ffmpegVersion",renderer.version());prov.put("ffmpegCommand",rr.command());prov.put("ollamaModel",cfg.get("ollamaModel","llama3.1:8b"));prov.put("autoNewsRollerCommit",commitSha());prov.put("output",finalVideo.toString());Json.write(finalVideo.resolveSibling(finalVideo.getFileName()+".json"),prov);
        history.append(c,finalVideo);publishHistory.append(c.id,finalVideo,c.fingerprint);events.emit(worker,slot,PipelineStage.APPROVED,finalVideo.getFileName().toString());logs.worker(worker,"slot="+slot+" approved output="+finalVideo);return finalVideo;
    }

    public void rejected(int worker,int slot,String detail){events.emit(worker,slot,PipelineStage.REJECTED,detail);logs.worker(worker,"slot="+slot+" rejected "+detail);}

    private static boolean categoryMatches(String requested,String articleCategory){
        return requested==null||requested.isBlank()||"general".equalsIgnoreCase(requested)||requested.equalsIgnoreCase(articleCategory);
    }

    private String commitSha(){String env=System.getenv("GITHUB_SHA");if(env!=null&&!env.isBlank())return env;try{Process p=new ProcessBuilder("git","rev-parse","HEAD").directory(root.toFile()).redirectErrorStream(true).start();if(p.waitFor(5,TimeUnit.SECONDS)&&p.exitValue()==0)return new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();}catch(Exception ignored){}return "unknown";}
    public record Candidate(StoryCluster cluster,FactPackage factPackage,double score){}
}
