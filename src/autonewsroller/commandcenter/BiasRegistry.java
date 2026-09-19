package autonewsroller.commandcenter;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

public final class BiasRegistry {
    private final Map<String,String> labels;
    private final String provider,asOf,note;

    private BiasRegistry(String provider,String asOf,String note,Map<String,String>labels){
        this.provider=provider;this.asOf=asOf;this.note=note;this.labels=new HashMap<>(labels);
    }

    public static BiasRegistry load(Path path){
        if(!Files.isRegularFile(path))return new BiasRegistry("unconfigured","","No political source classifications configured.",Map.of());
        try{
            Map<String,Object>root=Json.object(Json.read(path));
            Map<String,String>labels=new HashMap<>();
            Object src=root.get("sources");
            if(src instanceof Map<?,?>m){
                for(var e:m.entrySet()){
                    String key=String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
                    String value;
                    if(e.getValue() instanceof Map<?,?>detail)value=String.valueOf(detail.get("classification"));
                    else value=String.valueOf(e.getValue());
                    if(!key.isBlank())labels.put(key,normalize(value));
                }
            }
            return new BiasRegistry(
                    String.valueOf(root.getOrDefault("provider","unconfigured")),
                    String.valueOf(root.getOrDefault("asOf","")),
                    String.valueOf(root.getOrDefault("note","Political source labels are optional external metadata.")),
                    labels
            );
        }catch(Exception e){return new BiasRegistry("unconfigured","","Could not load source classification metadata.",Map.of());}
    }

    public Map<String,Object> mix(Collection<String>publishers){
        int left=0,center=0,right=0,unknown=0;
        Map<String,String>details=new LinkedHashMap<>();
        for(String p:publishers){
            String c=labels.getOrDefault(p.toLowerCase(Locale.ROOT),"unknown");
            details.put(p,c);
            switch(c){case "left"->left++;case "center"->center++;case "right"->right++;default->unknown++;}
        }
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("provider",provider);m.put("asOf",asOf);m.put("note",note);
        m.put("left",left);m.put("center",center);m.put("right",right);m.put("unknown",unknown);m.put("publishers",details);
        return m;
    }

    private static String normalize(String v){
        if(v==null)return "unknown";String x=v.trim().toLowerCase(Locale.ROOT);
        return switch(x){case "left","center","right"->x;default->"unknown";};
    }
}
