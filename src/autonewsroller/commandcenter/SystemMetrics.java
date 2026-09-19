package autonewsroller.commandcenter;

import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SystemMetrics {
    private SystemMetrics(){}

    public static Map<String,Object> snapshot(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("hostname",hostname());
        m.put("os",System.getProperty("os.name","unknown"));
        m.put("java",System.getProperty("java.version","unknown"));
        m.put("processors",Runtime.getRuntime().availableProcessors());
        Runtime rt=Runtime.getRuntime();
        m.put("jvmUsedMb",(rt.totalMemory()-rt.freeMemory())/1048576L);
        m.put("jvmMaxMb",rt.maxMemory()/1048576L);
        try{
            OperatingSystemMXBean base=ManagementFactory.getOperatingSystemMXBean();
            if(base instanceof com.sun.management.OperatingSystemMXBean os){
                m.put("cpuLoad",round(os.getCpuLoad()*100.0));
                m.put("processCpuLoad",round(os.getProcessCpuLoad()*100.0));
                m.put("memoryTotalMb",os.getTotalMemorySize()/1048576L);
                m.put("memoryFreeMb",os.getFreeMemorySize()/1048576L);
            }
        }catch(Exception ignored){}
        m.put("gpu",gpu());
        return m;
    }

    private static Map<String,Object>gpu(){
        Map<String,Object>m=new LinkedHashMap<>();m.put("available",false);
        Process p=null;
        try{
            p=new ProcessBuilder("nvidia-smi","--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu","--format=csv,noheader,nounits").redirectErrorStream(true).start();
            if(!p.waitFor(4,TimeUnit.SECONDS)){p.destroyForcibly();return m;}
            String line=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).lines().findFirst().orElse("").trim();
            if(p.exitValue()!=0||line.isBlank())return m;
            String[]x=line.split("\\s*,\\s*");
            if(x.length>=5){
                m.put("available",true);m.put("name",x[0]);
                m.put("utilization",number(x[1]));m.put("memoryUsedMb",number(x[2]));m.put("memoryTotalMb",number(x[3]));m.put("temperatureC",number(x[4]));
            }
        }catch(Exception ignored){}finally{if(p!=null&&p.isAlive())p.destroyForcibly();}
        return m;
    }

    private static String hostname(){try{return java.net.InetAddress.getLocalHost().getHostName();}catch(Exception e){return "unknown";}}
    private static double number(String x){try{return Double.parseDouble(x);}catch(Exception e){return 0;}}
    private static double round(double x){return Math.round(x*10.0)/10.0;}
}
