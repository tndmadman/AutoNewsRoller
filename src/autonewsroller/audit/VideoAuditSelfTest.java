package autonewsroller.audit;

import autonewsroller.video.FfmpegRunner;
import java.nio.file.*;
import java.util.*;

/** Verifies real FFprobe/FFmpeg media gates using synthetic, non-publishable fixtures. */
public final class VideoAuditSelfTest {
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("autonews-video-audit-");
        Path sourceAudio=dir.resolve("source.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","sine=frequency=440:duration=2.6:sample_rate=48000",
                "-c:a","pcm_s16le",sourceAudio.toString()),40);
        Path good=dir.resolve("good.mp4");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","color=c=blue:s=320x568:r=30:d=2.6",
                "-i",sourceAudio.toString(),"-c:v","libx264","-pix_fmt","yuv420p",
                "-c:a","aac","-shortest",good.toString()),90);
        VideoAudit audit=new VideoAudit("ffprobe","ffmpeg");
        Map<String,Object>report=audit.audit(good,sourceAudio,320,568,30);
        require("approved".equals(report.get("status")),"Valid MP4 should be approved");
        require(Math.abs(((Number)report.get("averageFps")).doubleValue()-30)<0.05,
                "Expected rate must be measured");
        require(((Number)report.get("sourceAudioDuration")).doubleValue()>2.5,
                "Narration duration must be recorded");
        expectRejected(()->audit.audit(good,sourceAudio,320,568,24),
                "Configured frame-rate mismatch");
        expectRejected(()->audit.audit(good,sourceAudio,640,320,30),
                "Wrong resolution");

        Path noAudio=dir.resolve("no-audio.mp4");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","color=c=blue:s=320x568:r=30:d=2.6",
                "-an","-c:v","libx264","-pix_fmt","yuv420p",noAudio.toString()),90);
        expectRejected(()->audit.audit(noAudio,sourceAudio,320,568,30),
                "Missing encoded audio");

        Path silence=dir.resolve("silent.mp4");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","color=c=blue:s=320x568:r=30:d=2.6",
                "-f","lavfi","-i","anullsrc=r=48000:cl=mono",
                "-t","2.6","-c:v","libx264","-pix_fmt","yuv420p",
                "-c:a","aac","-shortest",silence.toString()),90);
        expectRejected(()->audit.audit(silence,sourceAudio,320,568,30),
                "Silent encoded audio");
        System.out.println("VideoAuditSelfTest PASS");
    }

    private interface Attempt{void run()throws Exception;}
    private static void expectRejected(Attempt action,String context)throws Exception{
        try{action.run();throw new AssertionError("Audit permitted "+context);}
        catch(IllegalStateException expected){/* fail closed */}
    }
    private static void require(boolean value,String reason){
        if(!value)throw new AssertionError(reason);
    }
}
