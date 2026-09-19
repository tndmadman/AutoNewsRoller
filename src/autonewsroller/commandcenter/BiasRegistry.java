package autonewsroller.commandcenter;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

public final class BiasRegistry {
    private final Map<String,String> labels=new HashMap<>();
    private final String provider,asOf,note;

    private BiasRegistry(String provider,String asOf,String note){this.provider=provider;this.asOf=asOf;this.note=note;}

    public static BiasRegistry load(Path path){
        String provider="unconfigured",asOf="",note="No political source classifications configured.";
        BiasRegistry out=new BiasRegistry(provider,asOf,note);
        if(!Files.isRegularFile(path))return out;
        try{
            Map<String,Object>root=Json.object(Json.read(path));
            out.labels.clear();
            Object src=root.get("sources");
            if(src instanceof Map<?,?>m){
                for(var e:m.entrySet()){
                    String key=String.valueOf(e.getKey()).trim().toLowerCase(Locale.ROOT);
                    String value;
                    if(e.getValue() instanceof Map<?,?>detail)value=String.valueOf(detail.get("classification"));
                    else value=String.valueOf(e.getValue());
                    value=normalize(value);
                    if(!key.isBlank())out.labels.put(key,value);
                }
            }
            return new BiasRegistryWithLabels(
                    String.valueOf(root.getOrDefault("provider","unconfigured")),
                    String.valueOf(root.getOrDefault("asOf","")),
                    String.valueOf(root.getOrDefault("note","Political source labels are optional external metadata.")),
                    out.labels
            );
        }catch(Exception e){return out;}
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

    private static final class BiasRegistryWithLabels extends BiasRegistry{
        BiasRegistryWithLabels(String provider,String asOf,String note,Map<String,String>src){super(provider,asOf,note);super.labels.putAll(src);}
    }
}
