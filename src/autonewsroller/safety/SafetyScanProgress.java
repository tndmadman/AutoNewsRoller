package autonewsroller.safety;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Ephemeral, read-only controller rescan progress. Never an approval record. */
public final class SafetyScanProgress {
    private static final ThreadLocal<String> ACTIVE = new ThreadLocal<>();
    private static final ConcurrentHashMap<String,Map<String,Object>> JOBS = new ConcurrentHashMap<>();
    private SafetyScanProgress(){}
    public static final class Scope implements AutoCloseable {
        private final String previous;
        private Scope(String previous){this.previous=previous;}
        @Override public void close(){
            if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);
        }
    }
    public static Scope open(String jobId){
        String previous=ACTIVE.get();
        if(jobId==null||jobId.isBlank())throw new IllegalArgumentException("job id required");
        ACTIVE.set(jobId);
        stage("CONTROLLER_AUDIT");
        return new Scope(previous);
    }
    public static String current(){return Objects.toString(ACTIVE.get(),"");}
    public static void stage(String phase){
        update(current(),phase,0,0,"pending",0,0);
    }
    public static void update(String id,String phase,long done,long total,String backend,long pid,double seconds){
        if(id==null||id.isBlank())return;
        Map<String,Object>info=new LinkedHashMap<>();
        info.put("jobId",id);info.put("stage",phase);
        info.put("framesProcessed",Math.max(0,done));
        info.put("framesExpected",Math.max(0,total));
        info.put("backend",backend);info.put("scannerPid",pid);
        info.put("elapsedSeconds",Math.max(0,seconds));
        info.put("time",Instant.now().toString());
        info.put("updatedAtMillis",System.currentTimeMillis());
        JOBS.put(id,Collections.unmodifiableMap(info));
    }
    public static void finished(String id,boolean approved){
        update(id,approved?"CONTROLLER_APPROVAL":"CONTROLLER_BLOCKED",0,0,"complete",0,0);
    }
    public static Map<String,Object> snapshot(){
        long now=System.currentTimeMillis();
        ArrayList<Map<String,Object>>active=new ArrayList<>();
        JOBS.forEach((id,m)->{
            Object stamp=m.get("updatedAtMillis");
            if(!(stamp instanceof Number n)||now-n.longValue()>120000){
                JOBS.remove(id,m);
            }else if(!"CONTROLLER_APPROVAL".equals(m.get("stage"))&&
                     !"CONTROLLER_BLOCKED".equals(m.get("stage"))){
                Map<String,Object>record=new LinkedHashMap<>(m);
                record.remove("updatedAtMillis");
                active.add(record);
            }
        });
        active.sort(Comparator.comparing(x->String.valueOf(x.get("jobId"))));
        return Map.of("activeScans",active,"count",active.size(),
                "notice","Live progress only; a scan does not authorize publishing");
    }
}
