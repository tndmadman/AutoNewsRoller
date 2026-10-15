package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.video.SpeechTiming;
import java.util.*;

/** Maps narrated segments to scenes, reserving bounded room for source/context panels. */
public final class VisualDirector {
    public Storyboard direct(VisualPlan plan,NewsScript script,SpeechTiming.Alignment timing,double duration){
        List<VisualPlan.Item>items=plan.items();
        if(items.isEmpty())throw new IllegalArgumentException("No visual scenes");
        List<Double>weights=new ArrayList<>();
        for(VisualPlan.Item item:items){
            Object raw=item.data().get("segmentIndex");
            int index=raw instanceof Number n?n.intValue():-1;
            double seconds=index>=0&&index<timing.segmentDurations().size()
                    ?timing.segmentDurations().get(index):Math.min(2.2,Math.max(.8,item.duration()));
            weights.add(Math.max(.25,seconds));
        }
        double sum=weights.stream().mapToDouble(Double::doubleValue).sum();
        List<Storyboard.Scene>out=new ArrayList<>();
        double cursor=0;
        for(int i=0;i<items.size();i++){
            VisualPlan.Item item=items.get(i);
            double end=i==items.size()-1?duration:Math.min(duration,cursor+duration*weights.get(i)/sum);
            int seg=item.data().get("segmentIndex") instanceof Number n?n.intValue():-1;
            List<String>ids=new ArrayList<>();
            Object facts=item.data().get("factIds");
            if(facts instanceof List<?>l)for(Object x:l)ids.add(String.valueOf(x));
            String type=item.type();
            String motion=i==0?"PUSH_IN":(i%4==0?"PAN_LEFT":i%4==1?"PUSH_OUT":i%4==2?"PAN_RIGHT":"STATIC");
            if(type.equals("POLITICAL_CONTEXT")||type.equals("SOURCE_CARD")||type.equals("STATISTIC")||type.equals("QUOTE")||type.equals("TIMELINE"))motion="STATIC";
            out.add(new Storyboard.Scene(i,cursor,end,type,seg,ids,motion,i==0?"CUT":"EDITORIAL_CUT",ids.isEmpty()?"graphic": "verified-fact-reference"));
            cursor=end;
        }
        Storyboard board=new Storyboard(plan.storyId(),duration,timing.reason(),List.copyOf(out));
        board.validate();return board;
    }
}
