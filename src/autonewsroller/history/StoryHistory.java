package autonewsroller.history;

import autonewsroller.model.StoryCluster;
import autonewsroller.util.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class StoryHistory {
    private final Path path;
    public StoryHistory(Path path){this.path=path;}
    public boolean seen(String fingerprint){if(!Files.isRegularFile(path))return false;try{for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)){if(line.isBlank())continue;Map<String,Object>m=Json.object(Json.parse(line));if(fingerprint.equals(String.valueOf(m.get("fingerprint"))))return true;}}catch(Exception ignored){}return false;}
    public void append(StoryCluster c,Path video) throws IOException {Files.createDirectories(path.getParent());Map<String,Object>m=new LinkedHashMap<>();m.put("storyId",c.id);m.put("normalizedHeadline",c.topic);m.put("fingerprint",c.fingerprint);m.put("entities",c.entities);m.put("category",c.articles.isEmpty()?"":c.articles.get(0).category());m.put("sourceUrls",c.articles.stream().map(a->a.url()).toList());m.put("publishers",c.publishers());m.put("generatedVideo",video==null?null:video.toString());m.put("generationTime",Instant.now().toString());Files.writeString(path,Json.stringify(m)+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
}
