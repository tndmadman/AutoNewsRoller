package autonewsroller.orchestration;

import autonewsroller.config.NewsConfig;
import autonewsroller.safety.ContentSafetyService;
import autonewsroller.safety.SafetyPython;
import autonewsroller.safety.PersistentSafetyScanner;
import autonewsroller.safety.SafetyGpuConfig;
import autonewsroller.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight, bounded preflight before source enrichment and model generation.
 * Every detector must also run its normal image and final decoded-frame scan.
 * A health check is not a safety approval.
 */
public final class ProductionServiceReadiness {
    private ProductionServiceReadiness(){}
    private static final HttpClient HTTP=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private static final Map<String,HealthCache> CACHE=new HashMap<>();
    private record HealthCache(long expiresAt,boolean healthy,String reason){}
    private static final long HEALTH_OK_MILLIS=300000, HEALTH_FAILED_MILLIS=15000;

    public static void check(Path root,NewsConfig cfg,String encoder)throws Exception{
        checkOllama(cfg.get("ollamaUrl","http://127.0.0.1:11434/api/generate"),
                cfg.get("ollamaModel","llama3.1:8b"));
        checkNarration(root,cfg);
        checkEncoders(cfg.get("ffmpegCommand","ffmpeg"),
                cfg.get("ffprobeCommand","ffprobe"),encoder);
        // Both pinned independent models are required before any expensive
        // generation. Cache only a bounded health result, never a video verdict.
        health(root,cfg,"tools/content_safety_scan.py","NudeNet","3.4.2");
        health(root,cfg,"tools/content_context_scan.py","OpenNSFW2","0.19.0");
    }

    /** Ollama /api/tags has no inference cost; /api/generate would load a model. */
    public static void checkOllama(String endpoint,String model)throws Exception{
        if(model==null||model.isBlank())
            throw new IllegalArgumentException("CONFIGURATION_INVALID: Ollama model is empty");
        URI configured;
        try{configured=URI.create(endpoint);}
        catch(Exception invalid){throw new IllegalArgumentException("CONFIGURATION_INVALID: invalid Ollama endpoint",invalid);}
        if(configured.getScheme()==null
                ||!Set.of("http","https").contains(configured.getScheme().toLowerCase(Locale.ROOT))
                ||configured.getHost()==null
                ||!"/api/generate".equals(configured.getPath()))
            throw new IllegalArgumentException("CONFIGURATION_INVALID: Ollama must use an http(s) /api/generate endpoint");
        URI tags=configured.resolve("/api/tags");
        HttpResponse<String> response;
        try{
            HttpRequest request=HttpRequest.newBuilder(tags).timeout(Duration.ofSeconds(5)).GET().build();
            response=HTTP.send(request,HttpResponse.BodyHandlers.ofString());
        }catch(Exception unavailable){
            throw new IllegalStateException("SERVICE_UNAVAILABLE: Ollama model listing unreachable",unavailable);
        }
        if(response.statusCode()!=200)
            throw new IllegalStateException("SERVICE_UNAVAILABLE: Ollama /api/tags HTTP "+response.statusCode());
        Object entries;
        try{entries=Json.object(Json.parse(response.body())).get("models");}
        catch(Exception invalid){throw new IllegalStateException("SERVICE_UNAVAILABLE: invalid Ollama model inventory",invalid);}
        if(!(entries instanceof List<?>models))
            throw new IllegalStateException("SERVICE_UNAVAILABLE: Ollama model inventory missing");
        for(Object item:models){
            if(!(item instanceof Map<?,?>))continue;
            Map<String,Object>entry=Json.object(item);
            String name=String.valueOf(entry.getOrDefault("name",entry.getOrDefault("model","")));
            if(name.equals(model)||(!model.contains(":")&&name.equals(model+":latest")))return;
        }
        throw new IllegalArgumentException("CONFIGURATION_INVALID: OLLAMA_MODEL_MISSING: "+model+
                ". Install the configured model or select one returned by ollama list.");
    }

    /** A local executable and script are necessary, but never sufficient proof of audio. */
    static void checkNarration(Path root,NewsConfig cfg)throws Exception{
        boolean kokoro=Files.isRegularFile(root.resolve("tools/kokoro_tts.py"));
        boolean qwen=Files.isRegularFile(root.resolve("tools/qwen3_tts.py"));
        if(!kokoro&&!qwen)
            throw new IllegalArgumentException("CONFIGURATION_INVALID: neither Kokoro nor Qwen narration helper exists");
        // The primary Kokoro helper and auto-starting Qwen helper both use Python.
        // Keep the preferred/fallback order unchanged and let actual TTS validate WAV.
        String python=Files.isRegularFile(root.resolve(".venv-kokoro/Scripts/python.exe"))
                ?root.resolve(".venv-kokoro/Scripts/python.exe").toString():"python";
        boolean pythonReady=commandAvailable(python,"--version")||commandAvailable("python3","--version");
        if(!pythonReady)
            throw new IllegalStateException("SERVICE_UNAVAILABLE: no Python interpreter available for Kokoro or Qwen narration");
    }

