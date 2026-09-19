package autonewsroller.script;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;
import java.util.regex.*;

public final class ScriptValidator {
    public List<String> validate(NewsScript s,FactPackage facts,int targetSeconds){
        List<String>problems=new ArrayList<>();
        if(s.headline()==null||s.headline().isBlank())problems.add("missing headline");
        if(s.narration()==null||s.narration().isBlank())problems.add("missing narration");
        if(s.segments()==null||s.segments().isEmpty())problems.add("missing segments");

        int words=Text.words(s.narration());
        int minWords=Math.max(165,(int)Math.round(Math.max(68,targetSeconds)*2.35));
        int maxWords=Math.min(220,Math.max(minWords+24,(int)Math.round(Math.max(68,targetSeconds)*3.0)));
        if(words<minWords)problems.add("narration too short: "+words+" words; need at least "+minWords);
        if(words>maxWords)problems.add("narration too long: "+words+" words; max "+maxWords);

        int segmentCount=s.segments()==null?0:s.segments().size();
        if(segmentCount<7)problems.add("too few narrative segments: "+segmentCount+"; need 7-10");
        if(segmentCount>10)problems.add("too many narrative segments: "+segmentCount+"; need 7-10");
        if(segmentCount>0){
            int segmentWords=s.segments().stream().mapToInt(x->Text.words(x.narration())).sum();
            if(segmentWords<Math.round(words*.75))problems.add("segments do not cover enough of the narration");
            for(int i=0;i<segmentCount;i++){
                NewsScript.Segment seg=s.segments().get(i);
                if(seg.narration()==null||seg.narration().isBlank())problems.add("segment "+i+" missing narration");
            }
        }

        Set<String>allowedNumbers=numbers(JsonFactText(facts));
        for(String n:numbers(s.narration()))if(!allowedNumbers.contains(n))problems.add("script contains unsupported number: "+n);

        Set<String>allowedPublishers=new HashSet<>();
        for(Map<String,Object>x:facts.sources())allowedPublishers.add(String.valueOf(x.get("publisher")));
        for(String label:s.sourceLabels())if(!allowedPublishers.contains(label))problems.add("unknown source label: "+label);
        return problems;
    }

    private static String JsonFactText(FactPackage f){
        StringBuilder b=new StringBuilder(f.headline()).append(' ').append(f.summary());
        for(FactClaim c:f.facts())b.append(' ').append(c.statement());
        return b.toString();
    }

    private static Set<String>numbers(String s){
        Set<String>x=new HashSet<>();
        Matcher m=Pattern.compile("\\b\\d+(?:[.,]\\d+)?%?\\b").matcher(s==null?"":s);
        while(m.find())x.add(m.group());
        return x;
    }
}
