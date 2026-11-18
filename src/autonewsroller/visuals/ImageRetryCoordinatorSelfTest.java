package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.util.*;

/** CPU-only boundary regression for image attempt planning. */
public final class ImageRetryCoordinatorSelfTest {
    public static void main(String[]args){
        VisualPlan.Item scene=new VisualPlan.Item(0,"HOOK","Town council approves bridge inspection",
                "The council approved an inspection of the town bridge.",7.0,
                "Editorial illustration of the verified town bridge and inspection paperwork");
        List<ImageRetryCoordinator.Attempt> full=ImageRetryCoordinator.plan(scene,false,100,
                "story-a",0,"sdxl");
        require(full.size()==5,"Retries must be capped at five total attempts");
        require(ImageRetryCoordinator.plan(scene,false,-5,"story-a",0,"sdxl").size()==1,
                "Negative retry configuration must not permit zero attempts");
        Set<Long> seeds=new HashSet<>();
        Set<String> prompts=new HashSet<>();
        for(int i=0;i<full.size();i++){
            ImageRetryCoordinator.Attempt attempt=full.get(i);
            require(attempt.number()==i+1,"Attempts must have sequential one-based identifiers");
            require(seeds.add(attempt.seed()),"Every scene attempt must use a distinct seed");
            require(prompts.add(attempt.prompt()),"Retries must vary illustrated positive conditioning");
        }
        List<ImageRetryCoordinator.Attempt> again=ImageRetryCoordinator.plan(scene,false,4,
                "story-a",0,"sdxl");
        require(full.equals(again),"Interrupted requests must resume stable attempt plans");
        require(!full.get(0).prompt().toLowerCase(Locale.ROOT)
                .contains("realistic editorial news photograph"),"Photographic fallback is prohibited");
        System.out.println("ImageRetryCoordinatorSelfTest PASS");
    }
    private static void require(boolean valid,String message){
        if(!valid)throw new AssertionError(message);
    }
}
