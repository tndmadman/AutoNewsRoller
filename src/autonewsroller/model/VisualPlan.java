package autonewsroller.model;

import java.util.*;

public record VisualPlan(String storyId,List<Item> items){
    public record Item(int index,String type,String title,String body,double duration,String prompt,Map<String,Object> data){
        public Item(int index,String type,String title,String body,double duration,String prompt){this(index,type,title,body,duration,prompt,Map.of());}
        public Item{data=data==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(data));}
        public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("index",index);m.put("type",type);m.put("title",title);m.put("body",body);m.put("duration",duration);m.put("prompt",prompt);m.put("data",data);return m;}
    }
    public Map<String,Object> toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("storyId",storyId);m.put("items",items.stream().map(Item::toMap).toList());return m;}
}
