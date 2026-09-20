package autonewsroller.video;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class VideoRenderer {
    private final String ffmpeg,ffprobe;
    private final int width,height,fps;

    public VideoRenderer(String ffmpeg,String ffprobe,int width,int height,int fps){
        this.ffmpeg=ffmpeg;this.ffprobe=ffprobe;this.width=width;this.height=height;this.fps=fps;
    }

    public RenderResult render(List<Path>images,Path audio,Path output,String requestedEncoder,String captionMode,String narration)throws Exception{
        return render(images,List.of(),audio,output,requestedEncoder,captionMode,narration);
    }

    public RenderResult render(List<Path>images,List<Double>sceneWeights,Path audio,Path output,String requestedEncoder,String captionMode,String narration)throws Exception{
        if(images.isEmpty())throw new IllegalArgumentException("No visuals");
        Files.createDirectories(output.getParent());
        double duration=probeDuration(audio);
        List<Double>sceneDurations=normalizedSceneDurations(images.size(),sceneWeights,duration);

        Path concat=output.resolveSibling(output.getFileName()+".concat.txt");
        StringBuilder c=new StringBuilder();
        for(int i=0;i<images.size();i++){
            Path p=images.get(i);
            c.append("file '").append(escapeConcat(p.toAbsolutePath().toString())).append("'\n")
             .append("duration ").append(String.format(Locale.US,"%.4f",sceneDurations.get(i))).append("\n");
        }
        c.append("file '").append(escapeConcat(images.get(images.size()-1).toAbsolutePath().toString())).append("'\n");
        Files.writeString(concat,c.toString(),StandardCharsets.UTF_8);

        Path ass=CaptionWriter.write(output.resolveSibling(output.getFileName()+".ass"),narration,duration,captionMode);
        String resolved=new VideoEncoderProbe(ffmpeg).resolve(requestedEncoder);
        List<String>cmd=command(concat,audio,output,resolved,ass);

        String actual=resolved;
        try{
            FfmpegRunner.run(cmd,1200);
        }catch(Exception first){
            if("nvenc".equals(resolved)&&"auto".equals(VideoEncoderProbe.normalize(requestedEncoder))){
                System.err.println("NVENC render failed; retrying with x264: "+first.getMessage());
                actual="x264";
                cmd=command(concat,audio,output,actual,ass);
                FfmpegRunner.run(cmd,1200);
            }else throw first;
        }

        if(!Files.isRegularFile(output)||Files.size(output)<1024)
            throw new IllegalStateException("FFmpeg did not create a usable video");
        return new RenderResult(output,actual,duration,List.copyOf(cmd));
    }

    private List<String>command(Path concat,Path audio,Path output,String encoder,Path ass){
        List<String>cmd=new ArrayList<>(List.of(
                ffmpeg,"-y","-hide_banner","-loglevel","warning",
                "-f","concat","-safe","0","-i",concat.toString(),
                "-i",audio.toString()
        ));
        String vf="scale="+width+":"+height+":force_original_aspect_ratio=decrease,"+
                "pad="+width+":"+height+":(ow-iw)/2:(oh-ih)/2:black,"+
                "fps="+fps+",format=yuv420p";
        if(ass!=null)vf+=",subtitles='"+escapeFilter(ass.toAbsolutePath().toString())+"'";
        cmd.addAll(List.of("-vf",vf,"-shortest"));
        if("nvenc".equals(encoder))
            cmd.addAll(List.of("-c:v","h264_nvenc","-preset","p6","-tune","hq","-rc","vbr","-cq","19","-b:v","0"));
        else
            cmd.addAll(List.of("-c:v","libx264","-preset","medium","-crf","19"));
        cmd.addAll(List.of("-c:a","aac","-b:a","192k","-fps_mode","cfr","-movflags","+faststart",output.toString()));
        return cmd;
    }

    private static List<Double>normalizedSceneDurations(int count,List<Double>weights,double duration){
        if(count<=0)return List.of();
        List<Double>w=new ArrayList<>(count);
        double total=0;
        for(int i=0;i<count;i++){
            double v=(weights!=null&&i<weights.size()&&weights.get(i)!=null)?weights.get(i):1.0;
            if(!Double.isFinite(v)||v<=0)v=1.0;
            w.add(v);total+=v;
        }
        List<Double>out=new ArrayList<>(count);
        double assigned=0;
        for(int i=0;i<count;i++){
            double d=i==count-1?Math.max(.05,duration-assigned):duration*w.get(i)/total;
            out.add(d);assigned+=d;
        }
        return out;
    }

    public double probeDuration(Path file)throws Exception{
        String o=FfmpegRunner.run(List.of(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
        return Double.parseDouble(o.split("\\R")[0]);
    }

    public String version(){
        try{return FfmpegRunner.run(List.of(ffmpeg,"-version"),10).lines().findFirst().orElse("unknown");}
        catch(Exception e){return "unknown";}
    }

    private static String escapeConcat(String x){return x.replace("'","'\\''");}
    private static String escapeFilter(String x){return x.replace("\\","/").replace(":","\\:").replace("'","\\'").replace(",","\\,").replace("[","\\[").replace("]","\\]");}

    public record RenderResult(Path path,String encoder,double audioDuration,List<String> command){}
}
