package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class CardRenderer {
    private static final Color BG=new Color(7,11,16);
    private static final Color PANEL=new Color(15,23,31);
    private static final Color PANEL_2=new Color(24,34,44);
    private static final Color TEXT=new Color(247,249,252);
    private static final Color MUTED=new Color(178,191,205);
    private static final Color ACCENT=new Color(56,232,255);
    private static final Color RED=new Color(226,48,62);
    private static final Color TICKER=new Color(9,18,26);

    public Path render(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=prepare(canvas);

        if("HOOK".equalsIgnoreCase(item.type()))renderHookPlaceholder(g,item,w,h);
        else if("SOURCE_CARD".equalsIgnoreCase(item.type()))renderSourceCard(g,item,w,h);
        else renderStoryPlaceholder(g,item,w,h);

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

        if("HOOK".equalsIgnoreCase(item.type()))renderHookPackage(g,item,source,w,h);
        else renderStoryPackage(g,item,source,w,h);

        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    private static void renderHookPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        paintTopBar(g,w,"TOP STORY",true);

        int imageX=48,imageY=190,imageW=w-96,imageH=Math.min(1050,h-760);
        paintImageFrame(g,source,imageX,imageY,imageW,imageH,22);

        int lowerY=imageY+imageH-20;
        int lowerH=390;
        g.setColor(new Color(5,9,13,245));
        g.fillRect(48,lowerY,w-96,lowerH);

        g.setColor(RED);
        g.fillRoundRect(76,lowerY+30,190,42,7,7);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString("TOP STORY",98,lowerY+59);

        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),8),new Font("SansSerif",Font.BOLD,52),76,lowerY+132,w-152,58,3);

        g.setColor(MUTED);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.BOLD,31),76,lowerY+300,w-152,42,2);

        paintTicker(g,w,h,"AWARE NEWS  •  VERIFIED REPORTING");
    }

    private static void renderStoryPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        paintTopBar(g,w,storyLabel(item.type()),false);

        int imageX=62,imageY=235,imageW=w-124,imageH=Math.min(930,h-780);
        paintImageFrame(g,source,imageX,imageY,imageW,imageH,24);

        int lowerY=imageY+imageH+18;
        g.setColor(PANEL);
        g.fillRoundRect(62,lowerY,imageW,320,22,22);

        g.setColor(ACCENT);
        g.fillRect(62,lowerY,9,320);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        g.drawString(storyLabel(item.type()),94,lowerY+48);

        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),10),new Font("SansSerif",Font.BOLD,38),94,lowerY+102,imageW-64,46,2);

        g.setColor(MUTED);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.PLAIN,27),94,lowerY+215,imageW-64,38,3);

        paintTicker(g,w,h,"AWARE NEWS  •  STORY UPDATE");
    }

    private static void renderHookPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"TOP STORY",true);

        g.setColor(PANEL_2);
        g.fillRoundRect(48,215,w-96,920,28,28);
        paintPlaceholderGrid(g,70,240,w-140,870);

        int lowerY=1080;
        g.setColor(new Color(5,9,13,245));
        g.fillRect(48,lowerY,w-96,455);

        g.setColor(RED);
        g.fillRoundRect(76,lowerY+32,190,42,7,7);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString("TOP STORY",98,lowerY+61);

        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),8),new Font("SansSerif",Font.BOLD,52),76,lowerY+142,w-152,58,3);
        g.setColor(MUTED);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.BOLD,31),76,lowerY+330,w-152,42,2);

        paintTicker(g,w,h,"AWARE NEWS  •  VERIFIED REPORTING");
    }

    private static void renderStoryPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,storyLabel(item.type()),false);

        g.setColor(PANEL_2);
        g.fillRoundRect(62,250,w-124,890,24,24);
        paintPlaceholderGrid(g,84,272,w-168,846);

        int lowerY=1170;
        g.setColor(PANEL);
        g.fillRoundRect(62,lowerY,w-124,325,22,22);
        g.setColor(ACCENT);
        g.fillRect(62,lowerY,9,325);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        g.drawString(storyLabel(item.type()),94,lowerY+48);

        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),10),new Font("SansSerif",Font.BOLD,38),94,lowerY+102,w-188,46,2);
        g.setColor(MUTED);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.PLAIN,27),94,lowerY+215,w-188,38,3);

        paintTicker(g,w,h,"AWARE NEWS  •  STORY UPDATE");
    }

    private static void renderSourceCard(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"SOURCES",false);

        g.setColor(PANEL);
        g.fillRoundRect(70,310,w-140,970,30,30);
        g.setColor(ACCENT);
        g.fillRect(70,310,10,970);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif",Font.BOLD,24));
        g.drawString("REPORTING SOURCES",110,390);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.BOLD,38),110,500,w-220,58,9);

        g.setColor(MUTED);
        drawWrapped(g,"Sources used to verify and build this report.",new Font("SansSerif",Font.PLAIN,25),110,1130,w-220,38,2);

        paintTicker(g,w,h,"AWARE NEWS  •  SOURCES");
    }

    private static Graphics2D prepare(BufferedImage img){
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(BG);g.fillRect(0,0,img.getWidth(),img.getHeight());
        return g;
    }

    private static void paintTopBar(Graphics2D g,int w,String label,boolean topStory){
        g.setColor(new Color(8,15,22));
        g.fillRect(0,0,w,170);

        g.setColor(topStory?RED:ACCENT);
        g.fillRect(0,0,w,8);

        g.setColor(topStory?RED:ACCENT);
        g.fillRoundRect(48,45,190,74,10,10);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,35));
        g.drawString("AWARE",68,94);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif",Font.BOLD,26));
        g.drawString("NEWS",260,93);

        String right=label==null||label.isBlank()?"NEWS UPDATE":label.toUpperCase(Locale.ROOT);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        FontMetrics fm=g.getFontMetrics();
        int tagW=Math.max(150,fm.stringWidth(right)+38);
        g.setColor(new Color(23,34,45));
        g.fillRoundRect(w-48-tagW,53,tagW,58,9,9);
        g.setColor(topStory?new Color(255,145,151):ACCENT);
        g.drawString(right,w-48-tagW+19,90);

        g.setColor(new Color(40,58,72));
        g.fillRect(48,145,w-96,1);
    }

    private static void paintTicker(Graphics2D g,int w,int h,String message){
        int y=h-105;
        g.setColor(TICKER);g.fillRect(0,y,w,105);
        g.setColor(ACCENT);g.fillRect(0,y,w,5);
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString(message,50,y+58);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.BOLD,17));
        String mark="AWARE";
        FontMetrics fm=g.getFontMetrics();
        g.drawString(mark,w-50-fm.stringWidth(mark),y+58);
    }

    private static void paintImageFrame(Graphics2D g,BufferedImage source,int x,int y,int w,int h,int radius){
        g.setColor(new Color(31,45,57));
        g.fillRoundRect(x-5,y-5,w+10,h+10,radius+5,radius+5);
        Shape oldClip=g.getClip();
        g.setClip(new RoundRectangle2D.Double(x,y,w,h,radius,radius));
        drawCover(g,source,x,y,w,h);
        g.setClip(oldClip);
    }

    private static void paintPlaceholderGrid(Graphics2D g,int x,int y,int w,int h){
        g.setColor(new Color(31,43,54));
        g.fillRoundRect(x,y,w,h,20,20);
        g.setColor(new Color(42,57,69));
        for(int yy=y+70;yy<y+h;yy+=120)g.drawLine(x+40,yy,x+w-40,yy);
        for(int xx=x+80;xx<x+w;xx+=150)g.drawLine(xx,y+40,xx,y+h-40);
        g.setColor(ACCENT);
        g.fillOval(x+w/2-15,y+h/2-15,30,30);
    }

    private static String storyLabel(String type){
        if(type==null||type.isBlank())return "NEWS UPDATE";
        String t=type.replace('_',' ').trim().toUpperCase(Locale.ROOT);
        return switch(t){
            case "TIMELINE"->"TIMELINE";
            case "BACKGROUND"->"NEWS UPDATE";
            case "HOOK"->"TOP STORY";
            default->t;
        };
    }

    private static String coverHeadline(String text,int maxWords){
        if(text==null||text.isBlank())return "NEWS UPDATE";
        String clean=text.replaceAll("\\s+"," ").trim();
        String[] words=clean.split(" ");
        String result=String.join(" ",Arrays.copyOfRange(words,0,Math.min(maxWords,words.length)));
        if(words.length>maxWords)result+="…";
        return result.toUpperCase(Locale.ROOT);
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
