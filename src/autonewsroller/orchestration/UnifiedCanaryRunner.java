package autonewsroller.orchestration;

import autonewsroller.config.NewsConfig;
import autonewsroller.safety.ContentSafetyService;
import autonewsroller.util.Json;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Explicitly invoked only from an isolated Windows checkout. Uses built-in
 * synthetic RSS fixtures: it proves that production paths can execute, not that
 * external news stories were independently verified for publication.
 */
public final class UnifiedCanaryRunner {
    private UnifiedCanaryRunner(){}
    public static void main(String[]args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException(
                "Pass the absolute isolated batch directory as the only argument");
        Path root=Path.of("").toAbsolutePath().normalize();
        Path batch=Path.of(args[0]).toAbsolutePath().normalize();
        if(batch.equals(root)||batch.startsWith(root.resolve("data"))
                ||batch.startsWith(root.resolve("output/command_center")))
            throw new IllegalArgumentException("Canary output cannot use controller state or production output paths");
        Files.createDirectories(batch);
        NewsConfig cfg=NewsConfig.load(root);
        NewsPipeline fixtures=new NewsPipeline(root,cfg,batch.resolve("discovery"));
        List<NewsPipeline.Candidate>candidates=fixtures.discoverFixtures(24,2);
        if(candidates.isEmpty())throw new IllegalStateException("No independently verified fixture story is available");
        List<Map<String,Object>>results=new ArrayList<>();
        boolean failed=false;
        for(ProductionMode mode:List.of(ProductionMode.STANDARD,ProductionMode.MEME_RAGEBAIT,
                ProductionMode.FRAMING_EXPOSURE)){
            boolean imageMode=mode!=ProductionMode.FRAMING_EXPOSURE;
            boolean requireComfy=imageMode;
            NewsPipeline.Candidate selected=null;
            int images=imageMode?Math.max(3,cfg.getInt("commandCenterComfyImages",3)):0;
            for(NewsPipeline.Candidate candidate:candidates){
                if(ProductionPreflight.check(candidate,mode,imageMode,requireComfy,
                        Math.max(1,images),false,cfg).eligible()){
                    selected=candidate;break;
                }
            }
            Instant start=Instant.now();
            Map<String,Object>record=new LinkedHashMap<>();
            record.put("mode",mode.name());record.put("startedAt",start.toString());
            record.put("syntheticSourceFixtures",true);
            record.put("humanInspectionStatus","NOT_REVIEWED");
            try{
                if(selected==null)throw new IllegalStateException("No fixture satisfies the mode's editorial policy");
                record.put("storyId",selected.cluster().id);
                Path modeRoot=batch.resolve(mode.name());
                NewsPipeline pipeline=new NewsPipeline(root,cfg,modeRoot);
                Path video=pipeline.produce(selected,1,1,modeRoot.resolve("slot_1"),
                        mode==ProductionMode.MEME_RAGEBAIT?45:70,"auto",
                        imageMode,requireComfy,Math.max(1,images),null,mode,false);
                if(!Files.isRegularFile(video)||!video.toString().endsWith(".mp4"))
                    throw new IllegalStateException("Production did not return an approved MP4");
                Path sidecar=video.resolveSibling(video.getFileName().toString()+".json");
                if(!Files.isRegularFile(sidecar))
                    throw new IllegalStateException("Completed MP4 lacks approval sidecar");
                Map<String,Object>metadata=Json.object(Json.read(sidecar));
                ContentSafetyService.verify(video,Json.object(metadata.get("contentSafety")),true);
                record.put("status","PASSED_AUTOMATED_PIPELINE");
                record.put("video",video.toString());
                record.put("sha256",ContentSafetyService.hash(video));
                record.put("sidecar",sidecar.toString());
                record.put("narrationEngine",String.valueOf(metadata.getOrDefault("ttsEngineActuallyUsed","")));
                record.put("encoder",String.valueOf(metadata.getOrDefault("videoEncoderActuallyUsed","")));
                record.put("acceptedComfyImages",metadata.getOrDefault("comfyImagesGenerated",0));
            }catch(Exception error){
                failed=true;record.put("status","FAILED");
                record.put("failure",String.valueOf(error.getMessage()));
            }
            record.put("endedAt",Instant.now().toString());
            record.put("elapsedSeconds",Duration.between(start,Instant.now()).toMillis()/1000.0);
            results.add(record);
            Json.write(batch.resolve("unified_canary_execution.json"),Map.of(
                    "createdAt",Instant.now().toString(),"source","built-in synthetic fixtures",
                    "automatedOnly",true,"results",results));
        }
        if(failed)throw new IllegalStateException("One or more canary modes failed; see unified_canary_execution.json");
    }
}
