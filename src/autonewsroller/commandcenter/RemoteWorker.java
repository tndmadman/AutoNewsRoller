package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.orchestration.*;
import autonewsroller.util.Json;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public final class RemoteWorker {
    private final Path root;
    private final NewsConfig cfg;
    private final String controller,workerId,token;
    private final HttpClient http;
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor();
    private volatile String state="IDLE",currentJob="",currentTopic="";
    private volatile boolean running=true;

    public RemoteWorker(Path root,NewsConfig cfg,String controller,String workerId,String token){
        this.root=root;this.cfg=cfg;this.controller=controller.replaceAll("/+$","");this.workerId=workerId;this.token=token==null?"":token;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    public void runForever(){
        System.out.println("AutoNewsRoller GPU worker '"+workerId+"' -> "+controller);
        heartbeat.scheduleWithFixedDelay(this::heartbeatSafe,0,10,TimeUnit.SECONDS);
        while(running){
            try{
                Map<String,Object>job=claim();
                if(job==null){Thread.sleep(3000);continue;}
                runJob(job);
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            catch(Exception e){System.err.println("Worker controller error: "+e.getMessage());try{Thread.sleep(5000);}catch(InterruptedException ie){Thread.currentThread().interrupt();break;}}
        }
        heartbeat.shutdownNow();
    }

    public void stop(){running=false;heartbeat.shutdownNow();}

    private Map<String,Object>claim()throws Exception{
        HttpRequest req=request("/api/jobs/claim?worker="+URLEncoder.encode(workerId,StandardCharsets.UTF_8)).GET().timeout(Duration.ofSeconds(30)).build();
        HttpResponse<String>r=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()==204)return null;
        if(r.statusCode()!=200)throw new IllegalStateException("claim HTTP "+r.statusCode()+": "+r.body());
        return Json.object(Json.parse(r.body()));
    }

    private void runJob(Map<String,Object>job){
        String jobId=String.valueOf(job.get("jobId"));
        currentJob=jobId;currentTopic=String.valueOf(job.getOrDefault("topic",""));state="PRODUCING";
        try{
            NewsPipeline.Candidate candidate=NewsPipeline.Candidate.fromMap(Json.object(job.get("candidate")));
            Map<String,Object>settings=job.get("settings") instanceof Map<?,?>?Json.object(job.get("settings")):Map.of();
            int duration=settings.get("duration") instanceof Number n?n.intValue():cfg.duration();
            String encoder=String.valueOf(settings.getOrDefault("encoder",cfg.get("videoEncoder","auto")));
            boolean useComfy=bool(settings.get("useComfy"));
            boolean dryRun=bool(settings.get("dryRun"));
            String stamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
            Path batch=root.resolve("output/remote_worker").resolve(safeName(jobId)+"_"+stamp);
            NewsPipeline pipeline=new NewsPipeline(root,cfg,batch,event->progressSafe(jobId,event));
            Path output=pipeline.produce(candidate,1,1,batch.resolve("slot_001"),duration,encoder,useComfy,dryRun);
            if(!dryRun&&output.toString().toLowerCase(Locale.ROOT).endsWith(".mp4")){
                Map<String,Object>upload=uploadVideo(jobId,output);
                Map<String,Object>complete=new LinkedHashMap<>();
                complete.put("workerId",workerId);complete.put("localPath",output.toString());complete.put("uploaded",upload);
                complete.put("completedAt",Instant.now().toString());
                Path sidecar=output.resolveSibling(output.getFileName()+".json");
                if(Files.isRegularFile(sidecar))try{complete.put("sidecar",Json.read(sidecar));}catch(Exception ignored){}
                post("/api/jobs/"+enc(jobId)+"/complete",complete);
            }else{
                post("/api/jobs/"+enc(jobId)+"/complete",Map.of("workerId",workerId,"dryRun",true,"localPath",output.toString(),"completedAt",Instant.now().toString()));
            }
            System.out.println("Worker completed: "+currentTopic);
        }catch(Exception e){
            e.printStackTrace();
            try{post("/api/jobs/"+enc(jobId)+"/fail",Map.of("workerId",workerId,"error",safe(e.getMessage()),"failedAt",Instant.now().toString()));}catch(Exception ignored){}
        }finally{
            state="IDLE";currentJob="";currentTopic="";
        }
    }

    private Map<String,Object>uploadVideo(String jobId,Path file)throws Exception{
        HttpRequest.Builder b=request("/api/jobs/"+enc(jobId)+"/video").timeout(Duration.ofMinutes(30)).header("Content-Type","video/mp4").header("X-Filename",file.getFileName().toString());
        HttpResponse<String>r=http.send(b.PUT(HttpRequest.BodyPublishers.ofFile(file)).build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IllegalStateException("video upload HTTP "+r.statusCode()+": "+r.body());
        return Json.object(Json.parse(r.body()));
    }

    private void progressSafe(String jobId,WorkerState event){
        try{post("/api/jobs/"+enc(jobId)+"/progress",Map.of("stage",event.stage().name(),"detail",event.detail(),"timestamp",event.timestamp().toString()));}catch(Exception ignored){}
    }

    private void heartbeatSafe(){
        try{
            Map<String,Object>m=new LinkedHashMap<>();m.put("id",workerId);m.put("state",state);m.put("currentJob",currentJob);m.put("currentTopic",currentTopic);m.put("metrics",SystemMetrics.snapshot());m.put("timestamp",Instant.now().toString());
            post("/api/workers/heartbeat",m);
        }catch(Exception e){System.err.println("Worker heartbeat failed: "+e.getMessage());}
    }

    private Map<String,Object>post(String path,Map<String,Object>body)throws Exception{
        HttpRequest req=request(path).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body),StandardCharsets.UTF_8)).build();
        HttpResponse<String>r=http.send(req,HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()<200||r.statusCode()>=300)throw new IllegalStateException(path+" HTTP "+r.statusCode()+": "+r.body());
        return r.body().isBlank()?Map.of():Json.object(Json.parse(r.body()));
    }

    private HttpRequest.Builder request(String path){
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(controller+path)).header("User-Agent","AutoNewsRoller-Worker/1.0");
        if(!token.isBlank())b.header("X-AutoNews-Token",token);
        return b;
    }

    private static boolean bool(Object x){return x instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(x));}
    private static String enc(String x){return URLEncoder.encode(x,StandardCharsets.UTF_8);}
    private static String safeName(String x){return x==null?"job":x.replaceAll("[^A-Za-z0-9._-]+","_");}
    private static String safe(String x){return x==null?"":x.length()>1200?x.substring(0,1200):x;}
}
