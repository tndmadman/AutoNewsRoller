package autonewsroller.model;

import java.util.*;

public record VisualPromptPlan(
        int segmentIndex,
        String strategy,
        List<String> factIds,
        List<String> anchorEntities,
        String subject,
        String action,
        String setting,
        String location,
        String timeContext,
        String people,
        List<String> visibleObjects,
        List<String> mustNotShow,
        String composition,
        String prompt,
        double groundingScore,
        boolean valid,
        List<String> validationIssues,
        int repairAttempts,
        boolean fallback
) {
    public VisualPromptPlan {
        strategy=clean(strategy);
        factIds=copy(factIds);
        anchorEntities=copy(anchorEntities);
        subject=clean(subject);
        action=clean(action);
        setting=clean(setting);
        location=clean(location);
        timeContext=clean(timeContext);
        people=clean(people);
        visibleObjects=copy(visibleObjects);
        mustNotShow=copy(mustNotShow);
        composition=clean(composition);
        prompt=clean(prompt);
        validationIssues=copy(validationIssues);
    }
    public VisualPromptPlan withValidation(double score,boolean ok,List<String>issues){
        return new VisualPromptPlan(segmentIndex,strategy,factIds,anchorEntities,subject,action,setting,location,timeContext,people,visibleObjects,mustNotShow,composition,prompt,score,ok,issues,repairAttempts,fallback);
    }
    public VisualPromptPlan withRepairAttempts(int attempts){
        return new VisualPromptPlan(segmentIndex,strategy,factIds,anchorEntities,subject,action,setting,location,timeContext,people,visibleObjects,mustNotShow,composition,prompt,groundingScore,valid,validationIssues,attempts,fallback);
    }
    public VisualPromptPlan withMustNotShow(List<String> excluded){
        return new VisualPromptPlan(segmentIndex,strategy,factIds,anchorEntities,subject,action,setting,location,timeContext,people,visibleObjects,excluded,composition,prompt,groundingScore,valid,validationIssues,repairAttempts,fallback);
    }
    public Map<String,Object> toMap(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("segmentIndex",segmentIndex);m.put("strategy",strategy);m.put("factIds",factIds);m.put("anchorEntities",anchorEntities);
        m.put("subject",subject);m.put("action",action);m.put("setting",setting);m.put("location",location);m.put("timeContext",timeContext);
        m.put("people",people);m.put("visibleObjects",visibleObjects);m.put("mustNotShow",mustNotShow);m.put("composition",composition);m.put("prompt",prompt);
        m.put("groundingScore",Math.round(groundingScore*100.0)/100.0);m.put("valid",valid);m.put("validationIssues",validationIssues);
        m.put("repairAttempts",repairAttempts);m.put("fallback",fallback);return m;
    }
    private static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    private static List<String>copy(List<String>x){return x==null?List.of():List.copyOf(x.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isBlank()).toList());}
}
