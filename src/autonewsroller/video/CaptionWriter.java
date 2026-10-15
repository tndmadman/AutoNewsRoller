package autonewsroller.video;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Accessible, two-line ASS captions with optional validated Kokoro speech timing. */
public final class CaptionWriter {
    private CaptionWriter(){}
    public static Path write(Path out,String narration,double duration,String mode)throws Exception{
        return write(out,narration,duration,mode,null,1080,1920);
    }
    public static Path write(Path out,String narration,double duration,String mode,Path sidecar,int width,int height)throws Exception{
        if("off".equalsIgnoreCase(mode))return null;
        if(!(duration>0)||!Double.isFinite(duration))return null;
        SpeechTiming.Alignment timing=SpeechTiming.align(narration,List.of(),sidecar,duration);
        List<SpeechTiming.Word> words=timing.words();
        if(words.isEmpty())return null;
        int font=Math.max(18,(int)Math.round(44.0*width/1080));
        int marginH=Math.max(20,(int)Math.round(width*.072));
        int marginV=Math.max(80,(int)Math.round(height*.268));
        StringBuilder ass=new StringBuilder();
        ass.append("[Script Info]\n")
           .append("ScriptType: v4.00+\n")
           .append("PlayResX: ").append(width).append("\nPlayResY: ").append(height).append("\n")
           .append("WrapStyle: 2\nScaledBorderAndShadow: yes\n\n")
           .append("[V4+ Styles]\n")
           .append("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\n")
           .append("Style: News,Arial,").append(font).append(",&H00FFFFFF,&H00B27943,&H00140C06,&HC80A0D12,-1,0,0,0,100,100,0,0,3,3,0,2,")
           .append(marginH).append(',').append(marginH).append(',').append(marginV).append(",1\n\n")
           .append("[Events]\nFormat: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n");
        List<String>displayWords=new ArrayList<>();
        java.util.regex.Matcher lexical=java.util.regex.Pattern.compile(
                "[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*[.!?,;:]*").matcher(narration==null?"":narration);
        while(lexical.find())displayWords.add(lexical.group());
        boolean single="word".equalsIgnoreCase(mode);
        int maxLineChars=Math.max(20,(int)Math.floor(width/(Math.max(18,font)*.57)));
        int maxBlockChars=2*maxLineChars-2;
        for(int i=0;i<words.size();){
            int endIndex=i+1;
            if(!single){
                int count=displayWords.size()==words.size()?displayWords.get(i).length():words.get(i).text().length();
                while(endIndex<words.size()&&endIndex-i<8){
                    String candidate=displayWords.size()==words.size()?displayWords.get(endIndex):words.get(endIndex).text();
                    if(count+candidate.length()+1>maxBlockChars)break;
                    count+=candidate.length()+1;
                    endIndex++;
                }
            }
            double start=words.get(i).start(),end=words.get(endIndex-1).end();
            i=endIndex;
            if(end<=start||end>duration+.1)continue;
            List<String>unit=new ArrayList<>();
            for(int n=i;n<endIndex;n++)unit.add(escape(displayWords.size()==words.size()?displayWords.get(n):words.get(n).text()));
            String visual=String.join(" ",unit);
            if(!single&&unit.size()>1){
                int mid=1,best=Integer.MAX_VALUE;
                for(int cut=1;cut<unit.size();cut++){
                    int left=String.join(" ",unit.subList(0,cut)).length();
                    int right=String.join(" ",unit.subList(cut,unit.size())).length();
                    int penalty=Math.abs(left-right)+Math.max(0,left-maxLineChars)*20+
                            Math.max(0,right-maxLineChars)*20;
                    if(penalty<best){best=penalty;mid=cut;}
                }
                visual=String.join(" ",unit.subList(0,mid))+"\\N"+
                        String.join(" ",unit.subList(mid,unit.size()));
            }
            if(single&&timing.exact())visual="{\\c&H00B27943&}"+visual+"{\\c&H00FFFFFF&}";
            ass.append("Dialogue: 0,").append(ts(start)).append(',').append(ts(Math.min(duration,end)))
               .append(",News,,0,0,0,,").append(visual).append('\n');
        }
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out,ass.toString(),StandardCharsets.UTF_8);return out;
    }
    private static String escape(String s){
        if(s==null)return "";
        // Untrusted script text may not inject ASS override tags, escapes, or line breaks.
        return s.replace("\\","").replace("{","").replace("}","")
                .replace("\r"," ").replace("\n"," ").strip();
    }
    private static String ts(double seconds){
        long cs=Math.max(0,Math.round(seconds*100));
        long h=cs/360000;cs%=360000;long m=cs/6000;cs%=6000;long s=cs/100;cs%=100;
        return String.format(Locale.ROOT,"%d:%02d:%02d.%02d",h,m,s,cs);
    }
}
