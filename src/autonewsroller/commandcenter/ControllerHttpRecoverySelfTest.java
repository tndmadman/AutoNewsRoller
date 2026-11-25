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
            check(Boolean.FALSE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],Map.of()).body())).get("automaticSafetyScanningEnabled")),"tokenless OFF persisted");
            check(request(http,uri,"POST",on,json).statusCode()==200,"local controller toggle ON needs no token");
        }finally{server.stop();}
    }

    private static void masterAliasSettingRegression()throws Exception{
        Path root=Files.createTempDirectory("anr-master-alias-setting-");
        String id="master-alias-fixture";
        Path statePath=root.resolve("data/command_center_state.json");
        Json.write(statePath,Map.of("stories",Map.of(id,
            Map.of("id",id,"status","QUEUED","stage","QUEUED","topic","Synthetic fixture","productionMode","STANDARD","score",0.95)),"videos",Map.of()));
        NewsConfig cfg=NewsConfig.load(root);
        CommandCenterServer server=new CommandCenterServer(root,cfg,"127.0.0.1",0,600,false,0.68,5,"worker-token",false);
        HttpClient http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.start();
        byte[]off=Json.stringify(Map.of("enabled",false)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[]on=Json.stringify(Map.of("enabled",true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Map<String,String>auth=Map.of("X-AutoNews-Token","worker-token","Content-Type","application/json");
        Map<String,String>worker=Map.of("X-AutoNews-Token","worker-token");
        String claimId,epoch;
        try{
            URI legacy=URI.create(base+"/api/settings/content-safety");
            URI master=URI.create(base+"/api/settings/automatic-safety");
            URI image=URI.create(base+"/api/settings/image-safety");
            check(Boolean.TRUE.equals(Json.object(Json.parse(request(http,master,"GET",new byte[0],worker).body())).get("automaticSafetyScanningEnabled")),"master defaults on");
            check(request(http,legacy,"POST",off,Map.of("Content-Type","application/json")).statusCode()==401,"existing controller authentication still required");
            check(request(http,legacy,"GET",new byte[0],Map.of()).statusCode()==401,"anonymous settings access denied");
            check(request(http,legacy,"POST",off,worker).statusCode()==415,"non-JSON setting request rejected");
            check(request(http,legacy,"POST",off,auth).statusCode()==200,"legacy URL updates master only");
            for(URI uri:List.of(legacy,master,image)){
                Map<String,Object>settings=Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body()));
                check(Boolean.FALSE.equals(settings.get("automaticSafetyScanningEnabled"))
                    &&Boolean.FALSE.equals(settings.get("imageSafetyScanningEnabled"))
                    &&Boolean.TRUE.equals(settings.get("contentSafetyScanningEnabled")),"setting aliases agree and normal route remains enabled");
            }
            check(request(http,URI.create(base+"/api/jobs/claim?worker=old-worker"),"GET",new byte[0],worker).statusCode()==204,"old workers cannot claim master-OFF jobs");
            byte[]heartbeat=Json.stringify(Map.of("id","updated-worker","capabilities",List.of("automatic_safety_setting_v2","local_draft_v1"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            check(request(http,URI.create(base+"/api/workers/heartbeat"),"POST",heartbeat,auth).statusCode()==200,"compatible worker registers");
            HttpResponse<String>claim=request(http,URI.create(base+"/api/jobs/claim?worker=updated-worker"),"GET",new byte[0],worker);
            check(claim.statusCode()==200,"master-OFF claim uses ordinary route");
            Map<String,Object>job=Json.object(Json.parse(claim.body()));
            claimId=String.valueOf(job.get("claimId"));epoch=String.valueOf(job.get("productionEpoch"));
            Map<String,Object>settings=Json.object(job.get("settings"));
            check(Boolean.FALSE.equals(settings.get("automaticSafetyScanningEnabled"))
                &&Boolean.TRUE.equals(settings.get("contentSafetyScanningEnabled")),"claim snapshots master OFF without draft route");
            check(request(http,image,"POST",on,auth).statusCode()==200,"image alias restores master ON");
            Map<String,Object>disk=Json.object(Json.read(statePath));
            Map<String,Object>saved=Json.object(Json.object(disk.get("stories")).get(id));
            check(Boolean.TRUE.equals(disk.get("automaticSafetyScanningEnabled"))
                &&Boolean.FALSE.equals(saved.get("claimAutomaticSafetyScanningEnabled")),"global change leaves active claim unchanged");
            check(request(http,master,"POST",off,auth).statusCode()==200,"master OFF persisted for restart");
        }finally{server.stop();}
        CommandCenterServer restarted=new CommandCenterServer(root,cfg,"127.0.0.1",0,600,false,0.68,5,"worker-token",false);
        try{
            String url="http://127.0.0.1:"+restarted.start();
            URI uri=URI.create(url+"/api/settings/automatic-safety");
            check(Boolean.FALSE.equals(Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("automaticSafetyScanningEnabled")),"master OFF survives restart");
            Map<String,Object>saved=Json.object(Json.object(Json.object(Json.read(statePath)).get("stories")).get(id));
            check(claimId.equals(saved.get("claimId"))&&epoch.equals(saved.get("productionEpoch"))
                &&Boolean.FALSE.equals(saved.get("claimAutomaticSafetyScanningEnabled")),"active claim snapshot survives restart");
            ExecutorService racers=Executors.newFixedThreadPool(2);
            try{
                Future<HttpResponse<String>>first=racers.submit(()->request(http,URI.create(url+"/api/settings/content-safety"),"POST",on,auth));
                Future<HttpResponse<String>>second=racers.submit(()->request(http,URI.create(url+"/api/settings/image-safety"),"POST",off,auth));
                check(first.get(15,TimeUnit.SECONDS).statusCode()==200&&second.get(15,TimeUnit.SECONDS).statusCode()==200,"concurrent aliases serialize");
                Object live=Json.object(Json.parse(request(http,uri,"GET",new byte[0],worker).body())).get("automaticSafetyScanningEnabled");
                Object disk=Json.object(Json.read(statePath)).get("automaticSafetyScanningEnabled");
                check(Objects.equals(live,disk),"master state matches persisted setting");
                check(request(http,uri,"POST",on,auth).statusCode()==200,"master restored ON");
            }finally{racers.shutdownNow();}
        }finally{restarted.stop();}
    }

    private static void imageOnlySettingRegression()throws Exception{
        Path root=Files.createTempDirectory("anr-image-only-setting-");
        String id="image-scan-fixture";
        Json.write(root.resolve("data/command_center_state.json"),Map.of("contentSafetyScanningEnabled",false,
                "stories",Map.of(id,Map.of("id",id,"status","QUEUED","stage","QUEUED","topic","Image fixture","productionMode","STANDARD","score",0.95)),"videos",Map.of()));
        NewsConfig cfg=NewsConfig.load(root);
        CommandCenterServer server=new CommandCenterServer(root,cfg,"127.0.0.1",0,600,false,0.68,5,"",false);
        HttpClient http=HttpClient.newHttpClient();
        String base="http://127.0.0.1:"+server.start();
        URI uri=URI.create(base+"/api/settings/image-safety");
        Map<String,String>json=Map.of("Content-Type","application/json");
        try{
            Map<String,Object> migrated=Json.object(Json.parse(request(http,uri,"GET",new byte[0],Map.of()).body()));
            check(Boolean.FALSE.equals(migrated.get("imageSafetyScanningEnabled"))&&Boolean.TRUE.equals(migrated.get("contentSafetyScanningEnabled")),"old OFF migrates to image-only OFF; final scan remains on");
            byte[]off=Json.stringify(Map.of("enabled",false)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            check(request(http,uri,"POST",off,json).statusCode()==200,"image-only OFF accepted without draft warning");
            check(request(http,URI.create(base+"/api/jobs/claim?worker=old-worker"),"GET",new byte[0],Map.of()).statusCode()==204,"old worker cannot claim automatic scans OFF");
            byte[]heartbeat=Json.stringify(Map.of("id","image-worker","capabilities",List.of("automatic_safety_setting_v2"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            check(request(http,URI.create(base+"/api/workers/heartbeat"),"POST",heartbeat,json).statusCode()==200,"master toggle compatible worker registers");
            HttpResponse<String>claim=request(http,URI.create(base+"/api/jobs/claim?worker=image-worker"),"GET",new byte[0],Map.of());
            check(claim.statusCode()==200,"image-only OFF permits normal video claim");
            Map<String,Object>job=Json.object(Json.parse(claim.body()));Map<String,Object>settings=Json.object(job.get("settings"));
            check(Boolean.FALSE.equals(settings.get("imageSafetyScanningEnabled"))&&Boolean.FALSE.equals(settings.get("automaticSafetyScanningEnabled"))&&Boolean.TRUE.equals(settings.get("contentSafetyScanningEnabled")),"master OFF disables all detector scans and preserves normal route");
            Map<String,String>headers=new LinkedHashMap<>();headers.put("X-Worker-Id","image-worker");headers.put("X-Claim-Id",String.valueOf(job.get("claimId")));headers.put("X-Production-Epoch",String.valueOf(job.get("productionEpoch")));headers.put("X-Media-Sha256","4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a");
            check(request(http,URI.create(base+"/api/jobs/"+id+"/local-draft-video"),"PUT",new byte[]{1},headers).statusCode()==409,"image-only claim cannot use isolated draft upload");
            check(request(http,URI.create(base+"/api/jobs/"+id+"/video"),"PUT",new byte[]{1},headers).statusCode()==200,"image-only claim uses normal upload staging");
            Path image=root.resolve("fixture.png");Files.write(image,new byte[]{1});
            Map<String,Object>deferred=new ContentSafetyService(root,cfg).inspectImageForRender(image,false);
            check("DEFERRED_TO_FINAL_VIDEO".equals(deferred.get("status"))&&ContentSafetyService.eligibleImageForRender(deferred,false)&&!ContentSafetyService.eligibleImageForRender(deferred,true),"deferred image never represented as SAFE");
            check(!ContentSafetyService.eligibleImageForRender(Map.of("status","UNSAFE"),false),"known unsafe verdict never accepted");
            Map<String,Object>skipped=new ContentSafetyService(root,cfg).inspectImageForRender(image,false,false);
            check("NOT_SCANNED".equals(skipped.get("status"))&&ContentSafetyService.eligibleImageForRender(skipped,false,false)
                &&!ContentSafetyService.eligibleImageForRender(skipped,true,false)&&!ContentSafetyService.eligibleImageForRender(skipped,false,true),"master OFF records actual skipped scan without false approval");
            Map<String,Object>record=ContentSafetyService.unscanned(image,"CONTROLLER_UNSCANNED_ARCHIVE");
            ContentSafetyService.verifyUnscanned(image,record,"CONTROLLER_UNSCANNED_ARCHIVE");
            boolean denied=false;try{ContentSafetyService.verify(image,record,false);}catch(Exception expected){denied=true;}
            check(denied,"unscanned completion record cannot pass export approval");
            HttpResponse<String>health=request(http,URI.create(base+"/api/safety/health"),"GET",new byte[0],Map.of());
            check(health.statusCode()==200&&"DISABLED_AUTOMATIC_SCANS".equals(Json.object(Json.parse(health.body())).get("status")),"master OFF skips automatic detector health probes");
        }finally{server.stop();}
    }

    /** Synthetic persisted archive fixture; tests identity/access routing, not video decoding. */
    private static void currentArchiveReviewRegression()throws Exception{
        Path root=Files.createTempDirectory("anr-current-review-").toRealPath();
        Path dir=Files.createDirectories(root.resolve("output/command_center/videos"));
        String id="review-fixture",oldId=id+"#v1",newId=id+"#v2";
        Path old=dir.resolve("older.mp4"),current=dir.resolve("newer.mp4");
        Files.writeString(old,"old benign fixture");Files.writeString(current,"new benign fixture");
        Map<String,Object>record=ContentSafetyService.unscanned(current,"CONTROLLER_UNSCANNED_ARCHIVE");
        record.put("controllerEncodedAudit",Map.of("status","approved"));
        Map<String,Object>oldRecord=ContentSafetyService.unscanned(old,"CONTROLLER_UNSCANNED_ARCHIVE");
        oldRecord.put("controllerEncodedAudit",Map.of("status","approved"));
        Map<String,Object>older=Map.of("versionId",oldId,"storyId",id,"version",1,"productionMode","STANDARD",
                "filename","older.mp4","path",old.toString(),"safetyStatus","NOT_SCANNED","safetyManifest",oldRecord);
        Map<String,Object>newer=Map.of("versionId",newId,"storyId",id,"version",2,"productionMode","STANDARD",
                "filename","newer.mp4","path",current.toString(),"safetyStatus","NOT_SCANNED","safetyManifest",record);
        Map<String,Object>story=Map.of("id",id,"status","COMPLETE","topic","Review fixture","productionMode","STANDARD",
                "localDraft",Map.of("productionMode","STANDARD","hostPath","historical-draft.mp4"),"currentVideoVersionId",newId);
        Json.write(root.resolve("data/command_center_state.json"),Map.of("stories",Map.of(id,story),"videos",Map.of(oldId,older,newId,newer)));
        CommandCenterServer server=new CommandCenterServer(root,NewsConfig.load(root),"127.0.0.1",0,600,false,0.68,5,"review-token",false);
        HttpClient http=HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.start();
        Map<String,String>auth=Map.of("X-AutoNews-Token","review-token");
        URI review=URI.create(base+"/api/review-video?versionId="+id+"%23v2");
        try{
            check(request(http,review,"GET",new byte[0],Map.of()).statusCode()==401,"private review requires existing authentication");
            HttpResponse<String>response=request(http,review,"GET",new byte[0],auth);
            check(response.statusCode()==200&&"new benign fixture".equals(response.body()),"review resolves exact current archive bytes");
            check("NOT_SCANNED".equals(response.headers().firstValue("X-AutoNews-Safety-Status").orElse("")),"review retains unscanned identity");
            check(request(http,URI.create(base+"/api/review-video?versionId="+id+"%23v1"),"GET",new byte[0],auth).statusCode()==404,"superseded version cannot be served by current review");
            check(request(http,URI.create(base+"/api/review-video?versionId=staged"),"GET",new byte[0],auth).statusCode()==404,"unarchived media cannot be reviewed");
            check(request(http,URI.create(base+"/videos/newer.mp4"),"GET",new byte[0],auth).statusCode()==409,"publishable endpoint still rejects unapproved media");
            Map<String,Object>board=Json.object(Json.parse(request(http,URI.create(base+"/api/board/stories"),"GET",new byte[0],auth).body()));
            List<?>rows=(List<?>)board.get("stories");Map<String,Object>row=Json.object(rows.get(0));
            check(newId.equals(row.get("currentVideoVersionId"))&&"newer.mp4".equals(row.get("videoFilename"))
                    &&!row.containsKey("localDraft"),"current board ignores historical draft without deleting it");
            check(Boolean.FALSE.equals(row.get("publicationApproved"))&&"NOT_SCANNED".equals(row.get("safetyStatus")),"board does not imply publication approval");
            Map<String,Object>unscanned=Json.object(Json.parse(request(http,URI.create(base+"/api/board/stories?status=UNSCANNED"),"GET",new byte[0],auth).body()));
            List<?>unscannedRows=(List<?>)unscanned.get("stories");
            check(unscannedRows.size()==1&&newId.equals(Json.object(unscannedRows.get(0)).get("currentVideoVersionId")),"UNSCANNED lists the current completed unscanned version");
            check(((Number)Json.object(unscanned.get("facets")).get("UNSCANNED")).intValue()==1,"UNSCANNED facet counts current review entries");
            Map<String,Object>toPost=Json.object(Json.parse(request(http,URI.create(base+"/api/board/stories?status=TO_POST"),"GET",new byte[0],auth).body()));
            check(((List<?>)toPost.get("stories")).isEmpty(),"unscanned review entries do not appear in TO POST");
            Files.writeString(current,"changed");
            check(request(http,review,"GET",new byte[0],auth).statusCode()==409,"tampered archive denied");
            Files.delete(current);
            check(request(http,review,"GET",new byte[0],auth).statusCode()==409,"missing archive denied");
        }finally{server.stop();}
    }

    public static int run()throws Exception{
        count=0;
        currentArchiveReviewRegression();
        masterAliasSettingRegression();
        imageOnlySettingRegression();
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
