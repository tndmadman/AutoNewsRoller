package autonewsroller.safety;

import autonewsroller.orchestration.ProductionMode;
import autonewsroller.util.Json;
import autonewsroller.video.AigcDisclosureOverlay;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Real Java overlay -> FFmpeg encoded MP4 -> mandatory Python model audit smoke. */
public final class ContentSafetyOverlaySmoke {
    private ContentSafetyOverlaySmoke(){}
    public static void main(String[]args)throws Exception{
        Path root=Path.of("").toAbsolutePath().normalize();
        Path temp=Files.createTempDirectory("anr-aigc-ffmpeg-smoke-");
        try{
            BufferedImage background=new BufferedImage(540,960,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=background.createGraphics();
            g.setColor(new Color(20,24,29));g.fillRect(0,0,540,960);g.dispose();
            Path base=temp.resolve("background.png"),disclosure=temp.resolve("aigc.png"),
                    video=temp.resolve("actual-overlay.mp4");
            ImageIO.write(background,"png",base.toFile());
            AigcDisclosureOverlay.create(disclosure,ProductionMode.MEME_RAGEBAIT,540,960);
            command(List.of("ffmpeg","-y","-hide_banner","-loglevel","error",
                    "-loop","1","-framerate","3","-i",base.toString(),
                    "-loop","1","-framerate","3","-i",disclosure.toString(),
                    "-filter_complex","[0:v][1:v]overlay=0:0:format=auto,format=yuv420p[v]",
                    "-map","[v]","-frames:v","3","-c:v","mpeg4","-q:v","4",
                    video.toString()),root);
            String python=System.getenv().getOrDefault("SAFETY_PYTHON","python");
            for(String scanner:List.of("tools/content_safety_scan.py","tools/content_context_scan.py")){
                String output=command(List.of(python,root.resolve(scanner).toString(),
                        "--video",video.toString()),root);
                Map<String,Object>audit=Json.object(Json.parse(output));
                if(!"SAFE".equals(audit.get("status"))
                        ||!(audit.get("framesDecoded") instanceof Number count)||count.longValue()!=3
                        ||!(audit.get("framesInspected") instanceof Number seen)||seen.longValue()!=3
                        ||!(audit.get("framesExpected") instanceof Number expected)||expected.longValue()!=3)
                    throw new AssertionError(scanner+" rejected real encoded Java AIGC banner: "+output);
                if("tools/content_safety_scan.py".equals(scanner)
                        && !Boolean.TRUE.equals(audit.get("disclosureVerified")))
                    throw new AssertionError("Java overlay did not survive actual FFmpeg encoding");
            }
            System.out.println("ContentSafetyOverlaySmoke PASS (Java disclosure -> FFmpeg MP4 -> both models, 3/3 frames)");
        }finally{
            try(var walk=Files.walk(temp)){
                walk.sorted(Comparator.reverseOrder()).forEach(p->{
                    try{Files.deleteIfExists(p);}catch(Exception ignored){}
                });
            }
        }
    }
    private static String command(List<String>arguments,Path root)throws Exception{
        ProcessBuilder builder=new ProcessBuilder(arguments);
        builder.directory(root.toFile());
        if("ffmpeg".equals(arguments.get(0)))builder.redirectErrorStream(true);
        else builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p=builder.start();
        if(!p.waitFor(180,java.util.concurrent.TimeUnit.SECONDS)){
            p.destroyForcibly();throw new IllegalStateException("Integration smoke timed out: "+arguments.get(0));
        }
        String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();
        if(p.exitValue()!=0)
            throw new IllegalStateException("Integration smoke failed "+arguments.get(0)+": "+output);
        return output;
    }
}
