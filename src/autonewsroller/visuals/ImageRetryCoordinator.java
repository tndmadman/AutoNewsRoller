package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import java.util.*;

/**
 * Plans a bounded sequence of grounded illustrated-image attempts for one scene.
 * No attempt in this plan is an approval: every result must pass the existing
 * original-image, semantic, and post-composition safety gates.
 */
public final class ImageRetryCoordinator {
    private static final int MAX_RETRIES=4;
    private ImageRetryCoordinator(){}

    public record Attempt(int number,long seed,String prompt){
        public Attempt{
            if(number<1||prompt==null||prompt.isBlank())
                throw new IllegalArgumentException("Invalid image attempt");
        }
    }

    public static int retryCount(int requested){
        return Math.max(0,Math.min(MAX_RETRIES,requested));
    }

    public static List<Attempt> plan(VisualPlan.Item scene,boolean meme,int configuredRetries,
                                     String storyId,int sceneIndex,String checkpoint){
        Objects.requireNonNull(scene);
        Objects.requireNonNull(storyId);
        Objects.requireNonNull(checkpoint);
        List<Attempt> attempts=new ArrayList<>();
        for(int offset=0;offset<=retryCount(configuredRetries);offset++){
            String prompt=ComfyPromptProfiles.positive(scene,meme,offset);
            long seed=Integer.toUnsignedLong(Objects.hash(storyId,sceneIndex,checkpoint,offset));
            attempts.add(new Attempt(offset+1,seed,prompt));
        }
        return List.copyOf(attempts);
    }
}
