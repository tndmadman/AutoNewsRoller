package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Hashing;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Evidence is drawn from actual supplied articles, not free-form model quotations. */
public final class PoliticalFramingEvidenceBuilder {
    public static final String VERSION="framing-evidence-v1";
    public record Evidence(String storyId,String contentFingerprint,List<Map<String,Object>> articles,Map<String,Object> politicalAnalysis) {
        public Map<String,Object> toMap(){
            Map<String,Object>out=new LinkedHashMap<>();
            out.put("schemaVersion",VERSION);out.put("storyId",storyId);
            out.put("builtAt",Instant.now().toString());out.put("contentFingerprint",contentFingerprint);
            out.put("articles",articles);out.put("politicalAnalysis",politicalAnalysis);
            out.put("independentSourceCount",articles.size());return out;
        }
    }
    public Evidence build(StoryCluster cluster,FactPackage facts,PoliticalVisualData political){
        if(facts.independentSourceCount()<2)
            throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: Two independent sources required.");
        if(political==null||!political.analysisAvailable())
            throw new IllegalStateException("Current political analysis required for framing exposure.");
        LinkedHashMap<String,Article> independent=new LinkedHashMap<>();
        for(Article article:cluster.articles){
            if(article.publisher().isBlank()||article.title().isBlank()||article.url().isBlank())continue;
            String key=article.publisher().strip().toLowerCase(Locale.ROOT);
            independent.putIfAbsent(key,article);
        }
        if(independent.size()<2)throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: No two attributable independent headlines.");
        List<Article>selected=new ArrayList<>(independent.values());
        Article first=selected.get(0),second=null;
        for(int i=1;i<selected.size();i++)
            if(!first.title().equalsIgnoreCase(selected.get(i).title())&&sameEvent(first.title(),selected.get(i).title())){second=selected.get(i);break;}
        if(second==null)throw new IllegalStateException("INSUFFICIENT_FRAMING_EVIDENCE: Headlines do not provide a verifiable same-event comparison.");
        List<Map<String,Object>>out=new ArrayList<>();
        for(Article a:List.of(first,second)){
            Map<String,Object>x=new LinkedHashMap<>();
            x.put("articleId",a.id());x.put("publisher",a.publisher());x.put("url",a.url());
            x.put("publishedAt",a.publishedAt()==null?"":a.publishedAt().toString());
            x.put("headline",a.title());x.put("verifiedExcerpt",a.title());
            x.put("excerptLocation","headline");x.put("description",limit(a.description(),1200));
            x.put("bodyText",limit(a.bodyText(),3600));x.put("framingMechanism","headline wording and emphasis");
            x.put("confidence","observable text only; no inference of publisher motives");
            x.put("articleContentFingerprint",Hashing.sha256(a.id()+"|"+a.title()+"|"+a.description()+"|"+a.bodyText()));
            out.add(x);
        }
        Map<String,Object>analysis=political.toMap();
        return new Evidence(cluster.id,Hashing.sha256(cluster.articles.stream()
            .map(a->a.id()+"|"+a.title()+"|"+a.description()+"|"+a.bodyText()).sorted()
            .reduce("",(a,b)->a+"|"+b)),List.copyOf(out),analysis);
    }
    private static boolean sameEvent(String a,String b){
        Set<String>left=tokens(a),right=tokens(b);left.retainAll(right);
        return left.size()>=2;
    }
    private static Set<String> tokens(String s){
        Set<String>ignore=Set.of("about","after","before","against","their","there","these","those","would","could","should","reports","report","news","says","said","amid","under","with","from","into","over","this","that");
        Set<String>words=new HashSet<>();
        for(String word:Pattern.compile("[^a-z0-9]+").split(s.toLowerCase(Locale.ROOT)))
            if(word.length()>=4&&!ignore.contains(word))words.add(word);
        return words;
    }
    private static String limit(String s,int count){return s==null?"":s.substring(0,Math.min(count,s.length()));}
}
