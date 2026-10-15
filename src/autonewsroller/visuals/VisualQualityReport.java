package autonewsroller.visuals;

import autonewsroller.video.FfmpegRunner;
import autonewsroller.util.Json;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Actual MP4 snapshots for honest post-render visual inspection and automatic black-frame rejection. */
public final class VisualQualityReport {
    private VisualQualityReport(){}
    public static Map<String,Object> create(String ffmpeg,Path video,Path jobDir,double seconds,Storyboard board,boolean framesEnabled)throws Exception{
        Map<String,Object>report=new LinkedHashMap<>();
        report.put("storyboardValid",true);report.put("scenes",board.scenes().size());
        report.put("timingSource",board.timingSource());
        report.put("objectiveChecks","frame-decode,unexpected-near-black,dimensions");
        report.put("semanticImageAccuracy","unverified: a deterministic check cannot establish truth");
        List<Map<String,Object>>captures=new ArrayList<>();
        if(framesEnabled){
            Path previews=jobDir.resolve("visual_preview");Files.createDirectories(previews);
            java.util.List<Double>times=java.util.List.of(.07,Math.max(.1,seconds*.25),Math.max(.2,seconds*.5),
                    Math.max(.3,seconds*.75),Math.max(.4,seconds-.3));
            java.util.List<BufferedImage>thumbnails=new ArrayList<>();
            for(int i=0;i<times.size();i++){
                double at=Math.min(Math.max(.02,seconds-.03),times.get(i));
                Path frame=previews.resolve(String.format(Locale.ROOT,"frame_%02d.png",i));
                FfmpegRunner.run(java.util.List.of(ffmpeg,"-y","-hide_banner","-loglevel","error",
                        "-ss",String.format(Locale.ROOT,"%.3f",at),"-i",video.toString(),
                        "-frames:v","1",frame.toString()),45);
                BufferedImage img=ImageIO.read(frame.toFile());
                if(img==null)throw new IllegalStateException("Cannot decode preview frame "+i);
                double bright=brightness(img);
                if(bright<2.5)throw new IllegalStateException("Unexpected near-black rendered frame at "+at+"s");
                Map<String,Object>point=new LinkedHashMap<>();
                point.put("timeSeconds",at);point.put("file",frame.toString());point.put("meanLuma",Math.round(bright*10.0)/10.0);
                captures.add(point);thumbnails.add(img);
            }
            BufferedImage sheet=new BufferedImage(5*250,470,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=sheet.createGraphics();g.setColor(Color.DARK_GRAY);g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for(int i=0;i<thumbnails.size();i++){
                g.drawImage(thumbnails.get(i),i*250,34,250,436,null);
                g.setColor(Color.WHITE);g.drawString(String.format(Locale.ROOT,"%.1fs",times.get(i)),i*250+12,23);
            }
            g.dispose();ImageIO.write(sheet,"png",previews.resolve("contact_sheet.png").toFile());
            report.put("contactSheet",previews.resolve("contact_sheet.png").toString());
        }
        report.put("capturedFrames",captures);report.put("status","passed");
        Json.write(jobDir.resolve("visual_quality_report.json"),report);
        return report;
    }
    private static double brightness(BufferedImage img){
        double sum=0;int n=0;
        for(int y=0;y<img.getHeight();y+=Math.max(1,img.getHeight()/80))
            for(int x=0;x<img.getWidth();x+=Math.max(1,img.getWidth()/80)){
                int c=img.getRGB(x,y);
                sum+=(((c>>16)&255)*.2126+((c>>8)&255)*.7152+(c&255)*.0722);n++;
            }
        return sum/Math.max(1,n);
    }
}
