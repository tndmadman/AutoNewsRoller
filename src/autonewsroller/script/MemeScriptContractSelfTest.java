package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Json;
import autonewsroller.visuals.*;
import java.util.*;

/** Adversarial continuation tests: no Ollama or GPU required. */
public final class MemeScriptContractSelfTest {
    private MemeScriptContractSelfTest(){}
    public static int run(){
        FactPackage facts=new FactPackage("contract-story","Novelty signs downtown",
                "City approved novelty signs",
                List.of(new FactClaim("City purchased novelty signs for downtown",
                        List.of("https://example.test/signs"),.9,false)),
                List.of(),List.of(Map.of("publisher","Fixture Press")),1,1,.9,false);
        MemeEvidenceBuilder.Evidence evidence=new MemeEvidenceBuilder.Evidence(
                Map.of("storyId","contract-story"),Set.of("F1"),
                "City purchased novelty signs for downtown", "fixture-fingerprint");
        MemeHookPlanner.Selection hook=new MemeHookPlanner.Selection(
                "NOVELTY SIGNS WANT PAYCHECKS","Novelty signs demand a salary.",
                List.of("F1"),9.5,List.of(Map.of("caption","NOVELTY SIGNS WANT PAYCHECKS",
                        "visualIdea","Editorial cartoon of downtown novelty signs at a payroll desk")));
        Map<String,Object>anglePlan=Map.of("selectedAngle","Signs want overtime");
        Map<String,Object>payoff=Map.of(
                "speech","Those downtown signs want overtime for standing still.",
                "caption","OVERTIME FOR STANDING STILL",
                "visualPrompt","Cartoon novelty signs holding timesheets downtown",
                "factIds",List.of("F1"),"purpose","PAYOFF");
        int checks=0;
        for(int i=0;i<5;i++){
            Map<String,Object>response=new LinkedHashMap<>();
            response.put("angle","Signs want overtime");
            response.put("beats",List.of(payoff));
            if(i==1){response.put("hookText","novelty signs want paychecks");response.put("spokenHook","Novelty signs demand a salary!");}
            if(i==2){response.put("hookText","A TOTALLY DIFFERENT HOOK");response.put("spokenHook","The model rewrote the opener");}
            if(i==3){response.put("hookText","");response.put("spokenHook","");}
            if(i==4){response.put("hookText",null);response.put("spokenHook",null);}
            NewsScript script=verified(response,facts,evidence,anglePlan,hook);
            check(script.segments().size()==2 && canonical(script,hook)
                    &&script.narration().startsWith(hook.spoken()),"locked opening canonical "+i);
            checks++;
        }
        Map<String,Object>legacy=new LinkedHashMap<>();
        legacy.put("angle","Signs want overtime");
        legacy.put("hookText","LLM wrote a different top-level hook");
        legacy.put("spokenHook","But Java ignores this");
        legacy.put("beats",List.of(Map.of(
                "speech","novelty signs demand a salary!",
                "caption","novelty signs want paychecks.",
                "visualPrompt","Cartoon payroll desk",
                "purpose","HOOK","factIds",List.of("F1")),payoff));
        NewsScript old=verified(legacy,facts,evidence,anglePlan,hook);
        check(old.segments().size()==2&&canonical(old,hook),"unambiguous legacy hook omitted");
        checks++;
        Map<String,Object>many=new LinkedHashMap<>();
        many.put("angle","Signs want overtime");
        many.put("beats",List.of(
                Map.of("speech","The city gave the signs a new job.",
                        "caption","SIGN WORK","visualPrompt","Downtown signs at work",
                        "purpose","SETUP","factIds",List.of("F1")),
                Map.of("speech","They get overtime just standing around.",
                        "caption","STAND BY","visualPrompt","Downtown signs with punch clock",
                        "purpose","JOKE","factIds",List.of("F1")),payoff));
        NewsScript extended=verified(many,facts,evidence,anglePlan,hook);
        check(extended.segments().size()==4&&"PAYOFF".equals(
                extended.segments().get(3).purpose()),"continuation supports variable beats");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","Different hook","caption","DIFFERENT HOOK",
                        "visualPrompt","Cartoon","purpose","HOOK","factIds",List.of("F1")),payoff)),
                facts,evidence,anglePlan,hook,"HOOK");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","New signs want overtime.","caption","OVERTIME",
                        "visualPrompt","Signs working","purpose","PAYOFF","factIds",List.of("F99")))),
                facts,evidence,anglePlan,hook,"fact IDs");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","A sign asked for overtime.","caption","OVERTIME",
                        "visualPrompt","Signs working","purpose","UNKNOWN","factIds",List.of("F1")))),
                facts,evidence,anglePlan,hook,"purpose");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","The city bought 9999999 signs.","caption","SIGNS",
                        "visualPrompt","Downtown signs","purpose","PAYOFF","factIds",List.of("F1")))),
                facts,evidence,anglePlan,hook,"Unsupported number");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","The mayor said \"I bought the moon\".","caption","MAYOR",
                        "visualPrompt","Cartoon podium","purpose","PAYOFF","factIds",List.of("F1")))),
                facts,evidence,anglePlan,hook,"Unsupported direct quotation");
        checks++;
        reject(Map.of("angle","Signs want overtime","beats",List.of(
                Map.of("speech","Signs want overtime.","caption","PAY",
                        "visualPrompt","Signs","purpose","SETUP","factIds",List.of("F1")))),
                facts,evidence,anglePlan,hook,"payoff");
        checks++;
        return checks;
    }
    private static NewsScript verified(Map<String,Object>output,FactPackage facts,
            MemeEvidenceBuilder.Evidence evidence,Map<String,Object>anglePlan,
            MemeHookPlanner.Selection hook){
        NewsScript script=MemeScriptGenerator.assembleContinuation(output,facts,evidence,anglePlan,hook);
        new MemeScriptValidator().validate(script,evidence);
        return script;
    }
    private static boolean canonical(NewsScript script,MemeHookPlanner.Selection hook){
        return hook.caption().equals(script.hook().get("hookText"))
                &&hook.spoken().equals(script.hook().get("spokenHook"))
                &&hook.caption().equals(script.segments().get(0).visualType())
                &&hook.spoken().equals(script.segments().get(0).narration())
                &&"HOOK".equals(script.segments().get(0).purpose())
                &&hook.caption().equals(new MemeVisualPlanner().plan(script).items().get(0).title());
    }
    private static void reject(Map<String,Object>output,FactPackage facts,
            MemeEvidenceBuilder.Evidence evidence,Map<String,Object>anglePlan,
            MemeHookPlanner.Selection hook,String expected){
        try{verified(output,facts,evidence,anglePlan,hook);}
        catch(IllegalArgumentException ex){
            check(ex.getMessage().toLowerCase(Locale.ROOT)
                    .contains(expected.toLowerCase(Locale.ROOT)),
                    "expected rejection "+expected+" but got "+ex.getMessage());
            return;
        }
        throw new AssertionError("Unexpected acceptance: "+expected);
    }
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError("Meme script contract: "+message);
    }
}
