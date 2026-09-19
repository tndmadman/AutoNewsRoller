package autonewsroller.ingest;

import autonewsroller.config.SourceConfig;
import autonewsroller.util.Json;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class FeedRegistry {
    private FeedRegistry(){}
    public static List<SourceConfig> load(Path path) throws IOException {
        Map<String,Object> root=Json.object(Json.read(path)); List<SourceConfig> out=new ArrayList<>();
        for(Object o:Json.array(root.getOrDefault("sources",List.of())))out.add(SourceConfig.fromMap(Json.object(o)));
        return out;
    }
}
