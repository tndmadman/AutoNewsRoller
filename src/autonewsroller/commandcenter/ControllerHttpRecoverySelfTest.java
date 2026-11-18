package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.safety.ContentSafetyService;
import autonewsroller.util.Json;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

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
    public static int run()throws Exception{
        count=0;
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
            HttpResponse<String>first=request(http,video,"PUT",bytes,headers);
            check(first.statusCode()==200&&mediaCount(root)==1,
                    "first correct upload creates exactly one MP4 namespace entry");
            String filename=String.valueOf(Json.object(Json.parse(first.body())).get("filename"));
            HttpResponse<String>duplicate=request(http,video,"PUT",bytes,headers);
            check(duplicate.statusCode()==200&&mediaCount(root)==1
                    &&filename.equals(Json.object(Json.parse(duplicate.body())).get("filename")),
                    "lost upload acknowledgement does not allocate another file");
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
