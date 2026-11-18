package autonewsroller.orchestration;

import autonewsroller.util.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Append-only production metrics. No prompts, article bodies, access tokens or
 * credentials are recorded. A metrics write error cannot approve or fail a job.
 */
public final class ProductionStageTelemetry {
    private final Path journal;
    private final String jobId,workerId,claimId,epoch,mode;
    private final int attempt;
    private String stage="PRE_FLIGHT",hash="";
    private Instant started=Instant.now();
    private long startedNanos=System.nanoTime();
    private boolean reused=false;
    private int acceptedImages=0,rejectedImages=0;
    private long sceneNanos=-1;
    private Instant sceneStarted;
    private int sceneAttempt;
    private boolean finished;

    public ProductionStageTelemetry(Path journal,String jobId,String workerId,String claimId,
            String epoch,String mode,int attempt){
        this.journal=journal;this.jobId=jobId;this.workerId=workerId;
        this.claimId=claimId;this.epoch=epoch;this.mode=mode;
        this.attempt=Math.max(1,attempt);
    }
    public synchronized void progress(String next,String detail){
        if(finished)return;
        String message=detail==null?"":detail;
        if(message.startsWith("COMFYUI REUSED APPROVED SCENE")){reused=true;acceptedImages++;}
        if(message.startsWith("COMFYUI GENERATING")){
            if(sceneNanos>=0)endScene(false,"INTERRUPTED_ATTEMPT");
            sceneNanos=System.nanoTime();sceneStarted=Instant.now();
            sceneAttempt=parseAttempt(message);
        }
        if(message.startsWith("IMAGE REJECTED")||message.startsWith("IMAGE CONTENT SAFETY REJECTED")
                ||message.startsWith("IMAGE VISION WARNING")
                ||message.startsWith("POST-COMPOSITION SAFETY REJECTED")){
            rejectedImages++;
            endScene(false,FailurePolicy.classify("VISUALS",message).code().name());
        }
        if(message.startsWith("COMFYUI USED")){
            acceptedImages++;
            endScene(true,"");
        }
        if(message.startsWith("RESUMED")||message.startsWith("COMFYUI REUSED"))reused=true;
        if(next!=null&&!next.isBlank()&&!next.equals(stage)){
            closeStage(true,"");
            stage=next;started=Instant.now();startedNanos=System.nanoTime();reused=false;
        }
    }
    public synchronized void setHash(String sha){
        if(sha!=null&&sha.matches("[0-9a-f]{64}"))hash=sha;
    }
    public synchronized void complete(boolean success,String failureCode){
        if(finished)return;
        if(sceneNanos>=0)endScene(false,"INCOMPLETE_ATTEMPT");
        closeStage(success,failureCode==null?"":failureCode);
        Map<String,Object>summary=base();
        summary.put("event","JOB_FINISHED");summary.put("stage",stage);
        summary.put("success",success);
        summary.put("acceptedImageCount",acceptedImages);
        summary.put("rejectedImageCount",rejectedImages);
        summary.put("failureCode",failureCode==null?"":failureCode);
        if(!hash.isBlank())summary.put("outputSha256",hash);
        append(summary);finished=true;
    }
    private void closeStage(boolean success,String failureCode){
        Map<String,Object>record=base();
        record.put("event","STAGE_FINISHED");record.put("stage",stage);
        record.put("startedAt",started.toString());record.put("endedAt",Instant.now().toString());
        record.put("elapsedMillis",Math.max(0,(System.nanoTime()-startedNanos)/1000000));
        record.put("success",success);record.put("checkpointReused",reused);
        record.put("failureCode",failureCode==null?"":failureCode);
        if(!hash.isBlank())record.put("outputSha256",hash);
        append(record);
    }
    private void endScene(boolean success,String failureCode){
        if(sceneNanos<0)return;
        Map<String,Object>record=base();
        record.put("event","SCENE_ATTEMPT");record.put("stage","COMFY_SCENE");
        record.put("startedAt",sceneStarted.toString());record.put("endedAt",Instant.now().toString());
        record.put("elapsedMillis",Math.max(0,(System.nanoTime()-sceneNanos)/1000000));
        record.put("imageAttemptNumber",sceneAttempt);
        record.put("success",success);record.put("checkpointReused",false);
        record.put("failureCode",failureCode);
        append(record);sceneNanos=-1;
    }
    private Map<String,Object>base(){
        Map<String,Object>entry=new LinkedHashMap<>();
        entry.put("schema","anr-production-stage-v1");entry.put("time",Instant.now().toString());
        entry.put("jobId",jobId);entry.put("workerId",workerId);
        entry.put("productionEpoch",epoch);entry.put("claimId",claimId);
        entry.put("productionMode",mode);entry.put("attemptNumber",attempt);
        return entry;
    }
    private static int parseAttempt(String text){
        int start=text.indexOf("attempt=");
        if(start<0)return 1;
        StringBuilder digits=new StringBuilder();
        for(int i=start+8;i<text.length()&&Character.isDigit(text.charAt(i));i++)
            digits.append(text.charAt(i));
        try{return Math.max(1,Integer.parseInt(digits.toString()));}
        catch(Exception ignored){return 1;}
    }
    private void append(Map<String,Object>event){
        try{
            Files.createDirectories(journal.getParent());
            byte[]bytes=(Json.stringify(event)+"\n").getBytes(StandardCharsets.UTF_8);
            try(FileChannel file=FileChannel.open(journal,
                    StandardOpenOption.WRITE,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                FileLock lock=file.lock()){
                file.write(ByteBuffer.wrap(bytes));
                file.force(false);
            }
        }catch(Exception e){
            System.err.println("Production stage metrics unavailable: "+e.getClass().getSimpleName());
        }
    }
}
