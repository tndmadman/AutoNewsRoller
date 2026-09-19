package autonewsroller.model;

import java.util.*;

public record VisualPlan(String storyId,List<Item> items){
    public record Item(int index,String type,String title,String body,double duration,String prompt){
        public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("index",index);m.put("type",type);m.put("title",title);m.put("body",body);m.put("duration",duration);m.put("prompt",prompt);return m;}
    }
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("storyId",storyId);m.put("items",items.stream().map(Item::toMap).toList());return m;}
}
