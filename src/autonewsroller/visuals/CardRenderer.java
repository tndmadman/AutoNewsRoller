package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class CardRenderer {
    private static final Color BG=new Color(5,11,17);
    private static final Color CARD=new Color(10,19,28);
    private static final Color CARD_2=new Color(13,26,38);
    private static final Color TEXT=new Color(243,247,250);
    private static final Color MUTED=new Color(155,179,196);
    private static final Color CYAN=new Color(56,232,255);
    private static final Color AMBER=new Color(255,191,90);

    public Path render(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        return item.type().equals("SOURCE_CARD")
                ? renderSourceCard(item,out,w,h)
                : renderPost(item,null,out,w,h,false);
    }

    public Path renderPost(VisualPlan.Item item,Path mainImage,Path out,int w,int h,boolean aiIllustration)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=canvas.createGraphics();
        quality(g);

        g.setColor(BG);g.fillRect(0,0,w,h);
        BufferedImage source=read(mainImage);
        if(source!=null){
            Composite old=g.getComposite();
            g.setComposite(AlphaComposite.SrcOver.derive(.18f));
            drawCover(g,source,0,0,w,h);
            g.setComposite(old);
            g.setColor(new Color(0,0,0,150));g.fillRect(0,0,w,h);
        }

        int cardX=54,cardY=58,cardW=w-108,cardH=h-116;
        g.setColor(new Color(0,0,0,90));g.fillRoundRect(cardX+10,cardY+14,cardW,cardH,44,44);
        g.setColor(CARD);g.fillRoundRect(cardX,cardY,cardW,cardH,44,44);
        g.setColor(new Color(37,69,91));g.drawRoundRect(cardX,cardY,cardW,cardH,44,44);

        g.setFont(new Font("SansSerif",Font.BOLD,34));g.setColor(CYAN);g.drawString("AWARE",90,124);
        g.setFont(new Font("SansSerif",Font.BOLD,24));g.setColor(MUTED);
        String meta="NEWS  •  "+item.type().replace('_',' ');
        int metaW=g.getFontMetrics().stringWidth(meta);g.drawString(meta,w-90-metaW,122);

        int headlineTop=176;
        Font headline=fitFont(g,item.title(),64,52,Font.BOLD,w-180,3);
        g.setColor(TEXT);
        int afterHeadline=drawWrapped(g,item.title(),headline,90,headlineTop,w-180,3,1.12);

        int imageY=Math.max(365,afterHeadline+34);
        int imageX=90,imageW=w-180,imageH=Math.min(870,h-imageY-560);
        imageH=Math.max(760,imageH);
        Shape oldClip=g.getClip();
        g.setClip(new RoundRectangle2D.Double(imageX,imageY,imageW,imageH,32,32));
        if(source!=null)drawCover(g,source,imageX,imageY,imageW,imageH);
        else drawProceduralIllustration(g,item,imageX,imageY,imageW,imageH);
        g.setClip(oldClip);
        g.setColor(new Color(48,79,99));g.drawRoundRect(imageX,imageY,imageW,imageH,32,32);

        if(aiIllustration){
            String badge="AI ILLUSTRATION";
            g.setFont(new Font("SansSerif",Font.BOLD,22));
            int bw=g.getFontMetrics().stringWidth(badge)+28,bh=42,bx=imageX+imageW-bw-18,by=imageY+imageH-bh-18;
            g.setColor(new Color(3,10,15,205));g.fillRoundRect(bx,by,bw,bh,18,18);
            g.setColor(new Color(210,222,230));g.drawString(badge,bx+14,by+28);
        }

        int captionY=imageY+imageH+40;
        g.setColor(CARD_2);g.fillRoundRect(90,captionY,w-180,210,28,28);
        g.setColor(new Color(31,61,81));g.drawRoundRect(90,captionY,w-180,210,28,28);
        g.setFont(new Font("SansSerif",Font.BOLD,22));g.setColor(CYAN);g.drawString("LIVE CONTEXT",118,captionY+38);
        g.setFont(new Font("SansSerif",Font.PLAIN,26));g.setColor(MUTED);
        g.drawString("Synchronized narration captions appear here.",118,captionY+78);

        int footerY=Math.min(h-150,captionY+265);
        g.setColor(new Color(25,46,61));g.drawLine(90,footerY-24,w-90,footerY-24);
        g.setFont(new Font("SansSerif",Font.BOLD,27));g.setColor(TEXT);
        String sources=sourceFooter(item);
        drawEllipsized(g,"Sources: "+sources,90,footerY,w-180);
        g.setFont(new Font("SansSerif",Font.PLAIN,22));g.setColor(MUTED);
        g.drawString("AWARE  •  context-first news brief",90,footerY+42);

        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    public Path renderSourceCard(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=canvas.createGraphics();quality(g);
        GradientPaint grad=new GradientPaint(0,0,new Color(5,14,22),w,h,new Color(10,24,35));
        g.setPaint(grad);g.fillRect(0,0,w,h);
        g.setColor(CARD);g.fillRoundRect(70,140,w-140,h-300,46,46);
        g.setColor(new Color(37,69,91));g.drawRoundRect(70,140,w-140,h-300,46,46);

        g.setFont(new Font("SansSerif",Font.BOLD,40));g.setColor(CYAN);g.drawString("AWARE",112,220);
        g.setFont(new Font("SansSerif",Font.BOLD,58));g.setColor(TEXT);g.drawString("Sources & context",112,330);
        g.setFont(new Font("SansSerif",Font.PLAIN,29));g.setColor(MUTED);
        drawWrapped(g,"Reporting used for this brief. Full URLs and provenance are retained with the finished video.",new Font("SansSerif",Font.PLAIN,29),112,398,w-224,3,1.35);

        java.util.List<String>pubs=publishers(item);
        int y=565;
        for(String p:pubs.stream().limit(6).toList()){
            g.setColor(CARD_2);g.fillRoundRect(112,y,w-224,74,22,22);
            g.setColor(new Color(40,71,91));g.drawRoundRect(112,y,w-224,74,22,22);
            g.setFont(new Font("SansSerif",Font.BOLD,29));g.setColor(TEXT);drawEllipsized(g,p,142,y+47,w-284);
            y+=94;
        }
        g.setFont(new Font("SansSerif",Font.BOLD,25));g.setColor(AMBER);g.drawString("END OF BRIEF",112,h-235);
        g.setFont(new Font("SansSerif",Font.PLAIN,23));g.setColor(MUTED);g.drawString("Source details remain attached to the video provenance record.",112,h-190);
        g.dispose();ImageIO.write(canvas,"png",out.toFile());return out;
    }

    private static void drawProceduralIllustration(Graphics2D g,VisualPlan.Item item,int x,int y,int w,int h){
        GradientPaint gp=new GradientPaint(x,y,new Color(20,45,62),x+w,y+h,new Color(14,21,31));
        g.setPaint(gp);g.fillRect(x,y,w,h);
        g.setColor(new Color(56,232,255,55));
        for(int i=0;i<7;i++)g.fillOval(x+60+i*120,y+100+(i%3)*140,180,180);
        g.setColor(new Color(244,247,250,220));g.setFont(new Font("SansSerif",Font.BOLD,34));
        drawWrapped(g,item.displayCaption(),new Font("SansSerif",Font.BOLD,34),x+64,y+h/2,w-128,2,1.25);
    }

    private static BufferedImage read(Path p){
        try{return p!=null&&Files.isRegularFile(p)?ImageIO.read(p.toFile()):null;}catch(Exception e){return null;}
    }

    private static void drawCover(Graphics2D g,BufferedImage img,int x,int y,int w,int h){
        double scale=Math.max(w/(double)img.getWidth(),h/(double)img.getHeight());
        int dw=(int)Math.ceil(img.getWidth()*scale),dh=(int)Math.ceil(img.getHeight()*scale);
        int dx=x+(w-dw)/2,dy=y+(h-dh)/2;
        g.drawImage(img,dx,dy,dw,dh,null);
    }

    private static Font fitFont(Graphics2D g,String text,int max,int min,int style,int width,int maxLines){
        for(int size=max;size>=min;size-=2){
            Font f=new Font("SansSerif",style,size);
            if(lineCount(g,text,f,width)<=maxLines)return f;
        }
        return new Font("SansSerif",style,min);
    }

    private static int lineCount(Graphics2D g,String text,Font f,int width){
        g.setFont(f);FontMetrics fm=g.getFontMetrics();int lines=1;String line="";
        for(String word:(text==null?"":text).split("\\s+")){
            String test=line.isBlank()?word:line+" "+word;
            if(!line.isBlank()&&fm.stringWidth(test)>width){lines++;line=word;}else line=test;
        }
        return lines;
    }

    private static int drawWrapped(Graphics2D g,String text,Font font,int x,int y,int maxWidth,int maxLines,double spacing){
        g.setFont(font);FontMetrics fm=g.getFontMetrics();StringBuilder line=new StringBuilder();int yy=y;int lines=0;
        int step=(int)Math.round(font.getSize()*spacing);
        for(String word:(text==null?"":text).split("\\s+")){
            String test=line.isEmpty()?word:line+" "+word;
            if(fm.stringWidth(test)>maxWidth&&!line.isEmpty()){
                g.drawString(line.toString(),x,yy);yy+=step;lines++;line=new StringBuilder(word);
                if(lines>=maxLines)return yy;
            }else{if(!line.isEmpty())line.append(' ');line.append(word);}
        }
        if(!line.isEmpty()&&lines<maxLines){g.drawString(line.toString(),x,yy);yy+=step;}
        return yy;
    }

    private static void drawEllipsized(Graphics2D g,String text,int x,int y,int maxWidth){
        FontMetrics fm=g.getFontMetrics();String value=text==null?"":text;
        if(fm.stringWidth(value)<=maxWidth){g.drawString(value,x,y);return;}
        while(value.length()>3&&fm.stringWidth(value+"…")>maxWidth)value=value.substring(0,value.length()-1);
        g.drawString(value+"…",x,y);
    }

    private static String sourceFooter(VisualPlan.Item item){
        java.util.List<String>pubs=publishers(item);
        return pubs.isEmpty()?"provenance attached":String.join(" • ",pubs.stream().limit(3).toList());
    }

    private static java.util.List<String>publishers(VisualPlan.Item item){
        Object p=item.sourceMetadata().get("publishers");java.util.List<String>out=new ArrayList<>();
        if(p instanceof java.util.List<?>l)for(Object x:l){String s=String.valueOf(x);if(!s.isBlank())out.add(s);}
        return out;
    }

    private static void quality(Graphics2D g){
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
    }
}
