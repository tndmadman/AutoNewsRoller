package autonewsroller.safety;

import java.util.Map;

public final class SafetyScanProgressSelfTest {
    public static void main(String[]args) {
        if(!SafetyScanProgress.snapshot().get("count").equals(0))
            throw new AssertionError("unexpected live scanner");
        try(SafetyScanProgress.Scope scope=SafetyScanProgress.open("test-id")){
            SafetyScanProgress.update(SafetyScanProgress.current(),"CONTROLLER_OPENNSFW2",
                    8,20,"cpu",1234,1.5);
            Map<String,Object>record=((java.util.List<Map<String,Object>>)
                    SafetyScanProgress.snapshot().get("activeScans")).get(0);
            if(!"CONTROLLER_OPENNSFW2".equals(record.get("stage"))||
                    !Long.valueOf(8).equals(record.get("framesProcessed"))||
                    !Long.valueOf(20).equals(record.get("framesExpected")))
                throw new AssertionError("unverified progress fields");
            SafetyScanProgress.finished("test-id",false);
            if(!SafetyScanProgress.snapshot().get("count").equals(0))
                throw new AssertionError("blocked job still listed as active");
        }
        if(!SafetyScanProgress.current().isBlank())
            throw new AssertionError("thread-local scope leaked");
        System.out.println("SafetyScanProgressSelfTest PASS");
    }
}
