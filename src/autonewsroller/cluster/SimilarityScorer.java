package autonewsroller.cluster;

import autonewsroller.model.Article;
import autonewsroller.util.Text;
import java.util.*;

public final class SimilarityScorer {
    public double score(Article a,Article b){
        Set<String>x=Text.tokens(a.title()+" "+a.description()),y=Text.tokens(b.title()+" "+b.description()); double j=jaccard(x,y); Set<String>xe=Text.entities(a.title()),ye=Text.entities(b.title()); double e=jaccard(xe,ye); double time=1.0; if(a.publishedAt()!=null&&b.publishedAt()!=null){long h=Math.abs(java.time.Duration.between(a.publishedAt(),b.publishedAt()).toHours());time=h<=6?1:h<=24?.7:.2;} return 0.65*j+0.25*e+0.10*time;
    }
    public static double jaccard(Set<String>a,Set<String>b){if(a.isEmpty()&&b.isEmpty())return 0;Set<String>i=new HashSet<>(a);i.retainAll(b);Set<String>u=new HashSet<>(a);u.addAll(b);return u.isEmpty()?0:(double)i.size()/u.size();}
}
