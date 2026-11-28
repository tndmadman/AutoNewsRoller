package autonewsroller.video;

import autonewsroller.audit.VideoAudit;
import autonewsroller.util.Json;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Bounded, pre-approval visual-only correction. Never cuts or substitutes audio. */
public final class VideoSyncRepair {
    private final String ffmpeg,ffprobe;
    private final int width,height;
    public VideoSyncRepair(String ffmpeg,String ffprobe,int width,int height){
        this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.width=width;this.height=height;
    }
    private record Media(double video,double audio,int frames,double videoStart,double audioStart,int sampleRate){}
    private Media probe(Path file)throws Exception{
        Map<String,String> v=RenderTimeline.probe(ffprobe,file,"v:0","duration,nb_frames,start_time");
        Map<String,String> a=RenderTimeline.probe(ffprobe,file,"a:0","duration,start_time,sample_rate");
        Media m=new Media(Double.parseDouble(v.get("duration")),Double.parseDouble(a.get("duration")),
                Integer.parseInt(v.get("nb_frames")),Double.parseDouble(v.get("start_time")),
                Double.parseDouble(a.get("start_time")),Integer.parseInt(a.get("sample_rate")));
        if(!Double.isFinite(m.video)||!Double.isFinite(m.audio)||m.video<=0||m.audio<=0||m.frames<1
                ||!Double.isFinite(m.videoStart)||!Double.isFinite(m.audioStart)||m.sampleRate<=0)
            throw new IllegalStateException("Invalid synchronization stream metadata");
        return m;
    }
    private static double audioTolerance(Media m,RenderTimeline t){
        return Math.max(t.audioTolerance(),1024.0/m.sampleRate+0.002);
    }
    private static boolean audioValid(Media m,RenderTimeline t){
        return Math.abs(m.audio-t.audioDuration())<=audioTolerance(m,t)&&Math.abs(m.audioStart)<=0.002;
    }
    private static boolean aligned(Media m,RenderTimeline t){
        return audioValid(m,t)&&m.frames==t.totalFrames()&&Math.abs(m.video-t.videoDuration())<=0.002
                &&Math.abs(m.videoStart)<=0.002
                &&Math.abs(m.video-m.audio)<=Math.min(0.35,1.0/t.fps()+audioTolerance(m,t));
    }
    private static void metrics(Map<String,Object> r,Media m,String suffix){
        r.put("videoDuration"+suffix,m.video);r.put("audioDuration"+suffix,m.audio);
        r.put("difference"+suffix+"Seconds",Math.abs(m.video-m.audio));
        r.put("videoFrames"+suffix,m.frames);r.put("videoStart"+suffix,m.videoStart);r.put("audioStart"+suffix,m.audioStart);
    }
    public Map<String,Object> verify(Path output,Path source,RenderTimeline t,String encoder,
            boolean enabled,double maxMismatch,int maxAttempts,Consumer<String> progress)throws Exception{
        if(!Double.isFinite(maxMismatch)||maxMismatch<0||maxMismatch>2.0||maxAttempts<0||maxAttempts>2)
            throw new IllegalArgumentException("Sync repair limits: 0–2 seconds and 0–2 attempts");
        progress.accept("VERIFYING AUDIO/VIDEO SYNC");
        long start=System.nanoTime();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("sourceAudioDuration",t.audioDuration());report.put("sourceAudioSamples",t.audioSamples());
        report.put("sourceSampleRate",t.sampleRate());report.put("expectedVideoFrames",t.totalFrames());
        report.put("expectedVideoDuration",t.videoDuration());report.put("sceneFrames",t.sceneFrames());
        report.put("repairAttempted",false);report.put("repairAttempts",0);
        List<Map<String,Object>> attempts=new ArrayList<>();report.put("attemptDetails",attempts);
        try{
        Media before=probe(output);metrics(report,before,"BeforeRepair");
        if(!audioValid(before,t))throw new IllegalStateException("SYNC_FAILED: encoded audio does not match source narration timeline");
        if(aligned(before,t)){
            metrics(report,before,"AfterRepair");report.put("repairStatus","VERIFIED");
            report.put("actualEncoder",encoder);progress.accept("AUDIO/VIDEO SYNC VERIFIED");return report;
        }
        double mismatch=Math.max(Math.max(Math.abs(before.video-t.videoDuration()),Math.abs(before.video-before.audio)),
                Math.abs((long)before.frames-t.totalFrames())/(double)t.fps());
        if(!enabled||maxAttempts==0||mismatch>maxMismatch||Math.abs(before.videoStart)>0.002)
            throw new IllegalStateException("SYNC_FAILED: timing mismatch outside enabled repair limits: "+mismatch);
        // Do not repair silent or undecodable audio. Keep the original AAC bytes.
        String volume=FfmpegRunner.run(List.of(ffmpeg,"-hide_banner","-nostdin","-xerror","-i",output.toString(),
                "-map","0:a:0","-vn","-af","volumedetect","-f","null","-"),90);
        var level=java.util.regex.Pattern.compile("mean_volume:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(volume);
        if(!level.find()||Double.parseDouble(level.group(1))<=-80)
            throw new IllegalStateException("SYNC_FAILED: silent or unreadable encoded audio");
        progress.accept("AUDIO/VIDEO MISMATCH DETECTED");
        Exception last=null;
        for(int attempt=1;attempt<=maxAttempts;attempt++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sync repair interrupted");
            String actual="nvenc".equals(encoder)&&attempt==1?"nvenc":"x264";
            progress.accept("REPAIRING VIDEO SYNC — ATTEMPT "+attempt+"/"+maxAttempts);
            report.put("repairAttempted",true);report.put("repairAttempts",attempt);
            Map<String,Object> detail=new LinkedHashMap<>();attempts.add(detail);
            detail.put("attempt",attempt);detail.put("encoder",actual);
            long attemptStart=System.nanoTime();
            Path temp=Files.createTempFile(output.toAbsolutePath().getParent(),".sync-repair-",".mp4");
            try{
                List<String> cmd=new ArrayList<>(List.of(ffmpeg,"-y","-hide_banner","-nostdin","-xerror","-loglevel","warning",
                        "-i",output.toString(),"-map","0:v:0","-map","0:a:0","-vf",
                        "fps="+t.fps()+",tpad=stop_mode=clone:stop="+t.totalFrames()+",trim=end_frame="+t.totalFrames()
                                +",settb=1/"+t.fps()+",setpts=N",
                        "-r",Integer.toString(t.fps()),"-fps_mode","cfr","-c:a","copy"));
                if("nvenc".equals(actual))cmd.addAll(List.of("-c:v","h264_nvenc","-preset","p6","-tune","hq","-rc","vbr","-cq","19","-b:v","0"));
                else cmd.addAll(List.of("-c:v","libx264","-preset","medium","-crf","19"));
                cmd.addAll(List.of("-movflags","+faststart",temp.toString()));
                Path log=output.resolveSibling(output.getFileName()+".sync-attempt-"+attempt+".log");
                detail.put("command",List.copyOf(cmd));detail.put("log",log.getFileName().toString());
                FfmpegRunner.run(cmd,1200,log);
                detail.put("ffmpegExitStatus",0);
                progress.accept("VERIFYING REPAIRED VIDEO");
                Media after=probe(temp);
                if(!aligned(after,t))throw new IllegalStateException("Repaired streams still misaligned");
                new VideoAudit(ffprobe,ffmpeg).audit(temp,source,width,height,t.fps());
                Files.move(temp,output,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                metrics(report,after,"AfterRepair");report.put("repairAttempted",true);
                report.put("repairAttempts",attempt);report.put("repairMethod","FRAME_ALIGNMENT_AUDIO_COPY");
                report.put("actualEncoder",actual);report.put("repairStatus","VERIFIED");
                report.put("repairDurationMs",(System.nanoTime()-start)/1_000_000);
                detail.put("status","VERIFIED");
                progress.accept("AUDIO/VIDEO SYNC VERIFIED");return report;
            }catch(InterruptedException e){detail.put("status","INTERRUPTED");Thread.currentThread().interrupt();throw e;}
            catch(Exception e){last=e;detail.put("status","FAILED");detail.put("failure",String.valueOf(e.getMessage()));progress.accept("SYNC REPAIR FAILED: "+e.getMessage());}
            finally{detail.put("durationMs",(System.nanoTime()-attemptStart)/1_000_000);Files.deleteIfExists(temp);}
        }
        throw new IllegalStateException("SYNC_FAILED: bounded repair exhausted; original retained",last);
        }catch(Exception failure){
            report.put("repairStatus",failure instanceof InterruptedException?"INTERRUPTED":"FAILED");
            report.put("failure",String.valueOf(failure.getMessage()));throw failure;
        }finally{
            report.put("verificationDurationMs",(System.nanoTime()-start)/1_000_000);
            try{Json.write(output.resolveSibling(output.getFileName()+".sync.json"),report);}
            catch(Exception diagnosticFailure){System.err.println("Sync diagnostics write failed: "+diagnosticFailure);}
        }
    }
}
