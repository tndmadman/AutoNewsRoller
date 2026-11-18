package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Json;
import autonewsroller.util.Text;
import java.util.*;

/** A too-short model repair must progress, but cannot bypass final checks. */
public final class NewsScriptRepairProgressSelfTest {
    public static void main(String[]args){
        FactPackage fp=new FactPackage("fixture","City library expands community services",
                "The city library expanded study rooms and published the schedule.",
                List.of(new FactClaim("The city library expanded study rooms.",
                        List.of("source"),.9,false)),
                List.of(),List.of(Map.of("publisher","Fixture Wire")),1,1,.9,false);
        String hook="City library expands its community study spaces today";
        String beat="The city library announced more study space for residents using the local library.";
        List<NewsScript.Segment>segments=new ArrayList<>();
        segments.add(new NewsScript.Segment(0,hook,"hook","HOOK","",3));
        for(int i=1;i<8;i++)
            segments.add(new NewsScript.Segment(i,beat,"verified detail","CONTEXT","",9));
        String narration=segments.stream().map(NewsScript.Segment::narration)
                .reduce("",(a,b)->a.isBlank()?b:a+" "+b);
        NewsScript base=new NewsScript(fp.storyId(),fp.headline(),narration,
                segments,60,List.of("Fixture Wire"),Map.of("text",hook));
        int wordsBefore=Text.words(base.narration());
        var target=new NewsScriptGenerator.RepairTarget(2,26,30,28,beat);
        String near="The city library expanded study rooms and introduced more public reading "+
                "areas while the schedule explains how residents may use these shared community spaces today.";
        if(Text.words(near)!=25)
            throw new AssertionError("Fixture should be 25 words");
        String raw=repair(near);
        rejects(()->NewsScriptGenerator.applyReplacements(base,raw,fp,70,List.of(target)));
        NewsScript revised=NewsScriptGenerator.applyLengthRepairWithProgress(
                base,raw,fp,70,List.of(target),175);
        if(Text.words(revised.narration())<=wordsBefore||
                !hook.equals(revised.segments().get(0).narration())||
                !beat.equals(revised.segments().get(1).narration()))
            throw new AssertionError("Intermediate length repair lost progress or changed hook");
        if(new ScriptValidator().validate(revised,fp,70).isEmpty())
            throw new AssertionError("Incomplete draft wrongly passed duration verification");
        rejects(()->NewsScriptGenerator.applyLengthRepairWithProgress(
                base,repair(near+" Officials spent 999999 dollars."),fp,70,List.of(target),175));
        rejects(()->NewsScriptGenerator.applyLengthRepairWithProgress(
                base,repair(beat),fp,70,List.of(target),175));
        // One completely wrong replacement cannot erase a useful, source-safe
        // replacement in the same batch. A future attempt repairs the rest.
        var target3=new NewsScriptGenerator.RepairTarget(3,26,30,28,beat);
        String mixed=Json.stringify(Map.of("replacements",List.of(
                Map.of("segmentIndex",2,"narration",near),
                Map.of("segmentIndex",3,"narration","bad"))));
        NewsScript salvaged=NewsScriptGenerator.applyLengthRepairWithProgress(
                base,mixed,fp,70,List.of(target,target3),175);
        if(!near.equals(salvaged.segments().get(2).narration())||
                !beat.equals(salvaged.segments().get(3).narration()))
            throw new AssertionError("A bad reply caused valid repair work to be discarded");
        if(new ScriptValidator().validate(salvaged,fp,70).isEmpty())
            throw new AssertionError("Partial repairs incorrectly authorized production");
        System.out.println("NewsScriptRepairProgressSelfTest PASS");
    }
    private static String repair(String words){
        return Json.stringify(Map.of("replacements",List.of(
                Map.of("segmentIndex",2,"narration",words))));
    }
    private interface Trial{void run();}
    private static void rejects(Trial trial){
        try{trial.run();}catch(IllegalArgumentException expected){return;}
        throw new AssertionError("Unsafe or non-progress repair was accepted");
    }
}
