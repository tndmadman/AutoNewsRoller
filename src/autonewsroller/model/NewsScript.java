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
        /** Rehydrate a previously validated, content-hashed script from a checkpoint. */
    public static NewsScript fromMap(Map<String,Object>m){
        List<Segment>segments=new ArrayList<>();
        if(m.get("segments") instanceof List<?>rows)
            for(Object raw:rows)if(raw instanceof Map<?,?>){
                Map<String,Object>x=autonewsroller.util.Json.object(raw);
                segments.add(new Segment(x.get("index") instanceof Number n?n.intValue():segments.size(),
                        String.valueOf(x.getOrDefault("narration","")),
                        String.valueOf(x.getOrDefault("purpose","")),
                        String.valueOf(x.getOrDefault("visualType","")),
                        String.valueOf(x.getOrDefault("visualPrompt","")),
                        x.get("durationTarget") instanceof Number n?n.doubleValue():0));
            }
        if(segments.isEmpty())throw new IllegalArgumentException("Checkpoint script has no segments");
        List<String>sources=new ArrayList<>();
        if(m.get("sourceLabels") instanceof List<?>labels)
            for(Object label:labels)sources.add(String.valueOf(label));
        String narration=String.valueOf(m.getOrDefault("narration",""));
        String story=String.valueOf(m.getOrDefault("storyId",""));
        if(narration.isBlank()||story.isBlank())
            throw new IllegalArgumentException("Checkpoint script is incomplete");
        return new NewsScript(story,String.valueOf(m.getOrDefault("headline","")),narration,
                segments,m.get("estimatedDuration") instanceof Number n?n.doubleValue():0,
                sources,m.get("hook") instanceof Map<?,?>hook
                    ?new LinkedHashMap<>(autonewsroller.util.Json.object(hook)):Map.of());
    }

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
