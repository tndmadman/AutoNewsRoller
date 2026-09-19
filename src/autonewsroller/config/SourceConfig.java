package autonewsroller.config;

import java.util.Map;

public record SourceConfig(String name,String type,String category,String url,boolean enabled,int trustTier,boolean authoritativePrimary) {
    public static SourceConfig fromMap(Map<String,Object> m){
        return new SourceConfig(
            String.valueOf(m.getOrDefault("name","unknown")), String.valueOf(m.getOrDefault("type","rss")),
            String.valueOf(m.getOrDefault("category","general")), String.valueOf(m.getOrDefault("url","")),
            Boolean.parseBoolean(String.valueOf(m.getOrDefault("enabled",true))), ((Number)m.getOrDefault("trustTier",3L)).intValue(),
            Boolean.parseBoolean(String.valueOf(m.getOrDefault("authoritativePrimary",false)))
        );
    }
}
