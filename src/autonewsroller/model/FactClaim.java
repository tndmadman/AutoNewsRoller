package autonewsroller.model;

import java.util.*;

public record FactClaim(String statement,List<String> supportingSources,double confidence,boolean contested) {
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("statement",statement);m.put("supportingSources",supportingSources);m.put("confidence",confidence);m.put("contested",contested);return m;}
}
