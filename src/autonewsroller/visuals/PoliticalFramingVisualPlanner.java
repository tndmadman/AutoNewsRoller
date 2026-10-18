package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.script.PoliticalFramingEvidenceBuilder;
import java.util.*;

/** All framing scenes are source-text cards, not generated screenshots. */
public final class PoliticalFramingVisualPlanner {
    public VisualPlan plan(NewsScript script,PoliticalFramingEvidenceBuilder.Evidence evidence){
        List<VisualPlan.Item>out=new ArrayList<>();
        List<NewsScript.Segment>segments=script.segments();
        for(int i=0;i<segments.size();i++){
            NewsScript.Segment seg=segments.get(i);
            Map<String,Object>data=new LinkedHashMap<>();
            data.put("segmentIndex",i);
            data.put("evidenceSchemaVersion",PoliticalFramingEvidenceBuilder.VERSION);
            data.put("sources",evidence.articles());
            data.put("verifiedSourceText",true);
            String label=switch(i){
                case 0->"NEWS ANALYSIS";
                case 1->"POLITICAL FRAMING";
                case 2->"THE VERIFIED EVENT";
                case 3->"SOURCE 1 // DOCUMENTED LANGUAGE";
                case 4->"SOURCE 2 // DOCUMENTED LANGUAGE";
                case 5->"COMPARE THE LANGUAGE";
                case 6->"WHY WORDING MATTERS";
                default->"EVIDENCE AND LIMITATIONS";
            };
            out.add(new VisualPlan.Item(i,seg.visualType(),label,seg.narration(),Math.max(3,seg.durationTarget()),"",data));
        }
        return new VisualPlan(script.storyId(),List.copyOf(out));
    }
}
