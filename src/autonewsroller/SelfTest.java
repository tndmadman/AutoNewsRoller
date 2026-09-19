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
        testAuthoritative(root);
        testMalformed(root);
        testScriptValidation(a);
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
        store.applyDiscovery(unverifiedResult);
        Map<String,Object>before=store.storyDetail(unverifiedCluster.id);
        ok("DISCOVERED".equals(before.get("status"))&&!Boolean.TRUE.equals(before.get("verified")),"unverified story remains discovered under automatic rules");
        store.action(unverifiedCluster.id,"MAKE");
        Map<String,Object>forced=store.storyDetail(unverifiedCluster.id);
        ok("QUEUED".equals(forced.get("status"))&&Boolean.TRUE.equals(forced.get("manualVerificationOverride")),"manual MAKE force-queues unverified story");
        Map<String,Object>forcedJob=store.claim("override-worker",Map.of("duration",60));
        ok(unverifiedCluster.id.equals(forcedJob.get("jobId"))&&Boolean.TRUE.equals(forcedJob.get("manualVerificationOverride")),"worker can claim manual verification override job");
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

    private void testAuthoritative(Path root)throws Exception{
        try(InputStream in=Files.newInputStream(root.resolve("tests/fixtures/authoritative.xml"))){
            List<Article>a=RssSource.parse(in,new SourceConfig("Official Agency","rss","world","fixture",true,1,true),Instant.now());
            StoryCluster c=new StoryClusterer().cluster(a).get(0);
            VerificationResult v=new SourceVerifier().verify(c,2);
            ok(v.accepted()&&v.factPackage().authoritativePrimaryAccepted(),"authoritative primary-source exception");
        }
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
