package autonewsroller.config;

import autonewsroller.util.Json;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class NewsConfig {
    private final Properties p=new Properties();
    private final Map<String,Double> ranking=new LinkedHashMap<>();
    public static NewsConfig load(Path root) throws IOException {
        NewsConfig c=new NewsConfig(); Path d=root.resolve("defaults.txt"); if(Files.isRegularFile(d))try(InputStream in=Files.newInputStream(d)){c.p.load(in);} 
        Path r=root.resolve("config/ranking.json"); if(Files.isRegularFile(r)){for(var e:Json.object(Json.read(r)).entrySet()) if(e.getValue() instanceof Number n)c.ranking.put(e.getKey(),n.doubleValue());}
        return c;
    }
    public String get(String k,String def){return p.getProperty(k,def).trim();}
    public void set(String k,String value){if(value==null)p.remove(k);else p.setProperty(k,value);}
    public int getInt(String k,int def){try{return Integer.parseInt(get(k,String.valueOf(def)));}catch(Exception e){return def;}}
    public double getDouble(String k,double def){try{return Double.parseDouble(get(k,String.valueOf(def)));}catch(Exception e){return def;}}
    public boolean getBool(String k,boolean def){return Boolean.parseBoolean(get(k,String.valueOf(def)));}
    public List<String> csv(String k,String def){return Arrays.stream(get(k,def).split(",")).map(String::trim).filter(s->!s.isBlank()).toList();}
    public Map<String,Double> ranking(){return Collections.unmodifiableMap(ranking);}
    public int maxAgeHours(){return getInt("newsMaxAgeHours",24);} public int minimumIndependentSources(){return getInt("minimumIndependentSources",2);} public int duration(){return getInt("targetDurationSeconds",60);} public int workers(){return getInt("workers",4);}
}
