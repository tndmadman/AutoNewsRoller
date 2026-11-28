package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import autonewsroller.safety.ContentSafetyPolicy;
import java.util.*;

/** Closed, non-depictive prompt vocabulary. Never interpolate reporting or model-generated text. */
public final class NeutralEditorialArtwork {
    private NeutralEditorialArtwork(){}
    private static final List<String> COMPOSITIONS=List.of(
        "layered curved bands of warm ochre and slate on textured paper",
        "overlapping rounded rectangles in terracotta and cream with soft shadows",
        "broad watercolor washes of sage and muted indigo with paper texture",
        "interlocking arcs in sandstone and deep navy with generous negative space",
        "scattered abstract circles in muted rust and ivory on a painted ground",
        "layered angular planes of olive and warm gray with visible brushwork",
        "flowing abstract ribbons in dusty rose and charcoal on matte paper",
        "soft intersecting ellipses in muted amber and forest green");
    private static final List<String> RETRY=List.of("balanced asymmetric arrangement",
        "larger shapes and increased spacing","subtle paper collage texture",
        "gentle contrast and broad simple forms","clean matte painted surface");
    private static final List<String> FORBIDDEN=List.of("people","faces","human figures","animals",
        "weapons","injuries","blood","crime scene","disaster scene","vehicles","buildings",
        "maps","documents","photography","text","numbers","logos","flags","symbols");
    public static String base(int scene){return COMPOSITIONS.get(Math.floorMod(scene,COMPOSITIONS.size()));}
    public static List<String> exclusions(){return FORBIDDEN;}
    public static String positive(int scene,int attempt){
        if(attempt<0||attempt>=RETRY.size())throw new IllegalArgumentException("Invalid neutral art attempt");
        String value="Nonfigurative abstract editorial artwork, "+base(scene)+", "+RETRY.get(attempt)
            +", clearly painted illustration, vertical portrait composition, open central space for separate editorial graphics";
        ContentSafetyPolicy.validatePositive(value);
        return value;
    }
    public static void requireSource(VisualPlan.Item item,Map<String,Object> source){
        if(!Boolean.TRUE.equals(item.data().get("standardNeutralArtwork"))
                ||!base(item.index()).equals(item.prompt())
                ||!"NEUTRAL_EDITORIAL_BACKGROUND".equals(source.get("visualStrategy"))
                ||!(source.get("attempt") instanceof Number n)||n.intValue()<1||n.intValue()>RETRY.size()
                ||!positive(item.index(),n.intValue()-1).equals(source.get("prompt")))
            throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: invalid neutral artwork provenance for scene "+item.index());
    }
}
