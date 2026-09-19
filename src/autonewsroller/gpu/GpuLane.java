package autonewsroller.gpu;

import java.nio.channels.*;import java.nio.file.*;
public final class GpuLane implements AutoCloseable {private final FileChannel channel;private final FileLock lock;private GpuLane(FileChannel c,FileLock l){channel=c;lock=l;}public static GpuLane shared(Path p)throws Exception{return acquire(p,true);}public static GpuLane exclusive(Path p)throws Exception{return acquire(p,false);}private static GpuLane acquire(Path p,boolean shared)throws Exception{Files.createDirectories(p.toAbsolutePath().getParent());FileChannel c=FileChannel.open(p,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);FileLock l=c.lock(0,Long.MAX_VALUE,shared);return new GpuLane(c,l);}public void close()throws Exception{lock.release();channel.close();}}
