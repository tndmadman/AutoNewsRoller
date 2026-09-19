package autonewsroller.script;

import autonewsroller.model.*;import autonewsroller.util.Text;import java.util.*;import java.util.regex.*;

public final class ScriptValidator {
    public List<String> validate(NewsScript s,FactPackage facts,int targetSeconds){List<String> problems=new ArrayList<>();if(s.headline()==null||s.headline().isBlank())problems.add("missing headline");if(s.narration()==null||s.narration().isBlank())problems.add("missing narration");if(s.segments()==null||s.segments().isEmpty())problems.add("missing segments");int words=Text.words(s.narration());double est=words/2.5;if(words<35)problems.add("narration too short");if(est>targetSeconds*1.55)problems.add("narration too long for target");Set<String> allowedNumbers=numbers(JsonFactText(facts));for(String n:numbers(s.narration()))if(!allowedNumbers.contains(n))problems.add("script contains unsupported number: "+n);Set<String> allowedPublishers=new HashSet<>();for(Map<String,Object>x:facts.sources())allowedPublishers.add(String.valueOf(x.get("publisher")));for(String label:s.sourceLabels())if(!allowedPublishers.contains(label))problems.add("unknown source label: "+label);return problems;}
    private static String JsonFactText(FactPackage f){StringBuilder b=new StringBuilder(f.headline()).append(' ').append(f.summary());for(FactClaim c:f.facts())b.append(' ').append(c.statement());return b.toString();}
    private static Set<String> numbers(String s){Set<String>x=new HashSet<>();Matcher m=Pattern.compile("\\b\\d+(?:[.,]\\d+)?%?\\b").matcher(s==null?"":s);while(m.find())x.add(m.group());return x;}
}
