package autonewsroller.visuals;

import autonewsroller.model.*;
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
        else if("POLITICAL_CONTEXT".equalsIgnoreCase(item.type()))renderPoliticalContext(g,item,w,h);
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

    public Path renderOverlay(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=prepareTransparent(canvas);
        if("HOOK".equalsIgnoreCase(item.type()))paintHookOverlay(g,item,w,h);
        else paintStoryOverlay(g,item,w,h);
        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    private static void renderHookPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        drawCover(g,source,0,imageY,w,imageH,0.38);
        paintHookOverlay(g,item,w,h);
    }

    private static void paintHookOverlay(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"TOP STORY",true);
        int tickerY=h-BroadcastTheme.TICKER_HEIGHT;
        int panelH=430;
        int panelY=tickerY-panelH;

        for(int n=0;n<7;n++){
            int alpha=20+n*24;
            g.setColor(new Color(4,10,17,Math.min(190,alpha)));
            g.fillRect(0,panelY-150+n*24,w,28);
        }
        g.setColor(new Color(NAVY.getRed(),NAVY.getGreen(),NAVY.getBlue(),232));
        g.fillRect(0,panelY,w,panelH);
        g.setColor(RED);g.fillRect(0,panelY,w,8);

        g.setColor(RED);g.fillRoundRect(48,panelY+34,188,44,8,8);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString("TOP STORY",70,panelY+64);

        String headline=coverHeadline(item.title(),8);
        g.setColor(Color.WHITE);
        Font headlineFont=fitHeadlineFont(g,headline,w-96,3,70,50);
        drawWrapped(g,headline,headlineFont,48,panelY+145,w-96,headlineFont.getSize()+7,3);

        String deck=compactDeck(item.body(),14);
        g.setColor(new Color(226,231,237));
        drawWrapped(g,deck,new Font("SansSerif",Font.BOLD,27),48,panelY+337,w-96,36,2);
        paintTicker(g,w,h,"AWARE NEWS","VERIFIED REPORTING",true);
    }

    private static void renderStoryPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        drawCover(g,source,0,imageY,w,imageH,0.47);
        paintStoryOverlay(g,item,w,h);
    }

    private static void paintStoryOverlay(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,storyLabel(item.type()),false);
        int tickerY=h-BroadcastTheme.TICKER_HEIGHT;
        int panelH=300;
        int panelY=tickerY-panelH;

        for(int n=0;n<5;n++){
            int alpha=18+n*27;
            g.setColor(new Color(4,10,17,Math.min(160,alpha)));
            g.fillRect(0,panelY-100+n*22,w,26);
        }
        g.setColor(new Color(NAVY.getRed(),NAVY.getGreen(),NAVY.getBlue(),228));
        g.fillRect(0,panelY,w,panelH);
        g.setColor(BLUE);g.fillRect(0,panelY,12,panelH);
        g.setColor(RED);g.fillRect(12,panelY,176,7);

        String slug=storyLabel(item.type());
        g.setColor(BLUE_LIGHT);g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString(slug,50,panelY+50);

        String headline=coverHeadline(item.title(),10);
        g.setColor(TEXT);
        Font headlineFont=fitHeadlineFont(g,headline,w-100,2,45,37);
        drawWrapped(g,headline,headlineFont,50,panelY+112,w-100,headlineFont.getSize()+7,2);

        String deck=compactDeck(item.body(),13);
        g.setColor(MUTED);
        drawWrapped(g,deck,new Font("SansSerif",Font.PLAIN,24),50,panelY+225,w-100,31,2);
        paintTicker(g,w,h,"AWARE NEWS","STORY UPDATE",false);
    }

    private static void renderHookPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        paintPlaceholderGrid(g,0,imageY,w,imageH);
        paintHookOverlay(g,item,w,h);
    }

    private static void renderStoryPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        paintPlaceholderGrid(g,0,imageY,w,imageH);
        paintStoryOverlay(g,item,w,h);
    }

    private static void renderSourceCard(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"SOURCES",false);
        int x=62,y=210,panelW=w-124,panelH=h-210-BroadcastTheme.TICKER_HEIGHT-70;
        g.setColor(BroadcastTheme.PANEL);g.fillRoundRect(x,y,panelW,panelH,22,22);
        g.setColor(RULE);g.drawRoundRect(x,y,panelW,panelH,22,22);
        g.setColor(BLUE);g.fillRect(x,y,panelW/2,8);
        g.setColor(RED);g.fillRect(x+panelW/2,y,panelW-panelW/2,8);

        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,50));
        g.drawString("SOURCES FOR THIS REPORT",x+42,y+90);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,23));
        g.drawString("Independent reporting and primary material used to build this video.",x+42,y+132);

        List<String>sources=new ArrayList<>();
        for(String raw:(item.body()==null?"":item.body()).split("\\s*[•|]\\s*")){
            String s=raw.replaceAll("\\s+"," ").trim();
            if(!s.isBlank()&&!sources.contains(s))sources.add(s);
        }
        int rowY=y+205;
        for(int i=0;i<sources.size()&&i<7;i++){
            String s=sources.get(i);
            g.setColor(new Color(16,31,49));g.fillRoundRect(x+42,rowY,panelW-84,86,14,14);
            g.setColor(i%2==0?BLUE:RED);g.fillRoundRect(x+42,rowY,10,86,10,10);
            g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,31));
            drawWrapped(g,s,new Font("SansSerif",Font.BOLD,31),x+78,rowY+53,panelW-132,38,1);
            rowY+=105;
        }
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.BOLD,23));
        g.drawString(sources.size()+" REPORTING SOURCE"+(sources.size()==1?"":"S"),x+42,y+panelH-58);
        paintTicker(g,w,h,"AWARE NEWS","SOURCES",false);
    }

    private static void renderPoliticalContext(Graphics2D g,VisualPlan.Item item,int w,int h){
        PoliticalVisualData p=PoliticalVisualData.fromMap(item.data());
        paintTopBar(g,w,"POLITICAL CONTEXT",false);

        int panelX=BroadcastTheme.POLITICAL_PANEL_X;
        int panelY=BroadcastTheme.POLITICAL_PANEL_Y;
        int panelW=w-panelX*2;
        int panelH=Math.min(BroadcastTheme.POLITICAL_PANEL_BOTTOM_SAFE-panelY,h-BroadcastTheme.TICKER_HEIGHT-panelY-55);

        g.setColor(BroadcastTheme.PANEL);g.fillRoundRect(panelX,panelY,panelW,panelH,22,22);
        g.setColor(RULE);g.drawRoundRect(panelX,panelY,panelW,panelH,22,22);
        g.setColor(BLUE);g.fillRect(panelX,panelY,panelW/2,9);
        g.setColor(RED);g.fillRect(panelX+panelW/2,panelY,panelW-panelW/2,9);

        int x=panelX+44,contentW=panelW-88;
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,49));
        g.drawString("POLITICAL CONTEXT",x,panelY+78);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,22));
        g.drawString("Publisher mix and story framing are separate measurements.",x,panelY+116);

        boolean showSourceMix=bool(item.data().getOrDefault("showSourceMix",true));
        boolean showConfidence=bool(item.data().getOrDefault("showConfidence",true));
        Map<String,Object>details=map(item.data().get("sourceMix"));
        Map<String,Object>publisherDetails=map(details.get("publisherDetails"));

        int sourceY=panelY+180;
        if(showSourceMix){
            g.setColor(BLUE_LIGHT);g.setFont(new Font("SansSerif",Font.BOLD,27));
            g.drawString(p.totalSourceCount()==1?"SOURCE RATING":"SOURCE MIX",x,sourceY);
        }
        if(showSourceMix&&p.totalSourceCount()==1&&publisherDetails.size()==1){
            Map.Entry<String,Object>e=publisherDetails.entrySet().iterator().next();
            Map<String,Object>d=map(e.getValue());
            String bucket=String.valueOf(d.getOrDefault("bucket","unknown")).toUpperCase(Locale.ROOT);
            String original=String.valueOf(d.getOrDefault("originalClassification",bucket));
            g.setColor(TEXT);drawWrapped(g,e.getKey(),new Font("SansSerif",Font.BOLD,42),x,sourceY+68,contentW,50,2);
            g.setColor(colorForBucket(bucket));g.setFont(new Font("SansSerif",Font.BOLD,39));
            g.drawString(original.toUpperCase(Locale.ROOT),x,sourceY+177);
        }else if(showSourceMix){
            int total=Math.max(1,p.totalSourceCount());
            drawPoliticalLabels(g,x,sourceY+58,contentW,
                    sourcePercent(p.sourceLeft(),total),sourcePercent(p.sourceCenter(),total),sourcePercent(p.sourceRight(),total),
                    Integer.toString(p.sourceLeft()),Integer.toString(p.sourceCenter()),Integer.toString(p.sourceRight()));
            drawSourceSegment(g,x,sourceY+146,contentW,p.sourceLeft(),p.sourceCenter(),p.sourceRight(),p.sourceUnknown(),total);
            if(p.sourceUnknown()>0){
                g.setColor(BroadcastTheme.POLITICAL_UNKNOWN);g.setFont(new Font("SansSerif",Font.BOLD,20));
                g.drawString(p.sourceUnknown()+" UNRATED",x,sourceY+239);
            }
        }

        int dividerY=showSourceMix?panelY+515:panelY+325;
        g.setColor(RULE);g.fillRect(x,dividerY,contentW,2);

        int storyY=dividerY+72;
        g.setColor(BLUE_LIGHT);g.setFont(new Font("SansSerif",Font.BOLD,28));
        g.drawString("STORY FRAMING",x,storyY);

        int[] pct=p.storyPercentages();
        drawPoliticalLabels(g,x,storyY+60,contentW,pct[0],pct[1],pct[2],pct[0]+"%",pct[1]+"%",pct[2]+"%");
        int meterY=storyY+150;
        drawThreeWayMeter(g,x,meterY,contentW,pct[0],pct[1],pct[2]);

        String classification=p.storyClassification().replace('_',' ').toUpperCase(Locale.ROOT);
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,48));
        g.drawString(classification,x,meterY+137);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,23));
        g.drawString(showConfidence?"FRAMING WEIGHT • CONFIDENCE "+Math.round(p.storyConfidence()*100)+"%":"FRAMING WEIGHT",x,meterY+177);

        if(!p.storySummary().isBlank()){
            g.setColor(new Color(210,217,225));
            drawWrapped(g,p.storySummary(),new Font("SansSerif",Font.PLAIN,23),x,meterY+235,contentW,33,3);
        }

        String provider=p.sourceProvider();
        String sourceLine=provider.isBlank()?"Publisher baseline: unrated metadata":("Publisher baseline: "+provider+(p.sourceAsOf().isBlank()?"":" • "+p.sourceAsOf()));
        g.setColor(new Color(139,154,170));
        drawWrapped(g,sourceLine+" • Framing describes presentation, not factual accuracy.",new Font("SansSerif",Font.PLAIN,18),x,panelY+panelH-52,contentW,24,2);
        paintTicker(g,w,h,"AWARE NEWS","POLITICAL CONTEXT",false);
    }

    private static void drawPoliticalLabels(Graphics2D g,int x,int y,int w,int left,int center,int right,String leftValue,String centerValue,String rightValue){
        int col=w/3;
        String[]labels={"LEFT","CENTER","RIGHT"};
        String[]values={leftValue,centerValue,rightValue};
        Color[]colors={BroadcastTheme.POLITICAL_LEFT,BroadcastTheme.POLITICAL_CENTER,BroadcastTheme.POLITICAL_RIGHT};
        for(int i=0;i<3;i++){
            int cx=x+i*col;
            g.setColor(colors[i]);g.setFont(new Font("SansSerif",Font.BOLD,23));g.drawString(labels[i],cx,y);
            g.setFont(new Font("SansSerif",Font.BOLD,42));g.drawString(values[i],cx,y+47);
        }
    }

    private static int sourcePercent(int value,int total){return total<=0?0:(int)Math.round(value*100.0/total);}

    private static void drawSourceSegment(Graphics2D g,int x,int y,int w,int left,int center,int right,int unknown,int total){
        int h=68;
        int used=0;
        int[] values={left,center,right,unknown};
        Color[] colors={BroadcastTheme.POLITICAL_LEFT,BroadcastTheme.POLITICAL_CENTER,BroadcastTheme.POLITICAL_RIGHT,BroadcastTheme.POLITICAL_UNKNOWN};
        for(int i=0;i<values.length;i++){
            if(values[i]<=0)continue;
            int seg=i==values.length-1?w-used:(int)Math.round(w*(values[i]/(double)total));
            seg=Math.max(1,Math.min(w-used,seg));
            g.setColor(colors[i]);g.fillRect(x+used,y,seg,h);used+=seg;
        }
        if(used<w){g.setColor(BroadcastTheme.POLITICAL_UNKNOWN);g.fillRect(x+used,y,w-used,h);}
    }

    private static void drawThreeWayMeter(Graphics2D g,int x,int y,int w,int left,int center,int right){
        int h=72;
        int leftW=(int)Math.round(w*(left/100.0));
        int centerW=(int)Math.round(w*(center/100.0));
        int rightW=Math.max(0,w-leftW-centerW);
        g.setColor(BroadcastTheme.POLITICAL_LEFT);g.fillRect(x,y,leftW,h);
        g.setColor(BroadcastTheme.POLITICAL_CENTER);g.fillRect(x+leftW,y,centerW,h);
        g.setColor(BroadcastTheme.POLITICAL_RIGHT);g.fillRect(x+leftW+centerW,y,rightW,h);
        g.setColor(RULE);g.drawRect(x,y,w,h);
    }

    private static Color colorForBucket(String bucket){
        return switch(bucket.toLowerCase(Locale.ROOT)){
            case "left"->BroadcastTheme.POLITICAL_LEFT;
            case "center"->BroadcastTheme.POLITICAL_CENTER;
            case "right"->BroadcastTheme.POLITICAL_RIGHT;
            default->BroadcastTheme.POLITICAL_UNKNOWN;
        };
    }

    private static boolean bool(Object raw){return raw instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(raw));}

    private static Map<String,Object> map(Object raw){
        if(!(raw instanceof Map<?,?>m))return Map.of();
        Map<String,Object>out=new LinkedHashMap<>();
        for(var e:m.entrySet())out.put(String.valueOf(e.getKey()),e.getValue());
        return out;
    }

    private static Graphics2D prepare(BufferedImage img){
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(BG);g.fillRect(0,0,img.getWidth(),img.getHeight());
        return g;
    }

    private static Graphics2D prepareTransparent(BufferedImage img){
        Graphics2D g=img.createGraphics();
        g.setComposite(AlphaComposite.Clear);g.fillRect(0,0,img.getWidth(),img.getHeight());
        g.setComposite(AlphaComposite.SrcOver);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return g;
    }

    private static void paintTopBar(Graphics2D g,int w,String label,boolean topStory){
        g.setColor(new Color(NAVY.getRed(),NAVY.getGreen(),NAVY.getBlue(),244));
        g.fillRect(0,0,w,BroadcastTheme.HEADER_HEIGHT);
        g.setColor(BLUE);g.fillRect(0,0,w,7);
        g.setColor(RED);g.fillRect(0,7,Math.max(150,w/4),4);

        g.setColor(BLUE);g.fillRoundRect(34,25,166,61,8,8);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,30));
        g.drawString("AWARE",51,66);
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,23));
        g.drawString("NEWS",219,65);

        String right=label==null||label.isBlank()?"NEWS UPDATE":label.toUpperCase(Locale.ROOT);
        g.setFont(new Font("SansSerif",Font.BOLD,17));
        FontMetrics fm=g.getFontMetrics();int tagW=Math.max(150,fm.stringWidth(right)+34);
        g.setColor(topStory?RED:BLUE);g.fillRoundRect(w-34-tagW,29,tagW,50,7,7);
        g.setColor(Color.WHITE);g.drawString(right,w-34-tagW+17,61);
        g.setColor(RULE);g.fillRect(34,BroadcastTheme.HEADER_HEIGHT-12,w-68,2);
    }

    private static void paintTicker(Graphics2D g,int w,int h,String brand,String message,boolean urgent){
        int y=h-BroadcastTheme.TICKER_HEIGHT;
        g.setColor(TICKER);g.fillRect(0,y,w,BroadcastTheme.TICKER_HEIGHT);
        g.setColor(BLUE);g.fillRect(0,y,w,5);
        g.setColor(urgent?RED:BLUE);g.fillRect(0,y+5,205,BroadcastTheme.TICKER_HEIGHT-5);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,18));
        g.drawString(brand,29,y+49);
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,18));
        g.drawString(message,238,y+49);
        g.setColor(RED);g.fillRect(w-16,y+5,16,BroadcastTheme.TICKER_HEIGHT-5);
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
