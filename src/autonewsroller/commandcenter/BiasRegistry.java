package autonewsroller.commandcenter;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

public final class BiasRegistry {
    public record SourceRating(String bucket,String originalClassification,String confidence,String url){}

    private final Map<String,SourceRating> ratings;
    private final String provider,asOf,note,providerUrl;

    private BiasRegistry(String provider,String asOf,String note,String providerUrl,Map<String,SourceRating>ratings){
        this.provider=provider;this.asOf=asOf;this.note=note;this.providerUrl=providerUrl;this.ratings=new HashMap<>(ratings);
    }

    public static BiasRegistry load(Path path){
        if(!Files.isRegularFile(path))return empty("No political source classifications configured.");
        try{
            Map<String,Object>root=Json.object(Json.read(path));
            Map<String,SourceRating>ratings=new HashMap<>();
            Object src=root.get("sources");
            if(src instanceof Map<?,?>m){
                for(var e:m.entrySet()){
                    String key=String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
                    if(key.isBlank())continue;
                    if(e.getValue() instanceof Map<?,?>detailRaw){
                        Map<String,Object>detail=Json.object(detailRaw);
                        String raw=String.valueOf(detail.getOrDefault("classification","unknown"));
                        String original=String.valueOf(detail.getOrDefault("originalClassification",raw));
                        ratings.put(key,new SourceRating(
                                normalizeBucket(raw),
                                original,
                                String.valueOf(detail.getOrDefault("confidence","")),
                                String.valueOf(detail.getOrDefault("url",""))
                        ));
                    }else{
                        String raw=String.valueOf(e.getValue());
                        ratings.put(key,new SourceRating(normalizeBucket(raw),raw,"",""));
                    }
                }
            }
            return new BiasRegistry(
                    String.valueOf(root.getOrDefault("provider","unconfigured")),
                    String.valueOf(root.getOrDefault("asOf","")),
                    String.valueOf(root.getOrDefault("note","Political source labels are external metadata.")),
                    String.valueOf(root.getOrDefault("providerUrl","")),
                    ratings
            );
        }catch(Exception e){return empty("Could not load source classification metadata: "+e.getMessage());}
    }

    public Map<String,Object> mix(Collection<String>publishers){
        int left=0,center=0,right=0,unknown=0;
        Map<String,String>buckets=new LinkedHashMap<>();
        Map<String,Object>details=new LinkedHashMap<>();
        for(String p:publishers){
            SourceRating rating=ratings.getOrDefault(p.toLowerCase(Locale.ROOT),new SourceRating("unknown","Not rated","",""));
            buckets.put(p,rating.bucket());
            Map<String,Object>d=new LinkedHashMap<>();
            d.put("bucket",rating.bucket());
            d.put("originalClassification",rating.originalClassification());
            d.put("confidence",rating.confidence());
            d.put("url",rating.url());
            details.put(p,d);
            switch(rating.bucket()){case "left"->left++;case "center"->center++;case "right"->right++;default->unknown++;}
        }
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("provider",provider);m.put("providerUrl",providerUrl);m.put("asOf",asOf);m.put("note",note);
        m.put("left",left);m.put("center",center);m.put("right",right);m.put("unknown",unknown);
        m.put("publishers",buckets);m.put("publisherDetails",details);
        return m;
    }

    private static BiasRegistry empty(String note){return new BiasRegistry("unconfigured","",note,"",Map.of());}
    private static String normalizeBucket(String v){
        if(v==null)return "unknown";
        String x=v.trim().toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
        return switch(x){
            case "left","lean left"->"left";
            case "center","centre","middle"->"center";
            case "right","lean right"->"right";
            default->"unknown";
        };
    }
}
