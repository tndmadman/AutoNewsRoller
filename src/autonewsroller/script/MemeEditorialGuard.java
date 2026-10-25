package autonewsroller.script;

import autonewsroller.model.Article;
import autonewsroller.model.StoryCluster;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Conservative, auditable pre-production hold for clearly victim-centered reporting.
 * Operates on article headlines and descriptions (not unrelated mentions deep in a body).
 * It is not a semantic classifier; a human editor remains responsible for borderline cases.
 */
public final class MemeEditorialGuard {
    private static final Pattern HIGH_CONFIDENCE=Pattern.compile(
            "(?i)\\b(?:sexual assault|rape victim|mass shooting|fatal crash|fatal collision"+
            "|child abuse|abuse victim|victims? of (?:violence|abuse|shooting|assault|attack)"+
            "|suicide|homicide)\\b");
    private static final Pattern VICTIM_LANGUAGE=Pattern.compile(
            "(?i)\\b(?:died|dies|killed|murdered|grief|grieving|mourning|bereavement)\\b");
    private static final Pattern DEATH_OR_INJURY=Pattern.compile(
            "(?i)\\b(?:death|fatal|serious injuries|seriously injured|severe injuries|injured victims?)\\b");
    private static final Pattern HUMAN_CONTEXT=Pattern.compile(
            "(?i)\\b(?:family|families|father|mother|child|children|son|daughter|woman|man"+
            "|person|people|victim|worker|driver|pedestrian|player|manager|officer"+
            "|injured|mourning|funeral|crash|accident|attack|shot|shooting|murder)\\b");
    private static final Pattern METAPHOR=Pattern.compile(
            "(?i)\\b(?:death metal|death star|death of a franchise|fatal flaw|killed the mood)\\b");
    private MemeEditorialGuard(){}

    public static String reason(StoryCluster cluster){
        for(Article article:cluster.articles){
            String risk=reason(article.title(),article.description());
            if(!risk.isBlank())return risk;
        }
        return reason(cluster.topic,"");
    }

    public static String reason(String headline,String description){
        String title=headline==null?"":headline.trim();
        String desc=description==null?"":description.trim();
        String text=title+" "+desc;
        // Remove figurative phrases without hiding other genuine harm in the same story.
        String contextual=METAPHOR.matcher(text).replaceAll(" ");
        if(HIGH_CONFIDENCE.matcher(text).find()
                ||(VICTIM_LANGUAGE.matcher(contextual).find()&&HUMAN_CONTEXT.matcher(text).find())
                ||(DEATH_OR_INJURY.matcher(contextual).find()&&HUMAN_CONTEXT.matcher(text).find()))
            return "Victim-centered tragedy or serious harm indicated by source headline/description";
        return "";
    }
}
