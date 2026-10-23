package autonewsroller.script;

import autonewsroller.util.Text;
import java.util.*;

/**
 * Keeps all six editorial beats while reducing only by COMPLETE original sentences.
 * No new facts or paraphrases can be introduced by this deterministic fallback.
 */
public final class PoliticalFramingLengthEditor {
    private PoliticalFramingLengthEditor(){}

    public record Edit(List<String> paragraphs,int originalWords,int resultingWords,boolean changed,String method){}

    /**
     * Returns the original when no safe sentence-level edit fits the narration budget.
     * The caller must then request an explicit model rewrite and revalidate.
     */
    public static Edit compact(List<String> original,String headline,String teaser,int targetSeconds){
        if(original==null||original.size()!=6)throw new IllegalArgumentException("Six editorial paragraphs required");
        List<String> unchanged=List.copyOf(original);
        int prefix=Text.words(headline)+Text.words(teaser);
        int originalWords=prefix+original.stream().mapToInt(Text::words).sum();
        int min=ScriptValidator.minWords(targetSeconds);
        int max=ScriptValidator.maxWords(targetSeconds);
        if(originalWords>=min&&originalWords<=max)
            return new Edit(unchanged,originalWords,originalWords,false,"already-in-range");
        if(originalWords<min)
            return new Edit(unchanged,originalWords,originalWords,false,"too-short-requires-rewrite");

        List<List<String>>sentences=new ArrayList<>();
        List<List<String>>selected=new ArrayList<>();
        int[]positions=new int[6];
        int[]sizes=new int[6];
        int sum=prefix;
        for(int i=0;i<6;i++){
            List<String>parts=Text.sentences(original.get(i));
            if(parts.isEmpty())return new Edit(unchanged,originalWords,originalWords,false,"empty-paragraph");
            sentences.add(parts);
            selected.add(new ArrayList<>());
            String first=parts.get(0);
            selected.get(i).add(first);
            positions[i]=1;
            sizes[i]=Text.words(first);
            sum+=sizes[i];
        }
        if(sum>max)return new Edit(unchanged,originalWords,originalWords,false,"lead-sentences-too-long");

        // Preserve order inside each paragraph; prioritize the five analytical beats.
        // Source-event recap is capped when possible to protect >=65% analytical share.
        boolean moved;
        do{
            moved=false;
            int chosen=-1,chosenWords=Integer.MAX_VALUE;
            for(int i=0;i<6;i++){
                if(positions[i]>=sentences.get(i).size())continue;
                int words=Text.words(sentences.get(i).get(positions[i]));
                if(sum+words>max)continue;
                if(i==0&&sizes[i]+words>Math.max(22,(max-prefix)/6))continue;
                // Add the next shortest complete sentence to spread time across beats.
                if(words<chosenWords||(words==chosenWords&&i>chosen)){
                    chosen=i;chosenWords=words;
                }
            }
            if(chosen>=0){
                String next=sentences.get(chosen).get(positions[chosen]++);
                selected.get(chosen).add(next);
                sizes[chosen]+=chosenWords;
                sum+=chosenWords;
                moved=true;
            }
        }while(moved&&sum<Math.min(max,min+18));

        if(sum<min)return new Edit(unchanged,originalWords,originalWords,false,"cannot-meet-min-with-whole-sentences");
        int analysisWords=0;
        for(int i=1;i<6;i++)analysisWords+=sizes[i];
        if((double)analysisWords/Math.max(1,sum)<0.65)
            return new Edit(unchanged,originalWords,originalWords,false,"insufficient-analysis-share");

        List<String>edited=new ArrayList<>();
        for(List<String>parts:selected)edited.add(String.join(" ",parts));
        return new Edit(List.copyOf(edited),originalWords,sum,true,"extractive-complete-sentences");
    }
}
