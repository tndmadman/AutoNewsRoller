package autonewsroller.script;

import autonewsroller.util.Text;
import java.util.*;

/**
 * Experimental framing scripts may grow with meaningful article analysis.
 * Standard video jobs never use this policy.
 */
public final class PoliticalFramingDurationPolicy {
    public static final int DEFAULT_MAX_SECONDS=180;
    public static final double TARGET_WORDS_PER_SECOND=2.5;
    private PoliticalFramingDurationPolicy(){}

    /**
     * Select a nominal duration based on the complete script, including the locked
     * headline and teaser. The minimum is the operator's requested target.
     * The configurable ceiling applies only to automatic expansion; an explicit
     * longer target is never unexpectedly shortened.
     */
    public static int chooseSeconds(int totalWords,int minimumSeconds,int configuredMaximumSeconds){
        if(totalWords<=0)throw new IllegalArgumentException("Script must contain words");
        int minimum=Math.max(1,minimumSeconds);
        int ceiling=Math.max(minimum,Math.min(600,configuredMaximumSeconds));
        int needed=(int)Math.ceil(totalWords/TARGET_WORDS_PER_SECOND);
        return Math.max(minimum,Math.min(ceiling,needed));
    }

    /**
     * Reject long scripts whose word count is inflated by duplicated sentences.
     * Verbatim repeated sentences are not distinct editorial evidence.
     */
    public static boolean excessiveRepetition(List<String>paragraphs){
        if(paragraphs==null)return false;
        Map<String,Integer>seen=new HashMap<>();
        for(String paragraph:paragraphs){
            for(String sentence:Text.sentences(paragraph)){
                String normalized=Text.normalize(sentence);
                if(normalized.split(" ").length<5)continue;
                if(seen.merge(normalized,1,Integer::sum)>=3)return true;
            }
        }
        return false;
    }
}
