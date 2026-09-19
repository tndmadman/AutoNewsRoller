package autonewsroller.model;

import java.time.Instant;
import java.util.*;

public record Article(String id,String publisher,String title,String url,String canonicalUrl,Instant publishedAt,Instant discoveredAt,String author,String category,String description,String bodyText,String language,int sourceTrustTier,boolean authoritativePrimary) {
    public Map<String,Object> toMap(){
        Map<String,Object> m=new LinkedHashMap<>(); m.put("id",id);m.put("publisher",publisher);m.put("title",title);m.put("url",url);m.put("canonicalUrl",canonicalUrl);m.put("publishedAt",publishedAt==null?null:publishedAt.toString());m.put("discoveredAt",discoveredAt==null?null:discoveredAt.toString());m.put("author",author);m.put("category",category);m.put("description",description);m.put("bodyText",bodyText);m.put("language",language);m.put("sourceTrustTier",sourceTrustTier);m.put("authoritativePrimary",authoritativePrimary); return m;
    }
    public static Article fromMap(Map<String,Object>m){
        return new Article(
                str(m,"id"),str(m,"publisher"),str(m,"title"),str(m,"url"),str(m,"canonicalUrl"),
                instant(m.get("publishedAt")),instant(m.get("discoveredAt")),str(m,"author"),str(m,"category"),
                str(m,"description"),str(m,"bodyText"),str(m,"language"),
                num(m.get("sourceTrustTier"),3).intValue(),bool(m.get("authoritativePrimary"))
        );
    }
    private static String str(Map<String,Object>m,String k){Object v=m.get(k);return v==null?"":String.valueOf(v);}
    private static Instant instant(Object v){try{return v==null||String.valueOf(v).isBlank()?null:Instant.parse(String.valueOf(v));}catch(Exception e){return null;}}
    private static Number num(Object v,Number d){return v instanceof Number n?n:d;}
    private static boolean bool(Object v){return v instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(v));}
}
