package autonewsroller.model;

import autonewsroller.util.Json;
import java.util.*;

public record FactPackage(String storyId,String headline,String summary,List<FactClaim> facts,List<FactClaim> disputedClaims,List<Map<String,Object>> sources,int sourceCount,int independentSourceCount,double confidence,boolean authoritativePrimaryAccepted) {
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("storyId",storyId);m.put("headline",headline);m.put("summary",summary);m.put("facts",facts.stream().map(FactClaim::toMap).toList());m.put("disputedClaims",disputedClaims.stream().map(FactClaim::toMap).toList());m.put("sources",sources);m.put("sourceCount",sourceCount);m.put("independentSourceCount",independentSourceCount);m.put("confidence",confidence);m.put("authoritativePrimaryAccepted",authoritativePrimaryAccepted);return m;}
    public static FactPackage fromMap(Map<String,Object>m){
        List<FactClaim>facts=claims(m.get("facts")),disputed=claims(m.get("disputedClaims"));
        List<Map<String,Object>>sources=new ArrayList<>();Object so=m.get("sources");if(so instanceof List<?>l)for(Object v:l)if(v instanceof Map<?,?>)sources.add(new LinkedHashMap<>(Json.object(v)));
        return new FactPackage(
                String.valueOf(m.getOrDefault("storyId","")),String.valueOf(m.getOrDefault("headline","")),String.valueOf(m.getOrDefault("summary","")),
                facts,disputed,List.copyOf(sources),integer(m.get("sourceCount")),integer(m.get("independentSourceCount")),
                m.get("confidence") instanceof Number n?n.doubleValue():0,
                m.get("authoritativePrimaryAccepted") instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(m.getOrDefault("authoritativePrimaryAccepted",false)))
        );
    }
    private static List<FactClaim>claims(Object x){List<FactClaim>out=new ArrayList<>();if(x instanceof List<?>l)for(Object v:l)if(v instanceof Map<?,?>)out.add(FactClaim.fromMap(Json.object(v)));return List.copyOf(out);}
    private static int integer(Object x){return x instanceof Number n?n.intValue():0;}
}
