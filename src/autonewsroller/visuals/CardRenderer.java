package autonewsroller.visuals;

import autonewsroller.model.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

public class CardRenderer {
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
        g.scale(w/1080.0,h/1920.0);
        if("HOOK".equalsIgnoreCase(item.type()))renderHookPlaceholder(g,item,1080,1920);
        else if("SOURCE_CARD".equalsIgnoreCase(item.type()))renderSourceCard(g,item,1080,1920);
        else if("POLITICAL_CONTEXT".equalsIgnoreCase(item.type()))renderPoliticalContext(g,item,1080,1920);
        else if(isGraphic(item.type()))renderGraphicScene(g,item,1080,1920);
        else renderStoryPlaceholder(g,item,1080,1920);
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
        g.scale(w/1080.0,h/1920.0);
        if("HOOK".equalsIgnoreCase(item.type()))renderHookPackage(g,item,source,1080,1920);
        else renderStoryPackage(g,item,source,1080,1920);
        paintAiDisclosure(g,1080,1920);
        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    public Path renderOverlay(VisualPlan.Item item,Path out,int w,int h)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage canvas=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=prepareTransparent(canvas);
        g.scale(w/1080.0,h/1920.0);
        if("HOOK".equalsIgnoreCase(item.type()))paintHookOverlay(g,item,1080,1920);
        else paintStoryOverlay(g,item,1080,1920);
        paintAiDisclosure(g,1080,1920);
        g.dispose();
        ImageIO.write(canvas,"png",out.toFile());
        return out;
    }

    /** The identical base crop is used by Java previews and FFmpeg motion overlays. */
    public Path renderBackground(VisualPlan.Item item,Path sourcePath,Path out,int width,int height)throws Exception{
        Files.createDirectories(out.getParent());
        BufferedImage src=ImageIO.read(sourcePath.toFile());
        if(src==null)throw new IllegalArgumentException("Invalid image: "+sourcePath);
        BufferedImage img=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=prepare(img);g.scale(width/1080.0,height/1920.0);
        drawCover(g,src,0,BroadcastTheme.HEADER_HEIGHT,1080,
                1920-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT,
                "HOOK".equalsIgnoreCase(item.type())?.38:.47);
        g.dispose();ImageIO.write(img,"png",out.toFile());return out;
    }

    /** Public template classifier, used to animate only non-photographic information cards. */
    public static boolean supportsGraphicAnimation(String type){
        return type!=null&&(isGraphic(type)||"POLITICAL_CONTEXT".equalsIgnoreCase(type)
                ||"SOURCE_CARD".equalsIgnoreCase(type));
    }

    /**
     * Separate a deterministic editorial background from its accurate final graphic.
     * FFmpeg can reveal the difference layer in 0.18 seconds without any per-frame PNG
     * encoding or changing the source-backed text/data values.
     */
    public void renderGraphicLayers(Path fullFrame,Path baseOut,Path overlayOut,int width,int height)throws Exception{
        BufferedImage finalImage=ImageIO.read(fullFrame.toFile());
        if(finalImage==null||finalImage.getWidth()!=width||finalImage.getHeight()!=height)
            throw new IllegalArgumentException("Invalid graphic source for animation");
        Files.createDirectories(baseOut.getParent());
        Files.createDirectories(overlayOut.getParent());
        BufferedImage background=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=background.createGraphics();
        g.setColor(BroadcastTheme.NAVY);
        g.fillRect(0,0,width,height);
        g.setColor(BroadcastTheme.BLUE);
        g.fillRect(0,0,width,Math.max(2,(int)Math.round(height*.004)));
        g.setColor(BroadcastTheme.RED);
        g.fillRect(0,0,Math.max(25,width/5),Math.max(2,(int)Math.round(height*.003)));
        g.dispose();
        BufferedImage overlay=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int finalRgb=finalImage.getRGB(x,y)&0x00ffffff;
            int backgroundRgb=background.getRGB(x,y)&0x00ffffff;
            if(finalRgb!=backgroundRgb)overlay.setRGB(x,y,0xff000000|finalRgb);
        }
        ImageIO.write(background,"png",baseOut.toFile());
        ImageIO.write(overlay,"png",overlayOut.toFile());
    }

    private static boolean isGraphic(String type){
        return java.util.Set.of("STATISTIC","FACT_HIGHLIGHT","TIMELINE","QUOTE","DOCUMENT","CONTEXT").contains(type.toUpperCase(Locale.ROOT));
    }
    private static void paintAiDisclosure(Graphics2D g,int w,int h){
        g.setColor(new Color(6,10,16,220));g.fillRoundRect(w-295,140,255,47,7,7);
        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString("AI ILLUSTRATION",w-276,171);
    }

    /** Source-grounded typography, never a fake event photograph or generic grid. */
    private static void renderGraphicScene(Graphics2D g,VisualPlan.Item item,int w,int h){
        g.setColor(NAVY);g.fillRect(0,0,w,h);
        g.setColor(NAVY_2);g.fillRect(42,248,w-84,1335);
        g.setColor(RULE);g.drawRect(42,248,w-84,1335);
        g.setColor(BLUE);g.fillRect(42,248,12,1335);
        g.setColor(RED);g.fillRect(54,248,200,8);
        String type=item.type().toUpperCase(Locale.ROOT);
        paintTopBar(g,w,storyLabel(type),false);
        g.setColor(BLUE_LIGHT);g.setFont(new Font("SansSerif",Font.BOLD,26));
        g.drawString(switch(type){case "STATISTIC"->"BY THE NUMBERS";case "QUOTE"->"ON THE RECORD";
            case "TIMELINE"->"WHAT HAPPENED";case "FACT_HIGHLIGHT"->"THE KEY DEVELOPMENT";
            default->"THE STORY";},95,343);
        String main=type.equals("STATISTIC")?firstNumber(item.body()):"";
        if(type.equals("TIMELINE")){
            renderTimeline(g,item,w,h);
        }else if(type.equals("QUOTE")){
            g.setColor(RED_LIGHT);g.setFont(new Font("Serif",Font.BOLD,162));g.drawString("“",85,490);
            String quote=quoted(item.body());
            g.setColor(TEXT);
            Font quoteFont=fitHeadlineFont(g,quote,w-190,8,57,31);
            drawWrapped(g,quote,quoteFont,96,560,w-192,quoteFont.getSize()+19,8);
        }else{
            if(!main.isBlank()){
                g.setColor(TEXT);
                int size=150;
                while(size>45){
                    Font large=new Font("SansSerif",Font.BOLD,size);
                    if(g.getFontMetrics(large).stringWidth(main)<=w-190)break;
                    size-=5;
                }
                g.setFont(new Font("SansSerif",Font.BOLD,size));
                g.drawString(main,94,590);
                g.setColor(RED);g.fillRect(95,645,170,7);
            }
            int start=main.isBlank()?500:765;
            String text=item.body();
            if(text==null||text.isBlank())text=item.title();
            g.setColor(TEXT);
            int maxLines=main.isBlank()?9:6;
            Font font=fitHeadlineFont(g,text,w-190,maxLines,46,26);
            drawWrapped(g,text,font,95,start,w-190,font.getSize()+20,maxLines);
        }
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.BOLD,21));
        g.drawString("BASED ON VERIFIED REPORTING",95,1510);
        paintTicker(g,w,h,"AWARE NEWS",storyLabel(type),false);
    }
    private static void renderTimeline(Graphics2D g,VisualPlan.Item item,int w,int h){
        String narration=item.body()==null?"":item.body();
        java.util.List<String>events=autonewsroller.util.Text.sentences(narration);
        if(events.isEmpty())events=java.util.List.of(item.title());
        int count=Math.max(1,Math.min(3,events.size()));
        int x=110,start=540,step=230;
        g.setColor(RULE);g.fillRect(x+17,start-28,5,step*(count-1)+65);
        for(int i=0;i<count;i++){
            int y=start+i*step;
            g.setColor(i==0?RED:BLUE_LIGHT);
            g.fillOval(x,y-18,40,40);
            g.setColor(TEXT);
            String event=events.get(i);
            Font f=fitHeadlineFont(g,event,w-260,3,39,27);
            drawWrapped(g,event,f,x+75,y+10,w-260,f.getSize()+9,3);
        }
    }

    private static String firstNumber(String text){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\b\\d[\\d,.]*%?").matcher(text==null?"":text);
        return m.find()?m.group():"";
    }
    private static String quoted(String text){
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("[“\\\"]([^”\\\"]+)[”\\\"]").matcher(text==null?"":text);
        return m.find()?"“"+m.group(1)+"”":text;
    }

    private static void renderHookPackage(Graphics2D g,VisualPlan.Item item,BufferedImage source,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        drawCover(g,source,0,imageY,w,imageH,0.38);
        paintHookOverlay(g,item,w,h);
    }

    private static void paintHookOverlay(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"TOP STORY",true);
        int tickerY=h-BroadcastTheme.TICKER_HEIGHT-BroadcastLayout.postTextSafeFooter(h);
        int panelH=235;
        int panelY=tickerY-panelH;

        for(int n=0;n<7;n++){
            int alpha=20+n*24;
            g.setColor(new Color(4,10,17,Math.min(190,alpha)));
            g.fillRect(0,panelY-150+n*24,w,28);
        }
        g.setColor(new Color(NAVY.getRed(),NAVY.getGreen(),NAVY.getBlue(),232));
        g.fillRect(0,panelY,w,panelH);
        g.setColor(RED);g.fillRect(0,panelY,w,8);

        g.setColor(RED);g.fillRoundRect(48,panelY+16,188,36,8,8);
        g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString("TOP STORY",70,panelY+41);

        String headline=coverHeadline(item.title(),11);
        g.setColor(Color.WHITE);
        Font headlineFont=fitHeadlineFont(g,headline,w-96,3,55,35);
        drawWrapped(g,headline,headlineFont,48,panelY+107,w-96,headlineFont.getSize()+4,3);

        // No second oversized narration block: reserve space above for captions.
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
        int tickerY=h-BroadcastTheme.TICKER_HEIGHT-BroadcastLayout.postTextSafeFooter(h);
        int panelH=148;
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
        g.setColor(BLUE_LIGHT);g.setFont(new Font("SansSerif",Font.BOLD,20));
        g.drawString(slug,50,panelY+36);
        String deck=compactDeck(item.body(),16);
        g.setColor(TEXT);
        Font font=fitHeadlineFont(g,deck,w-100,2,32,22);
        drawWrapped(g,deck,font,50,panelY+82,w-100,font.getSize()+7,2);
        paintTicker(g,w,h,"AWARE NEWS","STORY UPDATE",false);
    }

    private static void renderHookPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        paintEditorialBackground(g,item,0,imageY,w,imageH);
        paintHookOverlay(g,item,w,h);
    }

    private static void renderStoryPlaceholder(Graphics2D g,VisualPlan.Item item,int w,int h){
        int imageY=BroadcastTheme.HEADER_HEIGHT;
        int imageH=h-BroadcastTheme.HEADER_HEIGHT-BroadcastTheme.TICKER_HEIGHT;
        paintEditorialBackground(g,item,0,imageY,w,imageH);
        paintStoryOverlay(g,item,w,h);
    }

    private static void renderSourceCard(Graphics2D g,VisualPlan.Item item,int w,int h){
        paintTopBar(g,w,"SOURCES",false);
        int x=62,y=210,panelW=w-124,panelH=h-210-BroadcastTheme.TICKER_HEIGHT-BroadcastLayout.postTextSafeFooter(h)-70;
        g.setColor(BroadcastTheme.PANEL);g.fillRoundRect(x,y,panelW,panelH,22,22);
        g.setColor(RULE);g.drawRoundRect(x,y,panelW,panelH,22,22);
        g.setColor(BLUE);g.fillRect(x,y,panelW/2,8);
        g.setColor(RED);g.fillRect(x+panelW/2,y,panelW-panelW/2,8);

        g.setColor(TEXT);g.setFont(new Font("SansSerif",Font.BOLD,50));
        g.drawString("SOURCES FOR THIS REPORT",x+42,y+90);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,23));
        g.drawString("Independent reporting and primary material used to build this video.",x+42,y+132);

        java.util.List<String>sources=new ArrayList<>();
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
        int panelH=Math.min(BroadcastTheme.POLITICAL_PANEL_BOTTOM_SAFE-panelY,
                h-BroadcastTheme.TICKER_HEIGHT-BroadcastLayout.postTextSafeFooter(h)-panelY-55);

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
        int footer=BroadcastLayout.postTextSafeFooter(h);
        int footerY=h-footer;
        // Opaque navy plate is behind TikTok's post description, not a new on-video title.
        g.setColor(NAVY);g.fillRect(0,footerY,w,footer);
        g.setColor(RULE);g.fillRect(0,footerY,w,2);
        int y=footerY-BroadcastTheme.TICKER_HEIGHT;
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

    private static void paintEditorialBackground(Graphics2D g,VisualPlan.Item item,int x,int y,int w,int h){
        g.setColor(NAVY_2);g.fillRect(x,y,w,h);
        g.setColor(BLUE);g.fillRect(60,y+200,11,Math.max(280,h-510));
        g.setColor(new Color(46,60,78));g.fillRect(91,y+202,w-185,3);
        g.setColor(TEXT);
        String statement=item.body()==null||item.body().isBlank()?item.title():item.body();
        Font f=fitHeadlineFont(g,statement,w-200,8,50,26);
        drawWrapped(g,statement,f,105,y+355,w-210,f.getSize()+19,8);
        g.setColor(MUTED);g.setFont(new Font("SansSerif",Font.BOLD,22));
        g.drawString("DOCUMENTED NEWS CONTEXT",105,y+215);
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
        java.util.List<String>words=new ArrayList<>();
        for(String raw:(text==null?"":text.trim()).split("\\s+")){
            if(raw.isBlank())continue;
            String remainder=raw;
            while(!remainder.isEmpty()){
                int count=remainder.length();
                while(count>1&&fm.stringWidth(remainder.substring(0,count))>maxWidth)count--;
                words.add(remainder.substring(0,count));
                remainder=remainder.substring(count);
            }
        }
        StringBuilder line=new StringBuilder();int yy=y,lines=0;
        for(int pos=0;pos<words.size();pos++){
            String word=words.get(pos);
            String next=line.length()==0?word:line+" "+word;
            if(fm.stringWidth(next)>maxWidth&&line.length()>0){
                if(lines+1>=maxLines){
                    drawWithEllipsis(g,line.toString(),x,yy,maxWidth);
                    return yy+step;
                }
                g.drawString(line.toString(),x,yy);
                yy+=step;lines++;
                line.setLength(0);line.append(word);
            }else{
                if(line.length()>0)line.append(' ');
                line.append(word);
            }
        }
        if(line.length()>0&&lines<maxLines){g.drawString(line.toString(),x,yy);yy+=step;}
        return yy;
    }

    private static void drawWithEllipsis(Graphics2D g,String input,int x,int y,int maxWidth){
        FontMetrics fm=g.getFontMetrics();
        String s=input;
        while(!s.isEmpty()&&fm.stringWidth(s+"…")>maxWidth)s=s.substring(0,s.length()-1);
        g.drawString(s+"…",x,y);
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
