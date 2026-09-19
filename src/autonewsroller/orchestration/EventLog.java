package autonewsroller.orchestration;

import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.time.Instant;
public final class EventLog {private final Path path;public EventLog(Path p){path=p;}public synchronized void emit(int worker,int slot,PipelineStage stage,String detail){try{Files.createDirectories(path.getParent());Files.writeString(path,new WorkerState(worker,slot,stage,detail,Instant.now()).json()+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){System.err.println("event log failed: "+e.getMessage());}}}
