package autonewsroller.model;

import java.time.Instant;
import java.util.*;

public record Article(String id,String publisher,String title,String url,String canonicalUrl,Instant publishedAt,Instant discoveredAt,String author,String category,String description,String bodyText,String language,int sourceTrustTier,boolean authoritativePrimary) {
    public Map<String,Object> toMap(){
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",id);m.put("publisher",publisher);m.put("title",title);m.put("url",url);m.put("canonicalUrl",canonicalUrl);m.put("publishedAt",publishedAt==null?null:publishedAt.toString());m.put("discoveredAt",discoveredAt==null?null:discoveredAt.toString());m.put("author",author);m.put("category",category);m.put("description",description);m.put("bodyText",bodyText);m.put("language",language);m.put("sourceTrustTier",sourceTrustTier);m.put("authoritativePrimary",authoritativePrimary); return m;
    }
}
