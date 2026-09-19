package autonewsroller.visuals;

import autonewsroller.gpu.GpuLane;
import autonewsroller.util.Json;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * Persistent ComfyUI SDXL text-to-image client.
 * Normal successful generations deliberately DO NOT call /free or unload models.
 */
public final class ComfyImageGenerator {
    private final Path root;
    private final String baseUrl,qwenUrl;
    private final HttpClient client;
    private boolean qwenReleased;
    private int sequence;

    public ComfyImageGenerator(Path root,String baseUrl,String qwenUrl){
        this.root=root;this.baseUrl=baseUrl.replaceAll("/+$","");this.qwenUrl=qwenUrl.replaceAll("/+$","");
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean reachable(){try{return get("/system_stats",3).statusCode()/100==2;}catch(Exception e){return false;}}

    public List<String> checkpoints()throws Exception{
        HttpResponse<String>x=get("/object_info/CheckpointLoaderSimple",8);
        if(x.statusCode()/100!=2)throw new IllegalStateException("ComfyUI checkpoint query HTTP "+x.statusCode());
        List<String>out=new ArrayList<>();collectStrings(Json.parse(x.body()),out);
        return out.stream().filter(s->s.toLowerCase(Locale.ROOT).endsWith(".safetensors")||s.toLowerCase(Locale.ROOT).endsWith(".ckpt")).distinct().toList();
    }

    public Path generate(String prompt,String negative,String checkpoint,Path output,int width,int height,int steps,double cfg)throws Exception{
        Files.createDirectories(output.getParent());
        try(GpuLane ignored=GpuLane.exclusive(root.resolve("output/runtime/gpu_ai_lane.lock"))){
            if(!qwenReleased){requestQwenRelease();qwenReleased=true;}
            sequence++;
            System.out.println("[ComfyUI] Reusing persistent server; submitting image "+sequence+" checkpoint="+checkpoint);
            long started=System.nanoTime();
            try{
                Path result=generateOnce(prompt,negative,checkpoint,output,width,height,steps,cfg);
                double seconds=(System.nanoTime()-started)/1_000_000_000.0;
                System.out.printf(Locale.US,"[ComfyUI] Image %d completed in %.2f sec; keeping SDXL/CLIP/VAE resident%n",sequence,seconds);
                return result;
            }catch(Exception first){
                if(!looksLikeOom(first))throw first;
                System.err.println("[ComfyUI] CUDA OOM recovery required; unloading models once and retrying image "+sequence);
                try{
                    postJson("/free","{\"unload_models\":true,\"free_memory\":true}",10);
                }catch(Exception freeError){
                    System.err.println("[ComfyUI] OOM cleanup endpoint failed: "+freeError.getMessage());
                }
                Thread.sleep(1500);
                long retryStarted=System.nanoTime();
                Path result=generateOnce(prompt,negative,checkpoint,output,width,height,steps,cfg);
                double seconds=(System.nanoTime()-retryStarted)/1_000_000_000.0;
                System.out.printf(Locale.US,"[ComfyUI] OOM retry image %d completed in %.2f sec; keeping model resident%n",sequence,seconds);
                return result;
            }
        }
    }

    private Path generateOnce(String prompt,String negative,String checkpoint,Path output,int width,int height,int steps,double cfg)throws Exception{
        Map<String,Object>wf=new LinkedHashMap<>();
        // Keep node IDs and the checkpoint-loader inputs stable between requests so ComfyUI can reuse cached model objects.
        wf.put("1",node("CheckpointLoaderSimple",Map.of("ckpt_name",checkpoint)));
        wf.put("2",node("CLIPTextEncode",Map.of("text",prompt,"clip",List.of("1",1))));
        wf.put("3",node("CLIPTextEncode",Map.of("text",negative,"clip",List.of("1",1))));
        wf.put("4",node("EmptyLatentImage",Map.of("width",width,"height",height,"batch_size",1)));
        Map<String,Object>ksampler=new LinkedHashMap<>();
        ksampler.put("seed",Math.floorMod(Objects.hash(prompt,System.nanoTime()),Integer.MAX_VALUE));
        ksampler.put("steps",steps);ksampler.put("cfg",cfg);ksampler.put("sampler_name","dpmpp_2m_sde");ksampler.put("scheduler","karras");ksampler.put("denoise",1.0);
        ksampler.put("model",List.of("1",0));ksampler.put("positive",List.of("2",0));ksampler.put("negative",List.of("3",0));ksampler.put("latent_image",List.of("4",0));
        wf.put("5",node("KSampler",ksampler));
        wf.put("6",node("VAEDecode",Map.of("samples",List.of("5",0),"vae",List.of("1",2))));
        wf.put("7",node("SaveImage",Map.of("filename_prefix","AutoNewsRoller","images",List.of("6",0))));

        Map<String,Object>request=new LinkedHashMap<>();request.put("prompt",wf);request.put("client_id","autonewsroller");
        HttpResponse<String>submit=postJson("/prompt",Json.stringify(request),20);
        if(submit.statusCode()/100!=2)throw new IllegalStateException("ComfyUI /prompt HTTP "+submit.statusCode()+": "+compact(submit.body()));
        String promptId=String.valueOf(Json.object(Json.parse(submit.body())).getOrDefault("prompt_id",""));
        if(promptId.isBlank())throw new IllegalStateException("ComfyUI did not return prompt_id");

        Map<String,Object>image=waitForImage(promptId,600);
        String filename=String.valueOf(image.getOrDefault("filename",""));
        String subfolder=String.valueOf(image.getOrDefault("subfolder",""));
        String type=String.valueOf(image.getOrDefault("type","output"));
        if(filename.isBlank())throw new IllegalStateException("ComfyUI history did not contain an output filename");

        String q="?filename="+URLEncoder.encode(filename,java.nio.charset.StandardCharsets.UTF_8)
                +"&subfolder="+URLEncoder.encode(subfolder,java.nio.charset.StandardCharsets.UTF_8)
                +"&type="+URLEncoder.encode(type,java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest view=HttpRequest.newBuilder(URI.create(baseUrl+"/view"+q)).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<byte[]>img=client.send(view,HttpResponse.BodyHandlers.ofByteArray());
        if(img.statusCode()/100!=2||img.body().length<1024)throw new IllegalStateException("ComfyUI /view failed HTTP "+img.statusCode());
        Files.write(output,img.body());
        return output;
    }

    private Map<String,Object>waitForImage(String promptId,int timeoutSeconds)throws Exception{
        long end=System.nanoTime()+Duration.ofSeconds(timeoutSeconds).toNanos();
        while(System.nanoTime()<end){
            HttpResponse<String>h=get("/history/"+URLEncoder.encode(promptId,java.nio.charset.StandardCharsets.UTF_8),10);
            if(h.statusCode()/100==2){
                Map<String,Object>rootMap=Json.object(Json.parse(h.body()));
                Object entry=rootMap.get(promptId);
                if(entry instanceof Map<?,?>em){
                    Object outputs=((Map<?,?>)em).get("outputs");
                    if(outputs instanceof Map<?,?>om){
                        for(Object ov:om.values())if(ov instanceof Map<?,?>node){
                            Object images=node.get("images");
                            if(images instanceof List<?>l&&!l.isEmpty()&&l.get(0) instanceof Map<?,?>m){
                                Map<String,Object>out=new LinkedHashMap<>();
                                for(var e:m.entrySet())out.put(String.valueOf(e.getKey()),e.getValue());
                                return out;
                            }
                        }
                    }
                }
            }
            Thread.sleep(750);
        }
        throw new IllegalStateException("ComfyUI timed out waiting for prompt "+promptId);
    }

    private void requestQwenRelease(){
        try{
            HttpRequest r=HttpRequest.newBuilder(URI.create(qwenUrl+"/release-gpu")).timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.noBody()).build();
            client.send(r,HttpResponse.BodyHandlers.discarding());
            System.out.println("[ComfyUI] Requested Qwen GPU release before SDXL session");
        }catch(Exception ignored){}
    }

    private static boolean looksLikeOom(Exception e){
        String x=String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return x.contains("out of memory")||x.contains("cuda oom")||x.contains("allocation on device");
    }

    private HttpResponse<String>get(String path,int seconds)throws Exception{
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(seconds)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String>postJson(String path,String body,int seconds)throws Exception{
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(Duration.ofSeconds(seconds)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static Map<String,Object>node(String type,Map<String,Object>inputs){
        Map<String,Object>m=new LinkedHashMap<>();m.put("class_type",type);m.put("inputs",inputs);return m;
    }
    private static void collectStrings(Object v,List<String>out){
        if(v instanceof String s)out.add(s);
        else if(v instanceof Map<?,?>m)for(Object x:m.values())collectStrings(x,out);
        else if(v instanceof List<?>l)for(Object x:l)collectStrings(x,out);
    }
    private static String compact(String s){
        String x=s==null?"":s.replaceAll("\\s+"," ").trim();return x.length()>600?x.substring(0,600)+"...":x;
    }
}
