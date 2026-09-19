package autonewsroller;

import autonewsroller.commandcenter.*;
import autonewsroller.config.*;
import autonewsroller.orchestration.*;

import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class Main {
    public static void main(String[]args){
        Path root=Path.of("").toAbsolutePath().normalize();
        try{
            if(has(args,"--self-test")){new SelfTest().run(root);return;}
            NewsConfig cfg=NewsConfig.load(root);
            Map<String,String>o=parse(args);

            if(has(args,"--command-center")){
                String host=o.getOrDefault("--host",cfg.get("commandCenterHost","127.0.0.1"));
                int port=intv(o,"--port",cfg.getInt("commandCenterPort",8787));
                int scan=intv(o,"--scan-minutes",cfg.getInt("commandCenterScanMinutes",10));
                boolean auto=has(args,"--auto-queue")||cfg.getBool("commandCenterAutoQueue",true);
                double threshold=doublev(o,"--auto-threshold",cfg.getDouble("commandCenterAutoThreshold",0.68));
                int maxQueued=intv(o,"--max-queued",cfg.getInt("commandCenterMaxQueued",12));
                String token=o.getOrDefault("--token",env("AUTONEWS_TOKEN",""));
                CommandCenterServer server=new CommandCenterServer(root,cfg,host,port,scan,auto,threshold,maxQueued,token,!has(args,"--no-initial-scan"));
                Runtime.getRuntime().addShutdownHook(new Thread(server::stop,"autonews-command-center-shutdown"));
                server.start();server.block();return;
            }

            if(has(args,"--worker")){
                String controller=o.getOrDefault("--controller-url",cfg.get("commandCenterControllerUrl","http://127.0.0.1:8787"));
                String workerId=o.getOrDefault("--worker-id",defaultWorkerId());
                String token=o.getOrDefault("--token",env("AUTONEWS_TOKEN",""));
                NewsConfig cleanupCfg=cfg;
                Runtime.getRuntime().addShutdownHook(new Thread(()->OwnedProcesses.shutdownQwen(root,cleanupCfg.get("qwenUrl","http://127.0.0.1:8765")),"autonewsroller-owned-helper-shutdown"));
                RemoteWorker worker=new RemoteWorker(root,cfg,controller,workerId,token);
                Runtime.getRuntime().addShutdownHook(new Thread(worker::stop,"autonews-worker-shutdown"));
                worker.runForever();
                OwnedProcesses.shutdownQwen(root,cfg.get("qwenUrl","http://127.0.0.1:8765"));
                return;
            }

            NewsConfig cleanupCfg=cfg;
            Runtime.getRuntime().addShutdownHook(new Thread(()->OwnedProcesses.shutdownQwen(root,cleanupCfg.get("qwenUrl","http://127.0.0.1:8765")),"autonewsroller-owned-helper-shutdown"));
            try{
                if(has(args,"--keep-ollama-loaded"))cfg.set("ollamaKeepAlive","30m");
                if(has(args,"--unload-ollama-after"))cfg.set("ollamaKeepAlive","0");
                boolean dry=has(args,"--dry-run");
                int target=intv(o,"--batch-target",dry?5:1);
                int workers=intv(o,"--workers",cfg.workers());
                String category=o.getOrDefault("--category","general");
                int maxAge=intv(o,"--max-age-hours",cfg.maxAgeHours());
                int min=intv(o,"--minimum-independent-sources",cfg.minimumIndependentSources());
                int duration=intv(o,"--duration",cfg.duration());
                String encoder=o.getOrDefault("--encoder",cfg.get("videoEncoder","auto"));
                boolean comfy=has(args,"--comfyui");
                String stamp=DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now());
                Path batch=o.containsKey("--batch-dir")?root.resolve(o.get("--batch-dir")).normalize():root.resolve("output/batch_"+stamp);
                NewsPipeline p=new NewsPipeline(root,cfg,batch);
                BatchCoordinator.BatchResult r=new BatchCoordinator(root,p).run(target,workers,category,maxAge,min,duration,encoder,comfy,dry,has(args,"--fixture"),batch);
                System.out.println("Approved "+r.approved()+" / target "+target+" after "+r.attempts()+" attempt(s)");
                for(String e:r.errors())System.err.println("REJECTED "+e);
                if(r.approved()<target)System.exit(2);
            }finally{OwnedProcesses.shutdownQwen(root,cfg.get("qwenUrl","http://127.0.0.1:8765"));}
        }catch(Exception e){e.printStackTrace();System.exit(1);}
    }

    private static boolean has(String[]a,String k){for(String x:a)if(x.equalsIgnoreCase(k))return true;return false;}
    private static Map<String,String>parse(String[]a){Map<String,String>m=new HashMap<>();for(int i=0;i<a.length;i++)if(a[i].startsWith("--")&&i+1<a.length&&!a[i+1].startsWith("--"))m.put(a[i],a[++i]);return m;}
    private static int intv(Map<String,String>m,String k,int d){try{return Integer.parseInt(m.getOrDefault(k,String.valueOf(d)));}catch(Exception e){return d;}}
    private static double doublev(Map<String,String>m,String k,double d){try{return Double.parseDouble(m.getOrDefault(k,String.valueOf(d)));}catch(Exception e){return d;}}
    private static String env(String key,String def){String v=System.getenv(key);return v==null||v.isBlank()?def:v;}
    private static String defaultWorkerId(){
        String v=env("COMPUTERNAME",env("HOSTNAME",""));
        if(!v.isBlank())return v;
        try{return java.net.InetAddress.getLocalHost().getHostName();}catch(Exception e){return "worker-"+ProcessHandle.current().pid();}
    }
}
