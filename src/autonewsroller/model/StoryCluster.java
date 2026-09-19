package autonewsroller.model;

import java.util.*;

public final class StoryCluster {
    public final String id; public final String topic; public final List<Article> articles; public final Set<String> entities; public final String fingerprint;
    public StoryCluster(String id,String topic,List<Article> articles,Set<String> entities,String fingerprint){this.id=id;this.topic=topic;this.articles=List.copyOf(articles);this.entities=Set.copyOf(entities);this.fingerprint=fingerprint;}
    public Set<String> publishers(){LinkedHashSet<String>s=new LinkedHashSet<>();for(Article a:articles)s.add(a.publisher());return s;}
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("id",id);m.put("topic",topic);m.put("fingerprint",fingerprint);m.put("entities",entities);m.put("publishers",publishers());m.put("articles",articles.stream().map(Article::toMap).toList());return m;}
}
