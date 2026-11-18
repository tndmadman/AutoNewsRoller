package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import java.nio.file.*;

public final class SafetyGpuConfigSelfTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("safety-gpu-config-");
        try{
            NewsConfig cfg=NewsConfig.load(root);
            if(!"off".equals(SafetyGpuConfig.from(root,cfg).mode()))
                throw new AssertionError("GPU must default to off");
            if(SafetyGpuConfig.cpuThreads(cfg)!=8)
                throw new AssertionError("default safety CPU budget must be eight threads");
            cfg.set("contentSafetyCpuThreads","0");
            rejects(()->SafetyGpuConfig.cpuThreads(cfg));
            cfg.set("contentSafetyCpuThreads","33");
            rejects(()->SafetyGpuConfig.cpuThreads(cfg));
            cfg.set("contentSafetyCpuThreads","8");
            java.util.Map<String,String> env=new java.util.HashMap<>();
            SafetyGpuConfig.applyCpuThreadBudget(env,cfg);
            if(!"8".equals(env.get("AUTONEWS_SAFETY_CPU_THREADS"))
                    ||!"1".equals(env.get("TF_NUM_INTEROP_THREADS")))
                throw new AssertionError("safety CPU budget not propagated");
            cfg.set("contentSafetyGpuMode","invalid");
            rejects(()->SafetyGpuConfig.from(root,cfg));
            cfg.set("contentSafetyGpuMode","require");
            cfg.set("contentSafetyGpuDevice","not-an-int");
            rejects(()->SafetyGpuConfig.from(root,cfg));
            cfg.set("contentSafetyGpuDevice","0");
            cfg.set("contentSafetyGpuReserveMiB","100000");
            rejects(()->SafetyGpuConfig.from(root,cfg));
            cfg.set("contentSafetyGpuReserveMiB","4096");
            cfg.set("contentSafetyScannerPersistent","false");
            rejects(()->SafetyGpuConfig.from(root,cfg));
            cfg.set("contentSafetyScannerPersistent","true");
            rejects(()->SafetyGpuConfig.from(root,cfg).requireArtifacts());
            cfg.set("contentSafetyGpuMode","prefer");
            if(!SafetyGpuConfig.from(root,cfg).enabled())
                throw new AssertionError("prefer mode missing");
            System.out.println("SafetyGpuConfigSelfTest PASS");
        }finally{Files.delete(root);}
    }
    interface Task{void run()throws Exception;}
    private static void rejects(Task task)throws Exception{
        try{task.run();}catch(IllegalArgumentException|IllegalStateException valid){return;}
        throw new AssertionError("invalid GPU configuration accepted");
    }
}
