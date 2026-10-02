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
        List<Path>overlays=new ArrayList<>();
        List<Boolean>motion=new ArrayList<>();
        for(int i=0;i<images.size();i++){overlays.add(null);motion.add(false);}
        return render(images,overlays,motion,sceneWeights,audio,output,requestedEncoder,captionMode,narration);
    }

    public RenderResult render(List<Path>backgrounds,List<Path>overlays,List<Boolean>motion,List<Double>sceneWeights,
                               Path audio,Path output,String requestedEncoder,String captionMode,String narration)throws Exception{
        if(backgrounds.isEmpty())throw new IllegalArgumentException("No visuals");
        if(!overlays.isEmpty()&&overlays.size()!=backgrounds.size())throw new IllegalArgumentException("Overlay count must match visuals");
        if(!motion.isEmpty()&&motion.size()!=backgrounds.size())throw new IllegalArgumentException("Motion flag count must match visuals");
        Files.createDirectories(output.getParent());

        double duration=probeDuration(audio);
        List<Double>sceneDurations=normalizedSceneDurations(backgrounds.size(),sceneWeights,duration);
        Path ass=CaptionWriter.write(output.resolveSibling(output.getFileName()+".ass"),narration,duration,captionMode);
        String resolved=new VideoEncoderProbe(ffmpeg).resolve(requestedEncoder);
        List<String>cmd=motionCommand(backgrounds,overlays,motion,sceneDurations,audio,output,resolved,ass);

        String actual=resolved;
        try{
            FfmpegRunner.run(cmd,1200);
        }catch(Exception first){
            if("nvenc".equals(resolved)&&"auto".equals(VideoEncoderProbe.normalize(requestedEncoder))){
                System.err.println("NVENC render failed; retrying with x264: "+first.getMessage());
                actual="x264";
                cmd=motionCommand(backgrounds,overlays,motion,sceneDurations,audio,output,actual,ass);
                FfmpegRunner.run(cmd,1200);
            }else throw first;
        }

        if(!Files.isRegularFile(output)||Files.size(output)<1024)
            throw new IllegalStateException("FFmpeg did not create a usable video");
        return new RenderResult(output,actual,duration,List.copyOf(cmd));
    }

    private List<String>motionCommand(List<Path>backgrounds,List<Path>overlays,List<Boolean>motion,List<Double>durations,
                                       Path audio,Path output,String encoder,Path ass){
        List<String>cmd=new ArrayList<>(List.of(ffmpeg,"-y","-hide_banner","-loglevel","warning"));
        List<Integer>backgroundInputs=new ArrayList<>();
        List<Integer>overlayInputs=new ArrayList<>();
        int inputIndex=0;

        for(int i=0;i<backgrounds.size();i++){
            double d=durations.get(i);
            cmd.addAll(List.of("-loop","1","-framerate",Integer.toString(fps),"-t",fmt(d),"-i",backgrounds.get(i).toString()));
            backgroundInputs.add(inputIndex++);
            Path overlay=(overlays!=null&&i<overlays.size())?overlays.get(i):null;
            if(overlay!=null){
                cmd.addAll(List.of("-loop","1","-framerate",Integer.toString(fps),"-t",fmt(d),"-i",overlay.toString()));
                overlayInputs.add(inputIndex++);
            }else overlayInputs.add(-1);
        }

        int audioInput=inputIndex;
        cmd.addAll(List.of("-i",audio.toString()));

        StringBuilder fc=new StringBuilder();
        for(int i=0;i<backgrounds.size();i++){
            double d=durations.get(i);
            int frames=Math.max(1,(int)Math.ceil(d*fps));
            String base="["+backgroundInputs.get(i)+":v]";
            if(motion!=null&&i<motion.size()&&Boolean.TRUE.equals(motion.get(i))){
                fc.append(base)
                  .append("scale=").append(width).append(":").append(height).append(":force_original_aspect_ratio=increase,")
                  .append("crop=").append(width).append(":").append(height).append(",")
                  .append("zoompan=z='min(zoom+0.00028,1.028)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=")
                  .append(width).append("x").append(height).append(":fps=").append(fps)
                  .append(",trim=duration=").append(fmt(d)).append(",setpts=PTS-STARTPTS[bg").append(i).append("];");
            }else{
                fc.append(base)
                  .append("scale=").append(width).append(":").append(height).append(":force_original_aspect_ratio=decrease,")
                  .append("pad=").append(width).append(":").append(height).append(":(ow-iw)/2:(oh-ih)/2:black,")
                  .append("fps=").append(fps).append(",trim=duration=").append(fmt(d))
                  .append(",setpts=PTS-STARTPTS[bg").append(i).append("];");
            }

            int overlayInput=overlayInputs.get(i);
            if(overlayInput>=0){
                fc.append("[").append(overlayInput).append(":v]scale=").append(width).append(":").append(height)
                  .append(",format=rgba,trim=duration=").append(fmt(d)).append(",setpts=PTS-STARTPTS[ov").append(i).append("];")
                  .append("[bg").append(i).append("][ov").append(i).append("]overlay=0:0:format=auto,")
                  .append("trim=duration=").append(fmt(d)).append(",setpts=PTS-STARTPTS[s").append(i).append("];");
            }else{
                fc.append("[bg").append(i).append("]trim=duration=").append(fmt(d)).append(",setpts=PTS-STARTPTS[s").append(i).append("];");
            }
        }

        for(int i=0;i<backgrounds.size();i++)fc.append("[s").append(i).append("]");
        fc.append("concat=n=").append(backgrounds.size()).append(":v=1:a=0[vcat];");
        String videoLabel="vcat";
        if(ass!=null){
            fc.append("[vcat]subtitles='").append(escapeFilter(ass.toAbsolutePath().toString())).append("'[vcap];");
            videoLabel="vcap";
        }
        fc.append("[").append(videoLabel).append("]format=yuv420p[vout]");

        cmd.addAll(List.of("-filter_complex",fc.toString(),"-map","[vout]","-map",audioInput+":a","-shortest"));
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

    private static String fmt(double x){return String.format(Locale.US,"%.4f",x);}
    private static String escapeFilter(String x){return x.replace("\\","/").replace(":","\\:").replace("'","\\'").replace(",","\\,").replace("[","\\[").replace("]","\\]");}

    public record RenderResult(Path path,String encoder,double audioDuration,List<String> command){}
}
