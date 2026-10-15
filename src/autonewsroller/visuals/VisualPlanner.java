package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class VisualPlanner {
    public VisualPlan plan(NewsScript script,FactPackage fp){
        return plan(script,fp,List.of(),PoliticalVisualData.empty(),0.60,true,5.0,true,true);
    }

    public VisualPlan plan(NewsScript script,FactPackage fp,PoliticalVisualData political,double minimumRelevance,boolean showUncertain,double politicalSceneSeconds,boolean showConfidence,boolean showSourceMix){
        return plan(script,fp,List.of(),political,minimumRelevance,showUncertain,politicalSceneSeconds,showConfidence,showSourceMix);
    }

    public VisualPlan plan(NewsScript script,FactPackage fp,List<VisualPromptPlan> prompts,PoliticalVisualData political,double minimumRelevance,boolean showUncertain,double politicalSceneSeconds,boolean showConfidence,boolean showSourceMix){
        List<VisualPlan.Item> out=new ArrayList<>();
        Map<Integer,VisualPromptPlan>bySegment=new LinkedHashMap<>();
        if(prompts!=null)for(VisualPromptPlan p:prompts)bySegment.put(p.segmentIndex(),p);

        int idx=0;
        List<NewsScript.Segment> segments=script.segments()==null?List.of():script.segments();
        for(NewsScript.Segment seg:segments){
            if(idx>=8)break;
            VisualPromptPlan grounded=bySegment.get(seg.index());
            String type;
            String prompt;
            Map<String,Object>data=new LinkedHashMap<>();
            if(grounded!=null){
                type=grounded.strategy();
                prompt=grounded.prompt();
                data.put("segmentIndex",grounded.segmentIndex());
                data.put("visualStrategy",grounded.strategy());
                data.put("factIds",grounded.factIds());
                data.put("anchorEntities",grounded.anchorEntities());
                data.put("groundingScore",grounded.groundingScore());
                data.put("mustNotShow",grounded.mustNotShow());
                data.put("visualPromptFallback",grounded.fallback());
                data.put("visualPromptRepairAttempts",grounded.repairAttempts());
            }else{
                type=idx==0?"HOOK":((idx%3==0)?"TIMELINE":"CONTEXT");
                prompt=seg.visualPrompt()==null?"":seg.visualPrompt();
                if(prompt.isBlank())prompt="Realistic editorial news photograph grounded only in verified reporting about: "+seg.narration();
            }
            if(idx==0)type="HOOK";
            else type=editorialType(seg,fp,grounded,idx);
            String body=seg.narration()==null?"":seg.narration();
            out.add(new VisualPlan.Item(idx,type,script.headline(),body,Math.max(idx==0?3.0:4.0,seg.durationTarget()),prompt,data));
            idx++;
        }

        if(political!=null&&political.shouldRender(minimumRelevance,showUncertain)){
            int insertAt=Math.min(3,out.size());
            Map<String,Object>renderData=new LinkedHashMap<>(political.toMap());
            renderData.put("showConfidence",showConfidence);
            renderData.put("showSourceMix",showSourceMix);
            out.add(insertAt,new VisualPlan.Item(-1,"POLITICAL_CONTEXT","Political context",political.storySummary(),Math.max(3.5,Math.min(8.0,politicalSceneSeconds)),"",renderData));
        }

        String src=fp.sources().stream().map(x->String.valueOf(x.get("publisher"))).distinct().limit(5).reduce((a,b)->a+" • "+b).orElse("");
        out.add(new VisualPlan.Item(-1,"SOURCE_CARD","Sources",src,2.5,""));

        List<VisualPlan.Item> reindexed=new ArrayList<>();
        for(int i=0;i<out.size();i++){
            VisualPlan.Item item=out.get(i);
            reindexed.add(new VisualPlan.Item(i,item.type(),item.title(),item.body(),item.duration(),item.prompt(),item.data()));
        }
        return new VisualPlan(script.storyId(),List.copyOf(reindexed));
    }
    /** Choose graphics only for details explicitly present in the verified fact package. */
    private static String editorialType(NewsScript.Segment seg,FactPackage facts,VisualPromptPlan grounded,int index){
        String narration=seg.narration()==null?"":seg.narration();
        String verified=facts.facts().stream().map(FactClaim::statement).reduce("",(a,b)->a+" "+b);
        String lowered=narration.toLowerCase(Locale.ROOT);
        if(grounded!=null&&!grounded.factIds().isEmpty()){
            java.util.regex.Matcher numbers=java.util.regex.Pattern.compile("\\b\\d+(?:[.,]\\d+)?%\\b|\\b\\d{2,}(?:[.,]\\d+)?\\b").matcher(narration);
            while(numbers.find())if(verified.contains(numbers.group()))return "STATISTIC";
            java.util.regex.Matcher quoted=java.util.regex.Pattern.compile("[“\\\"]([^”\\\"]{12,120})[”\\\"]").matcher(narration);
            if(quoted.find()&&verified.contains(quoted.group(1)))return "QUOTE";
            if(lowered.matches(".*\\b(january|february|march|april|may|june|july|august|september|october|november|december|yesterday|today|tomorrow)\\b.*")
                    &&verified.toLowerCase(Locale.ROOT).matches(".*\\b(20\\d{2}|january|february|march|april|may|june|july|august|september|october|november|december)\\b.*"))
                return "TIMELINE";
        }
        if(index%3==0)return "FACT_HIGHLIGHT";
        if(grounded!=null&&(grounded.strategy().equalsIgnoreCase("DIRECT_EVENT")||grounded.strategy().equalsIgnoreCase("LOCATION")
                ||grounded.strategy().equalsIgnoreCase("PERSON")))return "EDITORIAL_PHOTO";
        return "EDITORIAL_PHOTO";
    }

}
