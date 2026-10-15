package autonewsroller.video;

import autonewsroller.model.VisualPlan;
import autonewsroller.visuals.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Portable CPU-only end-to-end render using synthetic assets and narration. */
public final class VisualSmoke {
    public static void main(String[]args)throws Exception{
        Path d=Files.createTempDirectory("autonews-visual-v5-smoke-");
        Path audio=d.resolve("voice.wav");
        FfmpegRunner.run(java.util.List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                "-f","lavfi","-i","sine=frequency=440:sample_rate=24000",
                "-t","2.6","-c:a","pcm_s16le",audio.toString()),30);
        BufferedImage img=new BufferedImage(320,568,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=img.createGraphics();
        for(int i=0;i<8;i++){g.setColor(i%2==0?new Color(25,65,108):new Color(180,42,54));g.fillRect(0,i*71,320,71);}
        g.dispose();
        Path source=d.resolve("source.png");ImageIO.write(img,"png",source.toFile());
        VisualPlan.Item hook=new VisualPlan.Item(0,"HOOK","Council confirms regional infrastructure funding",
                "The council confirmed infrastructure work.",1.3,"");
        VisualPlan.Item detail=new VisualPlan.Item(1,"EDITORIAL_PHOTO","Infrastructure update",
                "The independent report describes the next steps.",1.3,"");
        CardRenderer cards=new CardRenderer();
        java.util.List<Path>backgrounds=new ArrayList<>(),overlays=new ArrayList<>();
        for(int i=0;i<2;i++){
            VisualPlan.Item item=i==0?hook:detail;
            Path bg=d.resolve("bg"+i+".png"),overlay=d.resolve("overlay"+i+".png");
            cards.renderBackground(item,source,bg,360,640);
            cards.renderOverlay(item,overlay,360,640);
            backgrounds.add(bg);overlays.add(overlay);
        }
        VideoRenderer renderer=new VideoRenderer("ffmpeg","ffprobe",360,640,15);
        for(String captions:java.util.List.of("off","sentence")){
            Path output=d.resolve("test-"+captions+".mp4");
            VideoRenderer.RenderResult result=renderer.render(backgrounds,overlays,
                    java.util.List.of(true,false),java.util.List.of(1.3,1.3),audio,output,
                    "x264",captions,"The council confirmed infrastructure funding for the region.");
            if(!Files.isRegularFile(result.path())||Files.size(result.path())<1024)
                throw new AssertionError("MP4 missing for captions="+captions);
            double duration=renderer.probeDuration(output);
            if(Math.abs(duration-2.6)>.30)throw new AssertionError("Video duration differs from audio: "+duration);
            if(captions.equals("sentence")){
                Storyboard board=new Storyboard("smoke",2.6,"fixture",java.util.List.of(
                        new Storyboard.Scene(0,0,1.3,"HOOK",0,java.util.List.of("FACT_A"),"PUSH_IN","CUT","fixture"),
                        new Storyboard.Scene(1,1.3,2.6,"EDITORIAL_PHOTO",1,java.util.List.of("FACT_B"),"STATIC","CUT","fixture")));
                board.validate();
                Map<String,Object>report=VisualQualityReport.create("ffmpeg",output,d,duration,board,true);
                if(!report.containsKey("contactSheet"))throw new AssertionError("No actual MP4 contact sheet");
            }
        }
        System.out.println("V5 FFMPEG SMOKE PASS: x264, synthetic narration, motion, captions off/on, contact sheet");
    }
}
