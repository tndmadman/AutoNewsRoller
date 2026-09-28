package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class VisualPlanner {
    public VisualPlan plan(NewsScript script,FactPackage fp){
        return plan(script,fp,PoliticalVisualData.empty(),0.60,true,5.0,true,true);
    }

    public VisualPlan plan(NewsScript script,FactPackage fp,PoliticalVisualData political,double minimumRelevance,boolean showUncertain,double politicalSceneSeconds,boolean showConfidence,boolean showSourceMix){
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
            double minDuration=idx==0?3.0:4.0;
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

        if(political!=null&&political.shouldRender(minimumRelevance,showUncertain)){
            int insertAt=Math.min(3,out.size());
            Map<String,Object>renderData=new LinkedHashMap<>(political.toMap());
            renderData.put("showConfidence",showConfidence);
            renderData.put("showSourceMix",showSourceMix);
            out.add(insertAt,new VisualPlan.Item(
                    -1,
                    "POLITICAL_CONTEXT",
                    "Political context",
                    political.storySummary(),
                    Math.max(3.5,Math.min(8.0,politicalSceneSeconds)),
                    "",
                    renderData
            ));
        }

        String src=fp.sources().stream().map(x->String.valueOf(x.get("publisher"))).distinct().limit(5)
                .reduce((a,b)->a+" • "+b).orElse("");
        out.add(new VisualPlan.Item(-1,"SOURCE_CARD","Sources",src,2.5,""));

        List<VisualPlan.Item> reindexed=new ArrayList<>();
        for(int i=0;i<out.size();i++){
            VisualPlan.Item item=out.get(i);
            reindexed.add(new VisualPlan.Item(i,item.type(),item.title(),item.body(),item.duration(),item.prompt(),item.data()));
        }
        return new VisualPlan(script.storyId(),List.copyOf(reindexed));
    }
}
