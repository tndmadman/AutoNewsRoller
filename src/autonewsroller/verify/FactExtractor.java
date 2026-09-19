package autonewsroller.verify;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class FactExtractor {
    public List<FactClaim> extract(StoryCluster c){
        Map<String,LinkedHashSet<String>> sources=new LinkedHashMap<>(); Map<String,String> display=new LinkedHashMap<>();
        for(Article a:c.articles){List<String> candidate=new ArrayList<>();candidate.add(a.title());candidate.addAll(Text.sentences(a.description()));for(String s:candidate){s=Text.clean(s);if(s.length()<18||s.length()>360)continue;String k=Text.normalize(s);if(k.isBlank())continue;display.putIfAbsent(k,s);sources.computeIfAbsent(k,z->new LinkedHashSet<>()).add(a.url());}}
        List<FactClaim> out=new ArrayList<>();for(var e:sources.entrySet()){int n=e.getValue().size();double conf=Math.min(0.98,0.55+0.15*n);out.add(new FactClaim(display.get(e.getKey()),List.copyOf(e.getValue()),conf,false));}out.sort(Comparator.comparingInt((FactClaim f)->f.supportingSources().size()).reversed());return out;
    }
}
