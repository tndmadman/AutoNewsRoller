package autonewsroller.history;

import autonewsroller.model.Article; import autonewsroller.util.Json;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.io.*;import java.util.*;

public final class ArticleHistory {
    private final Path path; public ArticleHistory(Path path){this.path=path;}
    public Set<String> ids(){Set<String>s=new HashSet<>();if(!Files.isRegularFile(path))return s;try{for(String l:Files.readAllLines(path,StandardCharsets.UTF_8)){if(l.isBlank())continue;s.add(String.valueOf(Json.object(Json.parse(l)).get("id")));}}catch(Exception ignored){}return s;}
    public void append(Article a)throws IOException{Files.createDirectories(path.getParent());Files.writeString(path,Json.stringify(a.toMap())+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}
}
