package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import autonewsroller.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Resident supervised CPU bridge. Each mandatory model lives in its own process.
 * No socket, cached verdict, worker-to-controller trust, or GPU claim.
 */
public final class PersistentSafetyScanner {
    private static final ConcurrentHashMap<String,Client> CLIENTS=new ConcurrentHashMap<>();
    private static final ExecutorService READERS=Executors.newCachedThreadPool(r->{
        Thread t=new Thread(r,"safety-scanner-io");t.setDaemon(true);return t;
    });
    private static final AtomicLong REQUESTS=new AtomicLong();
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            for(Client client:CLIENTS.values())client.close();
            READERS.shutdownNow();
        },"safety-scanner-shutdown"));
    }
    private PersistentSafetyScanner(){}
    static Map<String,Object> scan(Path root,NewsConfig cfg,String script,String kind,
            Path input,String sha,int timeoutSeconds)throws Exception{
        SafetyGpuConfig selection=SafetyGpuConfig.from(root,cfg);
        if(!selection.enabled())
            return client(root,cfg,script,selection,false).scan(kind,input,sha,timeoutSeconds,
                    SafetyScanProgress.current());
        try{
            selection.requireArtifacts();
            long waiting=System.nanoTime();
            try(autonewsroller.gpu.GpuLane lane=autonewsroller.gpu.GpuLane.exclusiveBounded(
                    root.resolve("output/runtime/gpu_ai_lane.lock"),
                    java.time.Duration.ofSeconds(selection.waitSeconds()))){
                long waitedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-waiting);
                Map<String,Object>result=client(root,cfg,script,selection,true)
                        .scan(kind,input,sha,timeoutSeconds,SafetyScanProgress.current());
                result.put("gpuWaitMillis",waitedMs);
                return result;
            }
        }catch(Exception gpuFailure){
            if(selection.required())throw new IOException("CUDA REQUIRED: "+
                    gpuFailure.getClass().getSimpleName()+": "+gpuFailure.getMessage(),gpuFailure);
            // Prefer may fall back ONLY after a transport/provider failure, never
            // an UNSAFE or REVIEW_REQUIRED verdict (those return normally).
            Map<String,Object>fallback=client(root,cfg,script,selection,false)
                    .scan(kind,input,sha,timeoutSeconds,SafetyScanProgress.current());
            fallback.put("backend","cpu-fallback");
            fallback.put("fallbackReason",gpuFailure.getClass().getSimpleName()+": "+
                    String.valueOf(gpuFailure.getMessage()).substring(0,
                    Math.min(180,String.valueOf(gpuFailure.getMessage()).length())));
            return fallback;
        }
    }
    public static Map<String,Object> health(Path root,NewsConfig cfg,String script)throws Exception{
        SafetyGpuConfig selection=SafetyGpuConfig.from(root,cfg);
        if(selection.enabled()){
            try{
                selection.requireArtifacts();
                return client(root,cfg,script,selection,true).health();
            }catch(Exception gpuFailure){
                if(selection.required())throw gpuFailure;
            }
        }
        return client(root,cfg,script,selection,false).health();
    }
    private static Client client(Path root,NewsConfig cfg,String script,
            SafetyGpuConfig selection,boolean cuda)throws Exception{
        String python=cuda?SafetyPython.resolveGpu(root,cfg):SafetyPython.resolve(root,cfg);
        Path file=root.resolve(script).toAbsolutePath().normalize();
        if(!Files.isRegularFile(file))throw new IOException("mandatory scanner missing: "+script);
        int cpuThreads=SafetyGpuConfig.cpuThreads(cfg);
        String key=root.toAbsolutePath().normalize()+"|"+python+"|"+file+
                "|"+cuda+"|"+selection.device()+"|"+selection.parityManifest()+
                "|"+selection.contextOnnx()+"|cpuThreads="+cpuThreads;
        Client c=CLIENTS.computeIfAbsent(key,ignored->new Client(root,python,file,
                cfg.getInt("contentSafetyScannerStartupSeconds",180),selection,cuda,cpuThreads));
        return c;
    }
    private static final class Client {
        private final Path root,script;
        private final String python;
        private final int startupSeconds;
        private final SafetyGpuConfig selection;
        private final boolean cuda;
        private final int cpuThreads;
        private Process process;
        private BufferedReader stdout;
        private BufferedWriter stdin;
        private Map<String,Object>ready;
        private Path stderrLog;
        Client(Path root,String python,Path script,int startupSeconds,
                SafetyGpuConfig selection,boolean cuda,int cpuThreads){
            this.root=root.toAbsolutePath();this.python=python;this.script=script;
            this.selection=selection;this.cuda=cuda;this.cpuThreads=cpuThreads;
            if(startupSeconds<5||startupSeconds>900)
                throw new IllegalArgumentException("contentSafetyScannerStartupSeconds outside 5..900");
            this.startupSeconds=startupSeconds;
        }
        synchronized Map<String,Object> health()throws Exception{
            ensureRunning();
            return new LinkedHashMap<>(ready);
        }
        synchronized Map<String,Object> scan(String kind,Path input,String sha,
                int timeoutSeconds,String jobId)throws Exception{
            if(!Set.of("image","video").contains(kind)||timeoutSeconds<1||
                    timeoutSeconds>86400)throw new IllegalArgumentException("invalid scanner request");
            ensureRunning();
            String id="req"+REQUESTS.incrementAndGet();
            String detector=String.valueOf(ready.get("detector"));
            String phase="NudeNet".equals(detector)?"CONTROLLER_NUDENET":"CONTROLLER_OPENNSFW2";
            if(!jobId.isBlank())SafetyScanProgress.update(jobId,phase,0,0,cuda?"cuda":"cpu",
                    process.pid(),0);
            try{
                Map<String,Object>req=Map.of("id",id,"operation",kind,
                        "path",input.toAbsolutePath().toString(),"sha256",sha);
                stdin.write(Json.stringify(req));stdin.newLine();stdin.flush();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSeconds);
                while(true){
                    long left=deadline-System.nanoTime();
                    if(left<=0)throw new TimeoutException("scanner request timeout");
                    Map<String,Object>msg=readTimed(left);
                    if(!id.equals(msg.get("id")))throw new IOException("uncorrelated scanner response");
                    String event=String.valueOf(msg.get("event"));
                    if("progress".equals(event)){
                        long done=number(msg.get("framesProcessed"));
                        long total=number(msg.get("framesExpected"));
                        if(done<0||total<0||done>total&&total>0)
                            throw new IOException("invalid scanner progress");
                        if(!jobId.isBlank())SafetyScanProgress.update(jobId,phase,done,total,
                                cuda?"cuda":"cpu",process.pid(),doubleNumber(msg.get("elapsedSeconds")));
                    }else if("result".equals(event)){
                        Map<String,Object>result=Json.object(msg.get("result"));
                        if(!Set.of("SAFE","UNSAFE","REVIEW_REQUIRED").contains(result.get("status")))
                            throw new IOException("resident scanner unavailable: "+
                                    String.valueOf(result.getOrDefault("reason","malformed result")));
                        if(!detector.equals(result.get("detector"))||
                                !ready.get("version").equals(result.get("version"))||
                                !(cuda?"cuda":"cpu").equals(result.get("backend"))||
                                (cuda&&number(result.get("cudaNodes"))<1))
                            throw new IOException("resident scanner identity/backend mismatch");
                        if(!jobId.isBlank())SafetyScanProgress.update(jobId,phase,
                                number(result.get("framesInspected")),
                                number(result.get("framesExpected")),cuda?"cuda":"cpu",
                                process.pid(),doubleNumber(result.get("elapsedSeconds")));
                        return result;
                    }else throw new IOException("unexpected scanner event");
                }
            }catch(Exception problem){
                close();
                throw new IOException("mandatory resident "+detector+" scan failed: "+
                        problem.getClass().getSimpleName()+": "+problem.getMessage(),problem);
            }
        }
        private void ensureRunning()throws Exception{
            if(process!=null&&process.isAlive())return;
            close();
            ArrayList<String>command=new ArrayList<>(List.of(python,script.toString(),"--serve"));
            if(cuda){
                command.addAll(List.of("--backend","cuda",
                        "--cuda-device",Integer.toString(selection.device()),
                        "--parity-manifest",selection.parityManifest().toString(),
                        "--vram-reserve-mb",Integer.toString(selection.reserveMiB())));
                if(script.getFileName().toString().equals("content_context_scan.py"))
                    command.addAll(List.of("--onnx-model",selection.contextOnnx().toString()));
            }
            ProcessBuilder pb=new ProcessBuilder(command);
            pb.directory(root.toFile());
            pb.environment().put("TF_CPP_MIN_LOG_LEVEL","2");
            String threads=Integer.toString(cpuThreads);
            for(String key:List.of("AUTONEWS_SAFETY_CPU_THREADS","OMP_NUM_THREADS",
                    "OPENBLAS_NUM_THREADS","MKL_NUM_THREADS","NUMEXPR_NUM_THREADS",
                    "TF_NUM_INTRAOP_THREADS"))pb.environment().put(key,threads);
            pb.environment().put("TF_NUM_INTEROP_THREADS","1");
            pb.environment().put("PYTHONUNBUFFERED","1");
            Path dir=root.resolve("output/runtime");
            Files.createDirectories(dir);
            stderrLog=Files.createTempFile(dir,"safety-scanner-",".stderr.log");
            pb.redirectError(stderrLog.toFile());
            try{process=pb.start();}
            catch(Exception startup){
                close();
                throw startup;
            }
            stdout=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8));
            stdin=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
            try{
                ready=readTimed(TimeUnit.SECONDS.toNanos(startupSeconds));
                if(!"ready".equals(ready.get("event"))||
                        !"READY".equals(ready.get("status"))||
                        !ContentSafetyService.POLICY.equals(ready.get("policyVersion"))||
                        !(cuda?"cuda":"cpu").equals(ready.get("backend"))||
                        !(cuda?"CUDAExecutionProvider":"CPU").equals(ready.get("provider"))||
                        (cuda&&number(ready.get("cudaNodes"))<1)||
                        !Set.of("NudeNet","OpenNSFW2").contains(ready.get("detector"))||
                        !("NudeNet".equals(ready.get("detector"))?"3.4.2":"0.19.0")
                            .equals(ready.get("version")))
                    throw new IOException("resident scanner startup identity invalid");
            }catch(Exception unavailable){
                String diagnostic=recentDiagnostics();
                close();
                throw new IOException("scanner model startup failed: "+
                        unavailable.getClass().getSimpleName()+": "+
                        unavailable.getMessage()+
                        (diagnostic.isBlank()?"":" / scanner stderr: "+diagnostic),
                        unavailable);
            }
        }
        private Map<String,Object> readTimed(long nanos)throws Exception{
            Future<String>f=READERS.submit(()->readBounded(stdout));
            try{
                String line=f.get(Math.max(1,nanos),TimeUnit.NANOSECONDS);
                if(line==null)throw new IOException("scanner process closed stdout");
                return Json.object(Json.parse(line));
            }catch(TimeoutException timeout){f.cancel(true);throw timeout;}
        }
        private static String readBounded(Reader reader)throws IOException{
            StringBuilder b=new StringBuilder();
            for(int i=0;i<262144;i++){
                int c=reader.read();
                if(c==-1)return b.length()==0?null:throwIncomplete();
                if(c=='\n')return b.toString();
                if(c!='\r')b.append((char)c);
            }
            throw new IOException("oversized scanner response");
        }
        private static String throwIncomplete()throws IOException{
            throw new IOException("truncated scanner response");
        }
        private String recentDiagnostics(){
            if(stderrLog==null)return "";
            try{
                String content=Files.readString(stderrLog,StandardCharsets.UTF_8).trim();
                return content.substring(Math.max(0,content.length()-450)).replaceAll("\\s+"," ");
            }catch(IOException ignored){return "";}
        }
        synchronized void close(){
            if(process!=null){
                process.destroy();
                try{if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();}
                catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}
            }
            process=null;ready=null;
            try{if(stdout!=null)stdout.close();}catch(IOException ignored){}
            try{if(stdin!=null)stdin.close();}catch(IOException ignored){}
            stdout=null;stdin=null;
            if(stderrLog!=null){
                try{Files.deleteIfExists(stderrLog);}catch(IOException ignored){}
                stderrLog=null;
            }
        }
    }
    private static long number(Object n){return n instanceof Number v?v.longValue():-1;}
    private static double doubleNumber(Object n){return n instanceof Number v?v.doubleValue():0;}
}
