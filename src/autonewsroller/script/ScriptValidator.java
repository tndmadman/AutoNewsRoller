package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

public final class ScriptValidator {
    public List<String> validate(NewsScript s,FactPackage facts,int targetSeconds){
        List<String> problems=new ArrayList<>();
        if(s.headline()==null||s.headline().isBlank())problems.add("missing headline");
        if(s.narration()==null||s.narration().isBlank())problems.add("missing narration");
        if(s.segments()==null||s.segments().isEmpty())problems.add("missing segments");

        int words=Text.words(s.narration());
        int minWords=minWords(targetSeconds),maxWords=maxWords(targetSeconds);
        if(words<minWords)problems.add("narration too short: "+words+" words; need at least "+minWords+" for "+targetSeconds+"s target");
        if(words>maxWords)problems.add("narration too long: "+words+" words; max "+maxWords+" for "+targetSeconds+"s target");
        if(s.segments()!=null&&(s.segments().size()<7||s.segments().size()>10))
            problems.add("expected 7-10 narration segments");

        for(String n:unsupportedNumbers(s,facts))
            problems.add("script contains unsupported number: "+n);

        Set<String> allowedPublishers=new HashSet<>();
        for(Map<String,Object>x:facts.sources())allowedPublishers.add(String.valueOf(x.get("publisher")));
        for(String label:s.sourceLabels())if(!allowedPublishers.contains(label))
            problems.add("unknown source label: "+label);
        return problems;
    }

    public Set<String> unsupportedNumbers(NewsScript s,FactPackage facts){
        Set<String> allowedNumbers=numbers(JsonFactText(facts));
        LinkedHashSet<String> unsupported=new LinkedHashSet<>();
        for(String n:numbers(s==null?"":s.narration()))
            if(!allowedNumbers.contains(n))unsupported.add(n);
        return unsupported;
    }

    public static int minWords(int targetSeconds){
        return Math.max(70,(int)Math.ceil(Math.max(1,targetSeconds)*2.25));
    }

    public static int maxWords(int targetSeconds){
        return Math.max(minWords(targetSeconds)+20,(int)Math.ceil(Math.max(1,targetSeconds)*2.75));
    }

    private static String JsonFactText(FactPackage f){
        StringBuilder b=new StringBuilder(f.headline()).append(' ').append(f.summary());
        for(FactClaim c:f.facts())b.append(' ').append(c.statement());
        for(FactClaim c:f.disputedClaims())b.append(' ').append(c.statement());
        return b.toString();
    }

    private static Set<String> numbers(String s){
        Set<String>x=new LinkedHashSet<>();
        Matcher m=Pattern.compile("\\b\\d+(?:[.,]\\d+)?%?\\b").matcher(s==null?"":s);
        while(m.find())x.add(m.group());
        return x;
    }
}
