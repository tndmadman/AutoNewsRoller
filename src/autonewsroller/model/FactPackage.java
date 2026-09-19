package autonewsroller.model;

import java.util.*;

public record FactPackage(String storyId,String headline,String summary,List<FactClaim> facts,List<FactClaim> disputedClaims,List<Map<String,Object>> sources,int sourceCount,int independentSourceCount,double confidence,boolean authoritativePrimaryAccepted) {
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("storyId",storyId);m.put("headline",headline);m.put("summary",summary);m.put("facts",facts.stream().map(FactClaim::toMap).toList());m.put("disputedClaims",disputedClaims.stream().map(FactClaim::toMap).toList());m.put("sources",sources);m.put("sourceCount",sourceCount);m.put("independentSourceCount",independentSourceCount);m.put("confidence",confidence);m.put("authoritativePrimaryAccepted",authoritativePrimaryAccepted);return m;}
}
