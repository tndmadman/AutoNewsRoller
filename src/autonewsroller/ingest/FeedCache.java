package autonewsroller.ingest;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

final class FeedCache {
    private final Path path; private final Map<String,Object> data;
    FeedCache(Path path){this.path=path;Map<String,Object>d=new LinkedHashMap<>();try{if(Files.isRegularFile(path))d.putAll(Json.object(Json.read(path)));}catch(Exception ignored){}this.data=d;}
    synchronized Map<String,String> get(String url){Object o=data.get(url);if(!(o instanceof Map<?,?>m))return Map.of();Map<String,String>r=new HashMap<>();for(var e:m.entrySet())r.put(String.valueOf(e.getKey()),String.valueOf(e.getValue()));return r;}
    synchronized void put(String url,String etag,String lastModified){Map<String,Object>m=new LinkedHashMap<>();if(etag!=null)m.put("etag",etag);if(lastModified!=null)m.put("lastModified",lastModified);data.put(url,m);try{Json.write(path,data);}catch(Exception ignored){}}
}
