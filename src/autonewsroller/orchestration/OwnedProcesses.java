package autonewsroller.orchestration;

import autonewsroller.util.Json;
import java.net.URI;import java.net.http.*;import java.nio.file.*;import java.time.Duration;import java.util.*;
/** Only stops helper processes that AutoNewsRoller can prove it owns. */
public final class OwnedProcesses {
    private OwnedProcesses(){}
    public static void shutdownQwen(Path root,String baseUrl){
        Path marker=root.resolve("output/runtime/qwen3_tts_owner.json");if(!Files.isRegularFile(marker))return;
        try{
            Map<String,Object>m=Json.object(Json.read(marker));String token=String.valueOf(m.getOrDefault("token",""));if(token.isBlank())return;String base=baseUrl.replaceAll("/+$","");HttpClient c=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String>h=c.send(HttpRequest.newBuilder(URI.create(base+"/health")).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());if(h.statusCode()!=200)return;Map<String,Object>hm=Json.object(Json.parse(h.body()));if(!token.equals(String.valueOf(hm.getOrDefault("owner_token",""))))return;
            String body=Json.stringify(Map.of("token",token));c.send(HttpRequest.newBuilder(URI.create(base+"/shutdown")).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding());
        }catch(Exception ignored){}
    }
}
