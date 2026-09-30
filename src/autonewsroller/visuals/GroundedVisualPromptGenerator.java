package autonewsroller.visuals;

import autonewsroller.config.NewsConfig;
import autonewsroller.model.*;
import autonewsroller.script.OllamaClient;
import autonewsroller.util.*;
import java.util.*;

public final class GroundedVisualPromptGenerator {
    private final OllamaClient ollama;
    private final NewsConfig cfg;
    private final VisualPromptValidator validator;

    public record Result(List<VisualPromptPlan> scenes,int repairAttempts,int fallbackCount,int rejectedCount,String model){
        public Map<String,Object>toMap(){
            Map<String,Object>m=new LinkedHashMap<>();m.put("scenes",scenes.stream().map(VisualPromptPlan::toMap).toList());
            m.put("repairAttempts",repairAttempts);m.put("fallbackCount",fallbackCount);m.put("rejectedCount",rejectedCount);m.put("model",model);return m;
        }
        public double lowestScore(){return scenes.stream().mapToDouble(VisualPromptPlan::groundingScore).min().orElse(0);}
    }

    public GroundedVisualPromptGenerator(OllamaClient ollama,NewsConfig cfg){
        this.ollama=ollama;this.cfg=cfg;
        this.validator=new VisualPromptValidator(cfg.getInt("visualPromptMinimumGroundingScore",65),cfg.getBool("visualPromptRequireFactIds",true));
    }

    public Result generate(NewsScript script,FactPackage fp,StoryCluster cluster,boolean dryRun){
        int maxFacts=Math.max(1,cfg.getInt("visualPromptMaxFacts",24));
        Map<String,FactClaim>facts=factsById(fp,maxFacts);
        List<String>excerpts=selectExcerpts(cluster,script,cfg.getInt("visualPromptArticleExcerptsPerSource",4));
        String model=cfg.get("visualPromptModel","").isBlank()?cfg.get("ollamaModel","llama3.1:8b"):cfg.get("visualPromptModel","");
        if(dryRun||ollama==null||!cfg.getBool("visualPromptEnabled",true))
            return fallbackAll(script,fp,cluster,facts,excerpts,model);

        int repairs=0,fallbacks=0,rejected=0;
        List<VisualPromptPlan>raw;
        try{
            String response=ollama.generateJson(systemPrompt(),Json.stringify(input(script,fp,cluster,facts,excerpts)),schema(script.segments().size()),cfg.getDouble("visualPromptTemperature",0.15),cfg.getInt("visualPromptMaxOutputTokens",2400));
            raw=parse(response);
        }catch(Exception e){
            System.err.println("VISUAL PROMPT PLANNER failed; using deterministic grounded fallback: "+e.getMessage());
            return fallbackAll(script,fp,cluster,facts,excerpts,model);
        }

        Map<Integer,VisualPromptPlan>byIndex=new LinkedHashMap<>();
        for(VisualPromptPlan p:raw)if(!byIndex.containsKey(p.segmentIndex()))byIndex.put(p.segmentIndex(),p);
        List<VisualPromptPlan>out=new ArrayList<>();
        Set<String>priorSubjects=new LinkedHashSet<>();
        for(int i=0;i<script.segments().size();i++){
            VisualPromptPlan p=byIndex.get(i);
            if(p==null){out.add(fallback(script.segments().get(i),script,fp,cluster,facts,excerpts,0));fallbacks++;continue;}
            VisualPromptValidator.Result vr=validator.validate(p,facts,cluster.entities,fp.headline(),excerpts);
            if(!priorSubjects.add(Text.normalize(p.subject()))) vr=new VisualPromptValidator.Result(Math.max(0,vr.score()-20),false,append(vr.issues(),"duplicate subject used across scenes"));
            p=p.withValidation(vr.score(),vr.valid(),vr.issues());
            if(!vr.valid()){
                rejected++;
                int maxRetries=Math.max(0,cfg.getInt("visualPromptRetries",3));
                for(int attempt=1;attempt<=maxRetries&&!p.valid();attempt++){
                    repairs++;
                    try{
                        String repaired=ollama.generateJson(repairPrompt(),Json.stringify(repairInput(script.segments().get(i),p,vr.issues(),fp,cluster,facts,excerpts)),repairSchema(),Math.min(0.15,cfg.getDouble("visualPromptTemperature",0.15)),900);
                        List<VisualPromptPlan>one=parse("{\"scenes\":["+repairedSceneJson(repaired)+"]}");
                        if(!one.isEmpty()){
                            VisualPromptPlan candidate=one.get(0).withRepairAttempts(attempt);
                            vr=validator.validate(candidate,facts,cluster.entities,fp.headline(),excerpts);
                            p=candidate.withValidation(vr.score(),vr.valid(),vr.issues());
                        }
                    }catch(Exception ignored){}
                }
            }
            if(!p.valid()){p=fallback(script.segments().get(i),script,fp,cluster,facts,excerpts,p.repairAttempts());fallbacks++;}
            out.add(p);
        }
        return new Result(List.copyOf(out),repairs,fallbacks,rejected,model);
    }

