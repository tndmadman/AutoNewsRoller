package autonewsroller.ingest;

import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;

final class FeedCache {
    private final Path path;
    private final Map<String,Object> data;

    FeedCache(Path path){
        this.path=path;
        Map<String,Object>d=new LinkedHashMap<>();
        try{if(Files.isRegularFile(path))d.putAll(Json.object(Json.read(path)));}catch(Exception ignored){}
        this.data=d;
    }

    synchronized Map<String,String> validators(String url){
        Object o=data.get(url);
        if(!(o instanceof Map<?,?>m))return Map.of();
        Map<String,String>r=new HashMap<>();
        Object etag=m.get("etag"),last=m.get("lastModified");
        if(etag!=null&&!String.valueOf(etag).isBlank())r.put("etag",String.valueOf(etag));
        if(last!=null&&!String.valueOf(last).isBlank())r.put("lastModified",String.valueOf(last));
        return r;
    }

    synchronized byte[] body(String url){
        Object o=data.get(url);
        if(!(o instanceof Map<?,?>m))return null;
        Object encoded=m.get("bodyBase64");
        if(encoded==null||String.valueOf(encoded).isBlank())return null;
        try{return Base64.getDecoder().decode(String.valueOf(encoded));}catch(Exception ignored){return null;}
    }

    synchronized void put(String url,String etag,String lastModified,byte[] body){
        Map<String,Object>m=new LinkedHashMap<>();
        if(etag!=null&&!etag.isBlank())m.put("etag",etag);
        if(lastModified!=null&&!lastModified.isBlank())m.put("lastModified",lastModified);
        if(body!=null&&body.length>0)m.put("bodyBase64",Base64.getEncoder().encodeToString(body));
        data.put(url,m);
        try{Json.write(path,data);}catch(Exception ignored){}
    }
}
