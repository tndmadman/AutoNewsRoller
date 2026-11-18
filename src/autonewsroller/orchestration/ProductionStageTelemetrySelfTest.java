package autonewsroller.orchestration;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

/** Executes without services, GPU, real articles or approval data. */
public final class ProductionStageTelemetrySelfTest {
    private ProductionStageTelemetrySelfTest(){}
    private static int count;
    private static void check(boolean good,String message){
        if(!good)throw new AssertionError(message);
        count++;System.out.println("PASS telemetry: "+message);
    }
    public static void main(String[]args)throws Exception{System.out.println("Passed: "+run());}
    public static int run()throws Exception{
        count=0;
        Path journal=Files.createTempFile("anr-stage-",".jsonl");
        ProductionStageTelemetry metrics=new ProductionStageTelemetry(
                journal,"job-test","worker-test","claim-test","epoch-test","STANDARD",2);
        metrics.progress("SCRIPT","Script begun");
        metrics.progress("VISUALS","COMFYUI GENERATING 1/2 attempt=1 checkpoint=test.safetensors");
        metrics.progress("VISUALS","IMAGE REJECTED scene=0 reason=wrong dimensions");
        metrics.progress("VISUALS","COMFYUI GENERATING 1/2 attempt=2 checkpoint=test.safetensors");
        metrics.progress("VISUALS","COMFYUI USED checkpoint=test.safetensors image=accepted.png count=1");
        metrics.progress("UPLOAD","Media ready");
        metrics.progress("COMPLETE_ACK","Controller replied");
        metrics.complete(true,"");
        metrics.complete(false,"ignored-duplicate");
        List<Map<String,Object>>events=new ArrayList<>();
        for(String line:Files.readAllLines(journal))events.add(Json.object(Json.parse(line)));
        long jobs=events.stream().filter(e->"JOB_FINISHED".equals(e.get("event"))).count();
        long scenes=events.stream().filter(e->"SCENE_ATTEMPT".equals(e.get("event"))).count();
        check(jobs==1,"only one terminal telemetry record is persisted");
        check(scenes==2,"accepted and rejected scene attempts are recorded separately");
        Map<String,Object>finalRecord=events.stream().filter(e->"JOB_FINISHED".equals(e.get("event"))).findFirst().orElseThrow();
        check(((Number)finalRecord.get("acceptedImageCount")).intValue()==1
                &&((Number)finalRecord.get("rejectedImageCount")).intValue()==1,
                "real attempt outcome counts are preserved");
        check(events.stream().filter(e->"STAGE_FINISHED".equals(e.get("event")))
                .allMatch(e->e.get("elapsedMillis") instanceof Number
                        &&((Number)e.get("elapsedMillis")).longValue()>=0),
                "all stage durations are numeric and nonnegative");
        check(events.stream().allMatch(e->"claim-test".equals(e.get("claimId"))
                &&"epoch-test".equals(e.get("productionEpoch"))
                &&((Number)e.get("attemptNumber")).intValue()==2),
                "every record contains claim, epoch and attempt for correlation");
        String raw=Files.readString(journal);
        check(!raw.contains("checkpoint=test")&&!raw.contains("Script begun"),
                "untrusted detail text and prompts are not persisted");
        return count;
    }
}
