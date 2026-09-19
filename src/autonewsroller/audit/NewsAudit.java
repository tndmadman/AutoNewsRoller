package autonewsroller.audit;

import autonewsroller.model.*;import java.util.*;
public final class NewsAudit {public List<String>check(FactPackage f,NewsScript s,int minSources){List<String>x=new ArrayList<>();if(f.facts().isEmpty())x.add("fact package has no facts");if(f.independentSourceCount()<minSources&&!f.authoritativePrimaryAccepted())x.add("independent source threshold not met");if(s.narration()==null||s.narration().isBlank())x.add("script empty");return x;}}
