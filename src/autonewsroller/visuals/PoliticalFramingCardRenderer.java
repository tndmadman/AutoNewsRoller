package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/** Deterministic typography over the existing AWARE frame; no fake publisher mastheads. */
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
        // Reserve the restored 118px header, the caption region and the 220px post-text footer.
        g.setColor(BroadcastTheme.NAVY);
        g.fillRoundRect(45,205,990,1035,26,26);
        g.setColor(BroadcastTheme.RULE);
        g.setStroke(new BasicStroke(3));g.drawRoundRect(45,205,990,1035,26,26);
        g.setColor(BroadcastTheme.RED);g.fillRect(45,205,990,13);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,29));
        g.drawString(trim(item.title(),44).toUpperCase(Locale.ROOT),78,275);
        g.setColor(BroadcastTheme.WHITE);
        g.setFont(new Font("SansSerif",Font.BOLD,43));
        g.drawString("POLITICAL FRAMING // SOURCE CHECK",78,346);
        List<Map<String,Object>>sources=extract(item.data().get("sources"));
        if(sources.size()>=2){
            Map<String,Object>first=sources.get(0),second=sources.get(1);
            if(item.type().equals("FRAMING_HEADLINE")){
                panel(g,first,78,395,924,450,true);
                note(g,"ORIGINAL HEADLINE // ATTRIBUTED SOURCE TEXT",78,925);
            }else if(item.type().equals("FRAMING_TEASER")){
                write(g,"How is this story being framed?",78,450,905,57,3,Font.BOLD);
                note(g,"Compare the actual words. Separate evidence from interpretation.",78,790);
            }else if(item.type().equals("EVIDENCE_CARD")){
                write(g,trim(item.body(),370),78,420,905,41,11,Font.PLAIN);
            }else if(item.type().equals("SOURCE_COMPARISON")){
                panel(g,first,78,397,924,356,false);
                panel(g,second,78,779,924,356,false);
            }else if(item.type().equals("LANGUAGE_HIGHLIGHT")){
                note(g,"SAME EVENT // TWO ORIGINAL HEADLINES",78,408);
                write(g,String.valueOf(first.get("headline")),78,445,905,37,5,Font.BOLD);
                g.setColor(BroadcastTheme.RED);g.fillRect(78,670,905,5);
                write(g,String.valueOf(second.get("headline")),78,728,905,37,5,Font.BOLD);
            }else{
                write(g,trim(item.body(),430),78,428,908,39,13,Font.PLAIN);
                note(g,"SOURCE COMPARISON, NOT AN ASSIGNMENT OF MOTIVE",78,1160);
            }
        }else write(g,trim(item.body(),240),78,440,900,39,8,Font.PLAIN);
        g.dispose();
        ImageIO.write(image,"png",destination.toFile());
        return destination;
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
    private static void panel(Graphics2D g,Map<String,Object>source,int x,int y,int w,int h,boolean hero){
        g.setColor(BroadcastTheme.NAVY_2);g.fillRoundRect(x,y,w,h,18,18);
        g.setColor(BroadcastTheme.BLUE_LIGHT);g.setStroke(new BasicStroke(3));g.drawRoundRect(x,y,w,h,18,18);
        g.setColor(BroadcastTheme.RED);g.fillRect(x,y,9,h);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,27));
        g.drawString(trim(String.valueOf(source.get("publisher")),42).toUpperCase(Locale.ROOT),x+31,y+49);
        write(g,String.valueOf(source.get("headline")),x+32,y+79,w-65,hero?46:39,hero?6:5,Font.BOLD);
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.PLAIN,22));
        g.drawString("EXACT SOURCE HEADLINE",x+32,y+h-24);
    }
    private static void note(Graphics2D g,String s,int x,int y){
        g.setColor(BroadcastTheme.MUTED);g.setFont(new Font("SansSerif",Font.BOLD,25));
        g.drawString(trim(s,58),x,y);
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
    private static String trim(String s,int max){return s==null?"":s.length()<=max?s:s.substring(0,max-1)+"…";}
}
