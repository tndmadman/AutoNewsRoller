package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public final class CardRenderer {
    private static final Color BG=BroadcastTheme.BACKGROUND;
    private static final Color NAVY=BroadcastTheme.NAVY;
    private static final Color NAVY_2=BroadcastTheme.NAVY_2;
    private static final Color BLUE=BroadcastTheme.BLUE;
    private static final Color BLUE_LIGHT=BroadcastTheme.BLUE_LIGHT;
    private static final Color RED=BroadcastTheme.RED;
    private static final Color RED_LIGHT=BroadcastTheme.RED_LIGHT;
    private static final Color TEXT=BroadcastTheme.TEXT;
    private static final Color MUTED=BroadcastTheme.MUTED;
    private static final Color RULE=BroadcastTheme.RULE;
    private static final Color TICKER=BroadcastTheme.TICKER;

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

        int imageX=0,imageY=170,imageW=w,imageH=Math.min(1120,h-700);
        drawCover(g,source,imageX,imageY,imageW,imageH,0.38);

        // Dark broadcast gradient-like wash built deterministically with stacked alpha bars.
        int lowerY=imageY+imageH-245;
        for(int n=0;n<8;n++){
            int alpha=35+n*24;
            g.setColor(new Color(4,15,27,Math.min(225,alpha)));
            g.fillRect(0,lowerY+n*32,w,36);
        }

        int panelY=imageY+imageH-34;
        g.setColor(NAVY);
        g.fillRect(0,panelY,w,430);
        g.setColor(RED);
        g.fillRect(0,panelY,w,9);

        g.setColor(RED);
        g.fillRect(54,panelY+38,192,46);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString("TOP STORY",77,panelY+69);

        String headline=coverHeadline(item.title(),7);
        g.setColor(Color.WHITE);
        Font headlineFont=fitHeadlineFont(g,headline,w-108,3,68,48);
        drawWrapped(g,headline,headlineFont,54,panelY+150,w-108,headlineFont.getSize()+7,3);

        String deck=compactDeck(item.body(),18);
        g.setColor(MUTED);
        drawWrapped(g,deck,new Font("SansSerif",Font.BOLD,29),54,panelY+345,w-108,40,2);

        paintTicker(g,w,h,"AWARE NEWS","VERIFIED REPORTING",true);
    }

    private static void renderStoryPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        paintTopBar(g,w,storyLabel(item.type()),false);

        int imageX=48,imageY=205,imageW=w-96,imageH=Math.min(980,h-790);
        paintImageFrame(g,source,imageX,imageY,imageW,imageH,8,0.47);

        int lowerY=imageY+imageH+16;
        g.setColor(NAVY);
        g.fillRect(48,lowerY,imageW,330);
        g.setColor(BLUE);
        g.fillRect(48,lowerY,10,330);
        g.setColor(RED);
        g.fillRect(58,lowerY,138,7);

        g.setColor(BLUE_LIGHT);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        g.drawString(storyLabel(item.type()),86,lowerY+52);

        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),10),new Font("SansSerif",Font.BOLD,39),86,lowerY+111,imageW-76,47,2);

        g.setColor(MUTED);
        drawWrapped(g,compactDeck(item.body(),28),new Font("SansSerif",Font.PLAIN,27),86,lowerY+222,imageW-76,38,3);

        paintTicker(g,w,h,"AWARE NEWS","STORY UPDATE",false);
    }

    private static void renderHookPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"TOP STORY",true);
        g.setColor(NAVY_2);
        g.fillRect(0,170,w,1070);
        paintPlaceholderGrid(g,42,215,w-84,915);

        int panelY=1190;
        g.setColor(NAVY);
        g.fillRect(0,panelY,w,430);
        g.setColor(RED);
        g.fillRect(0,panelY,w,9);
        g.setColor(RED);
        g.fillRect(54,panelY+38,192,46);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString("TOP STORY",77,panelY+69);

        String headline=coverHeadline(item.title(),7);
        g.setColor(Color.WHITE);
        Font headlineFont=fitHeadlineFont(g,headline,w-108,3,68,48);
        drawWrapped(g,headline,headlineFont,54,panelY+150,w-108,headlineFont.getSize()+7,3);
        g.setColor(MUTED);
        drawWrapped(g,compactDeck(item.body(),18),new Font("SansSerif",Font.BOLD,29),54,panelY+345,w-108,40,2);
        paintTicker(g,w,h,"AWARE NEWS","VERIFIED REPORTING",true);
    }

    private static void renderStoryPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,storyLabel(item.type()),false);
        g.setColor(NAVY_2);
        g.fillRect(48,220,w-96,900);
        paintPlaceholderGrid(g,70,245,w-140,850);

        int lowerY=1145;
        g.setColor(NAVY);
        g.fillRect(48,lowerY,w-96,330);
        g.setColor(BLUE);
        g.fillRect(48,lowerY,10,330);
        g.setColor(RED);
        g.fillRect(58,lowerY,138,7);
        g.setColor(BLUE_LIGHT);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        g.drawString(storyLabel(item.type()),86,lowerY+52);
        g.setColor(TEXT);
        drawWrapped(g,coverHeadline(item.title(),10),new Font("SansSerif",Font.BOLD,39),86,lowerY+111,w-172,47,2);
        g.setColor(MUTED);
        drawWrapped(g,compactDeck(item.body(),28),new Font("SansSerif",Font.PLAIN,27),86,lowerY+222,w-172,38,3);
        paintTicker(g,w,h,"AWARE NEWS","STORY UPDATE",false);
    }

    private static void renderSourceCard(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"SOURCES",false);

        g.setColor(NAVY);
        g.fillRect(58,300,w-116,990);
        g.setColor(BLUE);
        g.fillRect(58,300,10,990);
        g.setColor(RED);
        g.fillRect(68,300,180,8);

        g.setColor(BLUE_LIGHT);
        g.setFont(new Font("SansSerif",Font.BOLD,23));
        g.drawString("REPORTING SOURCES",105,390);

        g.setColor(TEXT);
        drawWrapped(g,item.body(),new Font("SansSerif",Font.BOLD,39),105,500,w-210,60,9);

        g.setColor(MUTED);
        drawWrapped(g,"Sources used to verify and build this report.",new Font("SansSerif",Font.PLAIN,25),105,1190,w-210,38,2);
        paintTicker(g,w,h,"AWARE NEWS","SOURCES",false);
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
        g.setColor(NAVY);
        g.fillRect(0,0,w,BroadcastTheme.HEADER_HEIGHT);
        g.setColor(BLUE);
        g.fillRect(0,0,w,9);
        g.setColor(RED);
        g.fillRect(0,9,Math.max(170,w/4),5);

        g.setColor(BLUE);
        g.fillRect(42,42,194,76);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,36));
        g.drawString("AWARE",62,94);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif",Font.BOLD,27));
        g.drawString("NEWS",260,93);

        String right=label==null||label.isBlank()?"NEWS UPDATE":label.toUpperCase(Locale.ROOT);
        g.setFont(new Font("SansSerif",Font.BOLD,19));
        FontMetrics fm=g.getFontMetrics();
        int tagW=Math.max(160,fm.stringWidth(right)+40);
        g.setColor(topStory?RED:BLUE);
        g.fillRect(w-42-tagW,52,tagW,58);
        g.setColor(Color.WHITE);
        g.drawString(right,w-42-tagW+20,90);

        g.setColor(RULE);
        g.fillRect(42,145,w-84,2);
    }

    private static void paintTicker(Graphics2D g,int w,int h,String brand,String message,boolean urgent){
        int y=h-BroadcastTheme.TICKER_HEIGHT;
        g.setColor(TICKER);g.fillRect(0,y,w,BroadcastTheme.TICKER_HEIGHT);
        g.setColor(BLUE);g.fillRect(0,y,w,6);
        g.setColor(urgent?RED:BLUE);
        g.fillRect(0,y+6,230,BroadcastTheme.TICKER_HEIGHT-6);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString(brand,38,y+63);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString(message,270,y+63);
        g.setColor(RED);
        g.fillRect(w-22,y+6,22,BroadcastTheme.TICKER_HEIGHT-6);
    }

    private static void paintImageFrame(Graphics2D g,BufferedImage source,int x,int y,int w,int h,int radius,double verticalBias){
        g.setColor(RULE);
        g.fillRoundRect(x-4,y-4,w+8,h+8,radius+3,radius+3);
        Shape oldClip=g.getClip();
        g.setClip(new RoundRectangle2D.Double(x,y,w,h,radius,radius));
        drawCover(g,source,x,y,w,h,verticalBias);
        g.setClip(oldClip);
    }

    private static void paintPlaceholderGrid(Graphics2D g,int x,int y,int w,int h){
        g.setColor(NAVY_2);
        g.fillRect(x,y,w,h);
        g.setColor(RULE);
        for(int yy=y+70;yy<y+h;yy+=120)g.drawLine(x+40,yy,x+w-40,yy);
        for(int xx=x+80;xx<x+w;xx+=150)g.drawLine(xx,y+40,xx,y+h-40);
        g.setColor(BLUE);
        g.fillRect(x+w/2-18,y+h/2-18,36,36);
        g.setColor(RED);
        g.fillRect(x+w/2-7,y+h/2-7,14,14);
    }

    private static String storyLabel(String type){
        if(type==null||type.isBlank())return "NEWS UPDATE";
        String t=type.replace('_',' ').trim().toUpperCase(Locale.ROOT);
        return switch(t){
            case "TIMELINE"->"TIMELINE";
            case "BACKGROUND"->"CONTEXT";
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

    private static String compactDeck(String text,int maxWords){
        if(text==null||text.isBlank())return "";
        String clean=text.replaceAll("\\s+"," ").trim();
        String[] words=clean.split(" ");
        return String.join(" ",Arrays.copyOfRange(words,0,Math.min(maxWords,words.length)))+(words.length>maxWords?"…":"");
    }

    private static Font fitHeadlineFont(Graphics2D g,String text,int maxWidth,int maxLines,int startSize,int minSize){
        for(int size=startSize;size>=minSize;size-=2){
            Font font=new Font("SansSerif",Font.BOLD,size);
            if(wrappedLineCount(g,text,font,maxWidth)<=maxLines)return font;
        }
        return new Font("SansSerif",Font.BOLD,minSize);
    }

    private static int wrappedLineCount(Graphics2D g,String text,Font font,int maxWidth){
        g.setFont(font);FontMetrics fm=g.getFontMetrics();
        int lines=1;StringBuilder line=new StringBuilder();
        for(String word:(text==null?"":text.trim()).split("\\s+")){
            if(word.isBlank())continue;
            String test=line.length()==0?word:line+" "+word;
            if(fm.stringWidth(test)>maxWidth&&line.length()>0){lines++;line.setLength(0);line.append(word);}
            else{if(line.length()>0)line.append(' ');line.append(word);}
        }
        return lines;
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

    private static void drawCover(Graphics2D g,BufferedImage src,int x,int y,int w,int h,double verticalBias){
        double scale=Math.max(w/(double)src.getWidth(),h/(double)src.getHeight());
        int sw=(int)Math.round(w/scale),sh=(int)Math.round(h/scale);
        int sx=Math.max(0,(src.getWidth()-sw)/2);
        int maxSy=Math.max(0,src.getHeight()-sh);
        double bias=Math.max(0.0,Math.min(1.0,verticalBias));
        int sy=(int)Math.round(maxSy*bias);
        g.drawImage(src,x,y,x+w,y+h,sx,sy,sx+sw,sy+sh,null);
    }
}
