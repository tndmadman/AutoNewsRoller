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
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
        testFeedTransportResilience(root);
        testClusterAndVerify(a);
        testCommandCenterQueue(a);
        testStoryBoardPagination();
        testStoryBoardApi(root);
        testCommandCenterMediaReporting(root,a);
        testCommandCenterLeaseRecovery(a);
        testPublisherBiasRegistry(root);
        testPoliticalAnalysisQueue(a);
        testEnrichedFramingSnapshot();
        testSelectedArticleEnrichment();
        testPoliticalExposureMode();
        testPairedSequentialVideoProduction();
        testMemePipeline();
        testPoliticalExposureLengthRecovery();
        testPoliticalVisualGraphics(root,a);
        testAuthoritative(root);
        testMalformed(root);
        testScriptValidation(a);
        testProductionVideoContract(root,a);
        testVisualV5();
        testComfyMock();
        testWorthyHourFilterContract(root);
        testFeedCardIdentityContract(root);
        testTtsFallback();
        testWorkerEvent();
        testFilename(root);
        testEncoderNormalize();
        System.out.println("SELF-TEST PASS: "+passed+" checks");
    }


    private void testMemePipeline()throws Exception{
        Instant now=Instant.now();
        Article article=new Article("meme-a","Fixture News",
                "City buys novelty traffic signs",
                "https://fixture.example/meme","https://fixture.example/meme",now,now,"","local",
                "The city purchased novelty signs.",
                "The city purchased novelty signs for the downtown plaza.","en",1,false);
        StoryCluster cluster=new StoryCluster("meme-story",article.title(),List.of(article),
                Set.of("City"),"meme-fingerprint");
        FactClaim claim=new FactClaim(article.title(),List.of(article.url()),.75,false);
        FactPackage fp=new FactPackage(cluster.id,article.title(),article.title(),
                List.of(claim),List.of(),List.of(Map.of("publisher",article.publisher())),1,1,.6,false);
        var evidence=new MemeEvidenceBuilder().build(cluster,fp);
        ok(!evidence.fingerprint().isBlank()&&evidence.factIds().contains("F1")
                &&!evidence.sourceText().isBlank(),
                "meme article evidence retains facts, URLs and fingerprints");
        NewsScript script=new MemeScriptGenerator(null,1).generate(fp,evidence,true);
        ok(script.segments().size()==2
                &&"MEME_RAGEBAIT".equals(script.hook().get("type"))
                &&"PAYOFF".equals(script.segments().get(script.segments().size()-1).purpose())
                &&script.estimatedDuration()<61,
                "independent meme generator admits short variable-beat output");
        new MemeScriptValidator().validate(script,evidence);
        ok(true,"short meme narration passes mode-specific evidence validation");
        VisualPlan plan=new MemeVisualPlanner().plan(script);
        ok(plan.items().size()==script.segments().size()
                &&"MEME_HOOK".equals(plan.items().get(0).type())
                &&plan.items().get(0).title().equals(script.hook().get("hookText")),
                "meme visual plan opens on its caption with no news graphics");
        Path dir=Files.createTempDirectory("autonews-meme-fixture-");
        Path frame=dir.resolve("opening.png");
        new MemeCardRenderer().render(plan.items().get(0),frame,360,640);
        BufferedImage opening=ImageIO.read(frame.toFile());
        ok(opening!=null&&opening.getWidth()==360&&opening.getHeight()==640,
                "meme opening frame renders with the vertical safe footer");
        try{
            NewsScript invalid=new NewsScript(script.storyId(),script.headline(),
                    "This report says the city bought 9999999 signs.",
                    script.segments(),8,script.sourceLabels(),script.hook());
            new MemeScriptValidator().validate(invalid,evidence);
            throw new AssertionError("Unsupported numerical meme claim accepted");
        }catch(IllegalArgumentException expected){
            ok(expected.getMessage().contains("Unsupported number"),
                    "meme validator rejects unsupported numerals");
        }
        Path state=dir.resolve("state.json");
        var discovery=new NewsPipeline.DiscoveryResult(
                List.of(new NewsPipeline.DiscoveryItem(cluster,fp,false,
                        "single attributed fixture",.9,false)),
                1,1,0,1,1,1,1,now);
        CommandCenterStore store=new CommandCenterStore(state,
                BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
        store.applyDiscovery(discovery);
        store.action(cluster.id,"MAKE","standard-first");
        store.action(cluster.id,"MAKE_MEME","meme-after-normal");
        store.action(cluster.id,"MAKE_MEME","meme-after-normal");
        ok(Boolean.TRUE.equals(store.storyDetail(cluster.id).get("pendingMemeRequest"))
                &&"STANDARD".equals(store.storyDetail(cluster.id).get("productionMode")),
                "meme requested during queued standard production waits without overwriting");
        var board=store.boardStories(1,25,"ALL","",0);
        ok(((Number)board.get("total")).intValue()==2
                &&((List<?>)board.get("stories")).stream().anyMatch(x->x instanceof Map<?,?>m
                        &&"meme-story::MEME_RAGEBAIT".equals(m.get("boardEntryId"))
                        &&"WAITING".equals(m.get("status"))),
                "meme has an independent waiting board card");
        CommandCenterStore resumed=new CommandCenterStore(state,
                BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
        resumed.applyDiscovery(discovery);
        ok(Boolean.TRUE.equals(resumed.storyDetail(cluster.id).get("pendingMemeRequest")),
                "meme request survives restart and RSS rescan");
        resumed.heartbeat("legacy",Map.of("id","legacy","capabilities",List.of()));
        Map<String,Object>normal=resumed.claim("legacy",Map.of());
        ok(normal!=null&&"STANDARD".equals(normal.get("productionMode")),
                "original news production claims before pending meme");
        resumed.complete(cluster.id,Map.of("workerId","legacy","claimId",normal.get("claimId"),
                "sidecar",Map.of("productionMode","STANDARD")));
        ok("MEME_RAGEBAIT".equals(resumed.storyDetail(cluster.id).get("productionMode"))
                &&"QUEUED".equals(resumed.storyDetail(cluster.id).get("status")),
                "meme queues immediately after standard completion");
        ok(resumed.claim("legacy",Map.of())==null,
                "worker without meme capability cannot claim queued meme");
        resumed.heartbeat("meme-worker",Map.of("id","meme-worker",
                "capabilities",List.of("meme_ragebait_v1")));
        Map<String,Object>job=resumed.claim("meme-worker",Map.of("duration",12));
        ok(job!=null&&"MEME_RAGEBAIT".equals(job.get("productionMode")),
                "meme-capable worker claims independent production mode");
        try{
            resumed.complete(cluster.id,Map.of("workerId","meme-worker",
                    "claimId",job.get("claimId")));
            throw new AssertionError("Meme without sidecar accepted");
        }catch(IllegalStateException expected){
            ok(expected.getMessage().contains("sidecar"),
                    "meme completion requires a provenance sidecar");
        }
        Map<String,Object>sidecar=new LinkedHashMap<>();
        sidecar.put("productionMode","MEME_RAGEBAIT");
        sidecar.put("comedyAngle","Absurd signs");
        sidecar.put("imageSources",List.of(Map.of("type","comfyui-generated-ai-illustration")));
        resumed.complete(cluster.id,Map.of("workerId","meme-worker","claimId",job.get("claimId"),
                "sidecar",sidecar));
        Map<String,Object>snapshot=resumed.snapshot();
        List<?> archive=(List<?>)snapshot.get("videos");
        ok(archive.size()==2&&archive.stream().anyMatch(v->v instanceof Map<?,?>m
                    &&"MEME_RAGEBAIT".equals(m.get("productionMode")))
                &&archive.stream().anyMatch(v->v instanceof Map<?,?>m
                    &&"STANDARD".equals(m.get("productionMode"))),
                "original and meme coexist as independently archived entries");
        ok(((Number)resumed.storyDetail(cluster.id).get("comfyImages")).intValue()==1,
                "real ComfyUI illustration provenance contributes to generated count");
        Map<String,Object>memeRow=Json.object(((List<?>)resumed.boardStories(1,25,
                "ALL","meme roast",0).get("stories")).get(0));
        ok("COMPLETE".equals(memeRow.get("status"))
                &&Boolean.TRUE.equals(memeRow.get("entryMayRemake")),
                "completed meme is separately searchable and remakable");
        resumed.action(cluster.id,"REMAKE_MEME","remake-once");
        ok("QUEUED".equals(resumed.storyDetail(cluster.id).get("status"))
                &&"MEME_RAGEBAIT".equals(resumed.storyDetail(cluster.id).get("productionMode")),
                "REMAKE_MEME queues only meme mode and preserves archive");
    }

    private void testSourceConfig(Path root)throws Exception{
        List<SourceConfig>s=FeedRegistry.load(root.resolve("config/sources.json"));
        ok(s.size()>=167&&s.stream().allMatch(x->x.url()!=null&&!x.url().isBlank()),"expanded source configuration parsing");
        ok(s.stream().filter(SourceConfig::enabled).count()>=167,"expanded sources enabled");
        ok(s.stream().map(SourceConfig::name).distinct().count()>=94,"publisher diversity maintained");
        ok(s.stream().anyMatch(x->x.category().equals("gaming"))&&s.stream().anyMatch(x->x.category().equals("local")),"gaming and local RSS source coverage");
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


    private void testFeedTransportResilience(Path root)throws Exception{
        byte[] good=Files.readAllBytes(root.resolve("tests/fixtures/source_a.xml"));
        java.util.concurrent.atomic.AtomicInteger hits=new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server=com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/retry",exchange->{
            int hit=hits.incrementAndGet();
            if(hit==1){
                exchange.sendResponseHeaders(503,-1);
            }else{
                exchange.getResponseHeaders().set("Content-Type","application/rss+xml");
                exchange.sendResponseHeaders(200,good.length);
                try(OutputStream out=exchange.getResponseBody()){out.write(good);}
            }
            exchange.close();
        });
        server.start();
        try{
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/retry";
            var config=new SourceConfig("Transient Fixture","rss","world",url,true,2,false);
            RssSource fetch=new RssSource(config,5,2,"AutoNewsRoller-SelfTest",root.resolve("output/test-feed-resilience.json"));
            ok(!fetch.discover().isEmpty()&&hits.get()==2,
                    "feed HTTP retry switches transport version while retaining XML parsing");
        }finally{server.stop(0);}
        String prefix="<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>News ";
        String suffix=" update</title><link>https://example.org/article</link></item></channel></rss>";
        byte[] left=prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right=suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] invalid=new byte[left.length+1+right.length];
        System.arraycopy(left,0,invalid,0,left.length);
        invalid[left.length]=(byte)0x92;
        System.arraycopy(right,0,invalid,left.length+1,right.length);
        var brokenConfig=new SourceConfig("Legacy Publisher","rss","general","fixture",true,2,false);
        java.util.List<Article> recovered=RssSource.parse(new java.io.ByteArrayInputStream(invalid),brokenConfig,Instant.now());
        ok(recovered.size()==1&&recovered.get(0).title().contains("News")&&
                recovered.get(0).title().contains("update"),
                "invalid legacy byte in declared UTF-8 feed is replaced without losing valid news entries");
        var current=FeedRegistry.load(root.resolve("config/sources.json"));
        ok(current.size()==167&&current.stream().map(SourceConfig::url).distinct().count()==167&&
                current.stream().anyMatch(x->x.url().equals("https://www.tomshardware.com/feeds.xml"))&&
                current.stream().anyMatch(x->x.url().equals("https://www.space.com/feeds.xml")),
                "RSS source pack retains 167 unique entries and uses canonical Future feeds");
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



    private void testStoryBoardApi(Path root)throws Exception{
        Path dir=Files.createTempDirectory("autonews-board-api-");
        NewsConfig cfg=NewsConfig.load(root);
        CommandCenterServer server=new CommandCenterServer(dir,cfg,"127.0.0.1",0,60,false,.75,10,"private-token",false);
        int port=server.start();
        try{
            HttpClient client=HttpClient.newHttpClient();
            String base="http://127.0.0.1:"+port;
            HttpRequest unauthenticated=HttpRequest.newBuilder(URI.create(base+"/api/board/stories")).GET().build();
            ok(client.send(unauthenticated,HttpResponse.BodyHandlers.ofString()).statusCode()==401,
                    "board API enforces configured token authentication");
            HttpRequest board=HttpRequest.newBuilder(URI.create(base+"/api/board/stories?page=999&pageSize=25"))
                    .header("X-AutoNews-Token","private-token").GET().build();
            HttpResponse<String> response=client.send(board,HttpResponse.BodyHandlers.ofString());
            Map<String,Object>payload=Json.object(Json.parse(response.body()));
            ok(response.statusCode()==200&&((Number)payload.get("page")).intValue()==1
                    &&((Number)payload.get("totalPages")).intValue()==1
                    &&((List<?>)payload.get("stories")).isEmpty(),
                    "authenticated endpoint clamps empty collections");
            HttpRequest invalid=HttpRequest.newBuilder(URI.create(base+"/api/board/stories?page=999999999999999&pageSize=500"))
                    .header("X-AutoNews-Token","private-token").GET().build();
            ok(client.send(invalid,HttpResponse.BodyHandlers.ofString()).statusCode()==400,
                    "malformed and excessive board query values return HTTP 400");
            HttpRequest lightweight=HttpRequest.newBuilder(URI.create(base+"/api/state?includeStories=false"))
                    .header("X-AutoNews-Token","private-token").GET().build();
            HttpResponse<String> stateResponse=client.send(lightweight,HttpResponse.BodyHandlers.ofString());
            Map<String,Object>state=Json.object(Json.parse(stateResponse.body()));
            ok(stateResponse.statusCode()==200&&state.containsKey("counts")
                    &&((List<?>)state.get("stories")).isEmpty(),
                    "lightweight state keeps dashboard shape and empty story array");
        }finally{server.stop();}
    }

    private void testStoryBoardPagination()throws Exception{
        Path dir=Files.createTempDirectory("autonews-board-pagination-");
        Path state=dir.resolve("board.json");
        Map<String,Object>records=new LinkedHashMap<>();
        Instant now=Instant.now();
        for(int i=0;i<3000;i++){
            String id=String.format(Locale.ROOT,"synthetic-%04d",i);
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("id",id);m.put("topic","Synthetic story "+i);
            m.put("score",0.9);  // Deliberate tie to test deterministic paging.
            m.put("status",i%5==0?"QUEUED":i%5==1?"FAILED":i%5==2?"COMPLETE":i%5==3?"SKIPPED":"VERIFIED");
            m.put("worthy",i%5==4);m.put("verified",i%5==4);
            m.put("latestPublishedAt",now.minusSeconds(i%2==0?1200:96*3600).toString());
            m.put("publishers",List.of("Wire "+i));
            m.put("uploaded",i%5==2&&i%2==0);
            m.put("scrapped",false);
            m.put("candidate",Map.of("oversized-private-object",List.of("not-on-the-wire")));
            records.put(id,m);
        }
        for(int size:new int[]{0,1,25,26,100,1000}){
            Map<String,Object>small=new LinkedHashMap<>();
            for(var e:records.entrySet()){
                if(small.size()==size)break;
                small.put(e.getKey(),e.getValue());
            }
            Path smallState=dir.resolve("mini-"+size+".json");
            Json.write(smallState,Map.of("stories",small));
            CommandCenterStore smallStore=new CommandCenterStore(smallState,BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
            Map<String,Object>smallPage=smallStore.boardStories(1,25,"ALL","",0);
            int expectedPages=Math.max(1,(size+24)/25);
            ok(((Number)smallPage.get("total")).intValue()==size
                    &&((Number)smallPage.get("totalPages")).intValue()==expectedPages
                    &&((List<?>)smallPage.get("stories")).size()==Math.min(25,size),
                    "pagination boundaries for "+size+" stories");
        }
        Map<String,Object>archive=new LinkedHashMap<>();
        for(Object rawRecord:records.values()){
            Map<String,Object>item=Json.object(rawRecord);
            if(!"COMPLETE".equals(item.get("status")))continue;
            String storyId=String.valueOf(item.get("id"));
            Map<String,Object>video=new LinkedHashMap<>();
            video.put("storyId",storyId);video.put("jobId",storyId);
            video.put("versionId",storyId+"#v1");video.put("version",1);
            video.put("topic",item.get("topic"));video.put("productionMode","STANDARD");
            video.put("uploaded",Boolean.TRUE.equals(item.get("uploaded")));
            video.put("scrapped",false);
            archive.put(storyId+"#v1",video);
        }
        Json.write(state,Map.of("stories",records,"feeds",Map.of(),"videos",archive));
        CommandCenterStore store=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
        Map<String,Object>tiny=store.snapshot(false);
        ok(((List<?>)tiny.get("stories")).isEmpty()
                &&((Number)Json.object(tiny.get("counts")).get("stories")).intValue()==3000,
                "lightweight snapshot retains global metrics without constructing stories");
        ok(((List<?>)store.snapshot().get("stories")).size()==3000,
                "full snapshot remains backward-compatible");
        Map<String,Object>first=store.boardStories(1,25,"ALL","",0);
        Map<String,Object>second=store.boardStories(2,25,"ALL","",0);
        Map<String,Object>last=store.boardStories(120,25,"ALL","",0);
        ok(((List<?>)first.get("stories")).size()==25&&((Number)first.get("total")).intValue()==3000
                &&((Number)last.get("page")).intValue()==120
                &&((List<?>)last.get("stories")).size()==25,
                "3,000 story board returns just 25 records and resolves last page");
        List<?>firstRecords=(List<?>)first.get("stories"), secondRecords=(List<?>)second.get("stories");
        ok("synthetic-0000".equals(Json.object(firstRecords.get(0)).get("id"))
                &&"synthetic-0025".equals(Json.object(secondRecords.get(0)).get("id"))
                &&!Json.object(firstRecords.get(0)).containsKey("candidate"),
                "stable score ties, no duplicates across page boundaries, public-only page");
        ok(((Number)store.boardStories(Integer.MAX_VALUE,100,"ALL","",0).get("page")).intValue()==30,
                "extreme requested page clamps without integer overflow");
        ok(((Number)store.boardStories(1,10,"ALL","Synthetic story 2999",0).get("total")).intValue()==1,
                "search reaches story 2,999 beyond the first page");
        Map<String,Object>assigned=new LinkedHashMap<>(Json.object(records.get("synthetic-2999")));
        assigned.put("assignedWorker","test-active-worker");
        Path assignedState=dir.resolve("assigned-search.json");
        Json.write(assignedState,Map.of("stories",Map.of("synthetic-2999",assigned)));
        CommandCenterStore assignedStore=new CommandCenterStore(assignedState,BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
        ok(((Number)assignedStore.boardStories(1,25,"ALL","test-active-worker",0).get("total")).intValue()==1,
                "active assigned worker is globally searchable");
        ok(((Number)store.boardStories(1,25,"QUEUED","",1).get("total")).intValue()==600
                &&((Number)store.boardStories(1,25,"FAILED","",1).get("total")).intValue()==300,
                "age filtering exempts queued operations but limits failed stories");
        ok(((Number)store.boardStories(1,25,"TO_POST","",0).get("total")).intValue()==300
                &&((Number)store.boardStories(1,25,"UPLOADED","",0).get("total")).intValue()==300,
                "story-level upload/to-post filters distinguish video archive metrics");
        ok(((Number)store.boardStories(1,25,"WORTHY","",0).get("total")).intValue()==600
                &&((Number)store.boardStories(1,25,"COMPLETE","",0).get("total")).intValue()==600,
                "worthy and complete special filters match the story board");
        Map<String,Integer>expected=Map.ofEntries(
                Map.entry("ALL",3000),Map.entry("DISCOVERED",0),Map.entry("WORTHY",600),
                Map.entry("VERIFIED",600),Map.entry("QUEUED",600),Map.entry("PRODUCING",0),
                Map.entry("HOLD",0),Map.entry("FAILED",600),Map.entry("COMPLETE",600),
                Map.entry("TO_POST",300),Map.entry("UPLOADED",300),Map.entry("SCRAPPED",0),
                Map.entry("SKIPPED",600));
        boolean filtersCorrect=true;
        for(var entry:expected.entrySet()){
            Map<String,Object>filtered=store.boardStories(1,10,entry.getKey(),"",0);
            filtersCorrect&=((Number)filtered.get("total")).intValue()==entry.getValue()
                    &&((Number)Json.object(filtered.get("facets")).get(entry.getKey())).intValue()==entry.getValue();
        }
        ok(filtersCorrect,"all supported status filters and full-pool facet totals");
        Map<String,Object>oldHold=new LinkedHashMap<>(Json.object(records.get("synthetic-0001")));
        oldHold.put("id","old-hold");oldHold.put("status","HOLD");
        Path holdState=dir.resolve("hold.json");
        Json.write(holdState,Map.of("stories",Map.of("old-hold",oldHold)));
        CommandCenterStore holdStore=new CommandCenterStore(holdState,BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null);
        ok(((Number)holdStore.boardStories(1,25,"HOLD","",1).get("total")).intValue()==1
                &&((Number)holdStore.boardStories(1,25,"ALL","",1).get("total")).intValue()==0,
                "old HOLD remains accessible under its operational filter but not age-filtered ALL");

        Map<String,Object>none=store.boardStories(60,25,"ALL","does not exist",0);
        ok(((Number)none.get("page")).intValue()==1&&((Number)none.get("totalPages")).intValue()==1
                &&((List<?>)none.get("stories")).isEmpty(),
                "empty result resolves to the first page");
        boolean rejected=false;
        try{store.boardStories(1,999,"ALL","",0);}catch(IllegalArgumentException e){rejected=true;}
        ok(rejected,"invalid server-side page size rejected");
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
        Map<String,Object>activeBefore=store.storyDetail(cluster.id);
        store.action(cluster.id,"MAKE","repeated-making");
        store.action(cluster.id,"WORTH","repeated-worthy");
        Map<String,Object>activeAfter=store.storyDetail(cluster.id);
        ok("PRODUCING".equals(activeAfter.get("status"))
            &&Objects.equals(activeBefore.get("leaseUntil"),activeAfter.get("leaseUntil"))
            &&Objects.equals(activeBefore.get("assignedWorker"),activeAfter.get("assignedWorker"))
            &&Objects.equals(activeBefore.get("claimCount"),activeAfter.get("claimCount")),
            "duplicate MAKE/WORTH cannot requeue or replace an active worker lease");
        boolean rejectedHold=false;
        try{store.action(cluster.id,"HOLD");}catch(IllegalStateException expected){rejectedHold=true;}
        ok(rejectedHold&&"PRODUCING".equals(store.storyDetail(cluster.id).get("status")),
            "manual HOLD cannot silently cancel a producing job");
        store.progress(cluster.id,new WorkerState(1,1,PipelineStage.TTS,"stale worker",Instant.now()),"other-worker");
        store.progress(cluster.id,new WorkerState(1,1,PipelineStage.TTS,"stale claim",Instant.now()),"test-worker","old-claim-token");
        ok(Objects.equals(activeAfter.get("stage"),store.storyDetail(cluster.id).get("stage")),
            "progress from another worker or an old claim cannot change the active job");
        boolean rejectedStaleComplete=false;
        try{store.complete(cluster.id,Map.of("workerId","test-worker","claimId","old-claim-token"));}
        catch(IllegalStateException expected){rejectedStaleComplete=true;}
        ok(rejectedStaleComplete&&"PRODUCING".equals(store.storyDetail(cluster.id).get("status")),
            "late completion from an earlier claim cannot create a duplicate video version");
        store.fail(cluster.id,"expected test failure");
        Map<String,Object>failedRequeued=store.storyDetail(cluster.id);
        ok("QUEUED".equals(failedRequeued.get("status"))&&((Number)failedRequeued.get("failureCount")).intValue()==1&&failedRequeued.get("retryNotBefore")!=null,
                "failed video job automatically returns to queue with retry metadata");
        store.action(cluster.id,"MAKE");
        Map<String,Object>manualRequeued=store.storyDetail(cluster.id);
        ok("QUEUED".equals(manualRequeued.get("status"))
                &&Objects.equals(failedRequeued.get("failureCount"),manualRequeued.get("failureCount"))
                &&Objects.equals(failedRequeued.get("retryNotBefore"),manualRequeued.get("retryNotBefore"))
                &&Objects.equals(failedRequeued.get("queuedAt"),manualRequeued.get("queuedAt")),
                "duplicate MAKE preserves queued retry metadata, timestamp, and retry budget");

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

        CommandCenterStore retryCapStore=new CommandCenterStore(
                dir.resolve("retry-cap-state.json"),BiasRegistry.load(dir.resolve("bias.json")),
                true,0.68,12,null,75,0.74,0.82,1,0
        );
        retryCapStore.applyDiscovery(result);
        ok(retryCapStore.claim("retry-worker-1",Map.of("duration",60))!=null,"retry-cap fixture job can be claimed");
        retryCapStore.fail(cluster.id,"first production failure");
        ok("QUEUED".equals(retryCapStore.storyDetail(cluster.id).get("status")),"first production failure is automatically requeued");
        ok(retryCapStore.claim("retry-worker-2",Map.of("duration",60))!=null,"automatically requeued failure can be claimed again");
        retryCapStore.fail(cluster.id,"second production failure");
        Map<String,Object>terminalFailure=retryCapStore.storyDetail(cluster.id);
        ok("FAILED".equals(terminalFailure.get("status"))&&((Number)terminalFailure.get("failureCount")).intValue()==2&&
                        terminalFailure.get("productionAttempts") instanceof List<?> attempts&&attempts.size()>=4,
                "job becomes terminal FAILED only after configured automatic retry budget is exhausted and retains attempt history");

        Path leaseState=dir.resolve("lease-cap-state.json");
        CommandCenterStore leaseStore=new CommandCenterStore(
                leaseState,BiasRegistry.load(dir.resolve("bias.json")),
                true,0.68,12,null,30,0.74,0.82,3,0,1
        );
        leaseStore.applyDiscovery(result);
        ok(leaseStore.claim("lease-worker-1",Map.of("duration",60))!=null,"lease-cap fixture job can be claimed");
        Map<String,Object>leaseRoot=Json.object(Json.read(leaseState));
        Map<String,Object>leaseStories=Json.object(leaseRoot.get("stories"));
        Map<String,Object>leaseStory=Json.object(leaseStories.get(cluster.id));
        leaseStory.put("leaseUntil",Instant.EPOCH.toString());
        leaseStory.put("leaseRecoveries",1);
        leaseStories.put(cluster.id,leaseStory);
        leaseRoot.put("stories",leaseStories);
        Json.write(leaseState,leaseRoot);
        CommandCenterStore recoveredLeaseStore=new CommandCenterStore(
                leaseState,BiasRegistry.load(dir.resolve("bias.json")),
                true,0.68,12,null,30,0.74,0.82,3,0,1
        );
        Map<String,Object>leaseTerminal=recoveredLeaseStore.storyDetail(cluster.id);
        ok("FAILED".equals(leaseTerminal.get("status"))&&"WORKER_LEASE_EXHAUSTED".equals(leaseTerminal.get("failureType"))&&
                        ((Number)leaseTerminal.get("leaseRecoveries")).intValue()==2,
                "worker lease recovery is separately bounded and becomes terminal FAILED after its safety budget is exhausted");
    }

    private void testCommandCenterMediaReporting(Path root,List<Article>a)throws Exception{
        NewsConfig cfg=NewsConfig.load(root);
        ok(cfg.getBool("commandCenterUseComfy",false)&&cfg.getBool("commandCenterRequireComfy",false)&&cfg.getInt("commandCenterComfyImages",0)>=3&&cfg.getBool("comfyAutoPickCheckpoint",false),"command center requires multiple ComfyUI images by default");
        ok(cfg.getInt("commandCenterFailureMaxRetries",0)>=1&&cfg.getInt("commandCenterFailureRetryDelaySeconds",-1)>=0&&
                        cfg.getInt("commandCenterLeaseRecoveryMaxRetries",0)>=1&&cfg.getInt("commandCenterJobLeaseSeconds",0)>=120,
                "command center retains bounded production retries and bounded worker lease recovery with a safer lease window");

        List<Article>fresh=a.stream().filter(x->!x.title().contains("Old archive")).toList();
        StoryCluster cluster=new StoryClusterer().cluster(fresh).stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        VerificationResult verified=new SourceVerifier().verify(cluster,2);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,verified.factPackage(),true,verified.reason(),0.91,false);
        NewsPipeline.DiscoveryResult result=new NewsPipeline.DiscoveryResult(List.of(item),3,3,0,fresh.size(),fresh.size(),fresh.size(),fresh.size(),Instant.now());
        Path dir=Files.createTempDirectory("autonews-command-media-");
        Path mediaState=dir.resolve("state.json");
        CommandCenterStore store=new CommandCenterStore(mediaState,BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null);
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

        Map<String,Object>completedSnapshot=store.snapshot();
        Map<String,Object>completedCounts=Json.object(completedSnapshot.get("counts"));
        Map<String,Object>completedPublic=Json.object(((List<?>)completedSnapshot.get("stories")).get(0));
        ok(((Number)completedCounts.get("worthy")).intValue()==0&&!Boolean.TRUE.equals(completedPublic.get("actionableWorthy")),
                "completed stories retain historical worth metadata but leave the actionable Worthy count");
        ok(((Number)completedCounts.get("toPost")).intValue()==1&&((Number)completedCounts.get("uploaded")).intValue()==0,
                "newly completed local video enters the manual TO POST queue");

        store.setUploadStatus(cluster.id,true,"TikTok","main account");
        Map<String,Object>uploadedSnapshot=store.snapshot();
        Map<String,Object>uploadedCounts=Json.object(uploadedSnapshot.get("counts"));
        Map<String,Object>uploadedStory=Json.object(((List<?>)uploadedSnapshot.get("stories")).get(0));
        Map<String,Object>uploadedVideo=Json.object(((List<?>)uploadedSnapshot.get("videos")).get(0));
        ok(Boolean.TRUE.equals(uploadedStory.get("uploaded"))&&"TikTok".equals(uploadedStory.get("uploadedPlatform"))&&
                        "main account".equals(uploadedStory.get("uploadNote"))&&uploadedStory.get("uploadedAt")!=null,
                "manual upload mark records platform, note, and timestamp on story");
        ok(Boolean.TRUE.equals(uploadedVideo.get("uploaded"))&&"TikTok".equals(uploadedVideo.get("uploadedPlatform")),
                "manual upload mark mirrors into video archive");
        ok(((Number)uploadedCounts.get("toPost")).intValue()==0&&((Number)uploadedCounts.get("uploaded")).intValue()==1,
                "manual upload mark moves video from TO POST to UPLOADED");

        CommandCenterStore reloadedUploadStore=new CommandCenterStore(mediaState,BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null);
        Map<String,Object>reloadedUpload=reloadedUploadStore.storyDetail(cluster.id);
        ok(Boolean.TRUE.equals(reloadedUpload.get("uploaded"))&&"TikTok".equals(reloadedUpload.get("uploadedPlatform")),
                "manual upload tracking survives command center restart");

        reloadedUploadStore.setUploadStatus(cluster.id,false,"","");
        Map<String,Object>unmarkedSnapshot=reloadedUploadStore.snapshot();
        Map<String,Object>unmarkedStory=Json.object(((List<?>)unmarkedSnapshot.get("stories")).get(0));
        Map<String,Object>unmarkedCounts=Json.object(unmarkedSnapshot.get("counts"));
        ok(!Boolean.TRUE.equals(unmarkedStory.get("uploaded"))&&unmarkedStory.get("uploadedAt")==null&&
                        unmarkedStory.get("uploadHistory") instanceof List<?> history&&history.size()>=2&&
                        ((Number)unmarkedCounts.get("toPost")).intValue()==1,
                "unmarking returns video to TO POST while retaining manual upload history");

        String version1=String.valueOf(unmarkedStory.get("currentVideoVersionId"));
        reloadedUploadStore.setUploadStatus(cluster.id,version1,true,"TikTok","version one posted","version-one-upload");
        int historyCount=((List<?>)reloadedUploadStore.storyDetail(cluster.id).get("uploadHistory")).size();
        reloadedUploadStore.setUploadStatus(cluster.id,version1,true,"TikTok","version one posted","version-one-upload");
        ok(((List<?>)reloadedUploadStore.storyDetail(cluster.id).get("uploadHistory")).size()==historyCount,
            "duplicate upload operation ID does not append a second history entry");
        boolean reusedIdRejected=false;
        try{reloadedUploadStore.setScrapStatus(cluster.id,version1,true,"test","version-one-upload");}
        catch(IllegalArgumentException expected){reusedIdRejected=true;}
        ok(reusedIdRejected,"operation IDs cannot be reused for a different mutation");
        reloadedUploadStore.setScrapStatus(cluster.id,version1,true,"bad pacing");
        Map<String,Object>scrappedSnapshot=reloadedUploadStore.snapshot();
        Map<String,Object>scrappedCounts=Json.object(scrappedSnapshot.get("counts"));
        Map<String,Object>scrappedStory=Json.object(((List<?>)scrappedSnapshot.get("stories")).get(0));
        Map<String,Object>scrappedVideo=Json.object(((List<?>)scrappedSnapshot.get("videos")).get(0));
        ok(Boolean.TRUE.equals(scrappedStory.get("scrapped"))&&Boolean.TRUE.equals(scrappedVideo.get("scrapped"))&&
                        "bad pacing".equals(scrappedVideo.get("scrapReason")),
                "completed video version can be scrapped with a retained reason");
        ok(((Number)scrappedCounts.get("scrapped")).intValue()==1&&((Number)scrappedCounts.get("uploaded")).intValue()==0&&
                        ((Number)scrappedCounts.get("toPost")).intValue()==0,
                "scrapped video leaves TO POST and UPLOADED counts without deleting its archive record");

        reloadedUploadStore.setScrapStatus(cluster.id,version1,false,"");
        Map<String,Object>restoredCounts=Json.object(reloadedUploadStore.snapshot().get("counts"));
        ok(((Number)restoredCounts.get("scrapped")).intValue()==0&&((Number)restoredCounts.get("uploaded")).intValue()==1,
                "restoring a scrapped uploaded version restores its prior upload state");

        reloadedUploadStore.action(cluster.id,"REMAKE");
        Map<String,Object>remakeQueued=reloadedUploadStore.storyDetail(cluster.id);
        ok("QUEUED".equals(remakeQueued.get("status"))&&((Number)remakeQueued.get("remakeCount")).intValue()==1&&
                        !Boolean.TRUE.equals(remakeQueued.get("uploaded"))&&!Boolean.TRUE.equals(remakeQueued.get("scrapped")),
                "REMAKE queues a fresh render and clears only the current story publication state");
        Map<String,Object>remakeArchiveBefore=Json.object(((List<?>)reloadedUploadStore.snapshot().get("videos")).get(0));
        ok(version1.equals(remakeArchiveBefore.get("versionId"))&&Boolean.TRUE.equals(remakeArchiveBefore.get("uploaded")),
                "REMAKE preserves the previous completed video version and its publication metadata");

        ok(reloadedUploadStore.claim("remake-worker",Map.of("duration",70))!=null,"queued remake can be claimed by a worker");
        reloadedUploadStore.videoUploaded(cluster.id,dir.resolve("version2.mp4"),"version2.mp4",1234);
        reloadedUploadStore.complete(cluster.id,Map.of("sidecar",sidecar));
        Map<String,Object>remadeSnapshot=reloadedUploadStore.snapshot();
        Map<String,Object>remadeCounts=Json.object(remadeSnapshot.get("counts"));
        Map<String,Object>remadeStory=Json.object(((List<?>)remadeSnapshot.get("stories")).get(0));
        List<?>versions=(List<?>)remadeSnapshot.get("videos");
        ok(versions.size()==2&&((Number)remadeCounts.get("videoVersions")).intValue()==2&&
                        ((Number)remadeStory.get("videoVersion")).intValue()==2&&!Boolean.TRUE.equals(remadeStory.get("uploaded")),
                "completed remake creates a separate version two instead of overwriting version one");
        ok(((Number)remadeCounts.get("uploaded")).intValue()==1&&((Number)remadeCounts.get("toPost")).intValue()==1,
                "version one remains uploaded while fresh version two enters TO POST");

        String version2=String.valueOf(remadeStory.get("currentVideoVersionId"));
        reloadedUploadStore.setScrapStatus(cluster.id,version2,true,"visual issue","scrap-version-two");
        reloadedUploadStore.setScrapStatus(cluster.id,version2,true,"visual issue","scrap-version-two");
        Map<String,Object>v1=Json.object(((List<?>)reloadedUploadStore.snapshot().get("videos")).stream()
            .filter(v->version1.equals(Json.object(v).get("versionId"))).findFirst().orElseThrow());
        ok(!Boolean.TRUE.equals(v1.get("scrapped")) && Boolean.TRUE.equals(reloadedUploadStore.storyDetail(cluster.id).get("scrapped")),
            "scrapping current version does not affect older version; duplicate request is safe");
        CommandCenterStore reloadedVersions=new CommandCenterStore(mediaState,BiasRegistry.load(dir.resolve("bias.json")),true,0.68,12,null);
        Map<String,Object>persistedVersionStory=reloadedVersions.storyDetail(cluster.id);
        Map<String,Object>persistedVersionCounts=Json.object(reloadedVersions.snapshot().get("counts"));
        ok(Boolean.TRUE.equals(persistedVersionStory.get("scrapped"))&&((Number)persistedVersionStory.get("videoVersion")).intValue()==2&&
                        ((Number)persistedVersionCounts.get("videoVersions")).intValue()==2&&((Number)persistedVersionCounts.get("scrapped")).intValue()==1,
                "remake versions and current scrap state survive command center restart");

        Path legacyDir=Files.createTempDirectory("autonews-video-migration-");
        Path legacyState=legacyDir.resolve("state.json");
        Map<String,Object>legacyStory=new LinkedHashMap<>();
        legacyStory.put("id","legacy-story");legacyStory.put("topic","Legacy video");legacyStory.put("status","COMPLETE");legacyStory.put("uploaded",true);legacyStory.put("uploadedAt",Instant.now().toString());
        Map<String,Object>legacyVideo=new LinkedHashMap<>();
        legacyVideo.put("jobId","legacy-story");legacyVideo.put("topic","Legacy video");legacyVideo.put("filename","legacy.mp4");legacyVideo.put("uploaded",true);legacyVideo.put("uploadedAt",Instant.now().toString());
        Json.write(legacyState,Map.of("stories",Map.of("legacy-story",legacyStory),"feeds",Map.of(),"videos",Map.of("legacy-story",legacyVideo),"lastScan",Map.of()));
        CommandCenterStore migratedStore=new CommandCenterStore(legacyState,BiasRegistry.load(legacyDir.resolve("bias.json")),false,0.68,12,null);
        Map<String,Object>migratedStory=migratedStore.storyDetail("legacy-story");
        List<?>migratedVideos=(List<?>)migratedStore.snapshot().get("videos");
        ok(migratedVideos.size()==1&&String.valueOf(Json.object(migratedVideos.get(0)).get("versionId")).endsWith("#v1")&&
                        ((Number)migratedStory.get("videoVersion")).intValue()==1,
                "legacy single-entry video archives migrate to versioned video records");

        Path filterDir=Files.createTempDirectory("autonews-worthy-filter-");
        CommandCenterStore filterStore=new CommandCenterStore(
                filterDir.resolve("state.json"),BiasRegistry.load(filterDir.resolve("bias.json")),
                false,0.68,12,null,75,0.74,0.82
        );
        filterStore.applyDiscovery(result);
        Map<String,Object>initialSnapshot=filterStore.snapshot();
        Map<String,Object>initialCounts=Json.object(initialSnapshot.get("counts"));
        Map<String,Object>initialPublic=Json.object(((List<?>)initialSnapshot.get("stories")).get(0));
        ok(((Number)initialCounts.get("worthy")).intValue()==1&&Boolean.TRUE.equals(initialPublic.get("actionableWorthy")),
                "verified unqueued story appears in actionable Worthy");

        ok(!Boolean.TRUE.equals(initialSnapshot.get("autoQueue")),"Command Center exposes automatic video creation as disabled");
        filterStore.setAutoQueue(true);
        ok(Boolean.TRUE.equals(filterStore.snapshot().get("autoQueue")),"runtime auto-video toggle enables automatic queueing");
        CommandCenterStore persistedToggleStore=new CommandCenterStore(
                filterDir.resolve("state.json"),BiasRegistry.load(filterDir.resolve("bias.json")),
                false,0.68,12,null,75,0.74,0.82
        );
        ok(Boolean.TRUE.equals(persistedToggleStore.snapshot().get("autoQueue")),"auto-video toggle survives Command Center restart");
        filterStore.setAutoQueue(false);

        filterStore.action(cluster.id,"HOLD");
        ok(((Number)Json.object(filterStore.snapshot().get("counts")).get("worthy")).intValue()==0,
                "held story leaves actionable Worthy");

        filterStore.action(cluster.id,"AUTO");
        ok(((Number)Json.object(filterStore.snapshot().get("counts")).get("worthy")).intValue()==1,
                "returning held story to auto restores actionable Worthy when still eligible");

        filterStore.action(cluster.id,"MAKE");
        Map<String,Object>queuedSnapshot=filterStore.snapshot();
        Map<String,Object>queuedPublic=Json.object(((List<?>)queuedSnapshot.get("stories")).get(0));
        ok(((Number)Json.object(queuedSnapshot.get("counts")).get("worthy")).intValue()==0&&!Boolean.TRUE.equals(queuedPublic.get("actionableWorthy")),
                "queued story leaves actionable Worthy");
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
        Map<String,Object>deduped=registry.mix(List.of("BBC News","BBC News","bbc news"));
        ok(((Number)deduped.get("center")).intValue()==1&&((Number)deduped.get("left")).intValue()==0&&((Number)deduped.get("right")).intValue()==0,"publisher bias mix deduplicates repeated publishers case-insensitively");
    }

    private void testPoliticalExposureMode()throws Exception{
        Instant now=Instant.now();
        Article first=new Article("frame-a","Publisher A",
                "Senate election bill sparks debate about voting rules",
                "https://publisher-a.example/news/bill","https://publisher-a.example/news/bill",now,now,"","politics",
                "Legislators discussed a voting bill.","","en",1,false);
        StoryCluster cluster=new StoryCluster("framing-experiment-fixture",first.title(),List.of(first),
                Set.of("Senate","election"),"fixture-fingerprint");
        FactPackage fp=new FactPackage(cluster.id,first.title(),
                "The Senate debated an election bill concerning voting rules.",
                List.of(),List.of(),List.of(Map.of("publisher","Publisher A")),1,1,0.45,false);
        NewsPipeline.DiscoveryItem item=new NewsPipeline.DiscoveryItem(cluster,fp,true,"fixture verified",0.91,false);
        NewsPipeline.DiscoveryResult found=new NewsPipeline.DiscoveryResult(List.of(item),2,2,0,2,2,2,2,now);
        Path dir=Files.createTempDirectory("autonews-political-exposure-");
        Path state=dir.resolve("state.json");
        CommandCenterStore store=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),false,0.68,12,null);
        store.applyDiscovery(found);
        Map<String,Object>queued=store.action(cluster.id,"MAKE_FRAMING_EXPOSURE","same-operation");
        ok("FRAMING_EXPOSURE".equals(queued.get("productionMode"))
                &&"QUEUED".equals(queued.get("status")),"framing exposure action sets explicit experimental mode");
        store.action(cluster.id,"MAKE_FRAMING_EXPOSURE","same-operation");
        ok("WAITING_FOR_FRAMING_ANALYSIS".equals(store.storyDetail(cluster.id).get("stage")),
                "experiment waits for framing evidence, duplicate operation does not create another job");
        Map<String,Object>analysisJob=store.claim("old-worker",Map.of());
        ok("POLITICAL_ANALYSIS".equals(analysisJob.get("jobType")),"legacy worker may handle political classification, not experimental video");
        Map<String,Object>analysis=new LinkedHashMap<>();
        analysis.put("politicalRelevance",0.11);analysis.put("overallClassification","not_political");
        analysis.put("overallConfidence",0.85);analysis.put("overallWeights",Map.of("left",0.3,"center",0.4,"right",0.3));
        analysis.put("summary","Insufficient evidence of political slant in the available text.");
        analysis.put("articles",List.of(Map.of("id",first.id(),"classification","not_political",
                "rationale","Only the headline and short description are available.")));
        analysis.put("model","fixture-model");
        store.politicalAnalysisComplete(cluster.id,analysis,"old-worker");
        ok(store.claim("old-worker",Map.of())==null,"worker without exposure capability cannot claim experimental render");
        CommandCenterStore loaded=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),false,0.68,12,null);
        ok("FRAMING_EXPOSURE".equals(loaded.storyDetail(cluster.id).get("productionMode")),
                "experimental mode survives restart");
        loaded.heartbeat("new-worker",Map.of("id","new-worker","capabilities",List.of("framing_exposure_v1")));
        Map<String,Object>job=loaded.claim("new-worker",Map.of("duration",75));
        ok("VIDEO".equals(job.get("jobType"))&&"FRAMING_EXPOSURE".equals(job.get("productionMode")),
                "capable worker claims only requested experiment");
        loaded.complete(cluster.id,Map.of("workerId","new-worker","claimId",job.get("claimId"),
                "sidecar",Map.of("productionMode","FRAMING_EXPOSURE")));
        Map<String,Object>done=loaded.storyDetail(cluster.id);
        String versionId=String.valueOf(done.get("currentVideoVersionId"));
        loaded.setUploadStatus(cluster.id,versionId,true,"TikTok","first experiment");
        loaded.action(cluster.id,"REMAKE");
        ok("STANDARD".equals(loaded.storyDetail(cluster.id).get("productionMode")),
                "ordinary remake explicitly returns to STANDARD");
        Map<String,Object>snapshot=loaded.snapshot();
        List<?>versions=(List<?>)snapshot.get("videos");
        ok(versions.stream().anyMatch(v->v instanceof Map<?,?>m&&
                "FRAMING_EXPOSURE".equals(m.get("productionMode"))&&Boolean.TRUE.equals(m.get("uploaded"))),
                "historic framing video keeps its mode and independent uploaded flag");

        PoliticalVisualData context=PoliticalVisualData.from(true,Map.of(),analysis,"COMPLETE","");
        var evidence=new PoliticalFramingEvidenceBuilder().build(cluster,fp,context);
        NewsScript script=new PoliticalFramingScriptGenerator(null,1).generate(fp,evidence,75,true);
        ok(script.narration().startsWith(first.title()+" "+PoliticalFramingScriptGenerator.TEASER),
                "title followed by exact mandatory audible teaser");
        VisualPlan plan=new PoliticalFramingVisualPlanner().plan(script,evidence);
        ok(plan.items().size()==8&&"FRAMING_HEADLINE".equals(plan.items().get(0).type())
                &&"LANGUAGE_HIGHLIGHT".equals(plan.items().get(3).type())
                &&"RHETORIC_CARD".equals(plan.items().get(4).type()),
                "visual plan inspects the one source without a publisher comparison");
        ok(evidence.articles().size()==1
                &&"HEADLINE_AND_DESCRIPTION".equals(evidence.articles().get(0).get("availableTextScope")),
                "single-source headline and description provide usable attributed framing evidence");
        ok(script.sourceLabels().equals(List.of("Publisher A"))
                &&script.segments().stream().noneMatch(s->s.purpose().equals("SECOND_SOURCE")),
                "single-source script does not request a second publisher");
        var filtered=new PoliticalFramingEvidenceBuilder().withArticleAnalysis(evidence,analysis);
        ok(Json.object(filtered.politicalAnalysis().get("primaryArticleAnalysis")).get("classification").equals("not_political"),
                "no-political-slant article finding is retained, not fabricated into a partisan verdict");
        // A non-political category must still be eligible for a user-triggered framing check.
        Article plain=new Article("frame-plain","Example News","City council announces new budget process",
                "https://example.org/budget","https://example.org/budget",now,now,"","local","","","en",1,false);
        StoryCluster plainCluster=new StoryCluster("manual-framing-story",plain.title(),List.of(plain),Set.of(),"plain");
        FactPackage plainFacts=new FactPackage(plainCluster.id,plain.title(),plain.title(),List.of(),
                List.of(),List.of(Map.of("publisher","Example News")),1,1,0.35,false);
        NewsPipeline.DiscoveryResult plainDiscovery=new NewsPipeline.DiscoveryResult(
                List.of(new NewsPipeline.DiscoveryItem(plainCluster,plainFacts,false,"one report",0.45,false)),
                1,1,0,1,1,1,1,now);
        CommandCenterStore singleStore=new CommandCenterStore(dir.resolve("non-political.json"),
                BiasRegistry.load(dir.resolve("bias.json")),false,0.68,12,null);
        singleStore.applyDiscovery(plainDiscovery);
        ok(!Boolean.TRUE.equals(singleStore.storyDetail(plainCluster.id).get("politicalCandidate")),
                "single-source non-political story is not auto-classified as political");
        singleStore.action(plainCluster.id,"MAKE_FRAMING_EXPOSURE");
        singleStore.applyDiscovery(plainDiscovery);
        ok("QUEUED".equals(singleStore.storyDetail(plainCluster.id).get("biasAnalysisStatus")),
                "manual exposure keeps its analysis queued across a feed rescan");
        ok(ProductionMode.fromPersisted(null)==ProductionMode.STANDARD,"legacy jobs default to standard");
        try{ProductionMode.fromPersisted("BROKEN");throw new AssertionError("Unknown mode accepted");}
        catch(IllegalArgumentException expected){ok(true,"unknown mode is explicitly rejected");}
    }

    private void testPairedSequentialVideoProduction()throws Exception{
        Instant now=Instant.now();
        Article article=new Article("paired-a","Fixture News",
                "Community market announces its Saturday vendor schedule",
                "https://fixture.example/vote","https://fixture.example/vote",now,now,"","local",
                "Local vendors plan to open their stalls on Saturday.","","en",1,false);
        StoryCluster cluster=new StoryCluster("paired-story",article.title(),List.of(article),Set.of("market"),"paired-fingerprint");
        FactPackage facts=new FactPackage(cluster.id,article.title(),"Market organizers released their Saturday schedule.",
                List.of(),List.of(),List.of(Map.of("publisher",article.publisher())),1,1,.6,false);
        NewsPipeline.DiscoveryResult discovery=new NewsPipeline.DiscoveryResult(
                List.of(new NewsPipeline.DiscoveryItem(cluster,facts,false,"one attributable report",0.9,false)),
                1,1,0,1,1,1,1,now);
        Path dir=Files.createTempDirectory("autonews-sequential-pair-");
        Path state=dir.resolve("state.json");
        CommandCenterStore store=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),
                false,.68,12,null,75,.74,.82,0,0,1,true);
        store.applyDiscovery(discovery);
        ok(!Boolean.TRUE.equals(store.storyDetail(cluster.id).get("politicalCandidate")),
                "ordinary local story does not begin as an automatic political classification");
        store.action(cluster.id,"MAKE","selected-once");
        ok("STANDARD_QUEUED".equals(store.storyDetail(cluster.id).get("pairedVideoState")),
                "chosen MAKE job records a two-video sequence before rendering");
        Map<String,Object>initialBoard=store.boardStories(1,25,"ALL","",0);
        List<?>initialEntries=(List<?>)initialBoard.get("stories");
        ok(initialEntries.size()==2
                &&"paired-story::STANDARD".equals(Json.object(initialEntries.get(0)).get("boardEntryId"))
                &&"paired-story::FRAMING_EXPOSURE".equals(Json.object(initialEntries.get(1)).get("boardEntryId")),
                "selected story immediately appears as two adjacent uniquely keyed board entries");
        ok("WAITING".equals(Json.object(initialEntries.get(1)).get("status"))
                &&"WAITING_FOR_NORMAL_VIDEO".equals(Json.object(initialEntries.get(1)).get("stage"))
                &&String.valueOf(Json.object(initialEntries.get(1)).get("displayTitle"))
                    .startsWith("Narrative Review: "),
                "narrative review entry has its own titled waiting state before production");
        ok(((Number)store.boardStories(1,25,"QUEUED","",0).get("total")).intValue()==2,
                "queue filter includes standard work and dependent review waiting state");
        store.heartbeat("updated-worker",Map.of("id","updated-worker",
                "capabilities",List.of("framing_exposure_v1")));
        Map<String,Object>normal=store.claim("updated-worker",Map.of("duration",70));
        ok("VIDEO".equals(normal.get("jobType"))&&"STANDARD".equals(normal.get("productionMode")),
                "first claimed job is always the original normal video");
        ok(store.claim("updated-worker",Map.of())==null,
                "second video is never claimable while the standard video is in progress");
        store.complete(cluster.id,Map.of("workerId","updated-worker","claimId",normal.get("claimId"),
                "sidecar",Map.of("productionMode","STANDARD")));
        Map<String,Object>afterFirst=store.storyDetail(cluster.id);
        ok("QUEUED".equals(afterFirst.get("status"))
                &&"FRAMING_EXPOSURE".equals(afterFirst.get("productionMode"))
                &&"FRAMING_QUEUED".equals(afterFirst.get("pairedVideoState")),
                "successful normal completion queues a separate framing review automatically");
        String firstVersion=String.valueOf(afterFirst.get("pairedStandardVersionId"));
        ok(firstVersion.equals(afterFirst.get("currentVideoVersionId")),
                "first normal video remains the published current version until the review completes");
        Map<String,Object>handoffBoard=store.boardStories(1,25,"ALL","",0);
        List<?>handoffRows=(List<?>)handoffBoard.get("stories");
        ok("COMPLETE".equals(Json.object(handoffRows.get(0)).get("status"))
                &&"QUEUED".equals(Json.object(handoffRows.get(1)).get("status"))
                &&firstVersion.equals(Json.object(handoffRows.get(0)).get("currentVideoVersionId")),
                "normal card remains completed and version-linked while review queues");
        ok(((Number)store.boardStories(1,25,"TO_POST","",0).get("total")).intValue()==1,
                "completed normal report remains independently visible in To Post");
        store.setUploadStatus(cluster.id,firstVersion,true,"TikTok","normal news posted");
        ok(((Number)store.boardStories(1,25,"UPLOADED","",0).get("total")).intValue()==1
                &&((Number)store.boardStories(1,25,"TO_POST","",0).get("total")).intValue()==0,
                "posting original report changes only the original board entry");
        store.applyDiscovery(discovery);
        ok("QUEUED".equals(store.storyDetail(cluster.id).get("status")),
                "feed rescan cannot discard or duplicate a pending second video");
        CommandCenterStore reloaded=new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),
                false,.68,12,null,75,.74,.82,0,0,1,true);
        ok("FRAMING_QUEUED".equals(reloaded.storyDetail(cluster.id).get("pairedVideoState")),
                "paired handoff survives a Command Center restart");
        ok(((Number)reloaded.boardStories(1,10,"ALL","narrative review",0).get("total")).intValue()==1,
                "narrative review is individually searchable after restart");
        Map<String,Object>analysisJob=reloaded.claim("old-worker",Map.of());
        ok(analysisJob!=null&&"POLITICAL_ANALYSIS".equals(analysisJob.get("jobType")),
                "second video waits for article framing analysis even for a local category");
        Map<String,Object>framing=new LinkedHashMap<>();
        framing.put("politicalRelevance",.12);framing.put("overallClassification","not_political");
        framing.put("overallConfidence",.7);
        framing.put("overallWeights",Map.of("left",.3,"center",.4,"right",.3));
        framing.put("summary","No documented partisan framing from the text.");
        framing.put("articles",List.of(Map.of("id",article.id(),"classification","not_political")));
        reloaded.politicalAnalysisComplete(cluster.id,framing,"old-worker");
        ok(reloaded.claim("old-worker",Map.of())==null,
                "legacy workers cannot take the review without the capability");
        reloaded.heartbeat("updated-worker",Map.of("id","updated-worker",
                "capabilities",List.of("framing_exposure_v1")));
        Map<String,Object>review=reloaded.claim("updated-worker",Map.of("duration",75));
        ok(review!=null&&"VIDEO".equals(review.get("jobType"))
                &&"FRAMING_EXPOSURE".equals(review.get("productionMode")),
                "second video is claimed as framing exposure rather than another news report");
        reloaded.complete(cluster.id,Map.of("workerId","updated-worker","claimId",review.get("claimId"),
                "sidecar",Map.of("productionMode","FRAMING_EXPOSURE")));
        Map<String,Object>done=reloaded.storyDetail(cluster.id);
        ok("COMPLETE".equals(done.get("status"))
                &&"COMPLETE".equals(done.get("pairedVideoState"))
                &&!firstVersion.equals(done.get("pairedFramingVersionId")),
                "two-video sequence completes with distinct archived version identifiers");
        List<?>versions=(List<?>)reloaded.snapshot().get("videos");
        ok(versions.size()==2&&versions.stream().anyMatch(v->v instanceof Map<?,?>m
                &&"STANDARD".equals(m.get("productionMode"))&&Boolean.TRUE.equals(m.get("uploaded")))
                &&versions.stream().anyMatch(v->v instanceof Map<?,?>m
                &&"FRAMING_EXPOSURE".equals(m.get("productionMode"))&&!Boolean.TRUE.equals(m.get("uploaded"))),
                "both versions retain independent publication status");
        Map<String,Object>completedBoard=reloaded.boardStories(1,25,"ALL","",0);
        List<?>completedRows=(List<?>)completedBoard.get("stories");
        ok(completedRows.size()==2
                &&"COMPLETE".equals(Json.object(completedRows.get(0)).get("status"))
                &&"COMPLETE".equals(Json.object(completedRows.get(1)).get("status"))
                &&!Json.object(completedRows.get(0)).get("boardEntryId")
                    .equals(Json.object(completedRows.get(1)).get("boardEntryId")),
                "successful pair has two separately completed board cards");
        ok(((Number)reloaded.boardStories(1,25,"TO_POST","",0).get("total")).intValue()==1
                &&((Number)reloaded.boardStories(1,25,"UPLOADED","",0).get("total")).intValue()==1,
                "posting filters treat review and original as separate completed entries");
        ok(((Number)reloaded.boardStories(1,10,"UPLOADED","",0).get("total")).intValue()==1
                &&"STANDARD".equals(Json.object(((List<?>)reloaded.boardStories(1,10,"UPLOADED","",0)
                        .get("stories")).get(0)).get("entryMode")),
                "uploaded filter selects original while review remains ready to post");
        ok(reloaded.claim("updated-worker",Map.of())==null,
                "a completed pair does not silently create a third video");

        // AUTO-selected stories receive the same pair as manually queued ones.
        Path autoState=dir.resolve("auto-queue.json");
        NewsPipeline.DiscoveryResult autoDiscovery=new NewsPipeline.DiscoveryResult(
                List.of(new NewsPipeline.DiscoveryItem(cluster,facts,true,"fixture verified",0.95,false)),
                1,1,0,1,1,1,1,now);
        CommandCenterStore automatic=new CommandCenterStore(autoState,
                BiasRegistry.load(dir.resolve("bias.json")),true,.68,12,null,75,.74,.82,0,0,1,true);
        automatic.applyDiscovery(autoDiscovery);
        ok("STANDARD".equals(String.valueOf(automatic.storyDetail(cluster.id).getOrDefault("productionMode","STANDARD")))
                &&"QUEUED".equals(automatic.storyDetail(cluster.id).get("status")),
                "automatic selection queues the standard story first");
        Map<String,Object>autoJob=automatic.claim("old-worker",Map.of());
        ok(autoJob!=null&&"STANDARD".equals(autoJob.get("productionMode")),
                "auto-selected videos begin with original news production");
        automatic.complete(cluster.id,Map.of("workerId","old-worker","claimId",autoJob.get("claimId"),
                "sidecar",Map.of("productionMode","STANDARD")));
        ok("FRAMING_QUEUED".equals(automatic.storyDetail(cluster.id).get("pairedVideoState"))
                &&"QUEUED".equals(automatic.storyDetail(cluster.id).get("status")),
                "auto-selected standard video independently triggers its framing companion");

        // Failure of the review must preserve the successful normal video and
        // the retry action must target only FRAMING_EXPOSURE.
        Map<String,Object>autoAnalysis=automatic.claim("old-worker",Map.of());
        ok("POLITICAL_ANALYSIS".equals(autoAnalysis.get("jobType")),
                "automatic pair queues framing analysis for a single-source nonpolitical story");
        automatic.politicalAnalysisComplete(cluster.id,framing,"old-worker");
        automatic.heartbeat("updated-worker",Map.of("id","updated-worker",
                "capabilities",List.of("framing_exposure_v1")));
        Map<String,Object>autoReview=automatic.claim("updated-worker",Map.of());
        automatic.fail(cluster.id,"Synthetic review render failure","updated-worker",
                String.valueOf(autoReview.get("claimId")));
        ok("FRAMING_FAILED".equals(automatic.storyDetail(cluster.id).get("pairedVideoState"))
                &&"FAILED".equals(automatic.storyDetail(cluster.id).get("status"))
                &&((List<?>)automatic.snapshot().get("videos")).size()==1,
                "failed framing review keeps completed normal version safe");
        ok(((Number)automatic.boardStories(1,10,"FAILED","",0).get("total")).intValue()==1
                &&((Number)automatic.boardStories(1,10,"COMPLETE","",0).get("total")).intValue()==1,
                "failed review remains individually actionable while original card stays complete");
        automatic.action(cluster.id,"MAKE");
        ok("FRAMING_EXPOSURE".equals(automatic.storyDetail(cluster.id).get("productionMode"))
                &&"FRAMING_QUEUED".equals(automatic.storyDetail(cluster.id).get("pairedVideoState")),
                "retrying the failed pair resumes the framing review, not the completed news video");
        ok(((List<?>)automatic.snapshot().get("videos")).size()==1,
                "framing retry creates no duplicate normal version");
        Map<String,Object>retryReview=automatic.claim("updated-worker",Map.of());
        ok(retryReview!=null&&"FRAMING_EXPOSURE".equals(retryReview.get("productionMode")),
                "manual retry claims only the failed narrative review");
        automatic.progress(cluster.id,new WorkerState(1,1,PipelineStage.SCRIPT,
                "Generating narrative review narration",Instant.now()),"updated-worker",
                String.valueOf(retryReview.get("claimId")));
        automatic.fail(cluster.id,"FRAMING_SCRIPT_VALIDATION_FAILED after 5 attempts",
                "updated-worker",String.valueOf(retryReview.get("claimId")));
        ok("SCRIPT_FAILURE".equals(automatic.storyDetail(cluster.id).get("failureType"))
                &&((List<?>)automatic.snapshot().get("videos")).size()==1,
                "script-generation failure is correctly labeled and the normal video stays archived");

        // Explicit exposure action remains a one-video choice, with no standard prelude.
        Path onlyState=dir.resolve("framing-only.json");
        CommandCenterStore only=new CommandCenterStore(onlyState,BiasRegistry.load(dir.resolve("bias.json")),
                false,.68,12,null,75,.74,.82,0,0,1,true);
        only.applyDiscovery(discovery);
        only.action(cluster.id,"MAKE_FRAMING_EXPOSURE");
        ok("FRAMING_EXPOSURE".equals(only.storyDetail(cluster.id).get("productionMode"))
                &&only.storyDetail(cluster.id).get("pairedStandardVersionId")==null,
                "standalone framing button does not unexpectedly create a news report");

        // A failed standard first stage must never enqueue a review.
        Path failureState=dir.resolve("first-failure.json");
        CommandCenterStore fail=new CommandCenterStore(failureState,BiasRegistry.load(dir.resolve("bias.json")),
                false,.68,12,null,75,.74,.82,0,0,1,true);
        fail.applyDiscovery(discovery);
        fail.action(cluster.id,"MAKE");
        Map<String,Object>bad=fail.claim("old-worker",Map.of());
        fail.fail(cluster.id,"Synthetic standard rendering failure","old-worker",
                String.valueOf(bad.get("claimId")));
        ok("FAILED".equals(fail.storyDetail(cluster.id).get("status"))
                &&"STANDARD_FAILED".equals(fail.storyDetail(cluster.id).get("pairedVideoState"))
                &&((List<?>)fail.snapshot().get("videos")).isEmpty(),
                "failed original video does not enqueue a premature framing review");

        // Re-making a completed pair starts a new independent two-video cycle.
        reloaded.action(cluster.id,"REMAKE");
        Map<String,Object>remake=reloaded.claim("updated-worker",Map.of());
        ok("STANDARD".equals(remake.get("productionMode")),"REMAKE starts a new normal video first");
        reloaded.complete(cluster.id,Map.of("workerId","updated-worker","claimId",remake.get("claimId"),
                "sidecar",Map.of("productionMode","STANDARD")));
        ok(((List<?>)reloaded.snapshot().get("videos")).size()==3
                &&"FRAMING_QUEUED".equals(reloaded.storyDetail(cluster.id).get("pairedVideoState")),
                "remake preserves both original files and queues its own review");
        Map<String,Object>remakeBoard=reloaded.boardStories(1,25,"ALL","",0);
        ok(((List<?>)remakeBoard.get("stories")).size()==2,
                "remakes replace current board state without duplicating cards for older archives");
    }

    private void testPoliticalExposureLengthRecovery()throws Exception{
        Instant now=Instant.now();
        Article article=new Article("framing-length-one","Fixture Gazette",
                "Senate debates election proposal and new voting procedures",
                "https://fixture.example/article","https://fixture.example/article",now,now,"","politics",
                "Senate members debated a proposal about voting procedures.","","en",1,false);
        StoryCluster cluster=new StoryCluster("length-fixture",article.title(),List.of(article),Set.of("Senate"),"length-test");
        FactPackage facts=new FactPackage(cluster.id,article.title(),
                "The source reports that senators debated changes to voting procedures.",
                List.of(),List.of(),List.of(Map.of("publisher",article.publisher())),1,1,.5,false);
        Map<String,Object>analysis=Map.of("politicalRelevance",.7,
                "overallClassification","uncertain","overallConfidence",.5,
                "overallWeights",Map.of("left",.3,"center",.4,"right",.3),
                "summary","Headline wording should be inspected without assuming motives.");
        PoliticalVisualData political=PoliticalVisualData.from(true,Map.of(),analysis,"COMPLETE","");
        var evidence=new PoliticalFramingEvidenceBuilder().build(cluster,facts,political);

        String[]sentences={
                "The article reports that senators debated changes to voting procedures today.",
                "The headline presents the debate as a proposed change under discussion.",
                "This wording foregrounds the institutional process rather than a definite outcome.",
                "The description discusses procedures but does not identify a final decision.",
                "That distinction matters because a proposal is not an enacted policy.",
                "Readers may interpret an active debate differently from a finished action.",
                "The available article excerpt cannot establish the publisher's private motives."
        };
        List<String>oversized=new ArrayList<>();
        for(int i=0;i<6;i++){
            List<String>parts=new ArrayList<>();
            for(int j=0;j<7;j++)parts.add(sentences[(i+j)%sentences.length]);
            oversized.add(String.join(" ",parts));
        }
        int rawWords=autonewsroller.util.Text.words(article.title())
                +autonewsroller.util.Text.words(PoliticalFramingScriptGenerator.TEASER)
                +oversized.stream().mapToInt(autonewsroller.util.Text::words).sum();
        ok(rawWords>300,"test Ollama produces a script longer than the 70-second upper limit");
        PoliticalFramingLengthEditor.Edit edited=PoliticalFramingLengthEditor.compact(
                oversized,article.title(),PoliticalFramingScriptGenerator.TEASER,70);
        ok(edited.changed()&&edited.resultingWords()>=ScriptValidator.minWords(70)
                &&edited.resultingWords()<=ScriptValidator.maxWords(70),
                "oversized six-beat script is edited to valid duration budget using complete sentences");
        ok(edited.paragraphs().size()==6
                &&edited.paragraphs().stream().allMatch(x->x.endsWith("."))
                &&edited.paragraphs().get(0).contains("reports"),
                "length repair retains all six editorial beats and complete original sentences");
        List<String>shortDraft=List.of("A factual summary.","An article headline.","A possible emphasis.",
                "Evidence is limited.","An interpretation is possible.","No motive is established.");
        ok(!PoliticalFramingLengthEditor.compact(shortDraft,article.title(),
                PoliticalFramingScriptGenerator.TEASER,70).changed(),
                "short drafts are never fabricated or padded with unsupported claims");

        // Different, substantive observations should be allowed to occupy additional
        // runtime rather than mechanically losing complete explanatory paragraphs.
        List<String>longDraft=List.of(
                "The article reports a debate about a proposed change to voting procedures. A discussion of a proposal is not the same as an enacted policy. The available description identifies a deliberative process without claiming final adoption. That distinction controls how confidently any outcome may be stated.",
                "The headline uses the active verb debates, keeping lawmakers and their deliberation at the center. The phrase election proposal identifies a plan under discussion instead of a completed result. Naming voting procedures foregrounds administration rather than an individual voter. This selection of emphasis could influence expectations about the story.",
                "The headline does not use an obvious alarm word or explicit partisan attack. Its reference to new procedures suggests change while leaving the impact unspecified. The short description contains no separate emotionally charged label. These observable choices leave the audience to infer possible effects without the article spelling them out.",
                "A reported debate is supported by the attributed description available here. Any assertion about legal consequences would need additional information that was not supplied. The word proposal is important because it restricts the reported development to consideration. Treating a contemplated measure as settled law would go beyond the evidence.",
                "Readers may focus first on the prospect of administrative change because the headline foregrounds procedures. A different reader may instead notice the ongoing debate and understand the outcome as open. Neither interpretation establishes intentional persuasion by the publisher. The text supports analysis of emphasis, not a judgment about concealed motives.",
                "The clearest framing observation is the contrast between an active debate and an unresolved proposal. The available wording supports scrutiny of what is presented as established. The short supplied source does not prove partisan manipulation or describe the full underlying policy. That limitation should remain clear in the final assessment."
        );
        int longWords=autonewsroller.util.Text.words(article.title())
                +autonewsroller.util.Text.words(PoliticalFramingScriptGenerator.TEASER)
                +longDraft.stream().mapToInt(autonewsroller.util.Text::words).sum();
        ok(longWords>ScriptValidator.maxWords(75),
                "a substantive six-part framing draft can exceed the short 75-second format");
        ok(!PoliticalFramingDurationPolicy.excessiveRepetition(longDraft),
                "distinct framing paragraphs are not mistaken for repetitive padding");
        ok(PoliticalFramingDurationPolicy.excessiveRepetition(oversized),
                "repeated sentences cannot justify an extended video");
        ok(PoliticalFramingDurationPolicy.chooseSeconds(320,75,180)==128
                &&PoliticalFramingDurationPolicy.chooseSeconds(400,75,180)==160,
                "nominal runtime expands to reflect meaningful word count");
        ok(PoliticalFramingDurationPolicy.chooseSeconds(600,75,180)==180
                &&PoliticalFramingDurationPolicy.chooseSeconds(120,75,180)==75,
                "automatic framing runtime remains capped and retains the minimum");

        AtomicInteger calls=new AtomicInteger();
        HttpServer mock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mock.createContext("/generate",exchange->{
            calls.incrementAndGet();
            byte[]response=Json.stringify(Map.of("response",Json.stringify(Map.of("segments",longDraft)),
                    "done_reason","stop")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(OutputStream output=exchange.getResponseBody()){output.write(response);}
        });
        mock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-length-recovery-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+mock.getAddress().getPort()+"/generate",
                    "mock-framing","5m",scratch.resolve("ollama.lock"));
            NewsScript expanded=new PoliticalFramingScriptGenerator(client,5,180).generate(facts,evidence,70,false);
            int expectedSeconds=PoliticalFramingDurationPolicy.chooseSeconds(longWords,70,180);
            ok(calls.get()==1,"a lengthy useful Ollama response does not trigger five regeneration attempts");
            ok(expanded.estimatedDuration()==expectedSeconds&&expanded.estimatedDuration()>70,
                    "substantive framing analysis automatically increases script duration");
            ok(autonewsroller.util.Text.words(expanded.narration())>=ScriptValidator.minWords(expectedSeconds)
                    &&autonewsroller.util.Text.words(expanded.narration())<=ScriptValidator.maxWords(expectedSeconds),
                    "expanded script remains inside its dynamically chosen narration budget");
            ok(expanded.narration().startsWith(article.title()+" "+PoliticalFramingScriptGenerator.TEASER)
                    &&expanded.sourceLabels().equals(List.of(article.publisher())),
                    "expanded duration preserves exact opening, attribution and one-source inspection");
            ok(expanded.segments().size()==8
                    &&expanded.narration().contains("The short supplied source does not prove partisan manipulation"),
                    "longer framing mode preserves detailed evidence limitations instead of truncating them");
        }finally{mock.stop(0);}

        // Repro: the 30-word first draft must be EXPANDED by targeted edits,
        // not condensed or blindly discarded. The mocked Ollama validates schema usage.
        AtomicInteger shortCalls=new AtomicInteger();
        AtomicInteger schemaChecks=new AtomicInteger();
        HttpServer shortMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        shortMock.createContext("/generate",exchange->{
            Map<String,Object>request=Json.object(Json.parse(new String(
                    exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            if(request.get("format") instanceof Map<?,?>)schemaChecks.incrementAndGet();
            int call=shortCalls.incrementAndGet();
            String responseText;
            if(call==1){
                responseText=Json.stringify(Map.of("segments",shortDraft));
            }else{
                Map<String,Object>controls=Json.object(Json.parse(
                        String.valueOf(request.get("prompt")).substring(
                                String.valueOf(request.get("prompt")).indexOf('{'))));
                List<?>indices=(List<?>)controls.get("repairIndices");
                List<Map<String,Object>>fixes=new ArrayList<>();
                for(Object index:indices)fixes.add(Map.of("segmentIndex",index,
                        "narration",longDraft.get(((Number)index).intValue())));
                responseText=Json.stringify(Map.of("replacements",fixes));
            }
            byte[]body=Json.stringify(Map.of("response",responseText,"done_reason","stop"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        shortMock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-short-repair-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                    shortMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
            NewsScript recovered=new PoliticalFramingScriptGenerator(client,4,180)
                    .generate(facts,evidence,75,false);
            ok(shortCalls.get()==2&&schemaChecks.get()==2,
                    "short framing draft receives one schema-validated targeted repair");
            ok(recovered.segments().get(2).narration().equals(shortDraft.get(0))
                    &&recovered.segments().get(3).narration().equals(longDraft.get(1)),
                    "short-draft repair preserves unaffected recap and expands analytical beats");
        }finally{shortMock.stop(0);}

        // Repro: the stray unsupported number zero is corrected only in its
        // offending paragraph; other source-grounded analysis must survive.
        List<String>numericDraft=new ArrayList<>(longDraft);
        numericDraft.set(2,numericDraft.get(2)+" 0.");
        AtomicInteger numericCalls=new AtomicInteger();
        HttpServer numberMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        numberMock.createContext("/generate",exchange->{
            int call=numericCalls.incrementAndGet();
            String responseText=call==1?Json.stringify(Map.of("segments",numericDraft))
                    :Json.stringify(Map.of("replacements",List.of(
                            Map.of("segmentIndex",2,"narration",longDraft.get(2)))));
            byte[]body=Json.stringify(Map.of("response",responseText,"done_reason","stop"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        numberMock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-number-repair-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                    numberMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
            NewsScript fixed=new PoliticalFramingScriptGenerator(client,4,180)
                    .generate(facts,evidence,75,false);
            ok(numericCalls.get()==2&&fixed.segments().get(4).narration().equals(longDraft.get(2))
                    &&fixed.segments().get(3).narration().equals(longDraft.get(1)),
                    "unsupported zero triggers single-segment factual repair");
        }finally{numberMock.stop(0);}


        // Truncated/malformed JSON must not be blindly regenerated five times
        // when Ollama returns the same invalid content verbatim.
        for(String broken:List.of("{\"segments\":[\"broken\",]",
                Json.stringify(Map.of("segments",longDraft.subList(0,5))),
                Json.stringify(Map.of("segments",List.of("one","two","three","four","five","six","seven"))))){
            AtomicInteger brokenCalls=new AtomicInteger();
            HttpServer brokenMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            brokenMock.createContext("/generate",exchange->{
                brokenCalls.incrementAndGet();
                byte[]body=Json.stringify(Map.of("response",broken,"done_reason","stop"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                try(OutputStream out=exchange.getResponseBody()){out.write(body);}
            });
            brokenMock.start();
            try{
                Path scratch=Files.createTempDirectory("framing-invalid-json-");
                OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                        brokenMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
                boolean safelyRejected=false;
                try{new PoliticalFramingScriptGenerator(client,5,180).generate(facts,evidence,75,false);}
                catch(IllegalStateException failure){
                    safelyRejected=failure.getMessage().contains("FRAMING_SCRIPT_FORMAT_FAILED");
                }
                ok(safelyRejected&&brokenCalls.get()==2,
                        "repeated malformed/five-or-seven segment JSON is rejected after two identical responses");
            }finally{brokenMock.stop(0);}
        }

        // A percentage is not interchangeable with the same bare number.
        List<String>unsupportedPercent=new ArrayList<>(longDraft);
        unsupportedPercent.set(2,longDraft.get(2)+" This change reaches 0% of readers.");
        AtomicInteger percentCalls=new AtomicInteger();
        HttpServer percentMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        percentMock.createContext("/generate",exchange->{
            int call=percentCalls.incrementAndGet();
            String responseText=call==1?Json.stringify(Map.of("segments",unsupportedPercent))
                    :Json.stringify(Map.of("replacements",List.of(
                            Map.of("segmentIndex",2,"narration",longDraft.get(2)))));
            byte[]body=Json.stringify(Map.of("response",responseText,"done_reason","stop"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        percentMock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-percent-repair-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                    percentMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
            NewsScript fixed=new PoliticalFramingScriptGenerator(client,4,180)
                    .generate(facts,evidence,75,false);
            ok(percentCalls.get()==2&&!fixed.narration().contains("0%"),
                    "unsupported percentages are checked and repaired independently of integer evidence");
        }finally{percentMock.stop(0);}

        List<String>quotedDraft=new ArrayList<>(longDraft);
        quotedDraft.set(1,longDraft.get(1)+
                " The actual headline says \"Senate   debates election proposal\".");
        AtomicInteger quoteCalls=new AtomicInteger();
        HttpServer quoteMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        quoteMock.createContext("/generate",exchange->{
            quoteCalls.incrementAndGet();
            byte[]body=Json.stringify(Map.of("response",Json.stringify(Map.of("segments",quotedDraft)),
                    "done_reason","stop")).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        quoteMock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-source-quote-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                    quoteMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
            NewsScript accepted=new PoliticalFramingScriptGenerator(client,2,180)
                    .generate(facts,evidence,75,false);
            ok(quoteCalls.get()==1&&accepted.narration().contains("Senate   debates election proposal"),
                    "attributed real headline quotation tolerates benign whitespace differences");
        }finally{quoteMock.stop(0);}

        Article bodyLimited=new Article("excerpt-limit","Fixture Gazette",article.title(),
                article.url(),article.canonicalUrl(),now,now,"","politics",
                "description ".repeat(200),"article paragraph ".repeat(300),"en",1,false);
        var clippedEvidence=new PoliticalFramingEvidenceBuilder().build(new StoryCluster(
                "excerpt-limit-test",article.title(),List.of(bodyLimited),Set.of(),"source-limit"),facts,political);
        Map<String,Object>clipped=clippedEvidence.articles().get(0);
        ok(Boolean.TRUE.equals(clipped.get("bodyTruncated"))
                &&Boolean.TRUE.equals(clipped.get("descriptionTruncated"))
                &&String.valueOf(clipped.get("bodyText")).length()<=3600
                &&clippedEvidence.contentFingerprint()!=null,
                "evidence truncation remains explicit and fingerprints preserve full source integrity");


        for(String unsupported:List.of(
                "The publisher deliberately hides crucial context from readers.",
                "The debate triggered an election crisis.")){
            List<String>unsafe=new ArrayList<>(longDraft);
            unsafe.set(2,longDraft.get(2)+" "+unsupported);
            AtomicInteger repairs=new AtomicInteger();
            HttpServer groundingMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            groundingMock.createContext("/generate",exchange->{
                int call=repairs.incrementAndGet();
                String responseText=call==1?Json.stringify(Map.of("segments",unsafe))
                        :Json.stringify(Map.of("replacements",List.of(
                                Map.of("segmentIndex",2,"narration",longDraft.get(2)))));
                byte[]body=Json.stringify(Map.of("response",responseText,"done_reason","stop"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length);
                try(OutputStream out=exchange.getResponseBody()){out.write(body);}
            });
            groundingMock.start();
            try{
                Path scratch=Files.createTempDirectory("framing-causal-omission-");
                OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                        groundingMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
                NewsScript result=new PoliticalFramingScriptGenerator(client,4,180)
                        .generate(facts,evidence,75,false);
                ok(repairs.get()==2&&!result.narration().contains(unsupported)
                        &&result.segments().get(4).narration().equals(longDraft.get(2)),
                        "fabricated omission or unsupported causal claim receives a targeted repair");
            }finally{groundingMock.stop(0);}
        }

        // Numbers from corroborating outlets are not source evidence for a review
        // of ONE article, even if the upstream fact package contains them.
        FactPackage crossOutletFacts=new FactPackage(facts.storyId(),facts.headline(),
                "A separate publisher reports 47 votes.",facts.facts(),facts.disputedClaims(),
                facts.sources(),facts.sourceCount(),facts.independentSourceCount(),
                facts.confidence(),facts.authoritativePrimaryAccepted());
        List<String>foreignNumeric=new ArrayList<>(longDraft);
        foreignNumeric.set(2,longDraft.get(2)+" The measure received 47 votes.");
        AtomicInteger foreignCalls=new AtomicInteger();
        HttpServer sourceMock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        sourceMock.createContext("/generate",exchange->{
            int call=foreignCalls.incrementAndGet();
            String responseText=call==1?Json.stringify(Map.of("segments",foreignNumeric))
                    :Json.stringify(Map.of("replacements",List.of(
                            Map.of("segmentIndex",2,"narration",longDraft.get(2)))));
            byte[]body=Json.stringify(Map.of("response",responseText,"done_reason","stop"))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(body);}
        });
        sourceMock.start();
        try{
            Path scratch=Files.createTempDirectory("framing-source-boundary-");
            OllamaClient client=new OllamaClient("http://127.0.0.1:"+
                    sourceMock.getAddress().getPort()+"/generate","mock","5m",scratch.resolve("gate.lock"));
            NewsScript fixed=new PoliticalFramingScriptGenerator(client,4,180)
                    .generate(crossOutletFacts,evidence,75,false);
            ok(foreignCalls.get()==2&&!fixed.narration().contains("47 votes"),
                    "numerical facts from another publisher cannot license claims about the selected article");
        }finally{sourceMock.stop(0);}

        Article headlineOnly=new Article("only-headline","Fixture Gazette",
                article.title(),article.url(),article.canonicalUrl(),now,now,"","politics",
                "","","en",1,false);
        StoryCluster limitedCluster=new StoryCluster("limited-framing",article.title(),
                List.of(headlineOnly),Set.of(),"limited-framing-test");
        var limitedEvidence=new PoliticalFramingEvidenceBuilder().build(limitedCluster,facts,political);
        boolean limitedRejected=false;
        try{new PoliticalFramingScriptGenerator(null,1).generate(facts,limitedEvidence,75,false);}
        catch(IllegalStateException insufficient){
            limitedRejected=insufficient.getMessage().contains("INSUFFICIENT_FRAMING_EVIDENCE");
        }
        ok(limitedRejected,"headline-only evidence reports an explicit insufficient-evidence result");
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

    private void testEnrichedFramingSnapshot()throws Exception{
        Instant now=Instant.now();
        Article rss=new Article("enriched-article","Fixture Politics",
                "Senate debates new election proposal",
                "https://fixture.example/election","https://fixture.example/election",
                now,now,"","politics","Lawmakers debated a proposal.","","en",1,false);
        StoryCluster cluster=new StoryCluster("enriched-story",rss.title(),List.of(rss),
                Set.of("Senate"),"unchanged-identity");
        FactPackage facts=new SourceVerifier().verify(cluster,2).factPackage();
        NewsPipeline.Candidate initial=new NewsPipeline.Candidate(cluster,facts,.8);
        var discoveryItem=new NewsPipeline.DiscoveryItem(cluster,facts,false,"test",.8,false);
        var scan=new NewsPipeline.DiscoveryResult(List.of(discoveryItem),1,1,0,1,1,1,1,now);
        Path dir=Files.createTempDirectory("framing-atomic-evidence-");
        CommandCenterStore store=new CommandCenterStore(dir.resolve("state.json"),
                BiasRegistry.load(dir.resolve("bias.json")),false,.68,12,null,75,.74,.82);
        store.applyDiscovery(scan);
        Map<String,Object>job=store.claim("analysis-worker",Map.of());
        ok("POLITICAL_ANALYSIS".equals(job.get("jobType")),
                "enrichment fixture claims a political-analysis job");
        String originalHash=String.valueOf(job.get("analysisInputFingerprint"));
        Article fetched=new Article(rss.id(),rss.publisher(),rss.title(),rss.url(),rss.canonicalUrl(),
                rss.publishedAt(),rss.discoveredAt(),rss.author(),rss.category(),rss.description(),
                "The full source discusses the proposal and describes proposed changes to voting procedures.",
                rss.language(),rss.sourceTrustTier(),rss.authoritativePrimary());
        StoryCluster enrichedCluster=new StoryCluster(cluster.id,cluster.topic,List.of(fetched),
                cluster.entities,cluster.fingerprint);
        NewsPipeline.Candidate enriched=new NewsPipeline.Candidate(enrichedCluster,facts,.8);
        Map<String,Object>analysis=Map.of("model","fixture","overallClassification","uncertain",
                "summary","Insufficient textual basis for a partisan verdict",
                "articles",List.of(Map.of("id",rss.id(),"classification","uncertain")));
        store.politicalAnalysisComplete(cluster.id,analysis,"analysis-worker",enriched,originalHash);
        Map<String,Object>saved=store.storyDetail(cluster.id);
        String updatedHash=String.valueOf(saved.get("biasAnalysisInputHash"));
        NewsPipeline.Candidate persisted=NewsPipeline.Candidate.fromMap(Json.object(saved.get("candidate")));
        ok(!originalHash.equals(updatedHash)
                &&updatedHash.equals(saved.get("framingInputHash"))
                &&"COMPLETE".equals(saved.get("biasAnalysisStatus"))
                &&persisted.cluster().articles.get(0).bodyText().equals(fetched.bodyText()),
                "enriched source, political analysis, and matching fingerprints commit together");

        store.applyDiscovery(scan);
        Map<String,Object>rescan=store.storyDetail(cluster.id);
        ok(updatedHash.equals(rescan.get("biasAnalysisInputHash"))
                &&"COMPLETE".equals(rescan.get("biasAnalysisStatus"))
                &&NewsPipeline.Candidate.fromMap(Json.object(rescan.get("candidate")))
                    .cluster().articles.get(0).bodyText().equals(fetched.bodyText()),
                "repeated RSS scans retain unchanged enriched article evidence and prior analysis");

        Article revised=new Article(rss.id(),rss.publisher(),rss.title(),rss.url(),rss.canonicalUrl(),
                now,now,"",rss.category(),"Source issued a revised description.","","en",1,false);
        StoryCluster changed=new StoryCluster(cluster.id,cluster.topic,List.of(revised),
                cluster.entities,cluster.fingerprint);
        FactPackage changedFacts=new SourceVerifier().verify(changed,2).factPackage();
        var newItem=new NewsPipeline.DiscoveryItem(changed,changedFacts,false,"test",.8,false);
        store.applyDiscovery(new NewsPipeline.DiscoveryResult(List.of(newItem),1,1,0,1,1,1,1,now));
        Map<String,Object>invalidated=store.storyDetail(cluster.id);
        ok(!updatedHash.equals(invalidated.get("biasAnalysisInputHash"))
                &&"QUEUED".equals(invalidated.get("biasAnalysisStatus"))
                &&NewsPipeline.Candidate.fromMap(Json.object(invalidated.get("candidate")))
                    .cluster().articles.get(0).bodyText().isBlank(),
                "changed article description invalidates old analysis and cannot retain stale body");

        Map<String,Object>newJob=store.claim("analysis-worker",Map.of());
        ok("POLITICAL_ANALYSIS".equals(newJob.get("jobType")),
                "updated article can be re-analyzed against its new fingerprint");
        boolean staleRejected=false;
        try{store.politicalAnalysisComplete(cluster.id,analysis,"analysis-worker",enriched,originalHash);}
        catch(IllegalStateException stale){staleRejected=stale.getMessage().contains("STALE_FRAMING_EVIDENCE");}
        ok(staleRejected,"outdated worker results cannot overwrite a newer article version");

        NewsPipeline.Candidate replacement=NewsPipeline.Candidate.fromMap(Json.object(newJob.get("candidate")));
        store.politicalAnalysisComplete(cluster.id,analysis,"analysis-worker",replacement,
                String.valueOf(newJob.get("analysisInputFingerprint")));
        ok("COMPLETE".equals(store.storyDetail(cluster.id).get("biasAnalysisStatus")),
                "newly verified snapshot can finish analysis after a source-content change");
        store.action(cluster.id,"MAKE_FRAMING_EXPOSURE");
        store.heartbeat("video-worker",Map.of("id","video-worker",
                "capabilities",List.of("framing_exposure_v1")));
        Map<String,Object>videoJob=store.claim("video-worker",Map.of());
        ok(videoJob!=null&&"FRAMING_EXPOSURE".equals(videoJob.get("productionMode"))
                &&Objects.equals(store.storyDetail(cluster.id).get("biasAnalysisInputHash"),
                        videoJob.get("framingContentFingerprint")),
                "the framing video job receives the exact analyzed article fingerprint");
    }

    private void testSelectedArticleEnrichment()throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger fetches=new AtomicInteger();
        server.createContext("/article",exchange->{
            fetches.incrementAndGet();
            byte[]html=("<html><body><main><p>"+
                    "The original article describes the proposed voting procedure and the lawmakers who discussed it."+
                    "</p><p>The reporting distinguishes committee debate from an enacted law.</p>"+
                    "</main></body></html>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200,html.length);
            try(OutputStream out=exchange.getResponseBody()){out.write(html);}
        });
        server.start();
        try{
            Instant now=Instant.now();
            String url="http://127.0.0.1:"+server.getAddress().getPort()+"/article";
            Article unselected=new Article("alternate","Another Publisher","Different angle",
                    url,url,now,now,"","politics","Unrelated article text.","","en",2,false);
            Article primary=new Article("selected","Fixture Publisher","Senate debates new procedures",
                    url,url,now,now,"","politics","Senate discusses procedural reform.","","en",1,false);
            StoryCluster cluster=new StoryCluster("enrich-selection",primary.title(),
                    List.of(unselected,primary),Set.of(),"fixed");
            Path scratch=Files.createTempDirectory("framing-select-fetch-");
            NewsConfig cfg=NewsConfig.load(scratch);
            NewsPipeline pipeline=new NewsPipeline(scratch,cfg,scratch.resolve("batch"));
            StoryCluster enriched=pipeline.enrichForFraming(cluster);
            ok(fetches.get()==1&&enriched.articles.get(0).bodyText().isBlank()
                    &&enriched.articles.get(1).bodyText().contains("The original article"),
                    "political analysis fetches only the selected article, not unrelated outlets");
            StoryCluster unchanged=pipeline.enrichForFraming(enriched);
            ok(fetches.get()==1&&unchanged.articles.get(1).bodyText().equals(
                    enriched.articles.get(1).bodyText()),
                    "already enriched article evidence is not fetched a second time");
            Article unavailable=new Article(primary.id(),primary.publisher(),primary.title(),
                    "invalid://article","invalid://article",now,now,"","politics",
                    primary.description(),"","en",1,false);
            StoryCluster fallback=new StoryCluster("enrich-fallback",unavailable.title(),
                    List.of(unavailable),Set.of(),"fixed");
            ok(pipeline.enrichForFraming(fallback).articles.get(0).bodyText().isBlank(),
                    "inaccessible article returns unchanged headline/description evidence without inventing text");
        }finally{server.stop(0);}
    }

    private void testPoliticalVisualGraphics(Path root,List<Article>a)throws Exception{
        BiasRegistry registry=BiasRegistry.load(root.resolve("config/source_bias.json"));
        Map<String,Object>sourceMix=registry.mix(List.of("BBC News","Fox News","Unknown Fixture"));

        Map<String,Object>analysis=new LinkedHashMap<>();
        analysis.put("politicalRelevance",0.93);
        analysis.put("overallClassification","center");
        analysis.put("overallConfidence",0.84);
        analysis.put("overallWeights",Map.of("left",0.333,"center",0.333,"right",0.334));
        analysis.put("summary","The supplied coverage uses mostly neutral institutional framing with limited partisan cues.");

        PoliticalVisualData political=PoliticalVisualData.from(true,sourceMix,analysis,"COMPLETE","");
        int[] percentages=political.storyPercentages();
        ok(percentages[0]+percentages[1]+percentages[2]==100&&percentages[2]==34,
                "political story display percentages normalize and round to exactly 100");
        ok(political.ratedSourceCount()==2&&political.sourceUnknown()==1,
                "political visual data keeps rated and unrated publishers separate");
        ok(political.shouldRender(0.60,true)&&!PoliticalVisualData.empty().shouldRender(0.60,true),
                "political graphic appears only for analyzed relevant political stories");

        StoryCluster cluster=new StoryClusterer().cluster(a.stream().filter(x->!x.title().contains("Old archive")).toList())
                .stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        FactPackage fp=new SourceVerifier().verify(cluster,2).factPackage();
        List<NewsScript.Segment>segments=new ArrayList<>();
        segments.add(new NewsScript.Segment(0,"A verified political story opens with a direct factual hook.","retention hook","HOOK","Verified opening visual",3));
        for(int i=1;i<6;i++)segments.add(new NewsScript.Segment(i,"Verified context beat "+i+" explains documented facts without unsupported claims.","detail","BACKGROUND","Documentary visual",7));
        NewsScript script=new NewsScript(fp.storyId(),fp.headline(),"Verified political narration.",segments,70,List.of(),
                Map.of("text",segments.get(0).narration(),"type","direct_event","factIds",List.of("FACT_A")));

        VisualPlan plan=new VisualPlanner().plan(script,fp,political,0.60,true,5.0,true,true);
        List<VisualPlan.Item>politicalItems=plan.items().stream().filter(x->"POLITICAL_CONTEXT".equals(x.type())).toList();
        ok(politicalItems.size()==1&&politicalItems.get(0).index()>0&&!"POLITICAL_CONTEXT".equals(plan.items().get(0).type()),
                "visual planner inserts one political context scene without replacing the hook thumbnail");

        Path dir=Files.createTempDirectory("autonews-political-card-");
        Path rendered=new CardRenderer().render(politicalItems.get(0),dir.resolve("political.png"),1080,1920);
        BufferedImage image=ImageIO.read(rendered.toFile());
        ok(image!=null&&image.getWidth()==1080&&image.getHeight()==1920,
                "political context card renders deterministically at 1080x1920");

        PoliticalVisualData uncertain=PoliticalVisualData.from(true,sourceMix,Map.of(
                "politicalRelevance",0.90,
                "overallClassification","uncertain",
                "overallConfidence",0.42,
                "overallWeights",Map.of("left",0.32,"center",0.37,"right",0.31)
        ),"COMPLETE","");
        ok(!uncertain.shouldRender(0.60,false)&&uncertain.shouldRender(0.60,true),
                "uncertain political framing obeys the show-on-uncertain configuration");
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
        ok(cfg.getInt("ollamaContextTokens",0)>=8192&&cfg.getInt("ollamaMaxOutputTokens",0)>=1600&&cfg.getInt("ollamaRetries",0)>=5,
                "script Ollama budget reserves enough context, output tokens, and surgical retries");
        ok(cfg.getBool("articleEnrichmentEnabled",false),
                "production defaults enable article enrichment before script generation");
        ok(cfg.getBool("politicalGraphicsEnabled",false)&&cfg.getDouble("politicalGraphicsMinimumRelevance",0)<1&&cfg.getDouble("politicalGraphicsSceneSeconds",0)>=3.5,
                "production defaults enable bounded political context graphics");
        ok(!cfg.csv("kokoroVoices","").stream().anyMatch(v->v.equalsIgnoreCase("af_nicole")||v.equalsIgnoreCase("af-nicole")),
                "production defaults exclude blacklisted Kokoro voice af_nicole");
        ok(cfg.csv("kokoroVoices","").equals(List.of("af_sarah","am_michael")),
                "default news narration prefers Sarah and Michael");
        List<String>safeKokoro=NewsPipeline.allowedKokoroVoices(List.of("af_heart","af_nicole","af-nicole","bf_emma"));
        ok(safeKokoro.equals(List.of("af_heart","bf_emma")),
                "runtime Kokoro voice filter blocks af_nicole and af-nicole aliases");
        ok(NewsPipeline.allowedKokoroVoices(List.of("af_nicole")).equals(List.of("af_sarah","am_michael")),
                "all-blacklisted Kokoro configuration falls back to safe voices");
        String kokoroHelper=Files.readString(root.resolve("tools/kokoro_tts.py"));
        ok(kokoroHelper.contains("voice_key == 'af_nicole'")&&kokoroHelper.contains("blacklisted"),
                "Kokoro helper hard-blocks af_nicole direct invocation");

        StoryCluster cluster=new StoryClusterer().cluster(a.stream().filter(x->!x.title().contains("Old archive")).toList())
                .stream().max(Comparator.comparingInt(x->x.articles.size())).orElseThrow();
        FactPackage fp=new SourceVerifier().verify(cluster,2).factPackage();
        List<NewsScript.Segment>segments=new ArrayList<>();
        segments.add(new NewsScript.Segment(0,"Nvidia reveals a new AI accelerator chip","retention hook","HOOK","Immediate verified opening visual",3));
        for(int i=1;i<8;i++)segments.add(new NewsScript.Segment(i,"Verified narration beat number "+(i+1)+" adds factual context for viewers.","detail","BACKGROUND","Documentary visual grounded in the supplied facts",8));
        NewsScript visualScript=new NewsScript(fp.storyId(),fp.headline(),"Verified narration for visual planning.",segments,70,List.of(),
                Map.of("text",segments.get(0).narration(),"type","direct_event","factIds",List.of("FACT_A")));
        VisualPlan plan=new VisualPlanner().plan(visualScript,fp);
        ok(plan.items().size()==9&&"HOOK".equals(plan.items().get(0).type())&&
                        plan.items().stream().noneMatch(x->"HEADLINE_CARD".equals(x.type())),
                "visual planner starts at frame zero with hook visual and no standalone headline card");

        Path newsPackageDir=Files.createTempDirectory("autonews-news-package-");
        Path sourceImage=newsPackageDir.resolve("source.png");
        BufferedImage fixtureImage=new BufferedImage(768,1344,BufferedImage.TYPE_INT_RGB);
        ImageIO.write(fixtureImage,"png",sourceImage.toFile());
        Path openingFrame=newsPackageDir.resolve("opening.png");
        new CardRenderer().renderWithImage(plan.items().get(0),sourceImage,openingFrame,1080,1920);
        BufferedImage renderedOpening=ImageIO.read(openingFrame.toFile());
        ok(renderedOpening!=null&&renderedOpening.getWidth()==1080&&renderedOpening.getHeight()==1920,
                "broadcast news package renders a valid 1080x1920 hook thumbnail frame");
        String cardSource=Files.readString(root.resolve("src/autonewsroller/visuals/CardRenderer.java"));
        String themeSource=Files.readString(root.resolve("src/autonewsroller/visuals/BroadcastTheme.java"));
        ok(cardSource.contains("TOP STORY")&&cardSource.contains("VERIFIED REPORTING")&&
                        cardSource.contains("renderHookPackage")&&cardSource.contains("renderStoryPackage")&&
                        cardSource.contains("fitHeadlineFont")&&cardSource.contains("verticalBias"),
                "AWARE visual package includes thumbnail-first hook treatment, adaptive headline sizing, and protected subject cropping");
        ok(themeSource.contains("aware-broadcast-v5-editorial")&&themeSource.contains("new Color(30,74,132)")&&
                        themeSource.contains("new Color(185,23,38)")&&!themeSource.toLowerCase(Locale.ROOT).contains("56,232,255"),
                "broadcast v4 uses breaking-news red and restrained royal blue without neon cyan");
        ok(themeSource.contains("HEADER_HEIGHT=118")&&themeSource.contains("TICKER_HEIGHT=80")&&
                        cardSource.contains("renderOverlay")&&cardSource.contains("drawPoliticalLabels")&&
                        cardSource.contains("int h=68")&&cardSource.contains("int h=72"),
                "broadcast v4 increases image area and makes political source/framing meters materially more visible");
        Path overlayFrame=newsPackageDir.resolve("overlay.png");
        new CardRenderer().renderOverlay(plan.items().get(0),overlayFrame,1080,1920);
        BufferedImage overlayImage=ImageIO.read(overlayFrame.toFile());
        ok(overlayImage!=null&&overlayImage.getColorModel().hasAlpha(),
                "broadcast v4 can render a transparent fixed overlay for moving photographs");

        FactPackage hookFacts=new FactPackage(
                "hook-fixture",
                "Nvidia announces a new AI accelerator",
                "Nvidia announced a new AI accelerator for data centers.",
                List.of(new FactClaim(
                        "Nvidia announced a new AI accelerator for data centers.",
                        List.of("https://source-a.test/story","https://source-b.test/story"),
                        0.9,
                        false
                )),
                List.of(),
                List.of(Map.<String,Object>of("publisher","Fixture News")),
                2,2,0.9,false
        );
        HookPlanner.Selection hookSelection=new HookPlanner(null,1).select(hookFacts);
        ok(Text.words(hookSelection.text())>=HookPlanner.MIN_HOOK_WORDS&&
                        Text.words(hookSelection.text())<=HookPlanner.MAX_HOOK_WORDS&&
                        HookPlanner.validationProblems(hookSelection.text(),hookFacts,hookFacts.headline()).isEmpty(),
                "hook planner fallback produces a short factual opening inside hard hook bounds");
        ok(!HookPlanner.validationProblems("Breaking news this shocking story changes everything",hookFacts,hookFacts.headline()).isEmpty(),
                "hook validator rejects generic clickbait openings");

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
        String retryLine="Verified reporting describes the event using facts already present in the supplied package while keeping narration neutral.";
        for(int i=0;i<8;i++)retrySegments.add(new NewsScript.Segment(i,retryLine,"detail","BACKGROUND","Verified documentary visual",8));
        String retryNarration=retrySegments.stream().map(NewsScript.Segment::narration).reduce("",(x,y)->x.isBlank()?y:x+" "+y);
        String lockedHook="Nvidia reveals a new AI accelerator chip";
        retrySegments.set(0,new NewsScript.Segment(0,lockedHook,"retention hook","HOOK","Immediate verified opening visual",3));
        retryNarration=retrySegments.stream().map(NewsScript.Segment::narration).reduce("",(x,y)->x.isBlank()?y:x+" "+y);
        NewsScript retryBase=new NewsScript(
                fp.storyId(),fp.headline(),retryNarration,retrySegments,58,List.copyOf(allowedPublishers),
                Map.of("text",lockedHook,"type","direct_event","factIds",List.of("FACT_A"))
        );

        List<NewsScriptGenerator.RepairTarget>repairPlan=List.of(
                new NewsScriptGenerator.RepairTarget(1,18,24,21,retrySegments.get(1).narration()),
                new NewsScriptGenerator.RepairTarget(2,18,24,21,retrySegments.get(2).narration())
        );
        Map<String,Object>repairJson=new LinkedHashMap<>();
        repairJson.put("replacements",List.of(
                Map.of("segmentIndex",1,"narration","The replacement narration keeps documented facts intact while giving this beat enough detail for a clear broadcast explanation without adding unsupported claims."),
                Map.of("segmentIndex",2,"narration","Verified reporting supplies additional context for this segment while the rewrite remains factual, concise, neutral, and grounded in the available source material.")
        ));
        NewsScript repaired=NewsScriptGenerator.applyReplacements(retryBase,Json.stringify(repairJson),fp,70,repairPlan);
        ok(repaired.segments().size()==8&&lockedHook.equals(repaired.segments().get(0).narration())&&
                        lockedHook.equals(repaired.hook().get("text"))&&"HOOK".equals(repaired.segments().get(0).visualType()),
                "ordinary script repair preserves locked hook text, metadata, and visual role");

        boolean rejectedOversize=false;
        try{
            Map<String,Object>oversize=new LinkedHashMap<>();
            oversize.put("replacements",List.of(
                    Map.of("segmentIndex",1,"narration","This deliberately oversized replacement contains far too many words for the requested repair target and should be rejected before it can corrupt the otherwise usable current draft with an uncontrolled expansion response from the model."),
                    Map.of("segmentIndex",2,"narration","Verified reporting supplies additional context for this segment while the rewrite remains factual, concise, neutral, and grounded in the available source material.")
            ));
            NewsScriptGenerator.applyReplacements(retryBase,Json.stringify(oversize),fp,70,repairPlan);
        }catch(IllegalArgumentException expected){rejectedOversize=true;}
        ok(rejectedOversize,"script repair rejects model replacements outside hard word bounds");

        Path ass=Files.createTempFile("autonews-captions-",".ass");
        CaptionWriter.write(ass,
                "This caption contains enough words to split cleanly across two compact lines for a vertical news video.",
                7.0,"sentence");
        String captionText=Files.readString(ass);
        ok(captionText.contains("[V4+ Styles]")&&captionText.contains("Style: News,Arial,44")&&captionText.contains("\\N")&&
                        captionText.contains("B27943")&&!captionText.contains("FFD86F"),
                "captions use compact two-line ASS styling, higher safe-area placement, and broadcast-blue emphasis");

        String videoRendererSource=Files.readString(root.resolve("src/autonewsroller/video/VideoRenderer.java"));
        ok(videoRendererSource.contains("zoompan")&&videoRendererSource.contains("render(List<Path>backgrounds,List<Path>overlays,List<Boolean>motion")&&
                        videoRendererSource.contains("overlay=0:0"),
                "video renderer adds restrained photo motion beneath fixed broadcast overlays");
        String pipelineSource=Files.readString(root.resolve("src/autonewsroller/orchestration/NewsPipeline.java"));
        ok(pipelineSource.contains("audit.put(\"hook\",script.hook())")&&
                        pipelineSource.contains("openingVisualType")&&pipelineSource.contains("HOOK\".equalsIgnoreCase(chosen.type())")&&
                        pipelineSource.contains("BroadcastTheme.PACKAGE_ID")&&pipelineSource.contains("sceneNegativePrompt(chosen)")&&
                        pipelineSource.contains("assertNoExcludedConceptInPositive(chosen,prompt)")&&
                        pipelineSource.contains("imageSource.put(\"negativePrompt\",negativePrompt)")&&
                        pipelineSource.contains("neutral white balance")&&!pipelineSource.contains("no blue wash"),
                "pipeline separates positive and negative image conditioning and records the negative prompt");
        String commandCenterHtml=Files.readString(root.resolve("web/command-center/index.html"));
        String commandCenterJs=Files.readString(root.resolve("web/command-center/app.js"));
        String commandCenterServer=Files.readString(root.resolve("src/autonewsroller/commandcenter/CommandCenterServer.java"));
        ok(commandCenterHtml.contains("id=\"autoVideoToggle\"")&&commandCenterJs.contains("/api/settings/auto-video")&&
                        commandCenterServer.contains("store.setAutoQueue(enabled)"),
                "Command Center exposes a persisted auto-video creation toggle");
        ok(commandCenterHtml.contains("id=\"railFailed\"")&&commandCenterJs.contains("OPERATIONAL_FILTERS")&&
                        commandCenterJs.contains("failurePanelHtml")&&commandCenterJs.contains("RETRY VIDEO")&&
                        commandCenterJs.contains("boardData.facets")&&commandCenterJs.contains("/api/board/stories?")&&
                        !commandCenterJs.contains("QUEUED\",\"PRODUCING\",\"FAILED\",\"HOLD"),
                "Command Center gives terminal failures a first-class rail, server pagination, and retains manual retry controls");
        String defaultsText=Files.readString(root.resolve("defaults.txt"));
        ok(defaultsText.contains("cyan cast")&&defaultsText.contains("electric blue lighting")&&defaultsText.contains("blue monochrome"),
                "default negative prompt rejects synthetic cyan and electric-blue image grading");

        ok(cfg.getBool("visualPromptEnabled",false)&&cfg.getInt("visualPromptMinimumGroundingScore",0)>=65&&
                        cfg.getBool("visualPromptRequireFactIds",false)&&cfg.getInt("visualPromptRetries",0)>=3,
                "grounded visual prompt planner defaults enable FACT-ID validation, scoring, and repair");

        FactPackage visualFacts=new FactPackage(
                "visual-fixture","Boeing aircraft maintenance update","Boeing reported aircraft maintenance work.",
                List.of(
                        new FactClaim("Boeing reported maintenance work on a passenger aircraft at its Seattle-area facility.",List.of("a","b"),0.9,false),
                        new FactClaim("Maintenance workers inspected the passenger aircraft inside an aviation maintenance facility.",List.of("a","b"),0.9,false)
                ),
                List.of(),List.of(Map.<String,Object>of("publisher","Fixture Wire")),2,2,0.9,false
        );
        Map<String,FactClaim>visualFactMap=new LinkedHashMap<>();
        visualFactMap.put("FACT_A",visualFacts.facts().get(0));
        visualFactMap.put("FACT_B",visualFacts.facts().get(1));
        VisualPromptValidator visualValidator=new VisualPromptValidator(65,true);
        VisualPromptPlan validVisual=new VisualPromptPlan(
                0,"DIRECT_EVENT",List.of("FACT_A","FACT_B"),List.of("Boeing"),
                "Boeing passenger aircraft","maintenance workers inspecting the aircraft","aviation maintenance facility","Seattle area","daytime",
                "maintenance workers",List.of("passenger aircraft","maintenance equipment"),List.of("fire","crash"),
                "documentary medium-wide shot","Boeing passenger aircraft inside an aviation maintenance facility while maintenance workers inspect the aircraft.",0,false,List.of(),0,false
        );
        VisualPromptValidator.Result validResult=visualValidator.validate(validVisual,visualFactMap,List.of("Boeing","Seattle"),visualFacts.headline(),List.of());
        ok(validResult.valid()&&validResult.score()>=65,"visual prompt validator accepts a specific FACT-grounded scene");

        VisualPromptPlan unknownFact=new VisualPromptPlan(0,"DIRECT_EVENT",List.of("FACT_Z"),List.of("Boeing"),"Boeing passenger aircraft","","aviation facility","","","",List.of("aircraft"),List.of(),"documentary shot","Boeing passenger aircraft at an aviation facility.",0,false,List.of(),0,false);
        ok(!visualValidator.validate(unknownFact,visualFactMap,List.of("Boeing"),visualFacts.headline(),List.of()).valid(),
                "visual prompt validator rejects unknown FACT IDs");

        VisualPromptPlan inventedEntity=new VisualPromptPlan(0,"LOCATION",List.of("FACT_A"),List.of("Tesla"),"Tesla factory","","factory","","","",List.of("factory"),List.of(),"documentary shot","Tesla factory exterior.",0,false,List.of(),0,false);
        ok(!visualValidator.validate(inventedEntity,visualFactMap,List.of("Boeing","Seattle"),visualFacts.headline(),List.of()).valid(),
                "visual prompt validator rejects unsupported proper nouns");

        VisualPromptPlan inventedDrama=new VisualPromptPlan(0,"DIRECT_EVENT",List.of("FACT_A"),List.of("Boeing"),"Boeing passenger aircraft","burning after an explosion","aviation facility","Seattle","","",List.of("fire","smoke"),List.of(),"dramatic shot","Boeing aircraft burning after an explosion with smoke.",0,false,List.of(),0,false);
        ok(!visualValidator.validate(inventedDrama,visualFactMap,List.of("Boeing","Seattle"),visualFacts.headline(),List.of()).valid(),
                "visual prompt validator rejects unsupported violent/disaster imagery");

        VisualPromptPlan aiCliche=new VisualPromptPlan(0,"CONTEXT",List.of("FACT_A"),List.of("Boeing"),"Boeing passenger aircraft","","aviation facility","","","",List.of("aircraft"),List.of(),"conceptual shot","Boeing aircraft over a glowing futuristic world map with floating data.",0,false,List.of(),0,false);
        ok(!visualValidator.validate(aiCliche,visualFactMap,List.of("Boeing","Seattle"),visualFacts.headline(),List.of()).valid(),
                "visual prompt validator rejects unsupported AI-news clichés");

        GroundedVisualPromptGenerator.Result dryVisuals=new GroundedVisualPromptGenerator(null,cfg).generate(visualScript,fp,cluster,true);
        Path visualArtifact=Files.createTempDirectory("autonews-visual-plan-").resolve("visual_prompt_plan.json");
        Json.write(visualArtifact,dryVisuals.toMap());
        ok(Files.isRegularFile(visualArtifact)&&dryVisuals.scenes().size()==visualScript.segments().size()&&dryVisuals.scenes().stream().allMatch(VisualPromptPlan::valid),
                "offline dry-run produces deterministic validated visual_prompt_plan artifact without Ollama");

        String comfySource=Files.readString(root.resolve("src/autonewsroller/visuals/ComfyImageGenerator.java"));
        int recovery=comfySource.indexOf("public void recoverFromOom()");
        int generate=comfySource.indexOf("public Path generate(");
        ok(generate>=0&&recovery>generate&&!comfySource.substring(generate,recovery).contains("/free")&&comfySource.substring(recovery).contains("/free"),
                "ComfyUI success path keeps models warm and only OOM recovery frees them");
    }

    private void testVisualV5()throws Exception{
        Path dir=Files.createTempDirectory("autonews-v5-fixture-");
        List<NewsScript.Segment>segments=List.of(
                new NewsScript.Segment(0,"The council approved twelve projects.","hook","HOOK","",2),
                new NewsScript.Segment(1,"The budget increased by twenty percent.","fact","CONTEXT","",2));
        String narration=segments.get(0).narration()+" "+segments.get(1).narration();
        List<String>words=SpeechTiming.tokens(narration);
        Path sidecar=dir.resolve("narration.timing.tsv");
        StringBuilder tsv=new StringBuilder("autonewsroller-kokoro-timing-v1\n");
        for(int i=0;i<words.size();i++){
            String token=Base64.getUrlEncoder().withoutPadding().encodeToString(words.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tsv.append(String.format(Locale.ROOT,"word\t%.3f\t%.3f\t%s\n",i*.35,i*.35+.3,token));
        }
        Files.writeString(sidecar,tsv.toString());
        SpeechTiming.Alignment exact=SpeechTiming.align(narration,segments,sidecar,5.0);
        ok(exact.exact()&&exact.words().size()==words.size()&&exact.segmentDurations().size()==2,
                "Kokoro sidecar aligns verified spoken tokens with segment boundaries");
        Files.writeString(sidecar,"untrusted timing payload\n");
        SpeechTiming.Alignment fallback=SpeechTiming.align(narration,segments,sidecar,5.0);
        ok(!fallback.exact()&&fallback.words().size()==words.size(),
                "invalid or missing timing sidecars fall back without claiming exact word timing");
        NewsScript script=new NewsScript("v5-story","Council approves projects",narration,segments,5,List.of("Fixture News"));
        VisualPlan plan=new VisualPlan("v5-story",List.of(
                new VisualPlan.Item(0,"HOOK",script.headline(),segments.get(0).narration(),2,"",Map.of("segmentIndex",0,"factIds",List.of("FACT_A"))),
                new VisualPlan.Item(1,"STATISTIC",script.headline(),segments.get(1).narration(),2,"",Map.of("segmentIndex",1,"factIds",List.of("FACT_B")))));
        VisualPlan ungrounded=new VisualPlanner().plan(script,
                new FactPackage("v5-story",script.headline(),narration,List.of(),List.of(),List.of(),1,1,.8,false));
        ok(ungrounded.items().stream().filter(item->!item.type().equals("SOURCE_CARD")).allMatch(item->item.data().containsKey("segmentIndex")),
                "narration timing still retains segment references without optional grounded prompting");

        VisualPlan anchored=new VisualPlan("anchored",List.of(
                new VisualPlan.Item(0,"HOOK","Headline","First fact",2,"",Map.of("segmentIndex",0)),
                new VisualPlan.Item(1,"POLITICAL_CONTEXT","Context","",1,"",Map.of("segmentIndex",0,"supplemental",true,"supplementFraction",.25)),
                new VisualPlan.Item(2,"EDITORIAL_PHOTO","Detail","Second fact",2,"",Map.of("segmentIndex",1)),
                new VisualPlan.Item(3,"SOURCE_CARD","Sources","Trusted Wire",1,"",
                        Map.of("segmentIndex",1,"supplemental",true,"supplementFraction",.18))));
        Storyboard anchoredTimeline=new VisualDirector().direct(anchored,script,exact,5.0);
        double firstBoundary=anchoredTimeline.scenes().get(1).end();
        ok(Math.abs(firstBoundary-exact.segmentDurations().get(0))<.01 &&
                Math.abs(anchoredTimeline.scenes().get(3).end()-5)<.01,
                "political and source context are contained within original narration segment windows");

        Storyboard storyboard=new VisualDirector().direct(plan,script,exact,5.0);
        storyboard.validate();
        ok(Boolean.FALSE.equals(BroadcastLayout.review(storyboard,1080,1920,false).get("captionEnabled")),
                "captions-off mode is explicitly reflected in visual layout validation");
        ok("passed".equals(BroadcastLayout.review(storyboard,1080,1920,true).get("status"))&&
                "passed".equals(BroadcastLayout.review(storyboard,540,960,true).get("status")),
                "shared caption safety geometry prevents overlap with fixed lower-thirds");
        ok(BroadcastLayout.postTextSafeFooter(1920)==220&&
                        BroadcastLayout.postTextSafeFooter(960)==110&&
                        BroadcastLayout.tickerTop(1920)==1620,
                "TikTok caption clearance reserves exactly 220 scaled pixels at the bottom");
        ok(BroadcastLayout.captionArea(1080,1920).y+BroadcastLayout.captionArea(1080,1920).height
                        <BroadcastLayout.lowerThird("HOOK",1080,1920).y &&
                        "passed".equals(BroadcastLayout.review(storyboard,1080,1920,true,true).get("status")),
                "narration captions clear the shifted enhanced and classic hook panels");
        ok(storyboard.scenes().size()==2&&storyboard.scenes().get(0).start()==0.0&&
                        Math.abs(storyboard.scenes().get(1).end()-5.0)<.001,
                "typed storyboard covers actual narration duration with no gap");
        Path ass=dir.resolve("caption.ass");
        CaptionWriter.write(ass,narration,5.0,"sentence",sidecar,540,960);
        String captions=Files.readString(ass);
        ok(captions.contains("projects.")&&captions.contains("PlayResX: 540")&&captions.contains("PlayResY: 960")&&
                        !captions.contains("Dialogue: 0,0:00:05.00,0:00:05.00"),
                "captions use requested output geometry and skip zero-length events");
        BufferedImage flat=new BufferedImage(640,1136,BufferedImage.TYPE_INT_RGB);
        Path blank=dir.resolve("blank.png");ImageIO.write(flat,"png",blank.toFile());
        ImageQualityInspector inspector=new ImageQualityInspector();
        ok(!inspector.inspect(blank,640,1136,List.of()).accepted(),
                "deterministic visual inspection rejects blank images");
        BufferedImage stripes=new BufferedImage(640,1136,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=stripes.createGraphics();
        for(int y=0;y<1136;y+=142){g.setColor((y/142)%2==0?java.awt.Color.WHITE:java.awt.Color.BLACK);g.fillRect(0,y,640,142);}
        g.dispose();
        Path image=dir.resolve("stripes.png");ImageIO.write(stripes,"png",image.toFile());
        ImageQualityInspector.Result valid=inspector.inspect(image,640,1136,List.of());
        ok(valid.accepted()&&!inspector.inspect(image,640,1136,List.of(valid.perceptualHash())).accepted(),
                "valid images pass basic inspection and perceptually repeated images are rejected");
        OllamaVisionReviewer.Result unconfiguredVision=
                new OllamaVisionReviewer(dir,"","").review(image,"Council facility",List.of("council"),List.of("fire"));
        ok(!unconfiguredVision.evaluated()&&unconfiguredVision.accepted(),
                "optional vision review is disabled by default and never adds mandatory GPU work");
        CardRenderer classic=new ClassicCardRenderer();
        Path classicFrame=dir.resolve("classic.png");
        classic.render(plan.items().get(0),classicFrame,540,960);
        ok(ImageIO.read(classicFrame.toFile()).getHeight()==960,
                "classic broadcast package preserves V4 rendering fallback");
        Path classicOverlay=dir.resolve("classic_overlay.png");
        classic.renderOverlay(plan.items().get(0),classicOverlay,540,960);
        BufferedImage classicWithDisclosure=ImageIO.read(classicOverlay.toFile());
        ok(classicWithDisclosure.getColorModel().hasAlpha() &&
                classicWithDisclosure.getRGB(405,78)!=0,
                "classic fallback retains an on-screen AI illustration disclosure");
        CardRenderer cards=new CardRenderer();
        Path graphic=dir.resolve("statistic.png");cards.render(plan.items().get(1),graphic,540,960);
        ok(Files.isRegularFile(graphic)&&ImageIO.read(graphic.toFile()).getHeight()==960,
                "V5 statistic card renders a responsive verified-content layout");
        Path bgGraphic=dir.resolve("stat-base.png"),overlayGraphic=dir.resolve("stat-reveal.png");
        cards.renderGraphicLayers(graphic,bgGraphic,overlayGraphic,540,960);
        BufferedImage graphicOverlay=ImageIO.read(overlayGraphic.toFile());
        ok(graphicOverlay.getColorModel().hasAlpha()&&
                ImageIO.read(bgGraphic.toFile()).getWidth()==540&&
                graphicOverlay.getRGB(50,165)!=0,
                "statistic and context graphics can reveal accurate content over a lightweight base");
        Path background=dir.resolve("normalized.png"),overlay=dir.resolve("overlay.png"),preview=dir.resolve("preview.png");
        cards.renderBackground(plan.items().get(0),image,background,540,960);
        cards.renderOverlay(plan.items().get(0),overlay,540,960);
        cards.renderWithImage(plan.items().get(0),image,preview,540,960);
        BufferedImage normalized=ImageIO.read(background.toFile()),full=ImageIO.read(preview.toFile());
        ok(normalized.getHeight()==full.getHeight()&&normalized.getWidth()==full.getWidth(),
                "static previews and FFmpeg backgrounds share the same output crop geometry");
        int footerRgb=BroadcastTheme.NAVY.getRGB()&0xFFFFFF;
        BufferedImage renderedOverlay=ImageIO.read(overlay.toFile());
        ok((full.getRGB(250,930)&0xFFFFFF)==footerRgb &&
                        (renderedOverlay.getRGB(250,930)&0xFFFFFF)==footerRgb &&
                        ((renderedOverlay.getRGB(250,930)>>>24)&255)==255,
                "still preview and transparent motion overlay use the same opaque navy footer");
        ok((full.getRGB(5,25)&0xFFFFFF)!=(normalized.getRGB(5,25)&0xFFFFFF),
                "original broadcast header remains at top edge, independent of scaling");
        Path longAss=dir.resolve("long.ass");
        CaptionWriter.write(longAss,
            "Archæological-investigation. Consequences for extradition documentation remain unusually long but remain understandable without hiding the end of the sentence.",
            8.0,"sentence",null,540,960);
        String longOutput=Files.readString(longAss);
        ok(longOutput.contains("Dialogue:")&&longOutput.contains("\\N")&&
                longOutput.contains("sentence."),
                "caption writer groups long proper nouns into bounded two-line dialogue events");
        Path hostile=dir.resolve("hostile.ass");
        CaptionWriter.write(hostile,"This contains {\\rInjected} formatting.",3,"sentence",null,540,960);
        ok(!Files.readString(hostile).contains("{\\rInjected}"),
                "caption generation escapes script-authored ASS overrides");
    }

    private void testComfyMock()throws Exception{
        Path root=Files.createTempDirectory("autonews-comfy-mock-");
        BufferedImage fixture=new BufferedImage(512,896,BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g=fixture.createGraphics();
        for(int y=0;y<896;y++){g.setColor(new java.awt.Color(y%255,(y/2)%255,80));
            g.drawLine(0,y,511,y);}
        g.dispose();
        java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream();
        ImageIO.write(fixture,"png",buf);
        byte[]imageBytes=buf.toByteArray();
        AtomicInteger submitted=new AtomicInteger(),freed=new AtomicInteger(),released=new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong capturedSeed=new java.util.concurrent.atomic.AtomicLong(-1);
        HttpServer mock=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        mock.createContext("/system_stats",exchange->{
            byte[]response="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        mock.createContext("/release-gpu",exchange->{
            released.incrementAndGet();
            exchange.sendResponseHeaders(200,-1);exchange.close();
        });
        mock.createContext("/object_info/CheckpointLoaderSimple",exchange->{
            byte[]response="{\"input\":{\"required\":{\"ckpt_name\":[[\"fixture.safetensors\"]]}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        mock.createContext("/prompt",exchange->{
            Map<String,Object>request=Json.object(Json.parse(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));
            Map<String,Object>ksampler=Json.object(Json.object(request.get("prompt")).get("5"));
            capturedSeed.set(((Number)Json.object(ksampler.get("inputs")).get("seed")).longValue());
            submitted.incrementAndGet();
            byte[]response="{\"prompt_id\":\"fixture-id\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        mock.createContext("/history/fixture-id",exchange->{
            byte[]response="{\"fixture-id\":{\"outputs\":{\"7\":{\"images\":[{\"filename\":\"mock.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        mock.createContext("/view",exchange->{
            exchange.getResponseHeaders().add("Content-Type","image/png");
            exchange.sendResponseHeaders(200,imageBytes.length);
            try(var out=exchange.getResponseBody()){out.write(imageBytes);}
        });
        mock.createContext("/free",exchange->{freed.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        mock.start();
        try{
            String url="http://127.0.0.1:"+mock.getAddress().getPort();
            ComfyImageGenerator generator=new ComfyImageGenerator(root,url,url);
            ok(generator.reachable()&&generator.checkpoints().contains("fixture.safetensors"),
                    "ComfyUI mocked endpoint can discover a configured checkpoint");
            Path generated=root.resolve("visuals/fixture.png");
            generator.generate("verified editorial subject","exclude unsupported drama",
                    "fixture.safetensors",generated,512,896,20,5.0,876543210L);
            ok(Files.size(generated)>1024&&ImageIO.read(generated.toFile())!=null,
                    "ComfyUI mock generation saves a decodable output image");
            ok(capturedSeed.get()==876543210L&&submitted.get()==1&&released.get()==1&&freed.get()==0,
                    "ComfyUI preserves caller seed, releases Qwen and does not unload warm checkpoints on success");
        }finally{mock.stop(0);}
    }

    private void testWorthyHourFilterContract(Path root)throws Exception{
        String html=Files.readString(root.resolve("web/command-center/index.html"));
        String js=Files.readString(root.resolve("web/command-center/app.js"));
        ok(html.contains("id=\"storyAgeFilter\"")&&html.contains("AGE: LAST 1H")&&html.contains("AGE: LAST 72H"),
                "dashboard exposes story publication-age presets");
        ok(js.contains("autonewsStoryAgeHours")&&js.contains("latestPublishedAt")&&js.contains("withinStoryAge")&&
                        js.contains("storyAgeLimitHours"),
                "dashboard story age filter persists and filters by latest publication time");
    }

    private void testFeedCardIdentityContract(Path root)throws Exception{
        String js=Files.readString(root.resolve("web/command-center/app.js"));
        String css=Files.readString(root.resolve("web/command-center/styles.css"));
        ok(js.contains("feedEndpointLabel")&&js.contains("feedCategoryClass")&&js.contains("feedCategory")&&js.contains("feedEndpoint"),
                "dashboard feed cards expose publisher, category, and endpoint identity");
        ok(css.contains(".feedCategory")&&css.contains(".feedPublisher")&&css.contains(".feedMeta")&&
                        css.contains(".feed.ok .lamp")&&css.contains(".feed.failed .lamp"),
                "dashboard labels RSS categories in text and reserves feed lamp colors for connection status");
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
        // Deterministic fallback has only two beats; create a third fixture
        // segment so the repair contract can exercise reported segment index 2.
        List<NewsScript.Segment>repairSegments=new ArrayList<>(s.segments());
        NewsScript.Segment source=s.segments().get(1);
        repairSegments.add(new NewsScript.Segment(2,source.narration(),source.purpose(),
                source.visualType(),source.visualPrompt(),source.durationTarget()));
        NewsScript repairBase=new NewsScript(s.storyId(),s.headline(),
                s.narration()+" "+source.narration(),repairSegments,s.estimatedDuration(),
                s.sourceLabels(),s.hook());
        NewsScriptGenerator.RepairTarget target=new NewsScriptGenerator.RepairTarget(
                2,26,30,28,repairBase.segments().get(2).narration());
        String tooShort=Json.stringify(Map.of("replacements",List.of(
                Map.of("segmentIndex",2,"narration","grounded ".repeat(21).strip()))));
        boolean rejected=false;
        try{NewsScriptGenerator.applyReplacements(repairBase,tooShort,fp,60,List.of(target));}
        catch(IllegalArgumentException lengthError){
            rejected=lengthError.getMessage().contains("Repair segment 2 returned 21 words; required 26-30");
        }
        ok(rejected,"standard repair rejects 21-word segment when 26-30 words are required");
        String corrected=Json.stringify(Map.of("replacements",List.of(
                Map.of("segmentIndex",2,"narration","grounded ".repeat(28).strip()))));
        NewsScript fixed=NewsScriptGenerator.applyReplacements(repairBase,corrected,fp,60,List.of(target));
        ok(autonewsroller.util.Text.words(fixed.segments().get(2).narration())==28
                &&fixed.segments().get(0).narration().equals(repairBase.segments().get(0).narration())
                &&fixed.segments().get(1).narration().equals(repairBase.segments().get(1).narration()),
                "standard targeted repair accepts corrected length and keeps the hook and unrelated segments");
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
