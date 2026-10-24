package autonewsroller.script;

import autonewsroller.model.NewsScript;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

/** Mode-local quality gates: no news minimum duration and no fixed beat count. */
public final class MemeScriptValidator {
    private static final Pattern NUMBER=Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\$)?\\d+(?:[.,]\\d+)*(?:%)?");
    private static final Pattern QUOTE=Pattern.compile("[\\\"“]([^\\\"”]{3,160})[\\\"”]");
    private static final Pattern EMPTY_JOKE=Pattern.compile(
            "(?i)(this is (?:wild|crazy|ridiculous)|you (?:won.t|cannot|can.t) believe"+
            "|you can.t make this up|the internet (?:is|went)|plot twist"+
            "|reality (?:wrote|is)|apparently reality|here.s the (?:thing|kicker)"+
            "|brace yourself|hold onto your hats|make it make sense"+
            "|we live in a simulation|the irony is not lost|like and subscribe"+
            "|today we.re (?:going|talking)|in a world where|more personality than the meeting)");
    public void validate(NewsScript script,MemeEvidenceBuilder.Evidence evidence){
        if(script.segments().size()<2||script.segments().size()>12)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Expected 2-12 distinct beats");
        if(script.narration().isBlank()||Text.words(script.narration())<6)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Empty or unusable narration");
        String hook=String.valueOf(script.hook().getOrDefault("hookText",""));
        if(hook.isBlank()||Text.words(hook)>9)
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Hook is not instantly readable");
        String first=script.segments().get(0).narration();
        if(first.isBlank()||Text.words(first)>8||first.toLowerCase(Locale.ROOT).startsWith("breaking news"))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Spoken hook too long or generic");
        if(!"HOOK".equalsIgnoreCase(script.segments().get(0).purpose()))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: First beat must be a hook");
        if(!"PAYOFF".equalsIgnoreCase(script.segments().get(script.segments().size()-1).purpose()))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Ending lacks a payoff beat");
        if(script.segments().size()>=4&&script.segments().subList(1,script.segments().size()-1)
                .stream().noneMatch(s->"JOKE".equalsIgnoreCase(s.purpose())
                        ||"PAYOFF".equalsIgnoreCase(s.purpose())))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: No early joke before final payoff");
        if(script.segments().stream().anyMatch(s->s.narration().isBlank()
                ||s.visualPrompt()==null||s.visualPrompt().isBlank()))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Empty beat or visual");
        String lower=script.narration().toLowerCase(Locale.ROOT);
        if(EMPTY_JOKE.matcher(lower).find()||lower.contains("you won't believe what happened next")
                ||lower.contains("today we are discussing"))
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Cliched non-joke narration");
        Set<String>uniqueBeats=new HashSet<>();
        for(NewsScript.Segment beat:script.segments()){
            if(Text.words(beat.narration())>34)
                throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: TTS beat too long");
            String line=beat.narration().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","").trim();
            if(!uniqueBeats.add(line))
                throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Repeated TTS line");
            if(beat.narration().matches("(?is).*(\\[(?:laugh|pause|sound|applause)[^\\]]*\\]|\\((?:laugh|pause|sound|applause)[^\\)]*\\)).*"))
                throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: TTS stage directions");
        }
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
        Matcher quoted=QUOTE.matcher(script.narration());
        while(quoted.find()){
            String claim=quoted.group(1).replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);
            if(!source.contains(claim))
                throw new IllegalArgumentException(
                        "MEME_SCRIPT_VALIDATION_FAILED: Unsupported direct quotation: "+claim);
        }
        Matcher nums=NUMBER.matcher(script.narration());
        while(nums.find()){
            String n=nums.group();
            if(n.matches("\\d")||source.contains(n.toLowerCase(Locale.ROOT)))continue;
            throw new IllegalArgumentException("MEME_SCRIPT_VALIDATION_FAILED: Unsupported number "+n);
        }
    }
}
