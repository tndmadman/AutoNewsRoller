package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public final class CardRenderer {
    private static final Color BG=new Color(13,15,19);
    private static final Color PANEL=new Color(29,33,40);
    private static final Color PANEL_2=new Color(38,43,52);
    private static final Color TEXT=new Color(244,246,249);
    private static final Color MUTED=new Color(184,192,204);
    private static final Color ACCENT=new Color(105,197,255);

    public Path render(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=prepare(canvas);
        paintShell(g,w,h,item.type());

        if("HOOK".equalsIgnoreCase(item.type())){
            g.setColor(TEXT);
            drawWrapped(g,item.title(),new Font("SansSerif",Font.BOLD,36),90,285,w-180,48,3);
            g.setColor(PANEL_2);g.fillRoundRect(90,520,w-180,650,34,34);
            g.setColor(TEXT);
            drawWrapped(g,item.body(),new Font("SansSerif",Font.BOLD,48),128,680,w-256,62,5);
        }else if("HEADLINE_CARD".equalsIgnoreCase(item.type())){
            g.setColor(ACCENT);g.fillRoundRect(90,330,w-180,14,8,8);
            g.setColor(TEXT);
            drawWrapped(g,item.title(),new Font("SansSerif",Font.BOLD,52),90,455,w-180,68,7);
            g.setColor(MUTED);
            drawWrapped(g,"Verified story summary",new Font("SansSerif",Font.PLAIN,30),90,h-360,w-180,44,2);
        }else if("SOURCE_CARD".equalsIgnoreCase(item.type())){
            g.setColor(TEXT);
            drawWrapped(g,item.title(),new Font("SansSerif",Font.BOLD,46),90,360,w-180,62,3);
            g.setColor(MUTED);
            drawWrapped(g,item.body(),new Font("SansSerif",Font.PLAIN,30),90,530,w-180,48,8);
        }else{
            g.setColor(TEXT);
            drawWrapped(g,item.title(),new Font("SansSerif",Font.BOLD,44),90,240,w-180,58,3);
            g.setColor(PANEL_2);g.fillRoundRect(90,470,w-180,760,34,34);
            g.setColor(MUTED);
            drawWrapped(g,item.body(),new Font("SansSerif",Font.PLAIN,31),128,570,w-256,47,10);
        }

        paintFooter(g,w,h,item.type());
        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    public Path renderWithImage(VisualPlan.Item item,Path imagePath,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage source=ImageIO.read(imagePath.toFile());
        if(source==null)throw new IllegalArgumentException("Unsupported image: "+imagePath);

        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=prepare(canvas);
        paintShell(g,w,h,item.type());

        boolean hook="HOOK".equalsIgnoreCase(item.type());
        g.setColor(TEXT);
        drawWrapped(g,item.title(),new Font("SansSerif",Font.BOLD,hook?34:42),90,hook?195:205,w-180,hook?44:54,hook?2:3);

        int imageX=90;
        int imageY=hook?300:390;
        int imageW=w-180;
        int imageH=hook?Math.min(1060,(int)(h*0.56)):Math.min(930,(int)(h*0.49));
        g.setColor(PANEL_2);
        g.fillRoundRect(imageX-8,imageY-8,imageW+16,imageH+16,34,34);
        Shape oldClip=g.getClip();
        g.setClip(new java.awt.geom.RoundRectangle2D.Double(imageX,imageY,imageW,imageH,28,28));
        drawCover(g,source,imageX,imageY,imageW,imageH);
        g.setClip(oldClip);

        g.setColor(hook?TEXT:MUTED);
        int bodyY=imageY+imageH+(hook?55:70);
        drawWrapped(g,item.body(),new Font("SansSerif",hook?Font.BOLD:Font.PLAIN,hook?36:29),90,bodyY,w-180,hook?48:43,hook?4:5);

        paintFooter(g,w,h,item.type());
        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    private static Graphics2D prepare(BufferedImage img){
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(BG);g.fillRect(0,0,img.getWidth(),img.getHeight());
        return g;
    }

    private static void paintShell(Graphics2D g,int w,int h,String type){
        g.setColor(PANEL);g.fillRoundRect(48,70,w-96,h-140,44,44);
        g.setColor(ACCENT);g.fillRoundRect(78,108,180,8,8,8);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.BOLD,22));
        g.drawString("AWARE  /  NEWS",82,158);
    }

    private static void paintFooter(Graphics2D g,int w,int h,String type){
        g.setColor(new Color(112,122,138));g.setFont(new Font("SansSerif",Font.BOLD,21));
        String label=(type==null?"STORY":type.replace('_',' ')).toUpperCase();
        g.drawString(label,82,h-108);
        String mark="AWARE";
        FontMetrics fm=g.getFontMetrics();
        g.setColor(ACCENT);g.drawString(mark,w-82-fm.stringWidth(mark),h-108);
    }

    private static int drawWrapped(Graphics2D g,String text,Font font,int x,int y,int maxWidth,int step,int maxLines){
        g.setFont(font);FontMetrics fm=g.getFontMetrics();
        String[] words=(text==null?"":text.trim()).split("\\s+");
        StringBuilder line=new StringBuilder();int yy=y,lines=0;
        for(String word:words){
            if(word.isBlank())continue;
            String test=line.length()==0?word:line+" "+word;
            if(fm.stringWidth(test)>maxWidth&&line.length()>0){
                g.drawString(line.toString(),x,yy);yy+=step;lines++;
                if(lines>=maxLines)return yy;
                line.setLength(0);line.append(word);
            }else{
                if(line.length()>0)line.append(' ');
                line.append(word);
            }
        }
        if(line.length()>0&&lines<maxLines){g.drawString(line.toString(),x,yy);yy+=step;}
        return yy;
    }

    private static void drawCover(Graphics2D g,BufferedImage src,int x,int y,int w,int h){
        double scale=Math.max(w/(double)src.getWidth(),h/(double)src.getHeight());
        int sw=(int)Math.round(w/scale),sh=(int)Math.round(h/scale);
        int sx=Math.max(0,(src.getWidth()-sw)/2),sy=Math.max(0,(src.getHeight()-sh)/2);
        g.drawImage(src,x,y,x+w,y+h,sx,sy,sx+sw,sy+sh,null);
    }
}
