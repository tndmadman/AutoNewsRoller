package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import autonewsroller.safety.ContentSafetyPolicy;
import java.util.*;
import java.util.regex.Pattern;

/** Fixed, non-event contextual subjects. Source text selects a category but never enters its prompt. */
public final class NeutralEditorialArtwork {
    private NeutralEditorialArtwork(){}
    private record Theme(String key,Pattern evidence,List<String> subjects){}
    private static Theme theme(String key,String words,String... subjects){
        return new Theme(key,Pattern.compile("(?i)\\b(?:"+words+")\\b"),List.of(subjects));
    }
    private static final Theme NEWS=theme("reporting","news|reporting",
        "a reporter's notebook with blank pages and a pen on a wooden desk",
        "a broadcast microphone beside a blank notepad in a quiet studio",
        "a close view of a camera resting on a newsroom worktable",
        "a desk lamp illuminating neatly stacked blank paper and a pencil");
    private static final List<Theme> THEMES=List.of(
        theme("technology","semiconductors?|chips?|processors?|computers?|software|technology|servers?|electronics",
            "a semiconductor wafer on a clean electronics workbench","a computer motherboard with detailed circuitry",
            "a row of server cabinets in a clean data center","electronic components arranged on a laboratory bench"),
        theme("finance","revenue|earnings|stocks?|markets?|banks?|inflation|economy|financial|investments?|budget",
            "a calculator and neatly stacked coins on an office desk","a close view of a blank ledger beside a calculator",
            "an orderly financial analyst workstation with unmarked paper","a still life of coins and a plain notebook"),
        theme("institutions","election|senate|congress|government|legislation|parliament|council|policy",
            "an empty generic civic meeting room with wooden desks","an unoccupied public administration office",
            "a close view of empty desks in a generic council chamber","a neutral institutional hallway with daylight"),
        theme("law","courts?|legal|lawsuit|judge|trial|justice",
            "a wooden desk with plain legal books and a blank notepad","a close view of a shelf of plain law books",
            "an empty generic courthouse corridor","a plain notebook beside a stack of reference books"),
        theme("transport","transportation|railways?|trains?|transit|airports?|aviation|shipping|logistics",
            "an empty generic railway platform in daylight","a close view of a quiet transportation terminal interior",
            "a generic empty logistics warehouse aisle","a quiet station waiting area with rows of empty seats"),
        theme("industry","manufacturing|factory|factories|production|industrial|assembly|warehouses?",
            "an orderly generic manufacturing workshop","a close view of clean industrial machinery",
            "a generic empty assembly workspace with tools on a bench","a clean factory interior with industrial equipment"),
        theme("environment","climate|weather|environment|forests?|rivers?|conservation|rainfall|water",
            "a tranquil generic river landscape with trees","an ordinary woodland landscape in daylight",
            "a close view of leaves and a clear water surface","a peaceful landscape with distant hills"),
        theme("education","schools?|education|universities|university|colleges?|libraries|library",
            "a quiet library aisle with books on shelves","an empty classroom with desks in daylight",
            "a reading table with closed books and a lamp","a close view of books arranged on a study desk")
    );
    private static final List<String> RETRY=List.of("natural eye-level composition",
        "close framing with a clear central subject","wide composition with visible depth",
        "side lighting and detailed material textures","simple arrangement with natural color contrast");
    private static final List<String> FORBIDDEN=List.of("people","faces","human figures","animals",
        "weapons","injuries","blood","crime scene","disaster scene","event reenactment",
        "photography","text","numbers","logos","flags","symbols");
    public static String category(String narration,String verified){
        String source=verified==null?"":verified;
        String beat=narration==null?"":narration;
        for(Theme t:THEMES)if(t.evidence().matcher(beat).find()&&t.evidence().matcher(source).find())return t.key();
        for(Theme t:THEMES)if(t.evidence().matcher(source).find())return t.key();
        return NEWS.key();
    }
    private static Theme themeFor(String category){
        if(NEWS.key().equals(category))return NEWS;
        for(Theme t:THEMES)if(t.key().equals(category))return t;
        throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: unknown contextual artwork category");
    }
    public static String base(int scene){return base(NEWS.key(),scene);}
    public static String base(String category,int scene){
        List<String> subjects=themeFor(category).subjects();
        return subjects.get(Math.floorMod(scene,subjects.size()));
    }
    public static List<String> exclusions(){return FORBIDDEN;}
    public static String positive(int scene,int attempt){return positive(NEWS.key(),scene,attempt);}
    public static String positive(String category,int scene,int attempt){
        if(attempt<0||attempt>=RETRY.size())throw new IllegalArgumentException("Invalid contextual art attempt");
        String value="Detailed editorial illustration of "+base(category,scene)+", "+RETRY.get(attempt)
            +", realistic material detail, dimensional natural lighting, rich controlled colors, unmistakably illustrated editorial artwork, vertical portrait composition";
        ContentSafetyPolicy.validatePositive(value);
        return value;
    }
    public static String category(VisualPlan.Item item){
        return String.valueOf(item.data().getOrDefault("standardArtworkCategory",NEWS.key()));
    }
    public static void requireSource(VisualPlan.Item item,Map<String,Object> source){
        String category=category(item);
        if(!Boolean.TRUE.equals(item.data().get("standardNeutralArtwork"))
                ||!base(category,item.index()).equals(item.prompt())
                ||!"NEUTRAL_EDITORIAL_BACKGROUND".equals(source.get("visualStrategy"))
                ||!(source.get("attempt") instanceof Number n)||n.intValue()<1||n.intValue()>RETRY.size()
                ||!positive(category,item.index(),n.intValue()-1).equals(source.get("prompt")))
            throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: invalid contextual artwork provenance for scene "+item.index());
    }
}
