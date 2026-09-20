package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class VisualPlanner {
    public VisualPlan plan(NewsScript script,FactPackage fp){
        List<VisualPlan.Item> out=new ArrayList<>();
        out.add(new VisualPlan.Item(0,"HEADLINE_CARD",script.headline(),"",3.5,""));

        int idx=1;
        List<NewsScript.Segment> segments=script.segments()==null?List.of():script.segments();
        for(NewsScript.Segment seg:segments){
            if(idx>8)break;
            String type=seg.visualType();
            if(type==null||type.isBlank()||type.equalsIgnoreCase("HEADLINE_CARD")||type.equalsIgnoreCase("SOURCE_CARD"))
                type=idx%3==0?"TIMELINE":"BACKGROUND";
            String body=seg.narration()==null?"":seg.narration();
            String prompt=seg.visualPrompt()==null?"":seg.visualPrompt();
            out.add(new VisualPlan.Item(idx++,type,script.headline(),body,Math.max(4.0,seg.durationTarget()),prompt));
        }

        // A model that returns too few segments should not collapse the final video
        // back to two or three static scenes. Fill from narration sentences without
        // inventing any new factual content.
        if(idx<=6){
            for(String sentence:Text.sentences(script.narration())){
                if(idx>8)break;
                boolean duplicate=out.stream().anyMatch(x->x.body()!=null&&x.body().equalsIgnoreCase(sentence));
                if(duplicate||sentence.isBlank())continue;
                out.add(new VisualPlan.Item(idx++,idx%2==0?"BACKGROUND":"TIMELINE",script.headline(),sentence,7.0,
                        "Realistic editorial news image illustrating only this verified narration beat: "+sentence));
            }
        }

        String src=fp.sources().stream().map(x->String.valueOf(x.get("publisher"))).distinct().limit(5)
                .reduce((a,b)->a+" • "+b).orElse("");
        out.add(new VisualPlan.Item(idx,"SOURCE_CARD","Sources",src,3.5,""));
        return new VisualPlan(script.storyId(),List.copyOf(out));
    }
}
