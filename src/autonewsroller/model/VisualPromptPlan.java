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
    public static VisualPromptPlan fromMap(Map<String,Object>m){
        return new VisualPromptPlan(integer(m.get("segmentIndex")),str(m,"strategy"),
                strings(m.get("factIds")),strings(m.get("anchorEntities")),str(m,"subject"),
                str(m,"action"),str(m,"setting"),str(m,"location"),str(m,"timeContext"),
                str(m,"people"),strings(m.get("visibleObjects")),strings(m.get("mustNotShow")),
                str(m,"composition"),str(m,"prompt"),number(m.get("groundingScore")),
                Boolean.TRUE.equals(m.get("valid")),strings(m.get("validationIssues")),
                integer(m.get("repairAttempts")),Boolean.TRUE.equals(m.get("fallback")));
    }
    private static String str(Map<String,Object>m,String key){return String.valueOf(m.getOrDefault(key,""));}
    private static int integer(Object value){return value instanceof Number n?n.intValue():0;}
    private static double number(Object value){return value instanceof Number n?n.doubleValue():0;}
    private static List<String>strings(Object raw){
        if(!(raw instanceof List<?>list))return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    private static List<String>copy(List<String>x){return x==null?List.of():List.copyOf(x.stream().filter(Objects::nonNull).map(String::trim).filter(s->!s.isBlank()).toList());}
}
