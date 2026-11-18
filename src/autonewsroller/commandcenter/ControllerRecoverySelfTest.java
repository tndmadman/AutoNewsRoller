package autonewsroller.commandcenter;

import autonewsroller.safety.ContentSafetyService;
import autonewsroller.util.Json;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Isolated controller state-machine tests. The video and approval manifest are
 * synthetic fixtures; these tests do NOT prove a detector scanned actual media.
 */
public final class ControllerRecoverySelfTest {
    private static int passed;
    private ControllerRecoverySelfTest(){}
    private static void check(boolean yes,String why){
        if(!yes)throw new AssertionError(why);
        passed++;System.out.println("PASS controller-recovery: "+why);
    }
    private static void blocked(Runnable call,String why){
        boolean rejected=false;
        try{call.run();}catch(IllegalStateException|IllegalArgumentException expected){rejected=true;}
        check(rejected,why);
    }
    private static Map<String,Object> completion(String worker,String claim,String epoch,Path file)throws Exception{
        String sha=ContentSafetyService.hash(file);
        Map<String,Object>frames=new LinkedHashMap<>();
        frames.put("status","SAFE");frames.put("policyVersion",ContentSafetyService.POLICY);
        frames.put("sha256",sha);frames.put("framesDecoded",1);frames.put("framesExpected",1);
        frames.put("framesInspected",1);frames.put("disclosureFramesVerified",1);
        frames.put("disclosureVerified",true);frames.put("detector","NudeNet");
        frames.put("version","3.4.2");frames.put("threshold",0.25);
        frames.put("coverage","every decoded frame; full and three overlapping crops");
        frames.put("contextAudit",Map.of("status","SAFE","detector","OpenNSFW2","version","0.19.0",
                "reviewThreshold",0.20,"hardBlockThreshold",0.70,"framesDecoded",1,
                "framesExpected",1,"framesInspected",1,
                "coverage","every decoded frame; complete frame; upper/lower crops on still images"));
        Map<String,Object>manifest=new LinkedHashMap<>();
        manifest.put("status","APPROVED_FOR_EXPORT");manifest.put("policyVersion",ContentSafetyService.POLICY);
        manifest.put("videoSha256",sha);manifest.put("finalVideoAudit",frames);
        manifest.put("inputs",List.of());
        return Map.of("workerId",worker,"claimId",claim,"productionEpoch",epoch,
                "sidecar",Map.of("productionMode","STANDARD","contentSafety",manifest));
    }
    private static CommandCenterStore store(Path state,Path dir){
        return new CommandCenterStore(state,BiasRegistry.load(dir.resolve("bias.json")),
                false,0.68,10,null,60);
    }
    private static int versions(CommandCenterStore store){
        return ((List<?>)store.snapshot().get("videos")).size();
    }
    public static void main(String[] args)throws Exception{System.out.println("Passed: "+run());}
    public static int run()throws Exception{
        passed=0;
        Path dir=Files.createTempDirectory("anr-recovery-fixture-");
        Path state=dir.resolve("state.json");
        String id="recovery-fixture";
        Map<String,Object>story=new LinkedHashMap<>(Map.of("id",id,"status","QUEUED",
                "stage","QUEUED","topic","Fixture","productionMode","STANDARD","score",0.92));
        Json.write(state,Map.of("stories",Map.of(id,story),"videos",Map.of()));
        CommandCenterStore store=store(state,dir);
        Map<String,Object>claimed=store.claim("worker-one",Map.of());
        String claim=String.valueOf(claimed.get("claimId"));
        String epoch=String.valueOf(claimed.get("productionEpoch"));
        check(!claim.isBlank()&&!epoch.isBlank(),"first claim and epoch assigned");
        check(store.claim("worker-two",Map.of())==null,
                "simultaneous second worker cannot claim an active leased job");
        Path mp4=dir.resolve("fixture.mp4");
        Files.writeString(mp4,"SYNTHETIC HASH-ONLY FIXTURE");
        String sha=ContentSafetyService.hash(mp4);
        blocked(()->store.requireActiveClaim(id,"worker-one",""),"blank claim rejected");
        blocked(()->store.authorizeVideoTransfer(id,"worker-two",claim,epoch,sha),
                "foreign worker cannot upload");
        blocked(()->store.authorizeVideoTransfer(id,"worker-one","stale",epoch,sha),
                "superseded claim cannot upload");
        store.recordVideoTransfer(id,mp4,"worker-one",claim,epoch,sha,Files.size(mp4));
        blocked(()->store.authorizeVideoTransfer(id,"worker-one",claim,epoch,"0".repeat(64)),
                "one claim cannot change a committed upload identity");
        CommandCenterStore restarted=store(state,dir);
        check(Boolean.FALSE.equals(restarted.authorizeVideoTransfer(id,"worker-one",claim,epoch,sha).get("completed")),
                "uploaded bytes recorded durably before completion");
        Map<String,Object>done=completion("worker-one",claim,epoch,mp4);
        check(restarted.completeAuthorized(id,done),"first completion committed");
        check(versions(restarted)==1&&"COMPLETE".equals(restarted.storyDetail(id).get("status")),
                "one approved version in archive");
        restarted.setUploadStatus(id,true,"fixture-social","manual posting");
        check(!restarted.completeAuthorized(id,done),"lost completion response is idempotent");
        check(versions(restarted)==1&&Boolean.TRUE.equals(restarted.storyDetail(id).get("uploaded")),
                "duplicate completion leaves manual posting and version count unchanged");
        CommandCenterStore again=store(state,dir);
        check(!again.completeAuthorized(id,done)&&versions(again)==1,
                "restart does not allow duplicate completion");
        Map<String,Object>replay=again.authorizeVideoTransfer(id,"worker-one",claim,epoch,sha);
        check(Boolean.TRUE.equals(replay.get("completed"))&&versions(again)==1,
                "lost upload acknowledgement returns old approved identity");
        again.action(id,"REMAKE");
        blocked(()->again.authorizeVideoTransfer(id,"worker-one",claim,epoch,sha),
                "completed upload replay is rejected once a new RE-MAKE is queued");
        blocked(()->again.completeAuthorized(id,done),
                "old completion replay is rejected once a new RE-MAKE is queued");
        Map<String,Object>next=again.claim("worker-two",Map.of());
        String nextClaim=String.valueOf(next.get("claimId"));
        String nextEpoch=String.valueOf(next.get("productionEpoch"));
        check(!claim.equals(nextClaim)&&!epoch.equals(nextEpoch)&&versions(again)==1,
                "re-make preserves historical video and creates a new production epoch");
        blocked(()->again.requireActiveClaim(id,"worker-one",claim),
                "old claim cannot mutate a new active production");
        Map<String,Object>root=Json.object(Json.read(state));
        Map<String,Object>stories=Json.object(root.get("stories"));
        Map<String,Object>active=Json.object(stories.get(id));
        active.put("leaseUntil",Instant.EPOCH.toString());
        stories.put(id,active);root.put("stories",stories);Json.write(state,root);
        CommandCenterStore recovered=store(state,dir);
        check("QUEUED".equals(recovered.storyDetail(id).get("status"))&&versions(recovered)==1,
                "expired lease recovers without deleting archived video");
        blocked(()->recovered.requireActiveClaim(id,"worker-two",nextClaim),
                "expired worker cannot regain expired lease");
        Map<String,Object>newOwner=recovered.claim("worker-three",Map.of());
        check(newOwner!=null&&!nextClaim.equals(newOwner.get("claimId"))
                &&nextEpoch.equals(newOwner.get("productionEpoch")),
                "new worker receives fenced claim while retry retains production epoch");
        blocked(()->recovered.completeAuthorized(id,Map.of("workerId","worker-two",
                "claimId",nextClaim,"productionEpoch",nextEpoch)),
                "expired previous owner cannot complete after lease supersession");
        check("PRODUCING".equals(recovered.storyDetail(id).get("status"))
                &&versions(recovered)==1,
                "stale completion leaves new active owner and historical archive unchanged");
        String activeClaim=String.valueOf(newOwner.get("claimId"));
        recovered.action(id,"CANCEL");
        check("CANCELLED".equals(recovered.storyDetail(id).get("status"))
                &&versions(recovered)==1,
                "operator cancellation revokes active claim and preserves archived media");
        blocked(()->recovered.requireActiveClaim(id,"worker-three",activeClaim),
                "cancelled worker can no longer upload or report progress");
        blocked(()->recovered.completeAuthorized(id,Map.of("workerId","worker-three",
                "claimId",activeClaim,"productionEpoch",nextEpoch)),
                "late completion cannot resurrect a cancelled job");
        CommandCenterStore cancelledRestart=store(state,dir);
        check("CANCELLED".equals(cancelledRestart.storyDetail(id).get("status"))
                &&versions(cancelledRestart)==1,
                "cancelled job stays cancelled across restart and keeps historical version");
        check(cancelledRestart.claim("worker-four",Map.of())==null,
                "cancelled job cannot silently re-enter queue");
        cancelledRestart.action(id,"MAKE");
        Map<String,Object>afterManualQueue=cancelledRestart.claim("worker-four",Map.of());
        check(afterManualQueue!=null
                &&!nextEpoch.equals(afterManualQueue.get("productionEpoch"))
                &&versions(cancelledRestart)==1,
                "explicit new MAKE after cancellation receives a fresh production epoch");
        return passed;
    }
}
