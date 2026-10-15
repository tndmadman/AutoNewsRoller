package autonewsroller.visuals;

import autonewsroller.video.FfmpegRunner;
import autonewsroller.util.Json;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Measured post-render evidence from the actual MP4, not from Java previews.
 * Technical failures reject, heuristic visual observations remain warnings.
 */
public final class VisualQualityReport {
    private VisualQualityReport(){}
    public static Map<String,Object> create(String ffmpeg,Path video,Path jobDir,double seconds,Storyboard board,boolean framesEnabled)throws Exception{
        board.validate();
        if(!Double.isFinite(seconds)||seconds<=0||Math.abs(board.duration()-seconds)>.45)
            throw new IllegalStateException("Storyboard and rendered duration disagree: "+board.duration()+" vs "+seconds);
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("storyboardValid",true);
        report.put("sceneCount",board.scenes().size());
        report.put("timingSource",board.timingSource());
        report.put("visualCoverageSeconds",board.duration());
        report.put("objectiveChecks","decoded MP4 frame, expected dimensions, blank-frame, validated scene coverage");
        report.put("semanticImageAccuracy","not established by deterministic visual quality tests");
        List<Map<String,Object>> captures=new ArrayList<>();
        List<String> warnings=new ArrayList<>();
        if(framesEnabled){
            Path previews=jobDir.resolve("visual_preview");
            Files.createDirectories(previews);
            LinkedHashSet<Long>timesMs=new LinkedHashSet<>();
            timesMs.add(70L);
            for(Storyboard.Scene scene:board.scenes()){
                double at=scene.start()+Math.min((scene.end()-scene.start())*.5,2.3);
                timesMs.add(Math.max(70L,Math.round(at*1000)));
            }
            timesMs.add(Math.round(Math.max(.07,seconds-.20)*1000));
            List<BufferedImage> thumbnails=new ArrayList<>();
            int expectedW=-1, expectedH=-1, index=0;
            for(long stamp:timesMs){
                double time=Math.min(Math.max(.03,seconds-.035),stamp/1000.0);
                Path frame=previews.resolve(String.format(Locale.ROOT,"frame_%02d.png",index));
                FfmpegRunner.run(List.of(ffmpeg,"-y","-hide_banner","-loglevel","error","-ss",
                        String.format(Locale.ROOT,"%.3f",time),"-i",video.toString(),
                        "-frames:v","1",frame.toString()),45);
                BufferedImage image=ImageIO.read(frame.toFile());
                if(image==null)throw new IllegalStateException("Cannot decode rendered frame at "+time+"s");
                if(expectedW==-1){expectedW=image.getWidth();expectedH=image.getHeight();}
                if(image.getWidth()!=expectedW||image.getHeight()!=expectedH)
                    throw new IllegalStateException("Inconsistent output-frame dimensions");
                double luma=brightness(image,0,0,image.getWidth(),image.getHeight());
                // Ignore the always-present header and ticker in subject-area brightness tests.
                double subject=brightness(image,image.getWidth()/5,image.getHeight()/6,
                        image.getWidth()*3/5,image.getHeight()/2);
                if(luma<2.5||subject<1.6)
                    throw new IllegalStateException("Unexpected near-black frame at "+time+"s, luma="+luma+", subject="+subject);
                Map<String,Object> captured=new LinkedHashMap<>();
                captured.put("timeSeconds",time);captured.put("path",frame.toString());
                captured.put("width",image.getWidth());captured.put("height",image.getHeight());
                captured.put("meanLuma",round(luma));captured.put("subjectLuma",round(subject));
                captures.add(captured);
                thumbnails.add(image);index++;
            }
            int columns=Math.min(5,Math.max(1,thumbnails.size())),rows=(thumbnails.size()+columns-1)/columns;
            int tileW=220,tileH=390;
            BufferedImage sheet=new BufferedImage(columns*tileW,rows*(tileH+32),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=sheet.createGraphics();
            g.setColor(Color.DARK_GRAY);g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for(int i=0;i<thumbnails.size();i++){
                int x=(i%columns)*tileW,y=(i/columns)*(tileH+32);
                g.drawImage(thumbnails.get(i),x,y+32,tileW,tileH,null);
                g.setColor(Color.WHITE);
                g.drawString(String.format(Locale.ROOT,"%.1fs",((Number)captures.get(i).get("timeSeconds")).doubleValue()),x+10,y+21);
            }
            g.dispose();
            Path sheetPath=previews.resolve("contact_sheet.png");
            ImageIO.write(sheet,"png",sheetPath.toFile());
            report.put("contactSheet",sheetPath.toString());
            report.put("frameWidth",expectedW);report.put("frameHeight",expectedH);
        }else warnings.add("No post-render visual frames captured: contact-sheet generation disabled");
        if(framesEnabled){
            Map<String,Object>layout=BroadcastLayout.review(board,
                    ((Number)captures.get(0).get("width")).intValue(),
                    ((Number)captures.get(0).get("height")).intValue(),true);
            report.put("captionSafeLayout",layout);
            if("rejected".equals(layout.get("status")))
                throw new IllegalStateException("Caption and lower-third safe areas collide");
        }
        report.put("capturedFrames",captures);
        report.put("warnings",warnings);
        report.put("status",framesEnabled?"passed":"not-sampled");
        Json.write(jobDir.resolve("visual_quality_report.json"),report);
        return report;
    }
    private static double brightness(BufferedImage image,int x,int y,int width,int height){
        int maxX=Math.min(image.getWidth(),x+width),maxY=Math.min(image.getHeight(),y+height);
        int stride=Math.max(1,Math.min(width,height)/64);long samples=0;double total=0;
        for(int row=y;row<maxY;row+=stride)for(int col=x;col<maxX;col+=stride){
            int rgb=image.getRGB(col,row);
            total+=(((rgb>>16)&255)*.2126+((rgb>>8)&255)*.7152+(rgb&255)*.0722);
            samples++;
        }
        return total/Math.max(1,samples);
    }
    private static double round(double x){return Math.round(x*10.0)/10.0;}
}
