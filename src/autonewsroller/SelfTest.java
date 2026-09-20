package autonewsroller;

import autonewsroller.cluster.*;
import autonewsroller.commandcenter.*;
import autonewsroller.config.*;
import autonewsroller.ingest.*;
import autonewsroller.model.*;
import autonewsroller.orchestration.*;
import autonewsroller.script.*;
import autonewsroller.util.*;
import autonewsroller.verify.*;
import autonewsroller.video.*;
import autonewsroller.visuals.*;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class SelfTest {
    private int passed;

    public void run(Path root)throws Exception{
        testJson();
        testSourceConfig(root);
        List<Article>a=testRss(root);
        testRedirectAnd304Cache(root);
        testClusterAndVerify(a);
        testCommandCenterQueue(a);
        testCommandCenterMediaReporting(root,a);
        testCommandCenterLeaseRecovery(a);
        testPublisherBiasRegistry(root);
        testPoliticalAnalysisQueue(a);
        testAuthoritative(root);
        testMalformed(root);
        testScriptValidation(a);
        testProductionVideoContract(root,a);
        testTtsFallback();
        testWorkerEvent();
        testFilename(root);
        testEncoderNormalize();
        System.out.println("SELF-TEST PASS: "+passed+" checks");
    }

    private void testSourceConfig(Path root)throws Exception{
        List<SourceConfig>s=FeedRegistry.load(root.resolve("config/sources.json"));
        ok(s.size()>=80&&s.stream().allMatch(x->x.url()!=null&&!x.url().isBlank()),"expanded source configuration parsing");
        Set<String>urls=new HashSet<>();
        boolean unique=s.stream().allMatch(x->urls.add(x.url()));
        ok(unique,"expanded source URLs are unique");
    }

    private void ok(boolean v,String n){
        if(!v)throw new AssertionError(n);
        passed++;
        System.out.println("PASS "+n);
    }

    private void testJson(){
        Object x=Json.parse("{\"a\":[1,true,\"x\"]}");
        ok(Json.object(x).containsKey("a"),"JSON parser");
    }

    private List<Article>testRss(Path root)throws Exception{
        List<Article>all=new ArrayList<>();
        Instant now=Instant.parse("2026-09-19T17:00:00Z");
        String[]fs={"source_a.xml","source_b.xml","source_c.xml"};
        for(int i=0;i<fs.length;i++){
            SourceConfig sc=new SourceConfig("Publisher"+(char)('A'+i),"rss","technology","fixture",true,i==2?2:1,false);
            try(InputStream in=Files.newInputStream(root.resolve("tests/fixtures/"+fs[i]))){
                all.addAll(RssSource.parse(in,sc,now));
            }
        }
        ok(all.size()>=5,"RSS fixture parsing");
        return all;
    }

    private void testRedirectAnd304Cache(Path root)throws Exception{
        byte[] feed=Files.readAllBytes(root.resolve("tests/fixtures/source_a.xml"));
        AtomicInteger redirects=new AtomicInteger();
        AtomicInteger notModified=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);

        server.createContext("/redirect",exchange->{
            redirects.incrementAndGet();
            exchange.getResponseHeaders().add("Location","/feed");
            exchange.sendResponseHeaders(302,-1);
            exchange.close();
        });

        server.createContext("/feed",exchange->{
            String validator=exchange.getRequestHeaders().getFirst("If-None-Match");
            if("\"fixture-v1\"".equals(validator)){
                notModified.incrementAndGet();
                exchange.sendResponseHeaders(304,-1);
            }else{
                exchange.getResponseHeaders().add("Content-Type","application/rss+xml");
                exchange.getResponseHeaders().add("ETag","\"fixture-v1\"");
                exchange.sendResponseHeaders(200,feed.length);
                try(OutputStream out=exchange.getResponseBody()){out.write(feed);}
            }
            exchange.close();
        });

        server.start();
        try{
            Path cache=Files.createTempDirectory("autonews-feed-cache-").resolve("feed_cache.json");
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/redirect";
            SourceConfig sc=new SourceConfig("Redirect Fixture","rss","technology",url,true,1,false);
            RssSource source=new RssSource(sc,5,"AutoNewsRoller-SelfTest",cache);
            List<Article>first=source.discover();
            List<Article>second=source.discover();

            ok(!first.isEmpty()
                    &&second.size()==first.size()
                    &&redirects.get()>=2
                    &&notModified.get()>=1,
                    "RSS redirects and 304 cached snapshot reuse");
        }finally{
            server.stop(0);
        }
    }

    private void testClusterAndVerify(List<Article>a){
        List<Article>fresh=a.stream().filter(x->!x.title().contains("Old archive")).toList();
        List<StoryCluster>c=new StoryClusterer().cluster(fresh);
        ok(c.size()>=2,"duplicate clustering separates unrelated story");
        StoryCluster n=c.stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        ok(n.articles.size()>=3,"same event clustered");
        VerificationResult v=new SourceVerifier().verify(n,2);
        ok(v.accepted(),"FactPackage verification");
        ok(v.factPackage().independentSourceCount()>=2,"syndication duplicate does not destroy independent confirmations");
    }

    private void testCommandCenterQueue(List<Article>a)throws Exception{
        List<Article>fresh=a.stream().filter(x->!x.title().contains("Old archive")).toList();
        StoryCluster cluster=new StoryClusterer().cluster(fresh).stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        VerificationResult verified=new SourceVerifier().verify(cluster,2);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,verified.factPackage(),true,verified.reason(),0.91,false);
        NewsPipeline.DiscoveryResult result=new NewsPipeline.DiscoveryResult(List.of(item),3,3,0,fresh.size(),fresh.size(),fresh.size(),fresh.size(),Instant.now());
        Path dir=Files.createTempDirectory("autonews-command-center-");
        CommandCenterStore store=new CommandCenterStore(dir.resolve("state.json"),BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null);
        store.applyDiscovery(result);
        Map<String,Object>snap=store.snapshot();
        Map<String,Object>counts=Json.object(snap.get("counts"));
        ok(((Number)counts.get("queued")).intValue()==1,"command center auto-queues verified worthy story");
        Map<String,Object>job=store.claim("test-worker",Map.of("duration",60,"encoder","x264","useComfy",false));
        NewsPipeline.Candidate rebuilt=NewsPipeline.Candidate.fromMap(Json.object(job.get("candidate")));
        ok(rebuilt.cluster().id.equals(cluster.id)&&rebuilt.factPackage().independentSourceCount()>=2,"remote worker candidate round trip");
        store.fail(cluster.id,"expected test failure");
        store.action(cluster.id,"MAKE");
        ok("QUEUED".equals(store.storyDetail(cluster.id).get("status")),"command center manual MAKE requeues verified story");

        Article single=fresh.get(0);
        StoryCluster unverifiedCluster=new StoryCluster("manual-override-story",single.title(),List.of(single),Set.of(),"manual-override-fingerprint");
        VerificationResult unverified=new SourceVerifier().verify(unverifiedCluster,2);
        NewsPipeline.DiscoveryItem unverifiedItem=new NewsPipeline.DiscoveryItem(unverifiedCluster,unverified.factPackage(),false,unverified.reason(),0.76,false);
        NewsPipeline.DiscoveryResult unverifiedResult=new NewsPipeline.DiscoveryResult(List.of(unverifiedItem),1,1,0,1,1,1,1,Instant.now());
        CommandCenterStore overrideStore=new CommandCenterStore(dir.resolve("override-state.json"),BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null,75,0.74,0.82);
        overrideStore.applyDiscovery(unverifiedResult);
        Map<String,Object>before=overrideStore.storyDetail(unverifiedCluster.id);
        ok("WORTHY".equals(before.get("status"))&&Boolean.TRUE.equals(before.get("worthy"))&&!Boolean.TRUE.equals(before.get("verified")),"high-score one-source story becomes soft worthy");
        overrideStore.action(unverifiedCluster.id,"WORTH");
        Map<String,Object>forced=overrideStore.storyDetail(unverifiedCluster.id);
        ok("QUEUED".equals(forced.get("status"))&&Boolean.TRUE.equals(forced.get("manualVerificationOverride"))&&Boolean.TRUE.equals(forced.get("manualWorth")),"WORTH IT queues one-source story with explicit override");
        Map<String,Object>forcedJob=overrideStore.claim("override-worker",Map.of("duration",60));
        ok(unverifiedCluster.id.equals(forcedJob.get("jobId"))&&Boolean.TRUE.equals(forcedJob.get("manualVerificationOverride")),"worker can claim WORTH IT override job");

        StoryCluster autoCluster=new StoryCluster("soft-auto-story",single.title()+" auto",List.of(single),Set.of(),"soft-auto-fingerprint");
        VerificationResult autoUnverified=new SourceVerifier().verify(autoCluster,2);
        NewsPipeline.DiscoveryItem autoItem=new NewsPipeline.DiscoveryItem(autoCluster,autoUnverified.factPackage(),false,autoUnverified.reason(),0.86,false);
        CommandCenterStore relaxedAuto=new CommandCenterStore(dir.resolve("soft-auto-state.json"),BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null,75,0.74,0.82);
        relaxedAuto.applyDiscovery(new NewsPipeline.DiscoveryResult(List.of(autoItem),1,1,0,1,1,1,1,Instant.now()));
        Map<String,Object>autoQueued=relaxedAuto.storyDetail(autoCluster.id);
        ok("QUEUED".equals(autoQueued.get("status"))&&Boolean.TRUE.equals(autoQueued.get("softVerificationOverride")),"exceptional one-source story can auto-queue under relaxed threshold");
    }

    private void testCommandCenterMediaReporting(Path root,List<Article>a)throws Exception{
        NewsConfig cfg=NewsConfig.load(root);
        ok(cfg.getBool("commandCenterUseComfy",false)&&cfg.getBool("commandCenterRequireComfy",false)&&cfg.getInt("commandCenterComfyImages",0)>=3&&cfg.getBool("comfyAutoPickCheckpoint",false),"command center requires multiple ComfyUI images by default");

        List<Article>fresh=a.stream().filter(x->!x.title().contains("Old archive")).toList();
        StoryCluster cluster=new StoryClusterer().cluster(fresh).stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        VerificationResult verified=new SourceVerifier().verify(cluster,2);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,verified.factPackage(),true,verified.reason(),0.91,false);
        NewsPipeline.DiscoveryResult result=new NewsPipeline.DiscoveryResult(List.of(item),3,3,0,fresh.size(),fresh.size(),fresh.size(),fresh.size(),Instant.now());
        Path dir=Files.createTempDirectory("autonews-command-media-");
        CommandCenterStore store=new CommandCenterStore(dir.resolve("state.json"),BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null);
        store.applyDiscovery(result);
        store.claim("test-worker",Map.of("duration",60));
        store.progress(cluster.id,new WorkerState(1,1,PipelineStage.TTS,"KOKORO USED voice=af_heart",Instant.now()));
        store.progress(cluster.id,new WorkerState(1,1,PipelineStage.VISUALS,"COMFYUI USED checkpoint=test.safetensors image=00.png",Instant.now()));
        Map<String,Object>sidecar=new LinkedHashMap<>();
        sidecar.put("ttsEngineActuallyUsed","Kokoro");sidecar.put("voice","af_heart");sidecar.put("comfyCheckpoint","test.safetensors");
        sidecar.put("imageSources",List.of(Map.of("type","comfyui-generated"),Map.of("type","procedural-card")));
        store.complete(cluster.id,Map.of("sidecar",sidecar));
        Map<String,Object>story=store.storyDetail(cluster.id);
        ok("Kokoro".equals(story.get("ttsEngine"))&&((Number)story.get("comfyImages")).intValue()==1&&"test.safetensors".equals(story.get("comfyCheckpoint")),"command center retains actual TTS and ComfyUI results");
    }

    private void testCommandCenterLeaseRecovery(List<Article>a)throws Exception{
        List<Article>fresh=a.stream().filter(x->!x.title().contains("Old archive")).toList();
        StoryCluster cluster=new StoryClusterer().cluster(fresh).stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        VerificationResult verified=new SourceVerifier().verify(cluster,2);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,verified.factPackage(),true,verified.reason(),0.91,false);
        NewsPipeline.DiscoveryResult result=new NewsPipeline.DiscoveryResult(List.of(item),3,3,0,fresh.size(),fresh.size(),fresh.size(),fresh.size(),Instant.now());
        Path dir=Files.createTempDirectory("autonews-command-lease-");
        Path state=dir.resolve("state.json");
        CommandCenterStore store=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null,75);
        store.applyDiscovery(result);
        store.claim("dead-worker",Map.of("duration",60));
        Map<String,Object>root=Json.object(Json.read(state));
        Map<String,Object>stories=Json.object(root.get("stories"));
        Map<String,Object>story=Json.object(stories.get(cluster.id));
        story.remove("leaseUntil");
        Json.write(state,root);
        CommandCenterStore recovered=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null,75);
        Map<String,Object>after=recovered.storyDetail(cluster.id);
        ok("QUEUED".equals(after.get("status"))&&((Number)after.get("leaseRecoveries")).intValue()>=1,"command center recovers stranded producing job");
    }

    private void testPublisherBiasRegistry(Path root)throws Exception{
        BiasRegistry registry=BiasRegistry.load(root.resolve("config/source_bias.json"));
        Map<String,Object>mix=registry.mix(List.of("BBC News","Fox News","The Guardian"));
        ok(((Number)mix.get("left")).intValue()==1&&((Number)mix.get("center")).intValue()==1&&((Number)mix.get("right")).intValue()==1,"attributed publisher bias buckets load");
        Map<String,Object>details=Json.object(mix.get("publisherDetails"));
        Map<String,Object>fox=Json.object(details.get("Fox News"));
        ok("Right".equals(fox.get("originalClassification"))&&String.valueOf(fox.get("url")).contains("allsides.com"),"publisher bias preserves provider label and citation URL");
    }

    private void testPoliticalAnalysisQueue(List<Article>a)throws Exception{
        Article base=a.stream().filter(x->!x.title().contains("Old archive")).findFirst().orElseThrow();
        Article political=new Article(
                "political-analysis-fixture","Fixture Politics",
                "Senate election bill sparks debate over voting rules",
                base.url(),base.canonicalUrl(),Instant.now(),Instant.now(),"","politics",
                "Lawmakers from both parties debated an election bill and voting policy.", "", "en",1,false
        );
        StoryCluster cluster=new StoryCluster("political-analysis-story",political.title(),List.of(political),Set.of("Senate"),"political-analysis-fingerprint");
        ok(PoliticalFramingAnalyzer.likelyPolitical(cluster),"political framing heuristic identifies political story");

        VerificationResult vr=new SourceVerifier().verify(cluster,2);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,vr.factPackage(),false,vr.reason(),0.55,false);
        NewsPipeline.DiscoveryResult result=new NewsPipeline.DiscoveryResult(List.of(item),1,1,0,1,1,1,1,Instant.now());
        Path dir=Files.createTempDirectory("autonews-bias-queue-");
        CommandCenterStore store=new CommandCenterStore(dir.resolve("state.json"),BiasRegistry.load(dir.resolve("bias.json")),false,0.68,12,null,75,0.74,0.82);
        store.applyDiscovery(result);
        Map<String,Object>story=store.storyDetail(cluster.id);
        ok("QUEUED".equals(story.get("biasAnalysisStatus")),"political story queues framing analysis");

        Map<String,Object>job=store.claim("analysis-worker",Map.of());
        ok("POLITICAL_ANALYSIS".equals(job.get("jobType"))&&cluster.id.equals(job.get("jobId")),"idle worker claims political framing job");

        Map<String,Object>analysis=new LinkedHashMap<>();
        analysis.put("overallClassification","center");analysis.put("overallConfidence",0.72);analysis.put("politicalRelevance",0.96);
        analysis.put("summary","Fixture neutral framing.");analysis.put("articles",List.of());analysis.put("left",0);analysis.put("center",1);analysis.put("right",0);analysis.put("mixed",0);analysis.put("uncertain",0);analysis.put("notPolitical",0);analysis.put("model","fixture");
        store.politicalAnalysisComplete(cluster.id,analysis,"analysis-worker");
        Map<String,Object>done=store.storyDetail(cluster.id);
        ok("COMPLETE".equals(done.get("biasAnalysisStatus"))&&Json.object(done.get("framingAnalysis")).get("overallClassification").equals("center"),"political framing result persists in command center state");
    }

    private void testAuthoritative(Path root)throws Exception{
        try(InputStream in=Files.newInputStream(root.resolve("tests/fixtures/authoritative.xml"))){
            List<Article>a=RssSource.parse(in,new SourceConfig("Official Agency","rss","world","fixture",true,1,true),Instant.now());
            StoryCluster c=new StoryClusterer().cluster(a).get(0);
            VerificationResult v=new SourceVerifier().verify(c,2);
            ok(v.accepted()&&v.factPackage().authoritativePrimaryAccepted(),"authoritative primary-source exception");
        }
    }

    private void testProductionVideoContract(Path root,List<Article>a)throws Exception{
        NewsConfig cfg=NewsConfig.load(root);
        ok(cfg.duration()>=70&&cfg.getInt("commandCenterMinimumTargetSeconds",0)>=70&&cfg.getDouble("minimumFinalVideoSeconds",0)>60,
                "production duration defaults require one-minute-plus output");
        ok(cfg.getInt("commandCenterComfyImages",0)>=6&&cfg.getBool("comfyDisableDynamicVram",false),
                "production defaults request six Comfy images with dynamic VRAM disabled");
        ok(cfg.getInt("ollamaContextTokens",0)>=8192&&cfg.getInt("ollamaMaxOutputTokens",0)>=1600,
                "script Ollama budget reserves enough context and output tokens");

        StoryCluster cluster=new StoryClusterer().cluster(a.stream().filter(x->!x.title().contains("Old archive")).toList())
                .stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        FactPackage fp=new SourceVerifier().verify(cluster,2).factPackage();
        List<NewsScript.Segment>segments=new ArrayList<>();
        for(int i=0;i<8;i++)segments.add(new NewsScript.Segment(i,"Verified narration beat "+(i+1),"detail","BACKGROUND","Documentary visual grounded in the supplied facts",8));
        NewsScript visualScript=new NewsScript(fp.storyId(),fp.headline(),"Verified narration for visual planning.",segments,70,List.of());
        VisualPlan plan=new VisualPlanner().plan(visualScript,fp);
        ok(plan.items().size()>=9&&plan.items().size()<=10,"visual planner creates headline, 7-8 story beats, and source card");

        NewsScript shortScript=new NewsScript(fp.storyId(),fp.headline(),"This narration is deliberately too short.",segments,3,List.of());
        ok(new ScriptValidator().validate(shortScript,fp,70).stream().anyMatch(x->x.startsWith("narration too short")),
                "script validator rejects short narration for production target");

        Map<String,Object>modelJson=new LinkedHashMap<>();
        modelJson.put("headline",fp.headline());
        modelJson.put("narration","too short and intentionally ignored");
        List<Map<String,Object>>modelSegments=new ArrayList<>();
        String segmentNarration="This verified segment restates supplied reporting in complete neutral sentences while preserving only facts already present in the verified package for narration.";
        for(int i=0;i<8;i++)modelSegments.add(Map.of(
                "narration",segmentNarration,
                "purpose","verified detail",
                "visualType","BACKGROUND",
                "visualPrompt","Documentary visual grounded in verified source material"
        ));
        modelJson.put("segments",modelSegments);
        NewsScript assembled=NewsScriptGenerator.assembleFromModelJson(Json.stringify(modelJson),fp,70);
        Set<String>allowedPublishers=new HashSet<>();
        for(Map<String,Object>source:fp.sources())allowedPublishers.add(String.valueOf(source.get("publisher")));
        ok(assembled.segments().size()==8&&Text.words(assembled.narration())>=158&&
                        assembled.sourceLabels().stream().allMatch(allowedPublishers::contains),
                "script assembly uses combined segment narration and trusted publisher labels");

        List<NewsScript.Segment>retrySegments=new ArrayList<>();
        String retryLine="Verified reporting describes the event using facts already present in the supplied package while keeping the narration neutral and factual.";
        for(int i=0;i<8;i++)retrySegments.add(new NewsScript.Segment(i,retryLine,"detail","BACKGROUND","Verified documentary visual",8));
        String retryNarration=retrySegments.stream().map(NewsScript.Segment::narration).reduce("",(x,y)->x.isBlank()?y:x+" "+y);
        NewsScript retryBase=new NewsScript(fp.storyId(),fp.headline(),retryNarration,retrySegments,58,List.copyOf(allowedPublishers));
        int beforeRepair=Text.words(retryBase.narration());

        Map<String,Object>expansionJson=new LinkedHashMap<>();
        expansionJson.put("additions",List.of(
                Map.of("segmentIndex",0,"text","Additional verified context from the supplied reporting extends this beat without introducing any unsupported factual claim."),
                Map.of("segmentIndex",1,"text","The available verified material also supports this added neutral context while staying within the same documented facts.")
        ));
        NewsScript expanded=NewsScriptGenerator.applyExpansions(retryBase,Json.stringify(expansionJson),fp,70);
        ok(Text.words(expanded.narration())>beforeRepair&&expanded.segments().size()==8,
                "short-script repair appends targeted continuation text instead of regenerating the same draft");

        Path ass=Files.createTempFile("autonews-captions-",".ass");
        CaptionWriter.write(ass,
                "This caption contains enough words to split cleanly across two compact lines for a vertical news video.",
                7.0,"sentence");
        String captionText=Files.readString(ass);
        ok(captionText.contains("[V4+ Styles]")&&captionText.contains("Style: News,Arial,42")&&captionText.contains("\\N")&&captionText.contains("FFD86F"),
                "captions use compact two-line ASS styling with restrained accent");

        String comfySource=Files.readString(root.resolve("src/autonewsroller/visuals/ComfyImageGenerator.java"));
        int recovery=comfySource.indexOf("public void recoverFromOom()");
        int generate=comfySource.indexOf("public Path generate(");
        ok(generate>=0&&recovery>generate&&!comfySource.substring(generate,recovery).contains("/free")&&comfySource.substring(recovery).contains("/free"),
                "ComfyUI success path keeps models warm and only OOM recovery frees them");
    }

    private void testTtsFallback()throws Exception{
        Path d=Files.createTempDirectory("autonews-tts-");
        autonewsroller.tts.NarrationEngine bad=new autonewsroller.tts.NarrationEngine(){
            public String name(){return "bad";}
            public autonewsroller.tts.NarrationResult narrate(String t,String v,Path o)throws Exception{throw new IOException("expected primary failure");}
        };
        autonewsroller.tts.NarrationEngine good=new autonewsroller.tts.NarrationEngine(){
            public String name(){return "fallback";}
            public autonewsroller.tts.NarrationResult narrate(String t,String v,Path o)throws Exception{
                Files.write(o,new byte[]{1,2,3,4});
                return new autonewsroller.tts.NarrationResult("fallback",v,o,o.resolveSibling("meta"));
            }
        };
        var r=new autonewsroller.tts.FallbackNarrator(bad,good).narrate("hello","voice",d.resolve("x.wav"));
        ok(r.engine().equals("fallback"),"TTS fallback state logic");
    }

    private void testMalformed(Path root)throws Exception{
        boolean failed=false;
        try(InputStream in=Files.newInputStream(root.resolve("tests/fixtures/malformed.xml"))){
            RssSource.parse(in,new SourceConfig("bad","rss","general","x",true,3,false),Instant.now());
        }catch(Exception e){failed=true;}
        ok(failed,"malformed feed rejected without process exit");
    }

    private void testScriptValidation(List<Article>a){
        StoryCluster c=new StoryClusterer().cluster(a.stream().filter(x->x.title().contains("Nvidia")||x.title().contains("AI accelerator")).toList()).get(0);
        FactPackage fp=new SourceVerifier().verify(c,2).factPackage();
        NewsScript s=NewsScriptGenerator.deterministic(fp,60);
        ok(!s.narration().isBlank(),"dry-run script generation");
        ok(new ScriptValidator().validate(s,fp,60).stream().noneMatch(x->x.startsWith("unsupported number")),"script JSON/fact validation");
    }

    private void testWorkerEvent(){
        WorkerState s=new WorkerState(1,2,PipelineStage.VERIFY,"2 sources",Instant.now());
        ok(Json.object(Json.parse(s.json())).get("stage").equals("VERIFY"),"dashboard event parsing");
    }

    private void testFilename(Path root)throws Exception{
        Path d=Files.createTempDirectory("autonews-name-");
        Files.writeString(d.resolve("hello_world.mp4"),"x");
        ok(FileNames.unique(d,"Hello World",".mp4").getFileName().toString().equals("hello_world_2.mp4"),"output filename collision handling");
    }

    private void testEncoderNormalize(){
        ok(VideoEncoderProbe.normalize("auto").equals("auto")&&VideoEncoderProbe.normalize("cpu").equals("x264"),"NVENC probe mode logic");
    }
}
