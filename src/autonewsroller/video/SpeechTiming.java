package autonewsroller.video;

import autonewsroller.model.NewsScript;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Validated Kokoro token timestamps with an explicit duration-based fallback. */
public final class SpeechTiming {
    private SpeechTiming(){}
    public record Word(String text,double start,double end){}
    public record Alignment(List<Word> words,boolean exact,String reason,List<Double> segmentDurations){
        public Map<String,Object> toMap(){return Map.of("exact",exact,"reason",reason,"wordCount",words.size(),"segmentDurations",segmentDurations);}
    }
    public static Alignment align(String narration,List<NewsScript.Segment> segments,Path sidecar,double duration){
        List<String> spoken=tokens(narration);
        List<Word> observed=read(sidecar,duration);
        List<Word> aligned=new ArrayList<>();
        int cursor=0;
        for(String expected:spoken){
            int match=-1;
            for(int j=cursor;j<Math.min(observed.size(),cursor+5);j++){
                if(normalize(expected).equals(normalize(observed.get(j).text()))){match=j;break;}
            }
            if(match<0)continue;
            aligned.add(new Word(expected,observed.get(match).start(),observed.get(match).end()));
            cursor=match+1;
        }
        boolean exact=!spoken.isEmpty()&&aligned.size()>=Math.max(1,(int)Math.ceil(spoken.size()*.85))
                &&observed.size()>0&&aligned.get(aligned.size()-1).end()<=duration+.15;
        if(!exact){
            aligned=estimated(spoken,duration);
        }
        List<Double> durations=segmentDurations(segments,aligned,duration);
        return new Alignment(List.copyOf(aligned),exact,exact?"validated-kokoro-tokens":"estimated-from-audio-duration",durations);
    }
    public static List<Word> read(Path path,double duration){
        if(path==null||!Files.isRegularFile(path))return List.of();
        try{
            List<String>lines=Files.readAllLines(path,StandardCharsets.UTF_8);
            if(lines.isEmpty()||!lines.get(0).equals("autonewsroller-kokoro-timing-v1"))return List.of();
            List<Word>out=new ArrayList<>();
            double previous=0;
            for(int i=1;i<lines.size();i++){
                String[]p=lines.get(i).split("\t",-1);
                if(p.length!=4||!p[0].equals("word"))return List.of();
                double start=Double.parseDouble(p[1]),end=Double.parseDouble(p[2]);
                if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||start+0.03<previous||end>duration+.15)return List.of();
                String text=new String(Base64.getUrlDecoder().decode(p[3]),StandardCharsets.UTF_8);
                if(text.isBlank())continue;
                out.add(new Word(text,start,Math.min(end,duration)));
                previous=end;
            }
            return List.copyOf(out);
        }catch(Exception bad){return List.of();}
    }
    public static List<String> tokens(String text){
        if(text==null||text.isBlank())return List.of();
        List<String>out=new ArrayList<>();
        Matcher m=Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*").matcher(text);
        while(m.find())out.add(m.group());
        return out;
    }
    private static String normalize(String x){return x.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static List<Word> estimated(List<String> words,double duration){
        List<Word>out=new ArrayList<>();
        if(words.isEmpty())return out;
        double step=Math.max(.01,duration/words.size());
        for(int i=0;i<words.size();i++)out.add(new Word(words.get(i),i*step,Math.min(duration,(i+1)*step)));
        return out;
    }
    private static List<Double> segmentDurations(List<NewsScript.Segment> segments,List<Word> words,double total){
        if(segments==null||segments.isEmpty())return List.of();
        List<Double>out=new ArrayList<>();int all=0;
        for(var seg:segments){int count=tokens(seg.narration()).size();out.add((double)count);all+=count;}
        if(all==0)return Collections.nCopies(segments.size(),total/segments.size());
        // Use observed speech boundaries where token alignment covers the entire narration.
        if(words.size()>=all){
            List<Double>times=new ArrayList<>();int cursor=0;double previous=0;
            for(int i=0;i<segments.size();i++){
                cursor+=out.get(i).intValue();
                double next=i==segments.size()-1?total:Math.max(previous,words.get(Math.min(words.size()-1,Math.max(0,cursor-1))).end());
                times.add(Math.max(0.05,next-previous));previous=next;
            }
            return List.copyOf(times);
        }
        List<Double>estimated=new ArrayList<>();
        for(double count:out)estimated.add(total*count/all);
        return List.copyOf(estimated);
    }
}
