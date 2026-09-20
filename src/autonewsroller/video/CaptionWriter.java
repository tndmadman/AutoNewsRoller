package autonewsroller.video;

import autonewsroller.util.Text;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class CaptionWriter {
    private CaptionWriter(){}

    public static Path write(Path out,String narration,double duration,String mode)throws Exception{
        if("off".equalsIgnoreCase(mode))return null;
        List<String>units=captionUnits(narration,mode);
        if(units.isEmpty())return null;

        StringBuilder ass=new StringBuilder();
        ass.append("[Script Info]\n")
           .append("ScriptType: v4.00+\n")
           .append("PlayResX: 1080\nPlayResY: 1920\n")
           .append("WrapStyle: 2\nScaledBorderAndShadow: yes\n\n")
           .append("[V4+ Styles]\n")
           .append("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\n")
           .append("Style: News,Arial,42,&H00FFFFFF,&H00FFD86F,&H00101010,&H94000000,-1,0,0,0,100,100,0,0,3,3,0,2,82,82,205,1\n\n")
           .append("[Events]\n")
           .append("Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n");

        int total=units.stream().mapToInt(Text::words).sum();
        double t=0;
        for(int i=0;i<units.size();i++){
            String unit=units.get(i);
            double d=i==units.size()-1?duration-t:Math.max(.55,duration*Math.max(1,Text.words(unit))/Math.max(1,total));
            double end=Math.min(duration,t+d);
            ass.append("Dialogue: 0,").append(ts(t)).append(',').append(ts(end))
               .append(",News,,0,0,0,,").append(styled(unit)).append("\n");
            t=end;
        }
        Files.writeString(out,ass.toString(),StandardCharsets.UTF_8);
        return out;
    }

    private static List<String>captionUnits(String narration,String mode){
        String cleaned=Text.clean(narration);
        if(cleaned.isBlank())return List.of();
        if("word".equalsIgnoreCase(mode)){
            List<String>one=new ArrayList<>();
            for(String w:cleaned.split("\\s+"))if(!w.isBlank())one.add(w);
            return one;
        }
        List<String>out=new ArrayList<>();
        for(String sentence:Text.sentences(cleaned)){
            String[]words=sentence.trim().split("\\s+");
            for(int i=0;i<words.length;i+=11){
                int end=Math.min(words.length,i+11);
                out.add(String.join(" ",Arrays.copyOfRange(words,i,end)));
            }
        }
        if(out.isEmpty())out.add(cleaned);
        return out;
    }

    private static String styled(String raw){
        String[]w=raw.replace("{","").replace("}","").trim().split("\\s+");
        if(w.length==0)return "";
        int highlight=Math.min(2,w.length);
        String first=String.join(" ",Arrays.copyOfRange(w,0,highlight));
        String rest=highlight<w.length?" "+String.join(" ",Arrays.copyOfRange(w,highlight,w.length)):"";
        String text="{\\c&H00FFD86F&}"+first+"{\\c&H00FFFFFF&}"+rest;
        return wrapTwoLines(text,w.length);
    }

    private static String wrapTwoLines(String styled,int wordCount){
        if(wordCount<=6)return styled;
        // Insert a single ASS line break near the midpoint while preserving style tags.
        String plain=styled;
        int visible=0,breakAt=Math.max(3,wordCount/2),pos=-1;
        boolean inTag=false;
        for(int i=0;i<plain.length();i++){
            char ch=plain.charAt(i);
            if(ch=='{')inTag=true;
            else if(ch=='}')inTag=false;
            else if(!inTag&&ch==' '){
                visible++;
                if(visible==breakAt){pos=i;break;}
            }
        }
        return pos>0?plain.substring(0,pos)+"\\N"+plain.substring(pos+1):plain;
    }

    private static String ts(double seconds){
        long cs=Math.max(0,Math.round(seconds*100));
        long h=cs/360000;cs%=360000;
        long m=cs/6000;cs%=6000;
        long s=cs/100;cs%=100;
        return String.format(Locale.ROOT,"%d:%02d:%02d.%02d",h,m,s,cs);
    }
}
