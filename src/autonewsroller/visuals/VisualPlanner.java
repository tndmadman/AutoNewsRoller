package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class VisualPlanner {
    public VisualPlan plan(NewsScript script,FactPackage fp){
        List<VisualPlan.Item> out=new ArrayList<>();

        int idx=0;
        List<NewsScript.Segment> segments=script.segments()==null?List.of():script.segments();
        for(NewsScript.Segment seg:segments){
            if(idx>=8)break;

            String type=seg.visualType();
            if(idx==0){
                type="HOOK";
            }else if(type==null||type.isBlank()||type.equalsIgnoreCase("HEADLINE_CARD")||type.equalsIgnoreCase("SOURCE_CARD")||type.equalsIgnoreCase("HOOK")){
                type=(idx%3==0)?"TIMELINE":"BACKGROUND";
            }

            String body=seg.narration()==null?"":seg.narration();
            String prompt=seg.visualPrompt()==null?"":seg.visualPrompt();
            double minDuration=idx==0?2.4:4.0;
            out.add(new VisualPlan.Item(
                    idx,
                    type,
                    script.headline(),
                    body,
                    Math.max(minDuration,seg.durationTarget()),
                    prompt
            ));
            idx++;
        }

        // A malformed/legacy script with too few segments should still have enough
        // changing visuals, but the opening hook remains frame zero.
        if(idx<6){
            for(String sentence:Text.sentences(script.narration())){
                if(idx>=8)break;
                boolean duplicate=out.stream().anyMatch(x->x.body()!=null&&x.body().equalsIgnoreCase(sentence));
                if(duplicate||sentence.isBlank())continue;
                out.add(new VisualPlan.Item(
                        idx,
                        idx%2==0?"BACKGROUND":"TIMELINE",
                        script.headline(),
                        sentence,
                        7.0,
                        "Realistic editorial news image illustrating only this verified narration beat: "+sentence
                ));
                idx++;
            }
        }

        String src=fp.sources().stream().map(x->String.valueOf(x.get("publisher"))).distinct().limit(5)
                .reduce((a,b)->a+" • "+b).orElse("");
        out.add(new VisualPlan.Item(idx,"SOURCE_CARD","Sources",src,2.5,""));
        return new VisualPlan(script.storyId(),List.copyOf(out));
    }
}