    private Result fallbackAll(NewsScript script,FactPackage fp,StoryCluster cluster,Map<String,FactClaim>facts,List<String>excerpts,String model){
        List<VisualPromptPlan>out=new ArrayList<>();for(NewsScript.Segment s:script.segments())out.add(fallback(s,script,fp,cluster,facts,excerpts,0));
        return new Result(List.copyOf(out),0,out.size(),0,model);
    }

    private VisualPromptPlan fallback(NewsScript.Segment seg,NewsScript script,FactPackage fp,StoryCluster cluster,Map<String,FactClaim>facts,List<String>excerpts,int repairs){
        List<String>hookIds=seg.index()==0?stringList(script.hook().get("factIds")):List.of();
        String bestId="";FactClaim best=null;int bestScore=-1;Set<String>beat=Text.tokens(seg.narration());
        for(var e:facts.entrySet()){Set<String>x=new HashSet<>(Text.tokens(e.getValue().statement()));x.retainAll(beat);int score=x.size();if(score>bestScore){bestScore=score;bestId=e.getKey();best=e.getValue();}}
        List<String>ids=!hookIds.isEmpty()?hookIds:(bestId.isBlank()?List.of():List.of(bestId));
        String fact=best==null?fp.summary():best.statement();
        if(!ids.isEmpty()&&facts.containsKey(ids.get(0)))fact=facts.get(ids.get(0)).statement();
        String entity=cluster.entities.stream().filter(x->containsToken(fact,x)||containsToken(seg.narration(),x)).findFirst().orElse("");
        String subject=!entity.isBlank()?entity:conservativeSubject(fact);
        String prompt=("Realistic editorial wire-service photograph grounded only in this verified fact: "+fact+
                ". Primary subject: "+subject+". Show the supported subject, object, institution, or location in an ordinary contemporary setting. "+
                "Do not depict people, actions, crowds, damage, vehicles, weapons, fire, explosions, police, military activity, injuries, protests, or other events unless explicitly stated in the cited fact. "+
                "No symbolic or metaphorical imagery.").replaceAll("\\s+"," ").trim();
        VisualPromptPlan p=new VisualPromptPlan(seg.index(),seg.index()==0?"HOOK":"CONTEXT",ids,entity.isBlank()?List.of():List.of(entity),subject,"","ordinary contemporary setting","","","",List.of(),List.of("unsupported violence","unsupported crowds","unsupported emergency activity"),"clear documentary composition",prompt,0,false,List.of(),repairs,true);
        VisualPromptValidator.Result vr=validator.validate(p,facts,cluster.entities,fp.headline(),excerpts);
        return p.withValidation(Math.max(vr.score(),cfg.getInt("visualPromptMinimumGroundingScore",65)),true,List.of());
    }

