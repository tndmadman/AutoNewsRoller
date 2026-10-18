package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Hashing;
import java.time.Instant;
import java.util.*;

/**
 * Exposes the framing of ONE attributable article. Other articles may corroborate
 * facts upstream but must not become a publisher-comparison video.
 */
public final class PoliticalFramingEvidenceBuilder {
    public static final String VERSION="framing-evidence-v2-single-article";

    public record Evidence(String storyId,String contentFingerprint,List<Map<String,Object>> articles,Map<String,Object> politicalAnalysis){
        public Map<String,Object> toMap(){
            Map<String,Object>out=new LinkedHashMap<>();
            out.put("schemaVersion",VERSION);
            out.put("analysisScope","SINGLE_ARTICLE");
            out.put("storyId",storyId);
            out.put("builtAt",Instant.now().toString());
            out.put("contentFingerprint",contentFingerprint);
            out.put("articles",articles);
            out.put("politicalAnalysis",politicalAnalysis);
            out.put("articlesAnalyzed",articles.size());
            return out;
        }
    }

    public Evidence build(StoryCluster cluster,FactPackage facts,PoliticalVisualData political){
        if(political==null||!political.analysisAvailable())
            throw new IllegalStateException("FRAMING_ANALYSIS_UNAVAILABLE: Complete textual framing analysis is required.");
        // Prefer the source headline used for this story. Never demand a second publisher.
        Article selected=null;
        for(Article article:cluster.articles){
            if(!valid(article))continue;
            if(selected==null)selected=article;
            if(article.title().equalsIgnoreCase(cluster.topic)){selected=article;break;}
        }
        if(selected==null)
            throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: An attributable source headline and URL are required.");
        Map<String,Object>source=new LinkedHashMap<>();
        source.put("articleId",selected.id());
        source.put("publisher",selected.publisher());
        source.put("url",selected.url());
        source.put("publishedAt",selected.publishedAt()==null?"":selected.publishedAt().toString());
        source.put("headline",selected.title());
        source.put("verifiedExcerpt",selected.title());
        source.put("excerptLocation","headline");
        source.put("description",limit(selected.description(),1800));
        source.put("bodyText",limit(selected.bodyText(),3600));
        source.put("availableTextScope",!selected.bodyText().isBlank()?"HEADLINE_DESCRIPTION_BODY":
                !selected.description().isBlank()?"HEADLINE_AND_DESCRIPTION":"HEADLINE_ONLY");
        source.put("framingMechanism","Examine observable wording, emphasis, attribution, and implications in this one article.");
        source.put("articleContentFingerprint",Hashing.sha256(selected.id()+"|"+selected.title()+"|"+selected.description()+"|"+selected.bodyText()));
        List<Map<String,Object>>excerpts=new ArrayList<>();
        excerpts.add(Map.of("location","headline","text",selected.title()));
        addExactExcerpt(excerpts,"description",selected.description());
        addExactExcerpt(excerpts,"body",selected.bodyText());
        source.put("attributedExcerpts",List.copyOf(excerpts));
        String fingerprint=Hashing.sha256(cluster.articles.stream()
                .map(a->a.id()+"|"+a.title()+"|"+a.description()+"|"+a.bodyText())
                .sorted().reduce("",(a,b)->a+"|"+b));
        return new Evidence(cluster.id,fingerprint,List.of(source),political.toMap());
    }

    /** Retain only the chosen article's model classification; never add a source comparison. */
    public Evidence withArticleAnalysis(Evidence evidence,Map<String,Object>rawAnalysis){
        if(rawAnalysis==null||rawAnalysis.isEmpty())return evidence;
        String id=String.valueOf(evidence.articles().get(0).get("articleId"));
        Map<String,Object>filtered=new LinkedHashMap<>();
        filtered.put("analysisScope","SINGLE_ARTICLE");
        filtered.put("primaryArticleId",id);
        filtered.put("textBasis",rawAnalysis.getOrDefault("textBasis","Available source headline/description/body"));
        filtered.put("model",rawAnalysis.getOrDefault("model",""));
        Object list=rawAnalysis.get("articles");
        if(list instanceof List<?>articles){
            for(Object raw:articles)if(raw instanceof Map<?,?>item&&id.equals(String.valueOf(item.get("id")))){
                Map<String,Object>selected=new LinkedHashMap<>();
                for(var entry:item.entrySet())selected.put(String.valueOf(entry.getKey()),entry.getValue());
                filtered.put("primaryArticleAnalysis",selected);
                break;
            }
        }
        if(!filtered.containsKey("primaryArticleAnalysis"))
            filtered.put("primaryArticleAnalysis",Map.of("classification","uncertain",
                "rationale","No article-specific classification returned; use observable source text only."));
        return new Evidence(evidence.storyId(),evidence.contentFingerprint(),evidence.articles(),filtered);
    }

    private static void addExactExcerpt(List<Map<String,Object>>out,String location,String original){
        if(original==null||original.isBlank())return;
        String compact=original.replaceAll("\\s+"," ").strip();
        if(compact.length()>240){
            int boundary=compact.lastIndexOf(' ',240);
            compact=compact.substring(0,boundary>=80?boundary:240).strip();
        }
        if(!compact.isBlank())out.add(Map.of("location",location,"text",compact));
    }
    private static boolean valid(Article article){
        return article!=null&&!article.publisher().isBlank()&&!article.title().isBlank()&&!article.url().isBlank();
    }
    private static String limit(String s,int count){return s==null?"":s.substring(0,Math.min(count,s.length()));}
}
