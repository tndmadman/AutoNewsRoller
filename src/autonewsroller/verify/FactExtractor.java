package autonewsroller.verify;

import autonewsroller.model.*;
import autonewsroller.util.Text;
import java.util.*;

public final class FactExtractor {
    private static final int BODY_SENTENCES_PER_ARTICLE=12;
    private static final int MAX_FACTS=48;

    public List<FactClaim> extract(StoryCluster c){
        Map<String,LinkedHashSet<String>> sources=new LinkedHashMap<>();
        Map<String,String> display=new LinkedHashMap<>();

        for(Article a:c.articles){
            List<String> candidate=new ArrayList<>();
            candidate.add(a.title());
            candidate.addAll(Text.sentences(a.description()));

            int bodyAdded=0;
            for(String sentence:Text.sentences(a.bodyText())){
                if(bodyAdded>=BODY_SENTENCES_PER_ARTICLE)break;
                String clean=Text.clean(sentence);
                if(!usableBodySentence(clean))continue;
                candidate.add(clean);
                bodyAdded++;
            }

            for(String raw:candidate){
                String s=Text.clean(raw);
                if(s.length()<18||s.length()>360)continue;
                String k=Text.normalize(s);
                if(k.isBlank())continue;
                display.putIfAbsent(k,s);
                sources.computeIfAbsent(k,z->new LinkedHashSet<>()).add(a.url());
            }
        }

        List<FactClaim> out=new ArrayList<>();
        for(var e:sources.entrySet()){
            int n=e.getValue().size();
            double conf=Math.min(0.98,0.55+0.15*n);
            out.add(new FactClaim(display.get(e.getKey()),List.copyOf(e.getValue()),conf,false));
        }
        out.sort(
                Comparator.comparingInt((FactClaim f)->f.supportingSources().size()).reversed()
                        .thenComparingInt(f->f.statement().length())
        );
        if(out.size()>MAX_FACTS)out=new ArrayList<>(out.subList(0,MAX_FACTS));
        return List.copyOf(out);
    }

    private static boolean usableBodySentence(String s){
        if(s==null||s.length()<35||s.length()>360)return false;
        String x=s.toLowerCase(Locale.ROOT);
        return !x.contains("cookie")&&!x.contains("privacy policy")&&!x.contains("sign up")&&
                !x.contains("subscribe")&&!x.contains("advertisement")&&!x.contains("all rights reserved")&&
                !x.contains("click here")&&!x.contains("newsletter");
    }
}