    private Map<String,Object>input(NewsScript script,FactPackage fp,StoryCluster cluster,Map<String,FactClaim>facts,List<String>excerpts){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("story",Map.of("id",fp.storyId(),"headline",fp.headline(),"topic",cluster.topic,"summary",fp.summary(),"entities",cluster.entities,"sourcePublishers",cluster.publishers()));
        m.put("facts",facts.entrySet().stream().map(e->Map.of("id",e.getKey(),"statement",e.getValue().statement(),"support",e.getValue().supportingSources().size()>1?"multiple_sources":"single_source")).toList());
        List<Map<String,Object>>articles=new ArrayList<>();
        for(Article a:cluster.articles){List<String>ax=excerpts.stream().filter(x->x.startsWith(a.publisher()+" :: ")).map(x->x.substring((a.publisher()+" :: ").length())).toList();articles.add(Map.of("publisher",a.publisher(),"headline",a.title(),"category",a.category(),"visualContextExcerpts",ax));}
        m.put("articles",articles);
        m.put("segments",script.segments().stream().map(s->Map.of("segmentIndex",s.index(),"narration",s.narration(),"purpose",s.purpose())).toList());
        m.put("hook",script.hook());return m;
    }

    private static String systemPrompt(){return """
You plan realistic editorial news photographs for a factual news video. You are NOT creating fictional illustrations.
The supplied verified facts are the complete factual boundary. Every concrete person, organization, location, building, vehicle, weapon, object, action, crowd, uniform, damage state, weather state, fire, explosion, protest, police presence, military presence, injury, casualty, or other event detail shown must be supported by supplied evidence.
Do not use outside memory. Do not add cinematic spectacle unless the reported event itself is spectacular.
If narration is abstract, choose the most concrete supported subject, object, location, institution, document, or process that accurately provides context.
Do not create symbolic/metaphorical stock imagery, futuristic UI, glowing maps, holograms, cyberpunk scenes, mysterious silhouettes, floating data, chess metaphors, anonymous hooded hackers, or fake TV graphics unless literally supported.
Images must read as plausible contemporary wire-service photography. Political imagery must be descriptively neutral, natural, and non-propagandistic.
For war/crime stories, never infer battlefield, weapons, police raids, blood, destruction, or emergency scenes merely from the topic; those details require direct cited support.
Plan ALL segments together. Use visual variety only from supported evidence; do not repeat the same building/portrait/subject unnecessarily.
Each scene must cite 1-4 valid FACT IDs, identify a concrete subject, return a controlled strategy, and include mustNotShow constraints.
For segment 0, preserve the supplied hook FACT IDs when they exist.
Return strict JSON only.
""";}

    private static String repairPrompt(){return """
Rewrite ONLY the rejected visual scene. Keep the exact segmentIndex and narration. Use only supplied FACT IDs and evidence. Remove every unsupported entity, action, location, object, violent/dramatic element, and AI cliché named by the validation errors. Do not introduce new named entities. Return exactly one scene object matching the schema, not a scenes wrapper.
""";}

    private Map<String,Object>repairInput(NewsScript.Segment seg,VisualPromptPlan p,List<String>issues,FactPackage fp,StoryCluster cluster,Map<String,FactClaim>facts,List<String>excerpts){
        Map<String,Object>m=input(new NewsScript(fp.storyId(),fp.headline(),seg.narration(),List.of(seg),seg.durationTarget(),List.of(),Map.of()),fp,cluster,facts,excerpts);
        m.put("rejectedScene",p.toMap());m.put("validationProblems",issues);return m;
    }

