package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Purpose-built vertical meme cards. Always reserves bottom 220px for TikTok UI. */
public final class MemeCardRenderer extends CardRenderer {
    private static final int FOOTER=220;
    @Override public Path render(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        return paint(item,null,out,w,h,false,false);
    }
    @Override public Path renderWithImage(VisualPlan.Item item,Path source,Path out,int w,int h)throws Exception{
        BufferedImage input=ImageIO.read(source.toFile());
        if(input==null)throw new IllegalArgumentException("Invalid meme illustration: "+source);
        return paint(item,input,out,w,h,false,false);
    }
    @Override public Path renderOverlay(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        return paint(item,null,out,w,h,true,false);
    }
    @Override public Path renderBackground(VisualPlan.Item item,Path source,Path out,int w,int h)throws Exception{
        BufferedImage input=ImageIO.read(source.toFile());
        if(input==null)throw new IllegalArgumentException("Invalid meme illustration");
        return paint(item,input,out,w,h,false,true);
    }
    private Path paint(VisualPlan.Item item,BufferedImage src,Path out,int w,int h,
            boolean overlay,boolean backgroundOnly)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage img=new BufferedImage(w,h,overlay?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(w/1080.0,h/1920.0);
        if(!overlay){
            g.setColor(new Color(20,16,30));g.fillRect(0,0,1080,1920);
            if(src!=null){
                int dh=1920-FOOTER;
                double scale=Math.max(1080.0/src.getWidth(),dh/(double)src.getHeight());
                int ww=(int)Math.ceil(src.getWidth()*scale),hh=(int)Math.ceil(src.getHeight()*scale);
                g.drawImage(src,(1080-ww)/2,(dh-hh)/2,ww,hh,null);
            }else{
                g.setColor(new Color(99,54,140));g.fillOval(-160,290,970,970);
                g.setColor(new Color(210,62,117));g.fillOval(600,680,650,650);
            }
        }
        if(!backgroundOnly){
            int template=Math.floorMod(item.index(),4);
            // Switch visual structures across beats; keep text geometry deterministic.
            if(template==2){
                g.setColor(new Color(255,230,40,210));g.fillRect(534,345,12,1210);
                g.setColor(new Color(0,0,0,175));g.fillRoundRect(45,1390,990,150,24,24);
            }else if(template==3){
                g.setColor(new Color(255,67,129,180));g.fillRoundRect(55,980,970,430,48,48);
                g.setColor(new Color(0,0,0,180));g.fillRoundRect(65,995,950,400,42,42);
            }else if(template==1){
                g.setColor(new Color(255,232,39,215));g.fillRect(0,30,1080,18);
            }
            int barY=template==1?325:template==3?70:35;
            g.setColor(new Color(0,0,0,220));g.fillRoundRect(30,barY,1020,270,32,32);
            g.setColor(new Color(255,231,40));
            String text=item.title()==null?"":item.title().toUpperCase(Locale.ROOT);
            Font font=new Font("Impact",Font.BOLD,text.length()>38?53:72);
            g.setFont(font);
            FontMetrics fm=g.getFontMetrics();
            java.util.List<String>lines=wrap(text,fm,950);
            int y=barY+45+fm.getAscent();
            for(String line:lines.subList(0,Math.min(2,lines.size()))){
                g.setColor(Color.BLACK);
                for(int dx=-3;dx<=3;dx+=3)for(int dy=-3;dy<=3;dy+=3)
                    g.drawString(line,540-fm.stringWidth(line)/2+dx,y+dy);
                g.setColor(new Color(255,234,65));
                g.drawString(line,540-fm.stringWidth(line)/2,y);
                y+=fm.getHeight()+2;
            }
            // The full footer stays text-free: TikTok's native post description
            // overlays this region. The satire disclosure sits directly above it.
            g.setColor(new Color(10,8,17));g.fillRect(0,1920-FOOTER,1080,FOOTER);
            g.setColor(new Color(255,74,137));g.fillRect(0,1920-FOOTER,1080,7);
            g.setColor(new Color(0,0,0,215));g.fillRoundRect(35,1920-FOOTER-73,470,60,12,12);
            g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,28));
            g.drawString("SATIRE / AI ILLUSTRATION",48,1920-FOOTER-33);
        }
        g.dispose();ImageIO.write(img,"png",out.toFile());return out;
    }
    private static java.util.List<String>wrap(String s,FontMetrics fm,int maxWidth){
        java.util.List<String>out=new ArrayList<>();String line="";
        for(String word:s.split("\\s+")){
            String candidate=line.isEmpty()?word:line+" "+word;
            if(!line.isEmpty()&&fm.stringWidth(candidate)>maxWidth){out.add(line);line=word;}
            else line=candidate;
        }
        if(!line.isEmpty())out.add(line);return out.isEmpty()?java.util.List.of(""):out;
    }
}
