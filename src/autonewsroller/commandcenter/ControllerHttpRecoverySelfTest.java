package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.safety.ContentSafetyService;
import autonewsroller.util.Json;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Local HTTP upload fault test. The payload is a synthetic byte fixture,
 * not a detector-approved MP4. No publisher, GPU, or real controller is used.
 */
public final class ControllerHttpRecoverySelfTest {
    private static int count;
    private ControllerHttpRecoverySelfTest(){}
    private static void check(boolean ok,String explanation){
        if(!ok)throw new AssertionError(explanation);
        count++;System.out.println("PASS controller-http: "+explanation);
    }
    private static HttpResponse<String> request(HttpClient http,URI target,String method,
            byte[]body,Map<String,String>headers)throws Exception{
        HttpRequest.Builder builder=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if("GET".equals(method))builder.GET();
        else builder.method(method,HttpRequest.BodyPublishers.ofByteArray(body));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static long mediaCount(Path root)throws Exception{
        Path dir=root.resolve("output/command_center/videos");
        if(!Files.isDirectory(dir))return 0;
        try(var entries=Files.list(dir)){
            return entries.filter(p->p.getFileName().toString().endsWith(".mp4")).count();
        }
    }
    public static void main(String[]args)throws Exception{System.out.println("Passed: "+run());}
    private static void tokenlessLocalSafetySettingRegression()throws Exception{
        Path root=Files.createTempDirectory("anr-local-toggle-no-token-");
        CommandCenterServer server=new CommandCenterServer(root,NewsConfig.load(root),
                "127.0.0.1",0,600,false,0.68,5,"",false);
        HttpClient http=HttpClient.newHttpClient();
        URI uri=URI.create("http://127.0.0.1:"+server.start()+"/api/settings/content-safety");
        Map<String,String>json=Map.of("Content-Type","application/json");
        byte[]off=Json.stringify(Map.of("enabled",false,"confirmLocalOnly",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[]on=Json.stringify(Map.of("enabled",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try{
            check(request(http,uri,"GET",new byte[0],Map.of()).statusCode()==200,"local setting readable without token");
            check(request(http,uri,"POST",off,Map.of("Content-Type","text/plain")).statusCode()==415,"tokenless cross-site simple request rejected");
            check(request(http,uri,"POST",off,json).statusCode()==200,"local controller toggle OFF needs no token");
            check(Boolean.FALSE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],Map.of()).body())).get("contentSafetyScanningEnabled")),"tokenless OFF persisted");
            check(request(http,uri,"POST",on,json).statusCode()==200,"local controller toggle ON needs no token");
        }finally{server.stop();}
    }

