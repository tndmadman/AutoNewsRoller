package autonewsroller.visuals;

import autonewsroller.config.NewsConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class ComfyRuntime {
    private ComfyRuntime(){}

    public static void ensureRunning(Path projectRoot,NewsConfig cfg)throws Exception{
        ComfyImageGenerator probe=new ComfyImageGenerator(projectRoot,cfg.get("comfyUrl","http://127.0.0.1:8188"),cfg.get("qwenUrl","http://127.0.0.1:8765"));
        if(probe.reachable())return;
        if(!cfg.getBool("comfyAutoStart",true))throw new IllegalStateException("ComfyUI is not reachable and comfyAutoStart=false");

        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))
            throw new IllegalStateException("ComfyUI is not reachable and automatic portable startup is currently configured for Windows workers");

        Path root=resolveRoot(cfg);
        Path python=root.resolve("python_embeded/python.exe");
        Path main=root.resolve("ComfyUI/main.py");
        if(!Files.isRegularFile(python)||!Files.isRegularFile(main))
            throw new IllegalStateException("ComfyUI portable install not found at "+root+" (expected python_embeded\\python.exe and ComfyUI\\main.py)");

        Path log=projectRoot.resolve("output/runtime/comfyui.log");
        Files.createDirectories(log.getParent());
        List<String>cmd=new ArrayList<>();
        cmd.add(python.toString());cmd.add("-s");cmd.add(main.toString());cmd.add("--windows-standalone-build");
        if(cfg.getBool("comfyDisableDynamicVram",true))cmd.add("--disable-dynamic-vram");

        System.out.println("Starting ComfyUI: "+String.join(" ",cmd));
        Process p=new ProcessBuilder(cmd)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();

        int seconds=Math.max(15,cfg.getInt("comfyStartupSeconds",90));
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(System.nanoTime()<deadline){
            if(probe.reachable()){
                System.out.println("ComfyUI READY at "+cfg.get("comfyUrl","http://127.0.0.1:8188")+" pid="+p.pid());
                return;
            }
            if(!p.isAlive())throw new IllegalStateException("ComfyUI exited during startup with code "+p.exitValue()+". See "+log);
            Thread.sleep(1000);
        }
        throw new IllegalStateException("ComfyUI did not become ready within "+seconds+"s. See "+log);
    }

    private static Path resolveRoot(NewsConfig cfg){
        String configured=cfg.get("comfyRoot","");
        String env=System.getenv("AUTONEWS_COMFY_ROOT");
        if(env!=null&&!env.isBlank())configured=env;
        if(configured!=null&&!configured.isBlank())return Path.of(configured).toAbsolutePath().normalize();

        List<Path>candidates=new ArrayList<>();
        String home=System.getProperty("user.home","");
        if(!home.isBlank()){
            candidates.add(Path.of(home,"ComfyUI_windows_portable"));
            candidates.add(Path.of(home,"Downloads","ComfyUI_windows_portable"));
        }
        candidates.add(Path.of("C:\\ComfyUI_windows_portable"));
        candidates.add(Path.of("E:\\AI\\ComfyUI_windows_portable"));
        for(Path p:candidates)if(Files.isRegularFile(p.resolve("python_embeded/python.exe"))&&Files.isRegularFile(p.resolve("ComfyUI/main.py")))return p.toAbsolutePath().normalize();
        return candidates.get(candidates.size()-1);
    }
}
