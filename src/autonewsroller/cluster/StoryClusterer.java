package autonewsroller.cluster;

import autonewsroller.model.*;
import autonewsroller.util.*;
import java.util.*;

public final class StoryClusterer {
    private final SimilarityScorer scorer=new SimilarityScorer(); private final double threshold;
    public StoryClusterer(){this(0.52);} public StoryClusterer(double threshold){this.threshold=threshold;}
    public List<StoryCluster> cluster(List<Article> articles){
        List<List<Article>> groups=new ArrayList<>();
        for(Article a:articles){List<Article>best=null;double bs=0;for(List<Article>g:groups){double s=0;for(Article b:g)s=Math.max(s,scorer.score(a,b));if(s>bs){bs=s;best=g;}}if(best!=null&&bs>=threshold)best.add(a);else{List<Article>g=new ArrayList<>();g.add(a);groups.add(g);}}
        List<StoryCluster> out=new ArrayList<>();for(List<Article>g:groups){g.sort(Comparator.comparing(Article::publishedAt,Comparator.nullsLast(Comparator.naturalOrder())).reversed());String topic=g.get(0).title();Set<String>entities=new LinkedHashSet<>();for(Article a:g)entities.addAll(Text.entities(a.title()));String basis=Text.normalize(topic)+"|"+String.join(",",entities.stream().map(String::toLowerCase).sorted().toList());String fp=Hashing.sha256(basis);out.add(new StoryCluster(fp.substring(0,16),topic,g,entities,fp));}return out;
    }
}
