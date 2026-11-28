package autonewsroller.visuals;

import autonewsroller.model.VisualPlan;
import autonewsroller.model.VisualPromptPlan;
import autonewsroller.safety.ContentSafetyPolicy;
import java.util.*;

/** STANDARD-only full primary scene coverage; sensitive topics use non-depictive artwork. */
public final class StandardVisualCoverage {
    private StandardVisualCoverage(){}
    private static final Set<String> INFORMATION=Set.of("STATISTIC","QUOTE","TIMELINE","FACT_HIGHLIGHT","DOCUMENT","CONTEXT");
    private static final Set<String> CONTEXT=Set.of("LOCATION","OBJECT","INSTITUTION","PROCESS","DOCUMENT","CONTEXT");
    private static boolean primary(VisualPlan.Item item){
        return !Boolean.TRUE.equals(item.data().get("supplemental"))
                &&!Set.of("SOURCE_CARD","POLITICAL_CONTEXT","HEADLINE_CARD").contains(item.type());
    }
    public static VisualPlan annotate(VisualPlan plan,List<VisualPromptPlan> prompts,boolean evidenceOnly){
        return annotate(plan,prompts,evidenceOnly,false);
    }
    public static VisualPlan annotate(VisualPlan plan,List<VisualPromptPlan> prompts,boolean evidenceOnly,boolean sensitiveStory){
        Map<Integer,VisualPromptPlan> bySegment=new HashMap<>();
        for(VisualPromptPlan prompt:prompts)bySegment.put(prompt.segmentIndex(),prompt);
        List<VisualPlan.Item> items=new ArrayList<>();
        for(VisualPlan.Item item:plan.items()){
            Map<String,Object> data=new LinkedHashMap<>(item.data());
            String strategy="VERIFIED_EVIDENCE_GRAPHIC",reason;
            String scenePrompt=item.prompt();
            Object segment=data.get("segmentIndex");
            VisualPromptPlan grounded=segment instanceof Number n?bySegment.get(n.intValue()):null;
            if("SOURCE_CARD".equals(item.type())){
                strategy="SOURCE_ATTRIBUTION";reason="Standalone source attribution";
            }else if(!primary(item))reason="Supplemental or reserved evidence graphic";
            else if(evidenceOnly)reason="Existing story-level evidence-only policy";
            else if(sensitiveStory||ContentSafetyPolicy.sensitive(item.body()+" "+item.prompt()))
                reason="Sensitive reporting: non-depictive editorial artwork only";
            else if(grounded==null||!grounded.valid()||grounded.factIds().isEmpty()||grounded.prompt().isBlank())
                reason="No validated source-grounded visual prompt";
            else{
                try{
                    ContentSafetyPolicy.validatePositive(grounded.prompt());
                    if(ContentSafetyPolicy.sensitive(grounded.prompt()))throw new IllegalStateException("Sensitive visual");
                    if(!grounded.prompt().equals(item.prompt()))throw new IllegalStateException("Visual prompt identity mismatch");
                    // Test every configured retry profile before scheduling this scene.
                    for(int attempt=0;attempt<=4;attempt++)ComfyPromptProfiles.positive(item,false,attempt);
                    strategy=INFORMATION.contains(item.type())?"ILLUSTRATED_INFORMATION_GRAPHIC":
                            CONTEXT.contains(grounded.strategy())?"CONTEXTUAL_ILLUSTRATION":"EDITORIAL_ILLUSTRATION";
                    reason="Validated visual prompt citing verified facts";
                }catch(IllegalStateException rejected){reason="Positive prompt rejected by existing policy";}
            }
            if(primary(item)&&!evidenceOnly&&"VERIFIED_EVIDENCE_GRAPHIC".equals(strategy)){
                strategy=INFORMATION.contains(item.type())?"ILLUSTRATED_INFORMATION_GRAPHIC":"CONTEXTUAL_ILLUSTRATION";
                data.put("standardNeutralArtwork",true);
                data.put("visualStrategy","NEUTRAL_EDITORIAL_BACKGROUND");
                // No event names, article wording, people, or unverified objects enter diffusion conditioning.
                scenePrompt=NeutralEditorialArtwork.base(item.index());
                data.put("anchorEntities",List.of());
                data.put("mustNotShow",NeutralEditorialArtwork.exclusions());
                data.put("neutralArtworkGrounding","generic non-depictive design; factual content remains in Java overlays");
            }
            data.put("standardVisualStrategy",strategy);
            data.put("standardVisualReason",reason);
            items.add(new VisualPlan.Item(item.index(),item.type(),item.title(),item.body(),item.duration(),scenePrompt,data));
        }
        return new VisualPlan(plan.storyId(),List.copyOf(items));
    }
    public static boolean illustratedInformation(VisualPlan.Item item){
        return "ILLUSTRATED_INFORMATION_GRAPHIC".equals(item.data().get("standardVisualStrategy"));
    }
    public static int primaryCount(VisualPlan plan){return (int)plan.items().stream().filter(StandardVisualCoverage::primary).count();}
    public static List<Integer> eligible(VisualPlan plan){
        List<Integer> result=new ArrayList<>();
        for(int i=0;i<plan.items().size();i++){
            VisualPlan.Item item=plan.items().get(i);
            if(item.index()!=i)throw new IllegalStateException("VISUAL_ASSETS_MISSING: scene index mismatch");
            if(primary(item)&&Set.of("EDITORIAL_ILLUSTRATION","CONTEXTUAL_ILLUSTRATION","ILLUSTRATED_INFORMATION_GRAPHIC")
                    .contains(String.valueOf(item.data().get("standardVisualStrategy"))))result.add(i);
        }
        return List.copyOf(result);
    }
    /** STANDARD always generates every primary scene; legacy image budget is not a cap. */
    public static List<Integer> schedule(VisualPlan plan,int budget){
        return eligible(plan);
    }
    public static void verify(VisualPlan plan,List<Integer> scheduled,List<Integer> required,List<Map<String,Object>> sources){
        if(!scheduled.equals(eligible(plan))||!scheduled.equals(required)||sources.size()!=plan.items().size())
            throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: STANDARD schedule or scene count mismatch");
        Set<Integer> actual=new LinkedHashSet<>();
        Set<String> originals=new HashSet<>();
        for(int i=0;i<sources.size();i++){
            Map<String,Object> source=sources.get(i);
            if("comfyui-generated-ai-illustration".equals(source.get("type"))){
                actual.add(i);
                VisualPlan.Item item=plan.items().get(i);
                if(Boolean.TRUE.equals(item.data().get("standardNeutralArtwork")))
                    NeutralEditorialArtwork.requireSource(item,source);
                if(!originals.add(String.valueOf(source.get("generatedPath"))))
                    throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: duplicate generated asset");
            }
        }
        if(!actual.equals(new LinkedHashSet<>(scheduled)))
            throw new IllegalStateException("VISUAL_IMAGE_REQUIRED: STANDARD actual sources differ from required schedule");
    }
    /** Counts verified renderer inputs, not proof of visual identity in decoded MP4 frames. */
    public static Map<String,Object> report(VisualPlan plan,List<Integer> scheduled,int budget,List<Map<String,Object>> sources,
            Map<String,Object> sync){
        verify(plan,scheduled,scheduled,sources);
        Object raw=sync.get("sceneFrames");
        if(!(raw instanceof List<?> frames)||frames.size()!=sources.size())
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: renderer scene timeline missing");
        long total=0,generated=0;
        for(int i=0;i<frames.size();i++){
            if(!(frames.get(i) instanceof Number n)||n.longValue()<1)
                throw new IllegalStateException("VISUAL_ASSETS_MISSING: invalid renderer scene frame count");
            total+=n.longValue();
            if(scheduled.contains(i))generated+=n.longValue();
        }
        if(!(sync.get("videoFramesAfterRepair") instanceof Number actual)||actual.longValue()!=total)
            throw new IllegalStateException("VISUAL_ASSETS_MISSING: encoded frame count disagrees with renderer timeline");
        int eligible=eligible(plan).size();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("totalScenes",sources.size());result.put("primaryScenes",primaryCount(plan));
        result.put("illustrationEligibleScenes",eligible);result.put("legacyConfiguredImageCount",budget);
        result.put("requestedGeneratedImages",eligible);
        result.put("scheduledGeneratedImages",scheduled.size());result.put("acceptedGeneratedImages",scheduled.size());
        result.put("renderInputGeneratedImages",scheduled.size());
        result.put("eligibleSceneCoveragePercent",eligible==0?0.0:100.0*scheduled.size()/eligible);
        result.put("primarySceneCoveragePercent",primaryCount(plan)==0?0.0:100.0*scheduled.size()/primaryCount(plan));
        result.put("renderTimelineGeneratedFrames",generated);result.put("renderTimelineTotalFrames",total);
        result.put("renderTimelineGeneratedRuntimePercent",100.0*generated/total);
        result.put("proceduralScenes",sources.size()-scheduled.size());
        result.put("neutralArtworkScenes",plan.items().stream().filter(i->Boolean.TRUE.equals(i.data().get("standardNeutralArtwork"))).count());
        result.put("sceneDecisions",plan.items().stream().map(i->Map.of("scene",i.index(),
                "strategy",String.valueOf(i.data().get("standardVisualStrategy")),
                "reason",String.valueOf(i.data().get("standardVisualReason")),
                "scheduled",scheduled.contains(i.index()))).toList());
        return result;
    }
}
