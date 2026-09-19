package autonewsroller.orchestration;

import autonewsroller.util.Json;
import java.time.Instant;import java.util.*;
public record WorkerState(int worker,int slot,PipelineStage stage,String detail,Instant timestamp){public Map<String,Object>toMap(){Map<String,Object>m=new LinkedHashMap<>();m.put("worker",worker);m.put("slot",slot);m.put("stage",stage.name());m.put("detail",detail);m.put("timestamp",timestamp.toString());return m;}public String json(){return Json.stringify(toMap());}}