    private static void checkEncoders(String ffmpeg,String ffprobe,String encoder)throws Exception{
        if(!commandAvailable(ffmpeg,"-version"))
            throw new IllegalArgumentException("CONFIGURATION_INVALID: ffmpeg executable is unavailable");
        if(!commandAvailable(ffprobe,"-version"))
            throw new IllegalArgumentException("CONFIGURATION_INVALID: ffprobe executable is unavailable");
        String inventory=run(ffmpeg,8,"-hide_banner","-encoders");
        boolean cpu=inventory.contains("libx264"),gpu=inventory.contains("h264_nvenc");
        if(!cpu&&!gpu)throw new IllegalArgumentException(
                "CONFIGURATION_INVALID: ffmpeg has neither libx264 nor h264_nvenc video encoder");
        String requested=encoder==null?"auto":encoder.toLowerCase(Locale.ROOT);
        if(requested.equals("x264")&&!cpu)
            throw new IllegalArgumentException("CONFIGURATION_INVALID: configured x264 encoder missing");
        // The renderer's existing NVENC-to-x264 fallback remains available.
        if(requested.equals("nvenc")&&!gpu&&!cpu)
            throw new IllegalArgumentException("CONFIGURATION_INVALID: neither NVENC nor x264 is available");
    }

    static boolean commandAvailable(String program,String argument){
        try{return run(program,4,argument)!=null;}
        catch(Exception unavailable){return false;}
    }
    // Keep the machine-readable scanner JSON on stdout separate from
    // TensorFlow/Keras startup warnings and other diagnostics on stderr.
    // A nonzero exit or malformed stdout still fails closed in health().
    static String run(String executable,int seconds,String...arguments)throws Exception{
        ArrayList<String>cmd=new ArrayList<>();
        cmd.add(executable);cmd.addAll(List.of(arguments));
        Path stdout=Files.createTempFile("anr-readiness-",".stdout.log");
        Path stderr=Files.createTempFile("anr-readiness-",".stderr.log");
        try{
            Process process=new ProcessBuilder(cmd)
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile()).start();
            if(!process.waitFor(seconds,TimeUnit.SECONDS)){
                process.destroyForcibly();
                process.waitFor(5,TimeUnit.SECONDS);
                throw new IOException("readiness command timed out");
            }
            if(process.exitValue()!=0){
                String diagnostic=Files.readString(stderr,StandardCharsets.UTF_8).trim();
                if(diagnostic.length()>500)
                    diagnostic=diagnostic.substring(diagnostic.length()-500);
                throw new IOException("readiness command returned "+process.exitValue()
                        +(diagnostic.isEmpty()?"":": "+diagnostic));
            }
            return Files.readString(stdout,StandardCharsets.UTF_8);
        }finally{
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }
    private static synchronized void health(Path root,NewsConfig cfg,
            String script,String detector,String version)throws Exception{
        String python;
        try{python=SafetyPython.resolve(root,cfg);}
        catch(Exception missing){throw new IllegalStateException(
                "SAFETY_SCANNER_UNAVAILABLE: dedicated safety interpreter missing",missing);}
        Path tool=root.resolve(script);
        if(!Files.isRegularFile(tool))
            throw new IllegalStateException("SAFETY_SCANNER_UNAVAILABLE: missing "+detector+" scanner");
        SafetyGpuConfig gpu=SafetyGpuConfig.from(root,cfg);
        String key=root.toAbsolutePath()+"|"+python+"|"+script+"|"+ContentSafetyService.POLICY+
                "|"+gpu.mode()+"|"+gpu.parityManifest()+"|"+gpu.contextOnnx();
        long now=System.currentTimeMillis();
        HealthCache last=CACHE.get(key);
        if(last!=null&&last.expiresAt>now){
            if(last.healthy)return;
            throw new IllegalStateException("SAFETY_SCANNER_UNAVAILABLE: "+last.reason);
        }
        try{
            Map<String,Object>response;
            if(cfg.getBool("contentSafetyScannerPersistent",true)){
                response=PersistentSafetyScanner.health(root,cfg,script);
            }else{
                String output=run(python,90,tool.toAbsolutePath().toString(),"--health");
                response=Json.object(Json.parse(output.trim()));
            }
            if(!"READY".equals(response.get("status"))
                    ||!detector.equals(response.get("detector"))
                    ||!version.equals(response.get("version"))
                    ||!ContentSafetyService.POLICY.equals(response.get("policyVersion")))
                throw new IllegalStateException("scanner model/version/policy did not match");
            CACHE.put(key,new HealthCache(now+HEALTH_OK_MILLIS,true,""));
        }catch(Exception broken){
            CACHE.put(key,new HealthCache(now+HEALTH_FAILED_MILLIS,false,detector+" health failed"));
            throw new IllegalStateException("SAFETY_SCANNER_UNAVAILABLE: "+detector+
                    " pinned model health verification failed",broken);
        }
    }
}
