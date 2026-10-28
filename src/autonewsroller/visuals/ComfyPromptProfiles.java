package autonewsroller.visuals;

import autonewsroller.config.NewsConfig;
import autonewsroller.safety.ContentSafetyPolicy;
import autonewsroller.model.VisualPlan;
import autonewsroller.util.Text;
import java.util.*;

/**
 * Shared ComfyUI prompt profiles. Sampling settings are owned by NewsPipeline
 * and ComfyImageGenerator; this class keeps mode-specific conditioning separate.
 * All textual overlays are rendered by Java, not the diffusion checkpoint.
 */
public final class ComfyPromptProfiles {
    private ComfyPromptProfiles(){}

    public static String positive(VisualPlan.Item item,boolean meme,int attempt){
        if(attempt<0)throw new IllegalArgumentException("Negative image attempt");
        String base=item.prompt()==null||item.prompt().isBlank()
                ?item.title()+". "+item.body():item.prompt();
        String suffix;
        if(meme){
            suffix=", bold satirical editorial comic illustration, expressive caricature and physical comedy, one unmistakable visual gag tied to the story, vivid but controlled colors, readable focal subject in the upper-middle of a vertical portrait frame, clean lower quarter reserved for separately rendered meme captions, strong silhouette and phone-screen readability";
            if(attempt>0)suffix+=", simplified composition, stronger visual punchline, distinct large central subject";
        }else{
            suffix="HOOK".equalsIgnoreCase(item.type())
                    ?", clearly hand-illustrated editorial news artwork, immediate thumbnail-safe news subject, upper-middle focal subject, natural colors and skin texture, neutral white balance, restrained saturation, clear lower quarter for the separately rendered news lower-third, vertical editorial portrait"
                    :", clearly hand-illustrated editorial news artwork, clear source-grounded focal subject, natural colors and skin texture, neutral white balance, restrained saturation, editorial depth, clear lower quarter for the separately rendered news lower-third, vertical editorial portrait";
            if(attempt>0)suffix+=", simpler documented focal action, clean factual composition, clear subject separation";
        }
        String prompt=Text.clean(base+" "+suffix+", fully clothed people when shown, appropriate everyday clothing, nonsexual composition, not presented as a genuine event photograph");
        ContentSafetyPolicy.validatePositive(prompt);
        if(!meme)assertNoExcludedConceptInPositive(item,prompt);
        return prompt;
    }

    public static String negative(NewsConfig cfg,VisualPlan.Item item,boolean meme){
        String defaultNegative=meme
                ?"text, letters, captions, subtitles, watermark, logo, signature, blurry, low quality, malformed hands, extra fingers, muddy composition, graphic violence, realistic news screenshot, documentary evidence photograph"
                :"text, watermark, logo, captions, low quality, distorted, deformed";
        String configured=cfg.get(meme?"memeImageNegative":"imageNegative",defaultNegative);
        LinkedHashSet<String>terms=new LinkedHashSet<>();
        for(String part:configured.split(",")){
            String term=Text.clean(part);
            if(!term.isBlank())terms.add(term);
        }
        terms.addAll(meme
                ?List.of("text","watermark","logo","captions","subtitles","official newsroom graphics")
                :List.of("text","logos","watermark","captions","artificial UI","generic newsroom graphics",
                         "blue wash","cyan cast","teal cast","electric blue lighting",
                         "cinematic teal-orange grade","neon lighting","sci-fi colors","glowing edges"));
        if(!meme){
            Object raw=item.data().get("mustNotShow");
            if(raw instanceof Collection<?>xs)for(Object x:xs){
                String term=Text.clean(String.valueOf(x));
                if(!term.isBlank())terms.add(term);
            }
        }
        return ContentSafetyPolicy.negative(String.join(", ",terms));
    }

    private static void assertNoExcludedConceptInPositive(VisualPlan.Item item,String prompt){
        Object raw=item.data().get("mustNotShow");
        if(!(raw instanceof Collection<?>xs))return;
        String lower=prompt.toLowerCase(Locale.ROOT);
        for(Object x:xs){
            String term=Text.clean(String.valueOf(x)).toLowerCase(Locale.ROOT);
            if(term.length()<3)continue;
            if(lower.matches("(?s).*\\b"+java.util.regex.Pattern.quote(term)+"\\b.*"))
                throw new IllegalStateException("positive visual prompt contains excluded concept '"+term+"' for scene "+item.index());
        }
    }
}