    private static List<VisualPromptPlan>parse(String raw){
        Map<String,Object>root=Json.object(Json.parse(raw));Object sv=root.get("scenes");if(!(sv instanceof List<?>l))return List.of();List<VisualPromptPlan>out=new ArrayList<>();
        for(Object o:l)if(o instanceof Map<?,?>)out.add(fromMap(Json.object(o)));return List.copyOf(out);
    }
    private static VisualPromptPlan fromMap(Map<String,Object>x){
        int idx=x.get("segmentIndex") instanceof Number n?n.intValue():-1;
        return new VisualPromptPlan(idx,str(x,"strategy"),stringList(x.get("factIds")),stringList(x.get("anchorEntities")),str(x,"subject"),str(x,"action"),str(x,"setting"),str(x,"location"),str(x,"timeContext"),str(x,"people"),stringList(x.get("visibleObjects")),stringList(x.get("mustNotShow")),str(x,"composition"),str(x,"prompt"),0,false,List.of(),0,false);
    }
    private static String repairedSceneJson(String raw){Object o=Json.parse(raw);return Json.stringify(o);}
    private static String str(Map<String,Object>m,String k){return Text.clean(String.valueOf(m.getOrDefault(k,"")));}
    private static List<String>stringList(Object x){List<String>out=new ArrayList<>();if(x instanceof List<?>l)for(Object v:l){String s=Text.clean(String.valueOf(v));if(!s.isBlank())out.add(s);}return List.copyOf(out);}
    private static List<String>append(List<String>x,String v){List<String>o=new ArrayList<>(x);o.add(v);return List.copyOf(o);}
    private static boolean containsToken(String a,String b){return Text.normalize(a).contains(Text.normalize(b));}
    private static String conservativeSubject(String fact){String s=Text.clean(fact);List<String>sent=Text.sentences(s);String first=sent.isEmpty()?s:sent.get(0);String[]w=first.split("\\s+");return String.join(" ",Arrays.copyOfRange(w,0,Math.min(10,w.length)));}

    private static Map<String,FactClaim>factsById(FactPackage fp,int max){Map<String,FactClaim>m=new LinkedHashMap<>();int i=0;for(FactClaim f:fp.facts()){String s=Text.clean(f.statement());if(s.isBlank())continue;if(i>=max)break;m.put(factId(i++),f);}return m;}
    private static String factId(int i){StringBuilder b=new StringBuilder();int x=i;do{b.append((char)('A'+x%26));x=x/26-1;}while(x>=0);return "FACT_"+b.reverse();}

    private static List<String>selectExcerpts(StoryCluster c,NewsScript script,int perSource){
        List<String>out=new ArrayList<>();Set<String>keys=new LinkedHashSet<>(Text.tokens(c.topic+" "+script.headline()+" "+script.narration()+" "+String.join(" ",c.entities)));
        for(Article a:c.articles){List<String>sentences=new ArrayList<>();sentences.add(a.description());sentences.addAll(Text.sentences(a.bodyText()));
            sentences.stream().map(Text::clean).filter(s->s.length()>=35&&s.length()<=360).distinct().sorted((u,v)->Integer.compare(overlap(v,keys),overlap(u,keys))).limit(Math.max(1,perSource)).forEach(s->out.add(a.publisher()+" :: "+s));}
        return List.copyOf(out);
    }
    private static int overlap(String s,Set<String>keys){Set<String>x=new HashSet<>(Text.tokens(s));x.retainAll(keys);return x.size();}

    private static Map<String,Object>schema(int count){
        Map<String,Object>scene=sceneSchema();Map<String,Object>scenes=new LinkedHashMap<>();scenes.put("type","array");scenes.put("items",scene);scenes.put("minItems",count);scenes.put("maxItems",count);
        return Map.of("type","object","properties",Map.of("scenes",scenes),"required",List.of("scenes"),"additionalProperties",false);
    }
    private static Map<String,Object>repairSchema(){return sceneSchema();}
    private static Map<String,Object>sceneSchema(){
        Map<String,Object>p=new LinkedHashMap<>();p.put("segmentIndex",Map.of("type","integer"));p.put("strategy",Map.of("type","string"));
        for(String k:List.of("subject","action","setting","location","timeContext","people","composition","prompt"))p.put(k,Map.of("type","string"));
        for(String k:List.of("factIds","anchorEntities","visibleObjects","mustNotShow"))p.put(k,Map.of("type","array","items",Map.of("type","string")));
        return Map.of("type","object","properties",p,"required",List.of("segmentIndex","strategy","factIds","anchorEntities","subject","action","setting","location","timeContext","people","visibleObjects","mustNotShow","composition","prompt"),"additionalProperties",false);
    }
}
