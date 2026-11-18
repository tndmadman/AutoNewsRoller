package autonewsroller.gpu;

import java.nio.channels.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Shared file-based lane for ComfyUI, Qwen and CUDA safety inference on one root. */
public final class GpuLane implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private GpuLane(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}
    public static GpuLane shared(Path p)throws Exception{return acquire(p,true);}
    public static GpuLane exclusive(Path p)throws Exception{return acquire(p,false);}
    private static GpuLane acquire(Path p,boolean shared)throws Exception{
        Files.createDirectories(p.toAbsolutePath().getParent());
        FileChannel channel=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        try{return new GpuLane(channel,channel.lock(0,Long.MAX_VALUE,shared));}
        catch(Exception e){channel.close();throw e;}
    }
    /** Never wait forever behind ComfyUI. Any lock failure blocks GPU only. */
    public static GpuLane exclusiveBounded(Path p,Duration timeout)throws Exception{
        if(timeout.isNegative()||timeout.isZero())throw new IllegalArgumentException("positive timeout required");
        Files.createDirectories(p.toAbsolutePath().getParent());
        FileChannel channel=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
        long deadline=System.nanoTime()+timeout.toNanos();
        try{
            while(System.nanoTime()<deadline){
                try{
                    FileLock lock=channel.tryLock();
                    if(lock!=null)return new GpuLane(channel,lock);
                }catch(OverlappingFileLockException busy){}
                TimeUnit.MILLISECONDS.sleep(200);
            }
            throw new java.util.concurrent.TimeoutException("GPU lane busy beyond "+timeout);
        }catch(Exception e){channel.close();throw e;}
    }
    @Override public void close()throws Exception{try{lock.release();}finally{channel.close();}}
}
