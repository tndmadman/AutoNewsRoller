package autonewsroller;

import autonewsroller.video.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class RendererSmokeTest {
    public static void main(String[]args)throws Exception{
        Path dir=Path.of("output/ci_renderer_smoke").toAbsolutePath().normalize();
        Files.createDirectories(dir);
        List<Path>images=new ArrayList<>();
        for(int i=0;i<4;i++){
            BufferedImage img=new BufferedImage(360,640,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=img.createGraphics();
            g.setColor(new Color(8+i*10,18+i*8,28+i*6));g.fillRect(0,0,360,640);
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,28));g.drawString("AWARE SMOKE "+(i+1),42,90);
            g.setColor(new Color(56,232,255));g.fillRoundRect(38,150,284,300,28,28);
            g.dispose();
            Path p=dir.resolve("scene_"+i+".png");ImageIO.write(img,"png",p.toFile());images.add(p);
        }

        Path wav=dir.resolve("tone.wav");
        FfmpegRunner.run(List.of("ffmpeg","-y","-hide_banner","-loglevel","error","-f","lavfi","-i","sine=frequency=660:sample_rate=48000:duration=4.0","-c:a","pcm_s16le",wav.toString()),30);

        VideoRenderer renderer=new VideoRenderer("ffmpeg","ffprobe",360,640,30);
        List<VideoRenderer.Scene>scenes=List.of(
                new VideoRenderer.Scene(images.get(0),1.0,"push_in"),
                new VideoRenderer.Scene(images.get(1),1.0,"pan_left"),
                new VideoRenderer.Scene(images.get(2),1.0,"zoom_out"),
                new VideoRenderer.Scene(images.get(3),1.5,"pan_right")
        );
        Path out=dir.resolve("smoke.mp4");
        VideoRenderer.RenderResult result=renderer.renderScenes(
                scenes,wav,out,"x264","phrase",
                "This renderer smoke test checks phrase captions motion audio padding and constant frame rate output.",
                18,8
        );
        if(!Files.isRegularFile(out)||Files.size(out)<1024)throw new AssertionError("renderer smoke output missing");
        if(Math.abs(result.averageFps()-30.0)>.05)throw new AssertionError("renderer smoke fps="+result.averageFps());
        if(result.finalDuration()<4.3||result.finalDuration()>4.7)throw new AssertionError("renderer smoke duration="+result.finalDuration());
        System.out.printf(Locale.US,"RENDERER SMOKE PASS duration=%.3f fps=%.3f encoder=%s%n",result.finalDuration(),result.averageFps(),result.encoder());
    }
}
