package autonewsroller.orchestration;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BatchCoordinator {
    private final Path root;
    private final NewsPipeline pipeline;
    public BatchCoordinator(Path root,NewsPipeline pipeline){this.root=root;this.pipeline=pipeline;}

    public BatchResult run(int target,int workers,String category,int maxAge,int minSources,int duration,String encoder,boolean comfy,boolean dryRun,boolean fixture,Path batchDir)throws Exception{
        List<NewsPipeline.Candidate> cands=fixture?pipeline.discoverFixtures(maxAge,minSources):pipeline.discover(category,maxAge,minSources,!dryRun);
        if(cands.isEmpty()) return new BatchResult(0,0,List.of("No verified candidate stories found"));
        int effective=Math.max(1,Math.min(workers,cands.size()));
        System.out.println("Requested workers: "+workers+"; effective workers: "+effective+"; reason: "+(effective==workers?"requested capacity available":"candidate count/resource bound"));
        ExecutorService pool=Executors.newFixedThreadPool(effective);
        AtomicInteger next=new AtomicInteger(),approved=new AtomicInteger(),attempts=new AtomicInteger(),inFlight=new AtomicInteger();
        Object reservationLock=new Object();
        List<String> errors=Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> fs=new ArrayList<>();
        for(int w=1;w<=effective;w++){
            final int worker=w;
            fs.add(pool.submit(()->{
                while(true){
                    int idx;
                    synchronized(reservationLock){
                        if(approved.get()>=target) break;
                        if(approved.get()+inFlight.get()>=target){ idx=-2; }
                        else {
                            idx=next.getAndIncrement();
                            if(idx>=cands.size()) break;
                            inFlight.incrementAndGet();
                        }
                    }
                    if(idx==-2){ try{Thread.sleep(20);}catch(InterruptedException e){Thread.currentThread().interrupt();break;} continue; }
                    int slot=idx+1;attempts.incrementAndGet();
                    try{
                        Path slotDir=batchDir.resolve(String.format("slot_%03d",slot));
                        pipeline.produce(cands.get(idx),worker,slot,slotDir,duration,encoder,comfy,dryRun);
                        synchronized(reservationLock){inFlight.decrementAndGet();approved.incrementAndGet();reservationLock.notifyAll();}
                    }catch(Exception e){
                        errors.add("slot "+slot+": "+e.getMessage());
                        pipeline.rejected(worker,slot,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
                        System.err.println("Slot "+slot+" rejected: "+e.getMessage());
                        synchronized(reservationLock){inFlight.decrementAndGet();reservationLock.notifyAll();}
                    }
                }
            }));
        }
        for(Future<?> f:fs) f.get();
        pool.shutdownNow();
        return new BatchResult(approved.get(),attempts.get(),List.copyOf(errors));
    }
    public record BatchResult(int approved,int attempts,List<String>errors){}
}
