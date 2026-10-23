package autonewsroller.visuals;

import autonewsroller.model.*;
import java.util.*;

/** Captions come from the scripted beat; imagery is satire, not wire photography. */
public final class MemeVisualPlanner {
    public VisualPlan plan(NewsScript script){
        List<VisualPlan.Item>out=new ArrayList<>();
        for(NewsScript.Segment s:script.segments()){
            Map<String,Object>data=new LinkedHashMap<>();
            data.put("segmentIndex",s.index());
            data.put("factIds",script.hook().getOrDefault("beatEvidence",List.of()) instanceof List<?> rows
                    &&s.index()<rows.size()?rows.get(s.index()):List.of());
            data.put("satiricalIllustration",true);
            String type=s.index()==0?"MEME_HOOK":"MEME_BEAT";
            out.add(new VisualPlan.Item(s.index(),type,s.visualType(),s.narration(),
                    Math.max(.15,s.durationTarget()),s.visualPrompt(),data));
        }
        return new VisualPlan(script.storyId(),List.copyOf(out));
    }
}
