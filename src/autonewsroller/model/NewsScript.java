package autonewsroller.model;

import java.util.*;

public record NewsScript(
        String storyId,
        String headline,
        String narration,
        List<Segment> segments,
        double estimatedDuration,
        List<String> sourceLabels,
        Map<String,Object> hook
) {
    public NewsScript(String storyId,String headline,String narration,List<Segment>segments,double estimatedDuration,List<String>sourceLabels){
        this(storyId,headline,narration,segments,estimatedDuration,sourceLabels,Map.of());
    }

    public NewsScript{
        segments=segments==null?List.of():List.copyOf(segments);
        sourceLabels=sourceLabels==null?List.of():List.copyOf(sourceLabels);
        hook=hook==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(hook));
    }

    public record Segment(int index,String narration,String purpose,String visualType,String visualPrompt,double durationTarget){
        public Map<String,Object> toMap(){
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("index",index);
            m.put("narration",narration);
            m.put("purpose",purpose);
            m.put("visualType",visualType);
            m.put("visualPrompt",visualPrompt);
            m.put("durationTarget",durationTarget);
            return m;
        }
    }

    public Map<String,Object> toMap(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("storyId",storyId);
        m.put("headline",headline);
        m.put("narration",narration);
        m.put("segments",segments.stream().map(Segment::toMap).toList());
        m.put("estimatedDuration",estimatedDuration);
        m.put("sourceLabels",sourceLabels);
        if(!hook.isEmpty())m.put("hook",hook);
        return m;
    }
}
