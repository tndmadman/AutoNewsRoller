package autonewsroller.visuals;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

public final class VisualPromptValidator {
    private static final Set<String> STRATEGIES=Set.of("HOOK","DIRECT_EVENT","PERSON","LOCATION","OBJECT","INSTITUTION","PROCESS","DOCUMENT","TIMELINE","CONTEXT");
    private static final List<String> CLICHES=List.of("futuristic","holographic","cyberpunk","surreal","symbolic","conceptual","glowing interface","digital matrix","mysterious silhouette","dramatic chess","floating data","anonymous hacker","glowing world map");
    private static final List<String> DRAMA=List.of("fire","explosion","blood","injury","injured","dead bodies","riot","protest","police","soldier","soldiers","gun","guns","missile","missiles","tank","tanks","destroyed building","crash","smoke","emergency vehicle");
    private static final Set<String> GENERIC_PROPER=Set.of("Realistic","Editorial","Wire","Service","Documentary","Natural","Contemporary","Portrait","Medium","Wide","Daytime","Nighttime","Direct","Event","Context","Location","Institution","Process","Document","Timeline","Hook");

    private final int minimumScore;
    private final boolean requireFactIds;

    public VisualPromptValidator(int minimumScore,boolean requireFactIds){this.minimumScore=Math.max(0,Math.min(100,minimumScore));this.requireFactIds=requireFactIds;}

    public Result validate(VisualPromptPlan plan,Map<String,FactClaim>facts,Collection<String>storyEntities,String headline,List<String>excerpts){
        List<String>issues=new ArrayList<>();
        if(plan.segmentIndex()<0)issues.add("invalid segment index");
        if(!STRATEGIES.contains(plan.strategy().toUpperCase(Locale.ROOT)))issues.add("unknown strategy: "+plan.strategy());

        List<String>citedText=new ArrayList<>();
        for(String id:plan.factIds()){
            FactClaim f=facts.get(id.toUpperCase(Locale.ROOT));
            if(f==null)issues.add("unknown fact ID: "+id);
            else citedText.add(f.statement());
        }
        if(requireFactIds&&plan.factIds().isEmpty())issues.add("scene cites no FACT IDs");
        if(plan.subject().isBlank()||Text.words(plan.subject())<2)issues.add("scene lacks a concrete subject");

        String structured=String.join(" ",List.of(plan.subject(),plan.action(),plan.setting(),plan.location(),plan.people(),String.join(" ",plan.visibleObjects()),plan.prompt()));
        String evidence=String.join(" ",citedText)+" "+String.join(" ",storyEntities)+" "+headline+" "+String.join(" ",excerpts);
        Set<String>sceneTokens=meaningful(Text.tokens(structured));
        Set<String>evidenceTokens=meaningful(Text.tokens(evidence));
        Set<String>overlap=new LinkedHashSet<>(sceneTokens);overlap.retainAll(evidenceTokens);
        if(overlap.size()<2)issues.add("weak overlap with cited/story evidence");

        String lower=structured.toLowerCase(Locale.ROOT);
        String citedLower=String.join(" ",citedText).toLowerCase(Locale.ROOT);
        for(String phrase:CLICHES)if(lower.contains(phrase)&&!evidence.toLowerCase(Locale.ROOT).contains(phrase))issues.add("AI cliché not supported: "+phrase);
        for(String phrase:DRAMA)if(wordish(lower,phrase)&&!wordish(citedLower,phrase))issues.add("unsupported dramatic content: "+phrase);

        for(String proper:properPhrases(structured)){
            if(GENERIC_PROPER.contains(proper))continue;
            if(!containsIgnoreCase(evidence,proper))issues.add("unsupported proper noun: "+proper);
        }

        String generic=Text.normalize(plan.prompt());
        if(generic.equals("dramatic news scene")||generic.contains("dramatic news scene with people")||generic.equals("news image")||generic.equals("professional documentary image")||generic.equals("generic office")||generic.equals("busy city"))
            issues.add("prompt is vague/generic");

        int score=0;
        if(!plan.factIds().isEmpty()&&issues.stream().noneMatch(x->x.startsWith("unknown fact")))score+=30;
        score+=Math.min(20,overlap.size()*4);
        if(!plan.subject().isBlank()&&Text.words(plan.subject())>=2)score+=15;
        if(!plan.setting().isBlank())score+=10;
        if(!plan.action().isBlank()&&shares(plan.action(),citedText))score+=10;
        if(!plan.visibleObjects().isEmpty()&&shares(String.join(" ",plan.visibleObjects()),citedText))score+=10;
        if(!plan.location().isBlank()||!plan.timeContext().isBlank())score+=5;
        if(issues.stream().anyMatch(x->x.startsWith("unsupported proper noun")))score-=30;
        if(issues.stream().anyMatch(x->x.contains("vague/generic")))score-=25;
        if(issues.stream().anyMatch(x->x.startsWith("unsupported dramatic")))score-=40;
        if(issues.stream().anyMatch(x->x.startsWith("AI cliché")))score-=25;
        if(overlap.isEmpty())score-=20;
        score=Math.max(0,Math.min(100,score));
        if(score<minimumScore)issues.add("grounding score "+score+" below minimum "+minimumScore);
        return new Result(score,issues.isEmpty(),List.copyOf(issues));
    }

    public record Result(double score,boolean valid,List<String>issues){}

    private static Set<String>meaningful(Set<String>in){
        Set<String>out=new LinkedHashSet<>();
        for(String x:in)if(x.length()>=4&&!Set.of("realistic","editorial","news","image","photograph","photo","scene","show","with","from","that","this","only","natural","documentary").contains(x))out.add(x);
        return out;
    }
    private static boolean shares(String text,List<String>facts){
        Set<String>a=meaningful(Text.tokens(text)),b=meaningful(Text.tokens(String.join(" ",facts)));a.retainAll(b);return !a.isEmpty();
    }
    private static boolean wordish(String hay,String needle){
        String n=needle.toLowerCase(Locale.ROOT);
        if(n.contains(" "))return hay.contains(n);
        return Pattern.compile("(^|\\W)"+Pattern.quote(n)+"($|\\W)").matcher(hay).find();
    }
    private static boolean containsIgnoreCase(String hay,String needle){return hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));}
    private static List<String>properPhrases(String text){
        List<String>out=new ArrayList<>();
        Matcher m=Pattern.compile("\\b[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3}\\b").matcher(text);
        while(m.find()){String s=m.group().trim();if(!out.contains(s))out.add(s);}
        return out;
    }
}
