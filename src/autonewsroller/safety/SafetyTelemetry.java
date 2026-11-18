package autonewsroller.safety;

import autonewsroller.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Append-only detector wall-time evidence. Separate from authorizing manifests. */
public final class SafetyTelemetry {
    private SafetyTelemetry(){}
    public static synchronized void record(Path root,String detector,String mediaKind,
            String sha,Map<String,Object>result){
        try{
            Map<String,Object>line=new LinkedHashMap<>();
            line.put("event","SAFETY_SCAN");
            line.put("time",Instant.now().toString());
            line.put("origin",SafetyScanProgress.current().isBlank()?"local":"controller");
            line.put("mediaKind",mediaKind);
            line.put("detector",detector);
            line.put("sha256",sha);
            for(String key:List.of("status","backend","provider","cpuThreads","gpuWaitMillis","fallbackReason","scannerPid",
                    "modelLoadSeconds","framesDecoded","framesInspected","framesExpected",
                    "elapsedSeconds","decodeWallSeconds","modelAndPrepostWallSeconds",
                    "preprocessWallSeconds","inferWallSeconds")){
                Object value=result.get(key);
                if(value instanceof Number||value instanceof Boolean||value instanceof String)
                    line.put(key,value);
            }
            Path output=root.resolve("output/runtime/safety_scan_metrics.jsonl");
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output,Json.stringify(line)+"\n",StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException ignored){
            // Observability cannot override the independent content verdict.
        }
    }
}
