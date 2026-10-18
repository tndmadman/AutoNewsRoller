package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.script.PoliticalFramingEvidenceBuilder;
import java.util.*;

/** Each segment inspects rhetorical framing inside a single attributed article. */
public final class PoliticalFramingVisualPlanner {
    public VisualPlan plan(NewsScript script,PoliticalFramingEvidenceBuilder.Evidence evidence){
        if(evidence.articles().size()!=1)throw new IllegalArgumentException("One article expected");
        List<VisualPlan.Item>out=new ArrayList<>();
        List<NewsScript.Segment>segments=script.segments();
        for(int i=0;i<segments.size();i++){
            NewsScript.Segment seg=segments.get(i);
            Map<String,Object>data=new LinkedHashMap<>();
            data.put("segmentIndex",i);
            data.put("analysisScope","SINGLE_ARTICLE");
            data.put("evidenceSchemaVersion",PoliticalFramingEvidenceBuilder.VERSION);
            data.put("sources",evidence.articles());
            data.put("verifiedSourceText",true);
            String label=switch(i){
                case 0->"SOURCE HEADLINE";
                case 1->"POLITICAL FRAMING EXPOSED";
                case 2->"WHAT THE ARTICLE REPORTS";
                case 3->"HEADLINE WORDING";
                case 4->"RHETORIC & EMOTIONAL CUES";
                case 5->"FACTS VS IMPLICATION";
                case 6->"HOW THE FRAMING WORKS";
                default->"EVIDENCE-BASED VERDICT";
            };
            out.add(new VisualPlan.Item(i,seg.visualType(),label,seg.narration(),
                    Math.max(3,seg.durationTarget()),"",data));
        }
        return new VisualPlan(script.storyId(),List.copyOf(out));
    }
}
