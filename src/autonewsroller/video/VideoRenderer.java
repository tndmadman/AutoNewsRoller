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

    public record Scene(Path image,double duration,String transition){}

    public RenderResult render(List<Path>images,Path audio,Path output,String requestedEncoder,String captionMode,String narration)throws Exception{
        double audioDuration=probeDuration(audio);
        double each=audioDuration/images.size();
        List<Scene>scenes=new ArrayList<>();
        for(int i=0;i<images.size();i++)scenes.add(new Scene(images.get(i),each,i%2==0?"push_in":"zoom_out"));
        return renderScenes(scenes,audio,output,requestedEncoder,captionMode,narration,44,9);
    }

    public RenderResult renderScenes(List<Scene>scenes,Path audio,Path output,String requestedEncoder,String captionMode,String narration,int captionFontSize,int captionMaxWords)throws Exception{
        if(scenes.isEmpty())throw new IllegalArgumentException("No visuals");
        Files.createDirectories(output.getParent());
        double audioDuration=probeDuration(audio);
        double totalDuration=scenes.stream().mapToDouble(Scene::duration).sum();
        if(totalDuration<audioDuration-.15)throw new IllegalArgumentException("visual track shorter than narration: visuals="+totalDuration+" audio="+audioDuration);

        Path segmentDir=output.resolveSibling(output.getFileName()+".segments");
        Files.createDirectories(segmentDir);
        List<Path>segments=new ArrayList<>();
        for(int i=0;i<scenes.size();i++){
            Scene scene=scenes.get(i);
            Path segment=segmentDir.resolve(String.format(Locale.ROOT,"%02d.mp4",i));
            System.out.printf(Locale.US,"[Render] Building POST_CARD scene %d/%d duration=%.2fs transition=%s%n",i+1,scenes.size(),scene.duration(),scene.transition());
            renderScene(scene,segment,i);
            segments.add(segment);
        }

        Path concat=output.resolveSibling(output.getFileName()+".concat.txt");
        StringBuilder c=new StringBuilder();
        for(Path p:segments)c.append("file '").append(escapeConcat(p.toAbsolutePath().toString())).append("'\n");
        Files.writeString(concat,c.toString(),StandardCharsets.UTF_8);

        Path ass=CaptionWriter.write(
                output.resolveSibling(output.getFileName()+".ass"),
                narration,audioDuration,captionMode,width,height,captionFontSize,captionMaxWords
        );

        String requested=new VideoEncoderProbe(ffmpeg).resolve(requestedEncoder);
        List<String>cmd=finalCommand(concat,audio,output,requested,ass,totalDuration,audioDuration);
        String used=requested;
        try{
            FfmpegRunner.run(cmd,1200);
        }catch(Exception e){
            if(!"nvenc".equals(requested))throw e;
            System.err.println("[Render] NVENC final encode failed; retrying x264: "+e.getMessage());
            used="x264";cmd=finalCommand(concat,audio,output,used,ass,totalDuration,audioDuration);
            FfmpegRunner.run(cmd,1200);
        }

        if(!Files.isRegularFile(output)||Files.size(output)<1024)throw new IllegalStateException("FFmpeg did not create a usable video");
        double finalDuration=probeDuration(output);
        double avgFps=probeFps(output);
        System.out.printf(Locale.US,"[Render] Final duration: %.2f sec%n",finalDuration);
        System.out.printf(Locale.US,"[Render] CFR target: %d fps; measured average: %.3f fps%n",fps,avgFps);
        return new RenderResult(output,used,audioDuration,finalDuration,avgFps,List.copyOf(cmd));
    }

    private void renderScene(Scene scene,Path output,int index)throws Exception{
        if(scene.duration()<=0)throw new IllegalArgumentException("scene duration must be positive");
        int frames=Math.max(1,(int)Math.ceil(scene.duration()*fps));
        String motion=motionFilter(scene.transition(),frames);
        String vf="scale="+width+":"+height+":flags=lanczos,"+motion+",format=yuv420p";
        List<String>cmd=new ArrayList<>(List.of(
                ffmpeg,"-y","-hide_banner","-loglevel","warning",
                "-loop","1","-framerate",String.valueOf(fps),"-i",scene.image().toString(),
                "-t",String.format(Locale.US,"%.4f",scene.duration()),
                "-vf",vf,
                "-an","-c:v","libx264","-preset","ultrafast","-crf","18",
                "-r",String.valueOf(fps),"-fps_mode","cfr",
                "-pix_fmt","yuv420p",output.toString()
        ));
        FfmpegRunner.run(cmd,300);
    }

    private String motionFilter(String transition,int frames){
        String t=transition==null?"push_in":transition.toLowerCase(Locale.ROOT);
        double step=.024/Math.max(1,frames);
        String z,x,y;
        switch(t){
            case "zoom_out"->{
                z=String.format(Locale.US,"if(eq(on,0),1.024,max(1.0,zoom-%.8f))",step);
                x="iw/2-(iw/zoom/2)";y="ih/2-(ih/zoom/2)";
            }
            case "pan_left"->{
                z="1.018";x="(iw-iw/zoom)*(1-on/"+Math.max(1,frames-1)+")";y="ih/2-(ih/zoom/2)";
            }
            case "pan_right"->{
                z="1.018";x="(iw-iw/zoom)*(on/"+Math.max(1,frames-1)+")";y="ih/2-(ih/zoom/2)";
            }
            default->{
                z=String.format(Locale.US,"min(zoom+%.8f,1.024)",step);
                x="iw/2-(iw/zoom/2)";y="ih/2-(ih/zoom/2)";
            }
        }
        return "zoompan=z='"+z+"':x='"+x+"':y='"+y+"':d=1:s="+width+"x"+height+":fps="+fps;
    }

    private List<String>finalCommand(Path concat,Path audio,Path output,String enc,Path ass,double totalDuration,double audioDuration){
        List<String>cmd=new ArrayList<>(List.of(
                ffmpeg,"-y","-hide_banner","-loglevel","warning",
                "-f","concat","-safe","0","-i",concat.toString(),
                "-i",audio.toString()
        ));
        String vf="scale="+width+":"+height+":flags=lanczos,format=yuv420p";
        if(ass!=null)vf+=",ass='"+escapeFilter(ass.toAbsolutePath().toString())+"'";
        cmd.addAll(List.of("-vf",vf));
        double tail=Math.max(0,totalDuration-audioDuration);
        if(tail>.01)cmd.addAll(List.of("-af","apad=pad_dur="+String.format(Locale.US,"%.3f",tail+.1)));
        cmd.addAll(List.of("-t",String.format(Locale.US,"%.4f",totalDuration),"-r",String.valueOf(fps),"-fps_mode","cfr"));
        if(enc.equals("nvenc"))cmd.addAll(List.of("-c:v","h264_nvenc","-preset","p6","-tune","hq","-rc","vbr","-cq","19","-b:v","0"));
        else cmd.addAll(List.of("-c:v","libx264","-preset","medium","-crf","19"));
        cmd.addAll(List.of("-c:a","aac","-b:a","192k","-ar","48000","-movflags","+faststart",output.toString()));
        return cmd;
    }

    public double probeDuration(Path file)throws Exception{
        String o=FfmpegRunner.run(List.of(ffprobe,"-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
        return Double.parseDouble(o.split("\\R")[0]);
    }

    public double probeFps(Path file)throws Exception{
        String o=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams","v:0","-show_entries","stream=avg_frame_rate","-of","default=noprint_wrappers=1:nokey=1",file.toString()),30).trim();
        String v=o.split("\\R")[0].trim();
        if(v.contains("/")){
            String[]p=v.split("/",2);return Double.parseDouble(p[0])/Math.max(1e-9,Double.parseDouble(p[1]));
        }
        return Double.parseDouble(v);
    }

    public String version(){
        try{return FfmpegRunner.run(List.of(ffmpeg,"-version"),10).lines().findFirst().orElse("unknown");}
        catch(Exception e){return "unknown";}
    }

    private static String escapeConcat(String x){return x.replace("'","'\\''");}
    private static String escapeFilter(String x){return x.replace("\\","/").replace(":","\\:").replace("'","\\'").replace(",","\\,").replace("[","\\[").replace("]","\\]");}

    public record RenderResult(Path path,String encoder,double audioDuration,double finalDuration,double averageFps,List<String>command){}
}
