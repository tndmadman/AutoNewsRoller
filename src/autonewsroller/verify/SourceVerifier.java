package autonewsroller.verify;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class SourceVerifier {
    private final FactExtractor extractor=new FactExtractor();

    public VerificationResult verify(StoryCluster c,int minIndependent){
        List<Article> independent=independentArticles(c.articles);
        boolean authoritative=c.articles.stream().anyMatch(Article::authoritativePrimary);
        boolean accepted=independent.size()>=minIndependent||authoritative;

        List<FactClaim> facts=extractor.extract(c);
        List<Map<String,Object>> sources=new ArrayList<>();
        for(Article a:c.articles){
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("publisher",a.publisher());
            m.put("headline",a.title());
            m.put("url",a.url());
            m.put("publishedAt",a.publishedAt()==null?null:a.publishedAt().toString());
            m.put("trustTier",a.sourceTrustTier());
            m.put("authoritativePrimary",a.authoritativePrimary());
            sources.add(m);
        }

        double quality=c.articles.stream().mapToDouble(a->Math.max(0,4-a.sourceTrustTier())).average().orElse(0)/3.0;
        double conf=Math.min(.99,0.45+0.12*independent.size()+0.2*quality+(authoritative?.1:0));
        FactPackage fp=new FactPackage(
                c.id,
                c.topic,
                factualSummary(c.topic,facts),
                facts,
                List.of(),
                sources,
                c.articles.size(),
                independent.size(),
                conf,
                authoritative&&independent.size()<minIndependent
        );
        String reason=accepted
                ?(independent.size()>=minIndependent?independent.size()+" independent sources":"authoritative primary-source exception")
                :"only "+independent.size()+" independent source(s)";
        return new VerificationResult(accepted,reason,fp);
    }

    private static String factualSummary(String fallback,List<FactClaim>facts){
        StringBuilder b=new StringBuilder();
        for(FactClaim fact:facts){
            String s=Text.clean(fact.statement());
            if(s.isBlank())continue;
            if(b.length()>0)b.append(' ');
            b.append(s);
            if(b.length()>=700)break;
        }
        String out=b.toString().trim();
        if(out.length()>900)out=out.substring(0,900).trim();
        return out.isBlank()?fallback:out;
    }

    private List<Article> independentArticles(List<Article> all){
        List<Article> out=new ArrayList<>();
        outer:for(Article a:all){
            for(Article b:out){
                if(a.publisher().equalsIgnoreCase(b.publisher()))continue outer;
                String aa=Text.normalize(a.description().isBlank()?a.title():a.description());
                String bb=Text.normalize(b.description().isBlank()?b.title():b.description());
                Set<String>x=new HashSet<>(Arrays.asList(aa.split(" "))),y=new HashSet<>(Arrays.asList(bb.split(" ")));
                if(!x.isEmpty()&&Similarity(x,y)>.90)continue outer;
            }
            out.add(a);
        }
        return out;
    }

    private static boolean Similarity(Set<String>a,Set<String>b){
        Set<String>i=new HashSet<>(a);i.retainAll(b);
        Set<String>u=new HashSet<>(a);u.addAll(b);
        return u.isEmpty()?0:(double)i.size()/u.size();
    }
}
