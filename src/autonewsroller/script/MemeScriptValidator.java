package autonewsroller.script;

import autonewsroller.model.NewsScript;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

/** Mode-local quality gates: no news minimum duration and no fixed beat count. */
public final class MemeScriptValidator {
    private static final Pattern NUMBER=Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\$)?\\d+(?:[.,]\\d+)*(?:%)?");
    public void validate(NewsScript script,MemeEvidenceBuilder.Evidence evidence){
        if(script.segments().size()<2||script.segments().size()>12)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Expected 2-12 distinct beats");
        if(script.narration().isBlank()||Text.words(script.narration())<6)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Empty or unusable narration");
        String hook=String.valueOf(script.hook().getOrDefault("hookText",""));
        if(hook.isBlank()||Text.words(hook)>9)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Hook is not instantly readable");
        String first=script.segments().get(0).narration();
        if(first.isBlank()||Text.words(first)>13||first.toLowerCase(Locale.ROOT).startsWith("breaking news"))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Spoken hook too long or generic");
        if(script.segments().stream().anyMatch(s->s.narration().isBlank()
                ||s.visualPrompt()==null||s.visualPrompt().isBlank()))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Empty beat or visual");
        String lower=script.narration().toLowerCase(Locale.ROOT);
        if(lower.contains("you won't believe what happened next")||lower.contains("today we are discussing"))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Generic intro");
        List<?> citations=script.hook().get("beatEvidence") instanceof List<?> l?l:List.of();
        if(citations.size()!=script.segments().size())
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Missing beat evidence");
        for(Object row:citations){
            if(!(row instanceof Collection<?> ids)||ids.isEmpty())
                throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Unattributed beat");
            for(Object id:ids)if(!evidence.factIds().contains(String.valueOf(id)))
                throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Unknown fact reference "+id);
        }
        String source=evidence.sourceText().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
        Matcher nums=NUMBER.matcher(script.narration());
        while(nums.find()){
            String n=nums.group();
            if(n.matches("\\d")||source.contains(n.toLowerCase(Locale.ROOT)))continue;
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Unsupported number "+n);
        }
    }
}
