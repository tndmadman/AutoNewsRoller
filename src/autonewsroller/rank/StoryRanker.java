package autonewsroller.rank;

import autonewsroller.model.*;
import java.time.*;
import java.util.*;

public final class StoryRanker {
    private final Map<String,Double>w;
    public StoryRanker(Map<String,Double>w){this.w=w;}
    public double score(StoryCluster c,FactPackage f,Instant now,double novelty){double age=c.articles.stream().map(Article::publishedAt).filter(Objects::nonNull).mapToLong(x->Math.max(0,Duration.between(x,now).toHours())).min().orElse(24);double fresh=Math.max(0,1-age/48.0);double sc=Math.min(1,f.independentSourceCount()/4.0);double sq=c.articles.stream().mapToDouble(a->Math.max(0,4-a.sourceTrustTier())/3.0).average().orElse(0);double suitable=Math.min(1,f.facts().size()/8.0);return w.getOrDefault("freshness",.3)*fresh+w.getOrDefault("sourceCount",.2)*sc+w.getOrDefault("sourceQuality",.2)*sq+w.getOrDefault("novelty",.2)*novelty+w.getOrDefault("videoSuitability",.1)*suitable;}
}
