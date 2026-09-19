package autonewsroller.model;

import java.util.*;

public record VisualPlan(String storyId,List<Item> items){
    public record Item(
            int index,
            String type,
            String title,
            String body,
            String displayCaption,
            double duration,
            String prompt,
            String negativePrompt,
            List<String> emphasisWords,
            String transition,
            Map<String,Object> sourceMetadata
    ){
        public Item{
            displayCaption=displayCaption==null?"":displayCaption;
            prompt=prompt==null?"":prompt;
            negativePrompt=negativePrompt==null?"":negativePrompt;
            emphasisWords=emphasisWords==null?List.of():List.copyOf(emphasisWords);
            transition=transition==null||transition.isBlank()?"push_in":transition;
            sourceMetadata=sourceMetadata==null?Map.of():Map.copyOf(sourceMetadata);
        }
        public Item(int index,String type,String title,String body,double duration,String prompt){
            this(index,type,title,body,shortCaption(body),duration,prompt,"",List.of(),"push_in",Map.of());
        }
        public Map<String,Object> toMap(){
            Map<String,Object>m=new LinkedHashMap<>();
            m.put("index",index);m.put("type",type);m.put("title",title);m.put("body",body);
            m.put("displayCaption",displayCaption);m.put("duration",duration);m.put("prompt",prompt);
            m.put("negativePrompt",negativePrompt);m.put("emphasisWords",emphasisWords);
            m.put("transition",transition);m.put("sourceMetadata",sourceMetadata);
            return m;
        }
        private static String shortCaption(String body){
            if(body==null||body.isBlank())return "";
            String[]w=body.trim().split("\\s+");
            return String.join(" ",Arrays.copyOfRange(w,0,Math.min(8,w.length)));
        }
    }
    public Map<String,Object> toMap(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("storyId",storyId);m.put("items",items.stream().map(Item::toMap).toList());return m;
    }
}
