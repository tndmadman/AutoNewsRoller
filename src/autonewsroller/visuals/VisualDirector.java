package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.video.SpeechTiming;
import java.util.*;

/**
 * Keeps primary visuals aligned with their spoken script segment. Supplemental
 * graphics borrow a bounded fraction of the same segment, never extend runtime.
 */
public final class VisualDirector {
    public Storyboard direct(VisualPlan plan,NewsScript script,SpeechTiming.Alignment timing,double duration){
        List<VisualPlan.Item>items=plan.items();
        if(items.isEmpty()||!Double.isFinite(duration)||duration<=0)throw new IllegalArgumentException("Invalid storyboard input");
        List<NewsScript.Segment>segments=script.segments();
        Map<Integer,Integer>ordinals=new LinkedHashMap<>();
        for(int j=0;j<segments.size();j++)ordinals.put(segments.get(j).index(),j);
        List<Double>timings=timing.segmentDurations();
        double[]sceneWeights=new double[items.size()];
        Map<Integer,List<Integer>>grouped=new LinkedHashMap<>();
        for(int i=0;i<items.size();i++){
            VisualPlan.Item item=items.get(i);
            int segment=item.data().get("segmentIndex") instanceof Number n?n.intValue():-1;
            int ordinal=ordinals.getOrDefault(segment,Math.max(0,segments.size()-1));
            grouped.computeIfAbsent(ordinal,k->new ArrayList<>()).add(i);
        }
        double[]groupDurations=new double[Math.max(1,segments.size())];
        if(timings.size()==segments.size()){
            for(int i=0;i<segments.size();i++)groupDurations[i]=Math.max(0,timings.get(i));
        }else{
            for(int i=0;i<groupDurations.length;i++)
                groupDurations[i]=duration/groupDurations.length;
        }
        double audioTotal=Arrays.stream(groupDurations).sum();
        if(audioTotal<=0)throw new IllegalArgumentException("Invalid segment timings");
        for(var group:grouped.entrySet()){
            int index=group.getKey();
            List<Integer>members=group.getValue();
            double totalSupplemental=0;
            for(int visual:members)if(Boolean.TRUE.equals(items.get(visual).data().get("supplemental")))
                totalSupplemental+=Math.max(.05,Math.min(.35,number(items.get(visual).data().get("supplementFraction"),.18)));
            // Preserve most narration time for the actual fact being discussed.
            double supplementScale=totalSupplemental>.4?.4/totalSupplemental:1;
            int primaries=0;
            for(int visual:members)if(!Boolean.TRUE.equals(items.get(visual).data().get("supplemental")))primaries++;
            double available=Math.max(0,1-totalSupplemental*supplementScale);
            for(int visual:members){
                VisualPlan.Item item=items.get(visual);
                boolean supp=Boolean.TRUE.equals(item.data().get("supplemental"));
                double share=supp?number(item.data().get("supplementFraction"),.18)*supplementScale:
                    available/Math.max(1,primaries);
                sceneWeights[visual]=Math.max(.001,groupDurations[index]*share);
            }
        }
        double sum=Arrays.stream(sceneWeights).sum();
        if(sum<=0)throw new IllegalArgumentException("Empty visual allocation");
        List<Storyboard.Scene>out=new ArrayList<>();double cursor=0;
        for(int i=0;i<items.size();i++){
            VisualPlan.Item item=items.get(i);
            double end=i==items.size()-1?duration:cursor+duration*sceneWeights[i]/sum;
            int seg=item.data().get("segmentIndex") instanceof Number n?n.intValue():-1;
            List<String>ids=new ArrayList<>();
            if(item.data().get("factIds") instanceof List<?>l)for(Object x:l)ids.add(String.valueOf(x));
            String type=item.type();
            boolean graph=Set.of("POLITICAL_CONTEXT","SOURCE_CARD","STATISTIC","FACT_HIGHLIGHT","QUOTE","TIMELINE","DOCUMENT").contains(type);
            String motion=graph?"STATIC":i==0?"PUSH_IN":i%4==0?"PAN_LEFT":i%4==1?"PUSH_OUT":i%4==2?"PAN_RIGHT":"STATIC";
            out.add(new Storyboard.Scene(i,cursor,end,type,seg,ids,motion,
                    i==0?"CUT":"EDITORIAL_CUT",ids.isEmpty()?"source-backed-graphic":"verified-fact-reference"));
            cursor=end;
        }
        Storyboard board=new Storyboard(plan.storyId(),duration,timing.reason(),List.copyOf(out));
        board.validate();return board;
    }
    private static double number(Object x,double fallback){
        if(!(x instanceof Number n)||!Double.isFinite(n.doubleValue()))return fallback;
        return Math.max(.05,Math.min(.35,n.doubleValue()));
    }
}
