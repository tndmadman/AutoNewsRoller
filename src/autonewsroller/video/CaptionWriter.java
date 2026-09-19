package autonewsroller.video;

import autonewsroller.util.Text;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class CaptionWriter {
    private static final String WHITE="&H00F4F7FB&";
    private static final String CYAN="&H00FFE838&";
    private static final String AMBER="&H005ABFFF&";

    private CaptionWriter(){}

    public static Path write(Path out,String narration,double duration,String mode)throws Exception{
        return write(out,narration,duration,mode,1080,1920,44,9);
    }

    public static Path write(Path out,String narration,double duration,String mode,int playResX,int playResY,int fontSize,int maxWords)throws Exception{
        if("off".equalsIgnoreCase(mode))return null;
        List<String>phrases=phrases(narration,Math.max(4,Math.min(10,maxWords)));
        if(phrases.isEmpty())return null;

        int totalWords=phrases.stream().mapToInt(Text::words).sum();
        StringBuilder ass=new StringBuilder();
        ass.append("[Script Info]\n")
                .append("ScriptType: v4.00+\n")
                .append("PlayResX: ").append(playResX).append('\n')
                .append("PlayResY: ").append(playResY).append('\n')
                .append("WrapStyle: 2\n")
                .append("ScaledBorderAndShadow: yes\n\n")
                .append("[V4+ Styles]\n")
                .append("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\n")
                .append("Style: Caption,Arial,").append(fontSize)
                .append(",").append(WHITE)
                .append(",").append(WHITE)
                .append(",&H00101820&,&H88030A10&,-1,0,0,0,100,100,0,0,3,2,0,2,125,125,300,1\n\n")
                .append("[Events]\n")
                .append("Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n");

        double t=0;
        for(int i=0;i<phrases.size();i++){
            String phrase=phrases.get(i);
            double d=i==phrases.size()-1?duration-t:duration*Math.max(1,Text.words(phrase))/Math.max(1,totalWords);
            d=Math.max(.55,d);
            double end=Math.min(duration,t+d);
            ass.append("Dialogue: 0,").append(ts(t)).append(',').append(ts(end))
                    .append(",Caption,,0,0,0,,")
                    .append(decorate(phrase))
                    .append('\n');
            t=end;
        }

        Files.writeString(out,ass.toString(),StandardCharsets.UTF_8);
        return out;
    }

    static List<String>phrases(String narration,int maxWords){
        String cleaned=Text.clean(narration);
        if(cleaned.isBlank())return List.of();
        String[]tokens=cleaned.split("\\s+");
        List<String>out=new ArrayList<>();List<String>cur=new ArrayList<>();
        for(String token:tokens){
            cur.add(token);
            boolean punctuation=token.matches(".*[.!?;:]$");
            if(cur.size()>=maxWords||(cur.size()>=4&&punctuation)){
                out.add(String.join(" ",cur));cur.clear();
            }
        }
        if(!cur.isEmpty()){
            if(cur.size()<4&&!out.isEmpty())out.set(out.size()-1,out.get(out.size()-1)+" "+String.join(" ",cur));
            else out.add(String.join(" ",cur));
        }
        return List.copyOf(out);
    }

    private static String decorate(String phrase){
        String[]words=phrase.split("\\s+");
        Set<Integer>amber=new LinkedHashSet<>(),cyan=new LinkedHashSet<>();
        Pattern number=Pattern.compile(".*(?:\\d|\\$|%).*");
        for(int i=0;i<words.length&&amber.size()<2;i++){
            String bare=bare(words[i]);
            if(number.matcher(bare).matches())amber.add(i);
        }
        for(int i=0;i<words.length&&cyan.size()<2;i++){
            if(amber.contains(i))continue;
            String bare=bare(words[i]);
            if(bare.isBlank())continue;
            boolean acronym=bare.length()>1&&bare.equals(bare.toUpperCase(Locale.ROOT))&&bare.matches(".*[A-Z].*");
            boolean proper=i>0&&Character.isUpperCase(bare.charAt(0))&&bare.substring(1).chars().anyMatch(Character::isLowerCase);
            if(acronym||proper)cyan.add(i);
        }

        int breakAt=words.length>6?(int)Math.ceil(words.length/2.0):-1;
        StringBuilder b=new StringBuilder("{\\c").append(WHITE).append("}");
        for(int i=0;i<words.length;i++){
            if(i>0)b.append(i==breakAt?"\\N":" ");
            String word=escape(words[i]);
            if(amber.contains(i))b.append("{\\c").append(AMBER).append("}").append(word).append("{\\c").append(WHITE).append("}");
            else if(cyan.contains(i))b.append("{\\c").append(CYAN).append("}").append(word).append("{\\c").append(WHITE).append("}");
            else b.append(word);
        }
        return b.toString();
    }

    private static String bare(String w){return w==null?"":w.replaceAll("^[^A-Za-z0-9$]+|[^A-Za-z0-9%$.-]+$","");}
    private static String escape(String w){return (w==null?"":w).replace("{","").replace("}","").replace("\\","");}

    private static String ts(double v){
        long cs=Math.max(0,Math.round(v*100));
        long h=cs/360000;cs%=360000;
        long m=cs/6000;cs%=6000;
        long sec=cs/100;cs%=100;
        return String.format(Locale.ROOT,"%d:%02d:%02d.%02d",h,m,sec,cs);
    }
}
