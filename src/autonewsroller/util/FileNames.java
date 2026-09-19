package autonewsroller.util;

import java.nio.file.*;
import java.io.IOException;

public final class FileNames {
    private FileNames(){}
    public static String safe(String value){
        String s=(value==null?"":value).toLowerCase().replaceAll("[^a-z0-9]+","_").replaceAll("^_+|_+$","");
        if(s.length()>72)s=s.substring(0,72).replaceAll("_+$",""); return s.isBlank()?"news_video":s;
    }
    public static Path unique(Path dir,String base,String ext) throws IOException {
        Files.createDirectories(dir); String stem=safe(base); Path p=dir.resolve(stem+ext); if(!Files.exists(p))return p;
        for(int i=2;i<10000;i++){p=dir.resolve(stem+"_"+i+ext);if(!Files.exists(p))return p;} throw new IOException("Could not allocate unique filename for "+stem);
    }
}
