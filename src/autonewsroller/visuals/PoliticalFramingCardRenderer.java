package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Visual evidence from one real article; does not fabricate publisher screenshots. */
public final class PoliticalFramingCardRenderer {
    public Path render(VisualPlan.Item item,Path destination,int width,int height,boolean classic)throws Exception{
        if(classic)new ClassicCardRenderer().render(item,destination,width,height);
        else new CardRenderer().render(item,destination,width,height);
        BufferedImage image=ImageIO.read(destination.toFile());
        if(image==null)throw new IllegalStateException("Missing framing card background");
        Graphics2D g=image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.scale(width/1080.0,height/1920.0);
        // Overlay only the central region: preserve original top bar, captions, and 220px footer.
        g.setColor(BroadcastTheme.NAVY);g.fillRoundRect(45,205,990,1035,26,26);
        g.setColor(BroadcastTheme.RULE);g.setStroke(new BasicStroke(3));g.drawRoundRect(45,205,990,1035,26,26);
        g.setColor(BroadcastTheme.RED);g.fillRect(45,205,990,13);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,29));
        g.drawString(trim(item.title(),45).toUpperCase(Locale.ROOT),78,275);
        g.setColor(BroadcastTheme.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,43));
        g.drawString("FRAMING EXPOSED // ARTICLE TEXT",78,346);

        List<Map<String,Object>>sources=extract(item.data().get("sources"));
        if(sources.size()!=1)throw new IllegalArgumentException("Framing card requires one article");
        Map<String,Object>article=sources.get(0);
        List<Map<String,Object>>excerpts=extract(article.get("attributedExcerpts"));
        String type=item.type();
        if(type.equals("FRAMING_HEADLINE")){
            panel(g,article,78,396,924,460);
            note(g,"EXACT ATTRIBUTED HEADLINE",78,935);
        }else if(type.equals("FRAMING_TEASER")){
            write(g,"How is the story presented?",78,440,908,57,3,Font.BOLD);
            note(g,"Evidence first. Distinguish reporting from interpretation.",78,755);
        }else if(type.equals("LANGUAGE_HIGHLIGHT")){
            note(g,"THE ARTICLE'S HEADLINE",78,409);
            write(g,String.valueOf(article.get("headline")),78,470,905,43,6,Font.BOLD);
            g.setColor(BroadcastTheme.RED);g.fillRect(78,825,900,6);
            note(g,"OBSERVABLE WORDS // NOT AN ASSUMED MOTIVE",78,884);
        }else if(type.equals("RHETORIC_CARD")){
            Map<String,Object>excerpt=excerpts.size()>1?excerpts.get(1):excerpts.get(0);
            note(g,"ORIGINAL "+String.valueOf(excerpt.get("location")).toUpperCase(Locale.ROOT)+" EXCERPT",78,415);
            write(g,String.valueOf(excerpt.get("text")),78,468,907,37,8,Font.BOLD);
            g.setColor(BroadcastTheme.BLUE_LIGHT);g.fillRect(78,890,900,5);
            note(g,"Analysis concerns wording, not publisher intent",78,950);
        }else if(type.equals("FACT_CHECK_CARD")){
            write(g,trim(item.body(),550),78,430,905,38,14,Font.PLAIN);
            note(g,"ARTICLE CLAIMS VS EVIDENCE",78,1160);
        }else if(type.equals("FRAMING_CONTEXT")){
            write(g,trim(item.body(),540),78,430,905,38,14,Font.PLAIN);
            note(g,"POSSIBLE READER INTERPRETATION",78,1160);
        }else{
            write(g,trim(item.body(),540),78,430,905,38,14,Font.PLAIN);
            note(g,"A FINDING NEEDS SUPPORT IN THE SOURCE TEXT",78,1160);
        }
        g.dispose();ImageIO.write(image,"png",destination.toFile());return destination;
    }
    private static List<Map<String,Object>>extract(Object raw){
        if(!(raw instanceof List<?>items))return List.of();
        List<Map<String,Object>>out=new ArrayList<>();
        for(Object v:items)if(v instanceof Map<?,?>m){
            Map<String,Object>x=new LinkedHashMap<>();
            for(var e:m.entrySet())x.put(String.valueOf(e.getKey()),e.getValue());
            out.add(x);
        }
        return out;
    }
    private static void panel(Graphics2D g,Map<String,Object>source,int x,int y,int w,int h){
        g.setColor(BroadcastTheme.NAVY_2);g.fillRoundRect(x,y,w,h,18,18);
        g.setColor(BroadcastTheme.BLUE_LIGHT);g.setStroke(new BasicStroke(3));g.drawRoundRect(x,y,w,h,18,18);
        g.setColor(BroadcastTheme.RED);g.fillRect(x,y,9,h);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,27));
        g.drawString(trim(String.valueOf(source.get("publisher")),42).toUpperCase(Locale.ROOT),x+31,y+50);
        write(g,String.valueOf(source.get("headline")),x+32,y+90,w-65,46,6,Font.BOLD);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,22));
        g.drawString("SOURCE HEADLINE // NOT A SCREENSHOT",x+32,y+h-24);
    }
    private static void note(Graphics2D g,String s,int x,int y){
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,25));
        g.drawString(trim(s,60),x,y);
    }
    private static void write(Graphics2D g,String content,int x,int y,int maxWidth,int fontSize,int maxLines,int style){
        g.setColor(BroadcastTheme.WHITE);g.setFont(new Font("SansSerif",style,fontSize));
        FontMetrics fm=g.getFontMetrics();
        String[]words=String.valueOf(content).replaceAll("\\s+"," ").split(" ");
        StringBuilder line=new StringBuilder();int count=0;
        for(String word:words){
            String next=line.isEmpty()?word:line+" "+word;
            if(fm.stringWidth(next)>maxWidth&&!line.isEmpty()){
                g.drawString(line.toString(),x,y+count*(fontSize+16));count++;
                if(count>=maxLines)return;
                line=new StringBuilder(word);
            }else line=new StringBuilder(next);
        }
        if(!line.isEmpty()&&count<maxLines)g.drawString(line.toString(),x,y+count*(fontSize+16));
    }
    private static String trim(String s,int max){
        return s==null?"":s.length()<=max?s:s.substring(0,max-1)+"…";
    }
}
