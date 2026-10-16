package autonewsroller.visuals;

import autonewsroller.gpu.GpuLane;
import autonewsroller.util.Json;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Opt-in local multimodal Ollama image-content check. Model judgments are advisory,
 * and successful review never constitutes verification of a reported event.
 */
public final class OllamaVisionReviewer {
    private final Path root;private final String model,url;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public OllamaVisionReviewer(Path root,String model,String url){
        this.root=root;this.model=model==null?"":model.trim();
        this.url=url==null||url.isBlank()?"http://127.0.0.1:11434/api/generate":url.trim();
    }
    public record Result(boolean evaluated,boolean accepted,boolean majorMismatch,String reason,double confidence,String visibleSubjects){
        public Map<String,Object>toMap(){
            return Map.of("evaluated",evaluated,"accepted",accepted,"majorMismatch",majorMismatch,
                    "reason",reason,"modelConfidence",confidence,"visibleSubjects",visibleSubjects,
                    "factualVerification","not-established");
        }
    }
    public Result review(Path image,String sceneText,List<String> required,List<String> forbidden){
        if(model.isBlank())return new Result(false,true,false,"vision-model-not-configured",0,"");
        try{
            String instruction="""
                    You are an image-content reviewer, not a news fact checker.
                    Compare visible major objects in this image to the textual scene requirements.
                    Never claim an event actually happened. Do not infer facts outside the image.
                    Return JSON with keys matchesScene (boolean),
                    hasUnsupportedProminentObjects (boolean), confidence (0 to 1),
                    visibleSubjects (string), reason (string).
                    Flag mismatch only for clear major visible contradictions, not minor ambiguities.
                    """;
            Map<String,Object>request=new LinkedHashMap<>();
            request.put("model",model);request.put("stream",false);request.put("format","json");
            request.put("keep_alive","10m");
            request.put("prompt",instruction+"\nScene: "+sceneText+"\nExpected visible subjects: "+required+
                    "\nProminently forbidden objects: "+forbidden);
            request.put("images",List.of(Base64.getEncoder().encodeToString(Files.readAllBytes(image))));
            HttpRequest http=HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json")
                    .timeout(Duration.ofSeconds(95))
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(request))).build();
            HttpResponse<String>res;
            // Explicit GPU coordination: this optional inference must not overlap ComfyUI stages.
            try(GpuLane ignored=GpuLane.exclusive(root.resolve("output/runtime/gpu_ai_lane.lock"))){
                res=client.send(http,HttpResponse.BodyHandlers.ofString());
            }
            if(res.statusCode()/100!=2)
                return new Result(false,true,false,"vision-api-http-"+res.statusCode(),0,"");
            Map<String,Object>outer=Json.object(Json.parse(res.body()));
            Object raw=outer.get("response");
            if(!(raw instanceof String s))return new Result(false,true,false,"vision-response-missing",0,"");
            Map<String,Object>content=Json.object(Json.parse(s));
            boolean match=asBool(content.get("matchesScene"));
            boolean unsupported=asBool(content.get("hasUnsupportedProminentObjects"));
            double confidence=content.get("confidence") instanceof Number n?n.doubleValue():0;
            if(!Double.isFinite(confidence))confidence=0;
            confidence=Math.max(0,Math.min(1,confidence));
            boolean severe=confidence>=.85&&(!match||unsupported);
            String subjects=String.valueOf(content.getOrDefault("visibleSubjects",""));
            String reason=String.valueOf(content.getOrDefault("reason",""));
            return new Result(true,!severe,severe,
                    severe?"possible-major-content-mismatch: "+reason:"advisory-only: "+reason,
                    confidence,subjects);
        }catch(Exception error){
            return new Result(false,true,false,"vision-review-unavailable: "+error.getClass().getSimpleName(),0,"");
        }
    }
    private static boolean asBool(Object o){return o instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(o));}
}
