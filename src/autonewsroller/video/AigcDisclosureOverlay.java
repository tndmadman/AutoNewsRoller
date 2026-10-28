package autonewsroller.video;

import autonewsroller.orchestration.ProductionMode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Persistent post-caption overlay, intentionally included AFTER transitions and zooms. */
public final class AigcDisclosureOverlay {
    private AigcDisclosureOverlay(){}
    public static Path create(Path out,ProductionMode mode,int width,int height)throws Exception{
        if(width<480||height<640)throw new IllegalArgumentException("Disclosure requires portrait video");
        Files.createDirectories(out.toAbsolutePath().getParent());
        BufferedImage img=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(width/1080.0,height/1920.0);
        g.setColor(new Color(5,9,17,240));g.fillRoundRect(350,116,702,112,12,12);
        // Deliberate machine-verifiable magenta fiducial; test for its presence in each decoded frame.
        g.setColor(new Color(255,25,230));g.fillRect(355,127,18,22);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,26));
        String first=mode==ProductionMode.MEME_RAGEBAIT
            ?"SATIRE / AI VISUALS / AI NARRATION"
            :"AI-GENERATED VISUALS / AI NARRATION";
        g.drawString(first,390,166);
        g.setFont(new Font("SansSerif",Font.BOLD,23));
        g.drawString("ILLUSTRATION - NOT ACTUAL FOOTAGE",390,206);
        g.dispose();
        if(!ImageIO.write(img,"png",out.toFile()))throw new IllegalStateException("PNG overlay writer missing");
        return out;
    }
}