package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.util.*;

/** Immutable, inspectable scene timeline; timestamps correspond to the final WAV. */
public record Storyboard(String storyId,double duration,String timingSource,List<Scene> scenes){
    public record Scene(int index,double start,double end,String type,int segmentIndex,
                        List<String> factIds,String motion,String transition,String provenance){
        public Scene{factIds=factIds==null?List.of():List.copyOf(factIds);}
        public Map<String,Object> toMap(){
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("index",index);m.put("start",start);m.put("end",end);m.put("type",type);
            m.put("segmentIndex",segmentIndex);m.put("factIds",factIds);m.put("motion",motion);
            m.put("transition",transition);m.put("provenance",provenance);return m;
        }
    }
    public Map<String,Object> toMap(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("storyId",storyId);m.put("duration",duration);m.put("timingSource",timingSource);
        m.put("scenes",scenes.stream().map(Scene::toMap).toList());return m;
    }
    public List<Double> durations(){return scenes.stream().map(s->s.end()-s.start()).toList();}
    public void validate(){
        if(!(duration>0)||scenes.isEmpty())throw new IllegalArgumentException("Empty storyboard");
        double cursor=0;
        for(int i=0;i<scenes.size();i++){
            Scene s=scenes.get(i);
            if(s.index()!=i||Math.abs(s.start()-cursor)>.005||!(s.end()>s.start())||s.end()>duration+.005)
                throw new IllegalArgumentException("Invalid scene timeline at "+i);
            cursor=s.end();
        }
        if(Math.abs(cursor-duration)>.005)throw new IllegalArgumentException("Storyboard does not cover narration");
    }
}
