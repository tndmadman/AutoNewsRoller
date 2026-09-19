package autonewsroller.video;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class VideoRenderer {
    private final String ffmpeg,ffprobe;private final int width,height,fps;
    public VideoRenderer(String ffmpeg,String ffprobe,int width,int height,int fps){this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.width=width;this.height=height;this.fps=fps;}
    public RenderResult render(List<Path>images,Path audio,Path output,String requestedEncoder,String captionMode,String narration)throws Exception{
        if(images.isEmpty())throw new IllegalArgumentException("No visuals");Files.createDirectories(output.getParent());double duration=probeDuration(audio);double each=duration/images.size();
        Path concat=output.resolveSibling(output.getFileName()+".concat.txt");StringBuilder c=new StringBuilder();for(Path p:images){c.append("file '").append(escapeConcat(p.toAbsolutePath().toString())).append("'\n").append("duration ").append(String.format(Locale.US,"%.4f",each)).append("\n");}c.append("file '").append(escapeConcat(images.get(images.size()-1).toAbsolutePath().toString())).append("'\n");Files.writeString(concat,c.toString(),StandardCharsets.UTF_8);
        Path srt=CaptionWriter.write(output.resolveSibling(output.getFileName()+".srt"),narration,duration,captionMode);String enc=new VideoEncoderProbe(ffmpeg).resolve(requestedEncoder);
        List<String>cmd=new ArrayList<>(List.of(ffmpeg,"-y","-hide_banner","-loglevel","warning","-f","concat","-safe","0","-i",concat.toString(),"-i",audio.toString()));
        String vf="scale="+width+":"+height+":force_original_aspect_ratio=decrease,pad="+width+":"+height+":(ow-iw)/2:(oh-ih)/2:black,fps="+fps+",format=yuv420p";if(srt!=null)vf+=",subtitles='"+escapeFilter(srt.toAbsolutePath().toString())+"':force_style='FontName=Arial,FontSize=18,Outline=2,Shadow=0,MarginV=90'";cmd.addAll(List.of("-vf",vf,"-shortest"));
        if(enc.equals("nvenc"))cmd.addAll(List.of("-c:v","h264_nvenc","-preset","p6","-tune","hq","-rc","vbr","-cq","19","-b:v","0"));else cmd.addAll(List.of("-c:v","libx264","-preset","medium","-crf","19"));cmd.addAll(List.of("-c:a","aac","-b:a","192k","-movflags","+faststart",output.toString()));
        FfmpegRunner.run(cmd,900);if(!Files.isRegularFile(output)||Files.size(output)<1024)throw new IllegalStateException("FFmpeg did not create a usable video");return new RenderResult(output,enc,duration,List.copyOf(cmd));
    }
    public double probeDuration(Path file)throws Exception{String o=FfmpegRunner.run(List.of(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();return Double.parseDouble(o.split("\\R")[0]);}
    public String version(){try{return FfmpegRunner.run(List.of(ffmpeg,"-version"),10).lines().findFirst().orElse("unknown");}catch(Exception e){return "unknown";}}
    private static String escapeConcat(String x){return x.replace("'","'\\''");}private static String escapeFilter(String x){return x.replace("\\","/").replace(":","\\:").replace("'","\\'").replace(",","\\,").replace("[","\\[").replace("]","\\]");}
    public record RenderResult(Path path,String encoder,double audioDuration,List<String> command){}
}
