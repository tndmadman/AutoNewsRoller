package autonewsroller.safety;

import autonewsroller.config.NewsConfig;
import java.nio.file.*;
import java.util.*;

/** Strict opt-in CUDA configuration; CPU rollback remains the default. */
public record SafetyGpuConfig(String mode,int device,int reserveMiB,int waitSeconds,
        Path parityManifest,Path contextOnnx) {
    public static SafetyGpuConfig from(Path root,NewsConfig cfg){
        String mode=cfg.get("contentSafetyGpuMode","off").toLowerCase(Locale.ROOT);
        if(!Set.of("off","prefer","require").contains(mode))
            throw new IllegalArgumentException("contentSafetyGpuMode must be off, prefer, or require");
        int device=intSetting(cfg,"contentSafetyGpuDevice",0,0,15);
        int reserve=intSetting(cfg,"contentSafetyGpuReserveMiB",4096,1024,16384);
        int wait=intSetting(cfg,"contentSafetyGpuWaitSeconds",120,1,600);
        if(!"off".equals(mode)&&!cfg.getBool("contentSafetyScannerPersistent",true))
            throw new IllegalArgumentException("GPU scanning requires resident scanner protocol");
        return new SafetyGpuConfig(mode,device,reserve,wait,
                path(root,cfg.get("contentSafetyGpuParityManifest","")),
                path(root,cfg.get("contentSafetyGpuContextOnnx","")));
    }
    private static Path path(Path root,String raw){
        if(raw==null||raw.isBlank())return null;
        Path p=Path.of(raw);
        return (p.isAbsolute()?p:root.resolve(p)).toAbsolutePath().normalize();
    }
    private static int intSetting(NewsConfig cfg,String key,int fallback,int min,int max){
        String value=cfg.get(key,Integer.toString(fallback));
        int parsed;
        try{parsed=Integer.parseInt(value);}
        catch(NumberFormatException e){throw new IllegalArgumentException(key+" is not an integer",e);}
        if(parsed<min||parsed>max)throw new IllegalArgumentException(key+" outside "+min+".."+max);
        return parsed;
    }
    public boolean enabled(){return !"off".equals(mode);}
    public boolean required(){return "require".equals(mode);}
    public void requireArtifacts(){
        if(!enabled())return;
        if(parityManifest==null||!Files.isRegularFile(parityManifest))
            throw new IllegalStateException("CUDA parity manifest missing");
        if(contextOnnx==null||!Files.isRegularFile(contextOnnx))
            throw new IllegalStateException("converted OpenNSFW2 ONNX model missing");
    }
}
