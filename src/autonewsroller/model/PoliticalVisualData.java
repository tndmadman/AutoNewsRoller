package autonewsroller.model;

import java.util.*;

public record PoliticalVisualData(
        boolean politicalCandidate,
        boolean analysisAvailable,
        double politicalRelevance,
        String storyClassification,
        double storyConfidence,
        double storyLeftWeight,
        double storyCenterWeight,
        double storyRightWeight,
        String storySummary,
        String analysisStatus,
        String analysisError,
        Map<String,Object> sourceMix
){
    public PoliticalVisualData{
        storyClassification=normalizeClassification(storyClassification);
        politicalRelevance=clamp(politicalRelevance);
        storyConfidence=clamp(storyConfidence);
        double[] normalized=normalizeWeights(storyLeftWeight,storyCenterWeight,storyRightWeight,analysisAvailable);
        storyLeftWeight=normalized[0];
        storyCenterWeight=normalized[1];
        storyRightWeight=normalized[2];
        storySummary=safe(storySummary);
        analysisStatus=safe(analysisStatus);
        analysisError=safe(analysisError);
        sourceMix=sourceMix==null?Map.of():Collections.unmodifiableMap(new LinkedHashMap<>(sourceMix));
    }

    public static PoliticalVisualData from(boolean politicalCandidate,Map<String,Object>sourceMix,Map<String,Object>analysis,String status,String error){
        Map<String,Object>a=analysis==null?Map.of():analysis;
        boolean available=a.containsKey("overallClassification")&&a.get("overallWeights") instanceof Map<?,?>;
        Map<String,Object>w=available?object(a.get("overallWeights")):Map.of();
        return new PoliticalVisualData(
                politicalCandidate,
                available,
                available?number(a.get("politicalRelevance")):0,
                available?String.valueOf(a.get("overallClassification")):"uncertain",
                available?number(a.get("overallConfidence")):0,
                available?number(w.get("left")):0,
                available?number(w.get("center")):0,
                available?number(w.get("right")):0,
                available?String.valueOf(a.getOrDefault("summary","")):"",
                status==null||status.isBlank()?(available?"COMPLETE":politicalCandidate?"NOT_ANALYZED":"NOT_POLITICAL"):status,
                error,
                sourceMix
        );
    }

    public static PoliticalVisualData fromMap(Map<String,Object>m){
        if(m==null)return empty();
        return new PoliticalVisualData(
                bool(m.get("politicalCandidate")),
                bool(m.get("analysisAvailable")),
                number(m.get("politicalRelevance")),
                String.valueOf(m.getOrDefault("storyClassification","uncertain")),
                number(m.get("storyConfidence")),
                number(m.get("storyLeftWeight")),
                number(m.get("storyCenterWeight")),
                number(m.get("storyRightWeight")),
                String.valueOf(m.getOrDefault("storySummary","")),
                String.valueOf(m.getOrDefault("analysisStatus","")),
                String.valueOf(m.getOrDefault("analysisError","")),
                object(m.get("sourceMix"))
        );
    }

    public static PoliticalVisualData empty(){
        return new PoliticalVisualData(false,false,0,"not_political",0,0,0,0,"","NOT_POLITICAL","",Map.of());
    }

    public boolean shouldRender(double minimumRelevance,boolean showUncertain){
        if(!politicalCandidate||!analysisAvailable)return false;
        if(politicalRelevance<clamp(minimumRelevance))return false;
        if("not_political".equals(storyClassification))return false;
        return showUncertain||!"uncertain".equals(storyClassification);
    }

    public int sourceLeft(){return integer(sourceMix.get("left"));}
    public int sourceCenter(){return integer(sourceMix.get("center"));}
    public int sourceRight(){return integer(sourceMix.get("right"));}
    public int sourceUnknown(){return integer(sourceMix.get("unknown"));}
    public int ratedSourceCount(){return sourceLeft()+sourceCenter()+sourceRight();}
    public int totalSourceCount(){return ratedSourceCount()+sourceUnknown();}
    public String sourceProvider(){return String.valueOf(sourceMix.getOrDefault("provider",""));}
    public String sourceAsOf(){return String.valueOf(sourceMix.getOrDefault("asOf",""));}

    public int[] storyPercentages(){
        if(!analysisAvailable)return new int[]{0,0,0};
        double[] w=normalizeWeights(storyLeftWeight,storyCenterWeight,storyRightWeight,true);
        double[] raw={w[0]*100.0,w[1]*100.0,w[2]*100.0};
        int[] out={(int)Math.floor(raw[0]),(int)Math.floor(raw[1]),(int)Math.floor(raw[2])};
        int remaining=100-out[0]-out[1]-out[2];
        Integer[] order={0,1,2};
        Arrays.sort(order,(a,b)->Double.compare(raw[b]-Math.floor(raw[b]),raw[a]-Math.floor(raw[a])));
        for(int i=0;i<remaining;i++)out[order[i%3]]++;
        return out;
    }

    public Map<String,Object> toMap(){
        Map<String,Object>m=new LinkedHashMap<>();
        m.put("politicalCandidate",politicalCandidate);
        m.put("analysisAvailable",analysisAvailable);
        m.put("politicalRelevance",politicalRelevance);
        m.put("storyClassification",storyClassification);
        m.put("storyConfidence",storyConfidence);
        m.put("storyLeftWeight",storyLeftWeight);
        m.put("storyCenterWeight",storyCenterWeight);
        m.put("storyRightWeight",storyRightWeight);
        m.put("storySummary",storySummary);
        m.put("analysisStatus",analysisStatus);
        m.put("analysisError",analysisError);
        m.put("sourceMix",sourceMix);
        int[] p=storyPercentages();
        m.put("displayPercentages",Map.of("left",p[0],"center",p[1],"right",p[2]));
        return m;
    }

    private static double[] normalizeWeights(double left,double center,double right,boolean available){
        if(!available)return new double[]{0,0,0};
        left=clamp(left);center=clamp(center);right=clamp(right);
        double sum=left+center+right;
        if(sum<=0)return new double[]{0,0,0};
        return new double[]{left/sum,center/sum,right/sum};
    }

    private static String normalizeClassification(String x){
        if(x==null)return "uncertain";
        String v=x.trim().toLowerCase(Locale.ROOT).replace('-','_').replace(' ','_');
        return Set.of("left","center","right","mixed","uncertain","not_political").contains(v)?v:"uncertain";
    }
    private static Map<String,Object>object(Object raw){
        if(!(raw instanceof Map<?,?>m))return Map.of();
        Map<String,Object>out=new LinkedHashMap<>();
        for(var e:m.entrySet())out.put(String.valueOf(e.getKey()),e.getValue());
        return out;
    }
    private static double number(Object x){return x instanceof Number n?n.doubleValue():parseDouble(x);}
    private static double parseDouble(Object x){try{return Double.parseDouble(String.valueOf(x));}catch(Exception e){return 0;}}
    private static int integer(Object x){return x instanceof Number n?n.intValue():(int)Math.round(parseDouble(x));}
    private static boolean bool(Object x){return x instanceof Boolean b?b:Boolean.parseBoolean(String.valueOf(x));}
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    private static String safe(String x){return x==null?"":x.replaceAll("\\s+"," ").trim();}
}
