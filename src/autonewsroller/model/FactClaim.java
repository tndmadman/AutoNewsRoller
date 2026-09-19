package autonewsroller.model;

import java.util.*;

public record FactClaim(String statement,List<String> supportingSources,double confidence,boolean contested) {
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("statement",statement);m.put("supportingSources",supportingSources);m.put("confidence",confidence);m.put("contested",contested);return m;}
    public static FactClaim fromMap(Map<String,Object>m){
        List<String>src=new ArrayList<>();Object x=m.get("supportingSources");if(x instanceof List<?>l)for(Object v:l)src.add(String.valueOf(v));
        double c=m.get("confidence") instanceof Number n?n.doubleValue():0;
        boolean contested=m.get("contested") instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(m.getOrDefault("contested",false)));
        return new FactClaim(String.valueOf(m.getOrDefault("statement","")),List.copyOf(src),c,contested);
    }
}
