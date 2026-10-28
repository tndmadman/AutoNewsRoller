package autonewsroller.safety;

import java.util.*;
import java.util.regex.*;

/** Non-overridable production policy. Reporting text is never an instruction to generate explicit visuals. */
public final class ContentSafetyPolicy {
    private ContentSafetyPolicy(){}
    public static final String VERSION="content-safety-2026-10-28-v1";
    public static final String REQUIRED_NEGATIVE="nude, nudity, naked, topless, exposed breasts, nipples, areola, exposed genitals, explicit sexual content, erotic, pornographic, sexual activity, transparent clothing, fetish, sexualized pose, underwear model, graphic anatomy, exposed buttocks, porn, voyeurism, sexual assault reenactment";
    private static final Pattern SENSITIVE=Pattern.compile("(?i)\\b(war|battle|armed conflict|terroris\\w*|bombing|hostage|shooting|murder|rape|sexual assault|sex crime|trafficking|child abuse|minor\\w*|underage|fatal|dead|death|corpse|injur\\w*|medical|disaster|earthquake|flood|wildfire|evacuat\\w*|emergency|election|candidate|politic\\w*|accus\\w*|alleg\\w*|arrest|police|fraud|endorse\\w*)\\b");
    private static final Pattern UNSAFE_PROMPT=Pattern.compile("(?i)\\b(nude|nudity|naked|topless|breasts?|nipples?|areola|genitalia?|porn\\w*|erotic|fetish|lingerie|underwear|sex(?:ual)?\\s+(?:act|activity|pose)|transparent clothing|see-through clothing|sexualized|fake cctv|fake police footage|authentic wire.service|documentary photograph|eyewitness footage|real photo of the event)\\b");
    public static boolean sensitive(String text){return text!=null&&SENSITIVE.matcher(text).find();}
    public static void validatePositive(String prompt){
        if(prompt==null||prompt.isBlank())throw new IllegalStateException("Empty ComfyUI positive prompt");
        Matcher m=UNSAFE_PROMPT.matcher(prompt);
        if(m.find())throw new IllegalStateException("Unsafe or documentary-impersonating image prompt concept: "+m.group());
    }
    public static String negative(String configured){
        LinkedHashSet<String>words=new LinkedHashSet<>();
        if(configured!=null)for(String s:configured.split(","))if(!s.isBlank())words.add(s.trim());
        words.addAll(Arrays.asList(REQUIRED_NEGATIVE.split(", ")));
        return String.join(", ",words);
    }
}