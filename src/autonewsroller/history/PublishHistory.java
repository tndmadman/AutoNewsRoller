package autonewsroller.history;

import autonewsroller.util.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Lightweight append-only record of approved outputs, separate from semantic story history. */
public final class PublishHistory {
    private final Path path;
    public PublishHistory(Path path){ this.path=path; }
    public synchronized void append(String storyId,Path video,String fingerprint) throws IOException {
        Files.createDirectories(path.getParent());
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("storyId",storyId);row.put("video",video.toString());row.put("fingerprint",fingerprint);row.put("publishedAt", Instant.now().toString());
        Files.writeString(path, Json.stringify(row)+System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
}
