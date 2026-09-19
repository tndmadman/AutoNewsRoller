package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

public final class VisualPlanner {
    private static final String NEGATIVE="text, letters, words, captions, subtitles, watermarks, logos, fake UI, garbled signage, malformed hands, extra fingers, extra limbs, duplicated people, distorted faces, bad anatomy, unrelated objects, low detail, low quality";
    private static final String[]TRANSITIONS={"push_in","pan_left","zoom_out","pan_right","push_in","crossfade"};

    public VisualPlan plan(NewsScript s,FactPackage fp){
        return plan(s,fp,Math.max(68,s.estimatedDuration()),2.5,7,9);
    }

    public VisualPlan plan(NewsScript s,FactPackage fp,double audioDuration,double sourceTailSeconds,int minScenes,int maxScenes){
        int desired=Math.max(minScenes,Math.min(maxScenes,(int)Math.round(audioDuration/8.5)));
        desired=Math.max(7,Math.min(9,desired));

        List<String>beats=storyBeats(s,desired);
        List<Double>durations=durations(beats,audioDuration);
        List<String>publishers=fp.sources().stream().map(x->String.valueOf(x.get("publisher"))).filter(x->!x.isBlank()).distinct().toList();
        Map<String,Object>sourceMeta=new LinkedHashMap<>();
        sourceMeta.put("publishers",publishers);sourceMeta.put("sourceCount",fp.sourceCount());sourceMeta.put("independentSources",fp.independentSourceCount());

        List<VisualPlan.Item>out=new ArrayList<>();
        for(int i=0;i<beats.size();i++){
            String beat=beats.get(i);
            NewsScript.Segment seg=s.segments().isEmpty()?null:s.segments().get(Math.min(i,s.segments().size()-1));
            String rawPrompt=seg==null?"":seg.visualPrompt();
            String prompt=scenePrompt(s.headline(),beat,rawPrompt,i,beats.size());
            out.add(new VisualPlan.Item(
                    i,
                    "POST_CARD",
                    s.headline(),
                    beat,
                    caption(beat),
                    durations.get(i),
                    prompt,
                    NEGATIVE,
                    emphasis(beat),
                    TRANSITIONS[i%TRANSITIONS.length],
                    sourceMeta
            ));
        }

        String sourceLine=publishers.stream().limit(5).reduce((a,b)->a+" • "+b).orElse("Source details in provenance");
        Map<String,Object>endMeta=new LinkedHashMap<>(sourceMeta);endMeta.put("sourceLine",sourceLine);
        out.add(new VisualPlan.Item(
                out.size(),"SOURCE_CARD","Sources & context",sourceLine,"",
                Math.max(2.0,Math.min(3.0,sourceTailSeconds)),"",NEGATIVE,List.of(),"crossfade",endMeta
        ));
        return new VisualPlan(s.storyId(),List.copyOf(out));
    }

    private static List<String>storyBeats(NewsScript s,int desired){
        List<String>segmentText=s.segments().stream().map(NewsScript.Segment::narration).map(Text::clean).filter(x->!x.isBlank()).toList();
        if(segmentText.size()==desired)return segmentText;
        List<String>sentences=Text.sentences(s.narration());
        if(sentences.size()>=desired){
            List<String>out=new ArrayList<>();
            for(int i=0;i<desired;i++){
                int a=(int)Math.floor(i*sentences.size()/(double)desired);
                int b=(int)Math.floor((i+1)*sentences.size()/(double)desired);
                out.add(Text.clean(String.join(" ",sentences.subList(a,Math.max(a+1,b)))));
            }
            return out;
        }
        String[]words=Text.clean(s.narration()).split("\\s+");
        List<String>out=new ArrayList<>();
        for(int i=0;i<desired;i++){
            int a=(int)Math.floor(i*words.length/(double)desired);
            int b=(int)Math.floor((i+1)*words.length/(double)desired);
            out.add(Text.clean(String.join(" ",Arrays.copyOfRange(words,a,Math.max(a+1,b)))));
        }
        return out;
    }

    private static List<Double>durations(List<String>beats,double total){
        double avg=Math.max(1,total/beats.size());
        double[]weights=new double[beats.size()];
        double sum=0;
        double meanWords=beats.stream().mapToInt(Text::words).average().orElse(1);
        for(int i=0;i<beats.size();i++){
            double ratio=Text.words(beats.get(i))/Math.max(1,meanWords);
            weights[i]=Math.max(.82,Math.min(1.18,ratio));
            sum+=weights[i];
        }
        List<Double>out=new ArrayList<>();
        double used=0;
        for(int i=0;i<beats.size();i++){
            double d=i==beats.size()-1?Math.max(1,total-used):total*weights[i]/sum;
            d=Math.max(5.5,Math.min(10.5,d));
            out.add(d);used+=d;
        }
        double correction=total-out.stream().mapToDouble(Double::doubleValue).sum();
        if(!out.isEmpty())out.set(out.size()-1,Math.max(5.0,out.get(out.size()-1)+correction));
        return out;
    }

    private static String scenePrompt(String headline,String beat,String provided,int index,int count){
        String subject=provided==null||provided.isBlank()?beat:provided;
        return Text.clean(
                "Editorial news illustration for scene "+(index+1)+" of "+count+". "+
                "Story context: "+headline+". Current beat: "+beat+". "+
                "Visual direction: "+subject+". "+
                "Show a believable principal subject performing or experiencing the relevant action in a plausible real-world setting. "+
                "Use clear foreground and background separation, medium-to-wide editorial framing, realistic lighting, restrained story-appropriate mood, "+
                "professional magazine/news illustration quality, central subject placement with safe margins for a vertical social-news card. "+
                "No readable text, letters, captions, subtitles, logos, fake interfaces, giant symbolic objects, or fabricated on-image headlines. "+
                "Treat this as an AI editorial illustration, not authentic documentary evidence of the event."
        );
    }

    private static String caption(String beat){
        String[]words=Text.clean(beat).split("\\s+");
        if(words.length<=9)return String.join(" ",words);
        int end=Math.min(9,words.length);
        String phrase=String.join(" ",Arrays.copyOfRange(words,0,end));
        return phrase.replaceAll("[,;:]$","");
    }

    private static List<String>emphasis(String text){
        LinkedHashSet<String>out=new LinkedHashSet<>();
        Matcher number=Pattern.compile("\\b(?:\\d[\\d,.]*%?|\\$\\d[\\d,.]*)\\b").matcher(text==null?"":text);
        while(number.find()&&out.size()<3)out.add(number.group());
        for(String entity:Text.entities(text)){if(out.size()>=3)break;out.add(entity);}
        return List.copyOf(out);
    }
}
