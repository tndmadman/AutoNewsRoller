package autonewsroller.orchestration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** Append-only batch/worker logs. Structured state remains in events.jsonl. */
public final class RuntimeLog {
    private final Path batchDir;
    public RuntimeLog(Path batchDir){ this.batchDir=batchDir; }
    public synchronized void debug(String message){ append(batchDir.resolve("debug.log"), message); }
    public synchronized void worker(int worker,String message){ append(batchDir.resolve("runtime").resolve(String.format("worker_%03d.log",worker)), message); }
    private static void append(Path path,String message){
        try{
            Files.createDirectories(path.getParent());
            Files.writeString(path, Instant.now()+" "+message+System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException e){ System.err.println("runtime log failed: "+e.getMessage()); }
    }
}