    private static void localDraftSettingRegression()throws Exception{
        Path root=Files.createTempDirectory("anr-local-draft-setting-");
        String id="local-draft-fixture";
        Json.write(root.resolve("data/command_center_state.json"),Map.of("stories",Map.of(id,
            Map.of("id",id,"status","QUEUED","stage","QUEUED","topic","Synthetic fixture","productionMode","STANDARD","score",0.95)),"videos",Map.of()));
        NewsConfig cfg=NewsConfig.load(root);
        CommandCenterServer server=new CommandCenterServer(root,cfg,"127.0.0.1",0,600,false,0.68,5,"worker-token",false);
        HttpClient http=HttpClient.newHttpClient();
        String base="http://127.0.0.1:"+server.start();
        byte[]off=Json.stringify(Map.of("enabled",false,"confirmLocalOnly",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,String>worker=Map.of("X-AutoNews-Token","worker-token");
        Map<String,String>authorized=Map.of("X-AutoNews-Token","worker-token","Content-Type","application/json");
        try{
            URI uri=URI.create(base+"/api/settings/content-safety");
            check(Boolean.TRUE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("contentSafetyScanningEnabled")),"scanning defaults on");
            check(request(http,uri,"POST",off,Map.of("Content-Type","application/json")).statusCode()==401,"existing controller authentication still required");
            check(request(http,uri,"POST",off,worker).statusCode()==415,"non-JSON setting request rejected");
            check(request(http,uri,"POST",Json.stringify(Map.of("enabled",false)).getBytes(java.nio.charset.StandardCharsets.UTF_8),authorized).statusCode()==400,"local-only warning confirmation required");
            check(request(http,uri,"GET",new byte[0],Map.of()).statusCode()==401,"anonymous settings access denied");
            check(request(http,uri,"POST",off,authorized).statusCode()==200,"existing controller token can select local-only drafts without another secret");
            HttpResponse<String>health=request(http,URI.create(base+"/api/safety/health"),"GET",new byte[0],worker);
            check(health.statusCode()==200&&"DISABLED_LOCAL_ONLY".equals(Json.object(Json.parse(health.body())).get("status")),"disabled health does not probe missing scanners or GPU configuration");
            check(Boolean.FALSE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("contentSafetyScanningEnabled")),"updated setting visible without restart");
            byte[]heartbeat=Json.stringify(Map.of("id","updated-worker","capabilities",List.of("local_draft_v1"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpResponse<String>heartbeatResult=request(http,URI.create(base+"/api/workers/heartbeat"),"POST",heartbeat,worker);
            check(heartbeatResult.statusCode()==200,"updated worker registers");
            check(Boolean.FALSE.equals(Json.object(Json.parse(heartbeatResult.body())).get("contentSafetyScanningEnabled")),"heartbeat reports current policy");
            check(request(http,URI.create(base+"/api/jobs/claim?worker=old-worker"),"GET",new byte[0],worker).statusCode()==204,"old worker cannot claim a local-only job");
            HttpResponse<String>claimed=request(http,URI.create(base+"/api/jobs/claim?worker=updated-worker"),"GET",new byte[0],worker);
            check(claimed.statusCode()==200,"updated worker can claim local draft");
            Map<String,Object>job=Json.object(Json.parse(claimed.body()));
            check(Boolean.FALSE.equals(Json.object(job.get("settings")).get("contentSafetyScanningEnabled")),"claim receives immutable local draft setting");
            byte[]done=Json.stringify(Map.of("workerId","updated-worker","claimId",job.get("claimId"),"productionEpoch",job.get("productionEpoch"),
                "localPath","fixture.mp4","videoSha256","a".repeat(64))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            URI local=URI.create(base+"/api/jobs/"+id+"/local-draft-complete");
            byte[]on=Json.stringify(Map.of("enabled",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            check(request(http,uri,"POST",on,authorized).statusCode()==200,"administrator can restore scanning while draft is active");
            Map<String,String>uploadHeaders=new LinkedHashMap<>(worker);
            uploadHeaders.put("X-Worker-Id","updated-worker");
            uploadHeaders.put("X-Claim-Id",String.valueOf(job.get("claimId")));
            uploadHeaders.put("X-Production-Epoch",String.valueOf(job.get("productionEpoch")));
            uploadHeaders.put("X-Media-Sha256","4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");
            check(request(http,URI.create(base+"/api/jobs/"+id+"/video"),"PUT",new byte[]{1},uploadHeaders).statusCode()==409,"local-only claim cannot upload even after scanning is restored");
            Map<String,Object>forged=new LinkedHashMap<>(Json.object(Json.parse(new String(done,java.nio.charset.StandardCharsets.UTF_8))));
            forged.put("dryRun",true);
            check(request(http,URI.create(base+"/api/jobs/"+id+"/complete"),"POST",Json.stringify(forged).getBytes(java.nio.charset.StandardCharsets.UTF_8),worker).statusCode()==409,"local-only claim cannot masquerade as normal completion");
            URI draftUpload=URI.create(base+"/api/jobs/"+id+"/local-draft-video");
            check(request(http,draftUpload,"PUT",new byte[]{2},uploadHeaders).statusCode()==409,"draft hash mismatch rejected");
            HttpResponse<String>stored=request(http,draftUpload,"PUT",new byte[]{1},uploadHeaders);
            check(stored.statusCode()==200,"isolated draft uploaded under claim snapshot");
            Map<String,Object>draft=Json.object(Json.parse(stored.body()));
            String filename=String.valueOf(draft.get("filename"));
            check(filename.startsWith("NOT_SCANNED_synthetic_fixture_"),"draft includes sanitized title");
            Path hostPath=Path.of(String.valueOf(draft.get("hostPath")));
            check(hostPath.getParent().equals(root.resolve("output/command_center/unscanned_drafts").toAbsolutePath().normalize())
                &&Files.isRegularFile(hostPath),"draft stored in separate host directory");
            check(request(http,draftUpload,"PUT",new byte[]{1},uploadHeaders).statusCode()==200,"draft upload replay idempotent");
            check(request(http,local,"POST",done,worker).statusCode()==409,"metadata-only replay cannot replace hosted completion");
            check(mediaCount(root)==0,"local draft does not enter controller media archive");
            check(request(http,uri,"POST",off,authorized).statusCode()==200,"local-only policy can be restored for restart test");
        }finally{server.stop();}
        CommandCenterServer restarted=new CommandCenterServer(root,cfg,"127.0.0.1",0,600,false,0.68,5,"worker-token",false);
        try{
            URI uri=URI.create("http://127.0.0.1:"+restarted.start()+"/api/settings/content-safety");
            check(Boolean.FALSE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("contentSafetyScanningEnabled")),"local-only selection persists");
            Map<String,Object>state=Json.object(Json.read(root.resolve("data/command_center_state.json")));
            check(state.get("safetySettingAudit") instanceof List<?> audit&&audit.size()>=3,"durable settings audit retained");
            Map<String,Object>saved=Json.object(Json.object(state.get("stories")).get(id));
            check("NOT_SCANNED".equals(Json.object(saved.get("localDraft")).get("status")),"local draft classification survives restart");
            byte[]on=Json.stringify(Map.of("enabled",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            check(request(http,uri,"POST",on,authorized).statusCode()==200,"administrator can restore mandatory scanning");
            ExecutorService settingsRacers=Executors.newFixedThreadPool(2);
            try{
                Future<HttpResponse<String>>first=settingsRacers.submit(()->request(http,uri,"POST",off,authorized));
                Future<HttpResponse<String>>second=settingsRacers.submit(()->request(http,uri,"POST",on,authorized));
                check(first.get(15,TimeUnit.SECONDS).statusCode()==200&&second.get(15,TimeUnit.SECONDS).statusCode()==200,"concurrent setting updates serialized");
                Object live=Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("contentSafetyScanningEnabled");
                Object disk=Json.object(Json.read(root.resolve("data/command_center_state.json"))).get("contentSafetyScanningEnabled");
                check(Objects.equals(live,disk),"concurrent final state matches durable state");
                check(request(http,uri,"POST",on,authorized).statusCode()==200,"scanning restored after concurrency test");
            }finally{settingsRacers.shutdownNow();}
        }finally{restarted.stop();}
    }

    public static int run()throws Exception{
        count=0;
        localDraftSettingRegression();
        tokenlessLocalSafetySettingRegression();
        autonewsroller.safety.LocalDraftInspection.selfTest();
        Path root=Files.createTempDirectory("anr-http-recovery-");
        Files.createDirectories(root.resolve("data"));
        String id="http-fixture";
        Map<String,Object>story=new LinkedHashMap<>(Map.of("id",id,"status","QUEUED",
                "stage","QUEUED","topic","Synthetic fixture",
                "productionMode","STANDARD","score",0.95));
        Json.write(root.resolve("data/command_center_state.json"),
                Map.of("stories",Map.of(id,story),"videos",Map.of()));
        NewsConfig cfg=NewsConfig.load(root);
        check(RemoteWorker.completionAckTimeout(cfg).toSeconds()>=
                    cfg.getInt("contentSafetyVideoTimeoutSeconds",3600)*2L+240L,
                "worker completion wait covers both mandatory controller scans");
        HttpClient http=HttpClient.newHttpClient();
        CommandCenterServer server=new CommandCenterServer(root,cfg,
                "127.0.0.1",0,600,false,0.68,5,"",false);
        int port=server.start();
        try{
            String base="http://127.0.0.1:"+port;
            HttpResponse<String>claimed=request(http,
                    URI.create(base+"/api/jobs/claim?worker=test-worker"),"GET",new byte[0],Map.of());
            check(claimed.statusCode()==200,"worker can claim isolated job over real HTTP");
            Map<String,Object>job=Json.object(Json.parse(claimed.body()));
            String claim=String.valueOf(job.get("claimId"));
            String epoch=String.valueOf(job.get("productionEpoch"));
            check(!claim.isBlank()&&!epoch.isBlank(),"claim has required stable identities");
            byte[]bytes="NOT_REAL_MEDIA_ONLY_HTTP_UPLOAD_REGRESSION".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Path source=root.resolve("synthetic-bytes.mp4");
            Files.write(source,bytes);
            String hash=ContentSafetyService.hash(source);
            URI video=URI.create(base+"/api/jobs/"+id+"/video");
            Map<String,String>headers=Map.of("X-Worker-Id","test-worker",
                    "X-Claim-Id",claim,"X-Production-Epoch",epoch,"X-Media-Sha256",hash);
            // Synchronize request starts so both uploads race for the same
            // initially absent destination, not merely two sequential retries.
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            ExecutorService racers=Executors.newFixedThreadPool(2);
            HttpResponse<String>first,concurrent;
            try{
                Callable<HttpResponse<String>> duplicate=()->{
                    ready.countDown();
                    if(!start.await(5,TimeUnit.SECONDS))
                        throw new IllegalStateException("Concurrent upload barrier timed out");
                    return request(http,video,"PUT",bytes,headers);
                };
                Future<HttpResponse<String>> one=racers.submit(duplicate);
                Future<HttpResponse<String>> two=racers.submit(duplicate);
                if(!ready.await(5,TimeUnit.SECONDS))
                    throw new IllegalStateException("Concurrent uploads did not start");
                start.countDown();
                first=one.get(15,TimeUnit.SECONDS);
                concurrent=two.get(15,TimeUnit.SECONDS);
            }finally{
                start.countDown();
                racers.shutdownNow();
            }
            check(first.statusCode()==200&&concurrent.statusCode()==200&&mediaCount(root)==1,
                    "concurrent identical uploads produce one durable media file");
            String filename=String.valueOf(Json.object(Json.parse(first.body())).get("filename"));
            check(filename.equals(Json.object(Json.parse(concurrent.body())).get("filename")),
                    "concurrent acknowledgements return one stable media identity");
            HttpResponse<String>duplicate=request(http,video,"PUT",bytes,headers);
            check(duplicate.statusCode()==200&&mediaCount(root)==1
                    &&filename.equals(Json.object(Json.parse(duplicate.body())).get("filename")),
                    "lost upload acknowledgement does not allocate another file");
            HttpResponse<String>partial=request(http,video,"PUT",
                    Arrays.copyOf(bytes,bytes.length/2),headers);
            check(partial.statusCode()>=400&&mediaCount(root)==1,
                    "truncated upload body cannot alter durable media");
            HttpResponse<String>tampered=request(http,video,"PUT","DIFFERENT".getBytes(),headers);
            check(tampered.statusCode()>=400&&mediaCount(root)==1,
                    "same upload identity with different bytes fails closed");
            Map<String,String>obsolete=new LinkedHashMap<>(headers);
            obsolete.put("X-Claim-Id","stale-claim");
            HttpResponse<String>stale=request(http,video,"PUT",bytes,obsolete);
            check(stale.statusCode()==409&&mediaCount(root)==1,
                    "obsolete worker claim cannot put bytes in current namespace");
            Map<String,String>legacy=new LinkedHashMap<>(headers);
            legacy.remove("X-Media-Sha256");
            HttpResponse<String>oldProtocol=request(http,video,"PUT",bytes,legacy);
            check(oldProtocol.statusCode()>=400&&mediaCount(root)==1,
                    "mixed-version workers without identity headers are rejected");
            Map<String,Object>forgedManifest=new LinkedHashMap<>();
            forgedManifest.put("status","APPROVED_FOR_EXPORT");
            forgedManifest.put("policyVersion",ContentSafetyService.POLICY);
            forgedManifest.put("videoSha256",hash);
            Map<String,Object>fakeFrames=new LinkedHashMap<>();
            fakeFrames.put("status","SAFE");fakeFrames.put("policyVersion",ContentSafetyService.POLICY);
            fakeFrames.put("sha256",hash);fakeFrames.put("framesDecoded",1);
            fakeFrames.put("framesInspected",1);fakeFrames.put("framesExpected",1);
            fakeFrames.put("disclosureVerified",true);fakeFrames.put("disclosureFramesVerified",1);
            fakeFrames.put("detector","NudeNet");fakeFrames.put("version","3.4.2");
            fakeFrames.put("threshold",0.25);
            fakeFrames.put("coverage","every decoded frame; full and three overlapping crops");
            fakeFrames.put("contextAudit",Map.of("status","SAFE","detector","OpenNSFW2",
                    "version","0.19.0","reviewThreshold",0.20,
                    "hardBlockThreshold",0.70,"framesDecoded",1,"framesInspected",1,
                    "framesExpected",1,
                    "coverage","every decoded frame; complete frame; upper/lower crops on still images"));
            forgedManifest.put("finalVideoAudit",fakeFrames);
            Map<String,Object>forgedCompletion=Map.of("workerId","test-worker",
                    "claimId",claim,"productionEpoch",epoch,
                    "sidecar",Map.of("productionMode","STANDARD",
                            "contentSafety",forgedManifest));
            HttpResponse<String>forgery=request(http,
                    URI.create(base+"/api/jobs/"+id+"/complete"),"POST",
                    Json.stringify(forgedCompletion).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Map.of("Content-Type","application/json"));
            check(forgery.statusCode()>=400,
                    "worker-forged SAFE manifest cannot complete without controller detectors");
            // A worker-shaped manifest is structurally valid, but it is not
            // evidence that a controller ever scanned the synthetic bytes.
            ContentSafetyService.verify(source,forgedManifest,false);
            Json.write(root.resolve("output/command_center/videos").resolve(filename+".json"),
                    Map.of("contentSafety",forgedManifest));
            HttpResponse<String>unapprovedMedia=request(http,
                    URI.create(base+"/videos/"+filename),"GET",new byte[0],Map.of());
            check(unapprovedMedia.statusCode()!=200,
                    "structurally valid worker-only sidecar cannot unlock media serving");
            Map<String,Object>state=Json.object(Json.read(root.resolve("data/command_center_state.json")));
            Map<String,Object>saved=Json.object(Json.object(state.get("stories")).get(id));
            check(hash.equals(saved.get("uploadedSha256"))&&claim.equals(saved.get("uploadedClaimId")),
                    "controller state on disk records upload identity before acknowledgement");
            check(!"COMPLETE".equals(saved.get("status")),
                    "failed independent approval did not commit a completed archive version");
        }finally{server.stop();}
        CommandCenterServer restarted=new CommandCenterServer(root,cfg,
                "127.0.0.1",0,600,false,0.68,5,"",false);
        int newPort=restarted.start();
        try{
            // The first claim was not completed; restart must recover accepted
            // upload identity, but must not publish synthetic bytes.
            HttpResponse<String>current=request(http,
                    URI.create("http://127.0.0.1:"+newPort+"/api/jobs/claim?worker=other"),
                    "GET",new byte[0],Map.of());
            check(current.statusCode()==204&&mediaCount(root)==1,
                    "controller restart preserves active lease and uploaded path");
        }finally{restarted.stop();}
        return count;
    }
}
