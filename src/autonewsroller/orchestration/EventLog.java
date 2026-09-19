package autonewsroller.orchestration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.function.Consumer;

public final class EventLog {
    private final Path path;
    private final Consumer<WorkerState> listener;
    public EventLog(Path p){this(p,null);}
    public EventLog(Path p,Consumer<WorkerState> listener){path=p;this.listener=listener;}
    public synchronized void emit(int worker,int slot,PipelineStage stage,String detail){
        WorkerState state=new WorkerState(worker,slot,stage,detail,Instant.now());
        try{
            Files.createDirectories(path.getParent());
            Files.writeString(path,state.json()+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }catch(IOException e){System.err.println("event log failed: "+e.getMessage());}
        if(listener!=null)try{listener.accept(state);}catch(Exception ignored){}
    }
}
