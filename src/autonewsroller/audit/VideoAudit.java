package autonewsroller.audit;

import autonewsroller.video.*;import java.nio.file.*;import java.util.*;
public final class VideoAudit {
    private final String ffprobe,ffmpeg;
    public VideoAudit(String ffprobe){this(ffprobe,"ffmpeg");}
    public VideoAudit(String ffprobe,String ffmpeg){this.ffprobe=ffprobe;this.ffmpeg=ffmpeg;}
    public Map<String,Object>audit(Path video,Path audio,int w,int h)throws Exception{
        Map<String,Object>m=new LinkedHashMap<>();m.put("status","rejected");if(!Files.isRegularFile(video)||Files.size(video)<1024)throw new IllegalStateException("video missing/empty");if(!Files.isRegularFile(audio)||Files.size(audio)<128)throw new IllegalStateException("audio missing/empty");
        String volume=FfmpegRunner.run(List.of(ffmpeg,"-hide_banner","-i",audio.toString(),"-af","volumedetect","-f","null","-"),30);if(volume.toLowerCase(Locale.ROOT).contains("mean_volume: -inf"))throw new IllegalStateException("audio is silent");
        String dims=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams","v:0","-show_entries","stream=width,height","-of","csv=s=x:p=0",video.toString()),30).trim();if(!dims.contains(w+"x"+h))throw new IllegalStateException("wrong resolution: "+dims);
        String dur=FfmpegRunner.run(List.of(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",video.toString()),30).trim();double seconds=Double.parseDouble(dur.split("\\R")[0]);if(seconds<=0)throw new IllegalStateException("invalid video duration");m.put("videoDuration",seconds);m.put("resolution",dims);m.put("audioNonSilent",true);m.put("status","approved");return m;
    }
}
