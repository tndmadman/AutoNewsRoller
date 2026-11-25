package autonewsroller.video;

import autonewsroller.audit.VideoAudit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

public final class VideoRendererSyncSelfTest {
    public static void main(String[] args)throws Exception{run(Files.createTempDirectory("video-sync-"));}
    public static void run(Path dir)throws Exception{
        for(int fps:List.of(24,25,30,60))for(int count:List.of(1,3,8,15,30,60)){
            int total=(int)Math.ceil(35.55*fps);
            List<Integer> frames=RenderTimeline.allocate(total,count,List.of(0.1,Double.NaN,3.0));
            require(frames.stream().mapToInt(Integer::intValue).sum()==total,"Frame sum");
            require(frames.stream().allMatch(x->x>0),"Positive scenes");
            require(frames.equals(RenderTimeline.allocate(total,count,List.of(0.1,Double.NaN,3.0))),"Determinism");
        }
        Path wav=dir.resolve("sync-source.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-v","error","-f","lavfi","-i",
                "sine=frequency=440:duration=35.550:sample_rate=48000",wav.toString()),40);
        RenderTimeline t=RenderTimeline.fromAudio("ffprobe",wav,30,3,List.of(1.0,2.0,3.0));
        require(t.totalFrames()==1067&&t.audioSamples()==1706400,"Exact audio clock");
        Path image=dir.resolve("sync-background.png");
        BufferedImage bi=new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB);
        ImageIO.write(bi,"png",image.toFile());
        Path rendered=dir.resolve("sync-rendered.mp4");
        VideoRenderer.RenderResult result=new VideoRenderer("ffmpeg","ffprobe",64,64,30)
                .render(List.of(image,image,image),List.of(1.0,2.0,3.0),wav,rendered,"x264","off","fixture");
        require(Boolean.FALSE.equals(result.syncReport().get("repairAttempted")),"Normal render must not need repair");
        new VideoAudit("ffprobe","ffmpeg").audit(rendered,wav,64,64,30);
        for(int frames:List.of(1084,1050)){
            Path mismatch=dir.resolve("sync-mismatch-"+frames+".mp4");
            FfmpegRunner.run(List.of("ffmpeg","-y","-v","error","-i",wav.toString(),"-filter_complex",
                    "color=c=blue:s=64x64:r=30,trim=end_frame="+frames+",setpts=PTS-STARTPTS[v]",
                    "-map","[v]","-map","0:a:0","-r","30","-c:v","libx264","-preset","ultrafast",
                    "-c:a","aac",mismatch.toString()),90);
            VideoSyncRepair repair=new VideoSyncRepair("ffmpeg","ffprobe",64,64);
            byte[] original=Files.readAllBytes(mismatch);
            try{repair.verify(mismatch,wav,t,"x264",false,2,2,s->{});throw new AssertionError("Disabled repair accepted mismatch");}
            catch(IllegalStateException expected){}
            require(Arrays.equals(original,Files.readAllBytes(mismatch)),"Disabled repair preserves original");
            Map<String,Object> report=repair.verify(mismatch,wav,t,"x264",true,2,2,System.out::println);
            require(Boolean.TRUE.equals(report.get("repairAttempted")),"Repair attempted");
            require(((Number)report.get("differenceAfterRepairSeconds")).doubleValue()<0.034,"One frame target");
            require(Boolean.FALSE.equals(repair.verify(mismatch,wav,t,"x264",true,2,2,s->{}).get("repairAttempted")),"Idempotence");
            System.out.println(report);
        }
        System.out.println("VideoRendererSyncSelfTest PASS");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
