package autonewsroller.audit;

import autonewsroller.video.*;
import java.nio.file.*;
import java.util.*;

public final class VideoAudit {
    private final String ffprobe,ffmpeg;
    public VideoAudit(String ffprobe){this(ffprobe,"ffmpeg");}
    public VideoAudit(String ffprobe,String ffmpeg){this.ffprobe=ffprobe;this.ffmpeg=ffmpeg;}

    public Map<String,Object>audit(Path video,Path sourceAudio,int w,int h)throws Exception{
        Map<String,Object>m=new LinkedHashMap<>();m.put("status","rejected");
        if(!Files.isRegularFile(video)||Files.size(video)<1024)throw new IllegalStateException("video missing/empty");
        if(!Files.isRegularFile(sourceAudio)||Files.size(sourceAudio)<128)throw new IllegalStateException("audio missing/empty");

        String volume=FfmpegRunner.run(List.of(ffmpeg,"-hide_banner","-i",sourceAudio.toString(),"-af","volumedetect","-f","null","-"),30);
        if(volume.toLowerCase(Locale.ROOT).contains("mean_volume: -inf"))throw new IllegalStateException("audio is silent");

        String dims=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams","v:0","-show_entries","stream=width,height","-of","csv=s=x:p=0",video.toString()),30).trim();
        if(!dims.contains(w+"x"+h))throw new IllegalStateException("wrong resolution: "+dims);

        double seconds=probeFormatDuration(video);
        if(seconds<=60.0)throw new IllegalStateException(String.format(Locale.US,"final video must exceed 60 sec; got %.3f",seconds));

        double fps=probeRate(video,"avg_frame_rate");
        if(Math.abs(fps-30.0)>.05)throw new IllegalStateException(String.format(Locale.US,"final video is not CFR 30 fps; avg_frame_rate=%.4f",fps));

        double videoStream=probeStreamDuration(video,"v:0");
        double audioStream=probeStreamDuration(video,"a:0");
        if(videoStream>0&&audioStream>0&&Math.abs(videoStream-audioStream)>.35)
            throw new IllegalStateException(String.format(Locale.US,"audio/video duration mismatch: video=%.3f audio=%.3f",videoStream,audioStream));

        m.put("videoDuration",seconds);
        m.put("videoStreamDuration",videoStream);
        m.put("audioStreamDuration",audioStream);
        m.put("resolution",dims);
        m.put("averageFps",fps);
        m.put("cfr30",true);
        m.put("audioNonSilent",true);
        m.put("status","approved");
        return m;
    }

    private double probeFormatDuration(Path file)throws Exception{
        String out=FfmpegRunner.run(List.of(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
        return parseNumber(out);
    }

    private double probeStreamDuration(Path file,String selector)throws Exception{
        try{
            String out=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams",selector,"-show_entries","stream=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
            return parseNumber(out);
        }catch(Exception e){return 0;}
    }

    private double probeRate(Path file,String field)throws Exception{
        String out=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams","v:0","-show_entries","stream="+field,"-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
        String v=out.lines().findFirst().orElse("0").trim();
        if(v.contains("/")){
            String[]p=v.split("/",2);
            return Double.parseDouble(p[0])/Math.max(1e-9,Double.parseDouble(p[1]));
        }
        return Double.parseDouble(v);
    }

    private static double parseNumber(String out){
        String v=out.lines().findFirst().orElse("0").trim();
        if(v.isBlank()||"N/A".equalsIgnoreCase(v))return 0;
        return Double.parseDouble(v);
    }
}
