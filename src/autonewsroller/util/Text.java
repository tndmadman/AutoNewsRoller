package autonewsroller.util;

import java.util.*;
import java.util.regex.*;

public final class Text {
    private static final Set<String> STOP=Set.of("the","a","an","and","or","but","to","of","in","on","for","with","at","by","from","as","is","are","was","were","be","this","that","it","its","new","says","say","after","over","about");
    private Text(){}
    public static String clean(String s){return s==null?"":s.replaceAll("\\s+"," ").trim();}
    public static String normalize(String s){return clean(s).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+"," ").trim();}
    public static Set<String> tokens(String s){ LinkedHashSet<String> r=new LinkedHashSet<>(); for(String t:normalize(s).split(" ")) if(t.length()>1&&!STOP.contains(t))r.add(t); return r; }
    public static Set<String> entities(String s){
        LinkedHashSet<String> r=new LinkedHashSet<>(); if(s==null)return r;
        Matcher m=Pattern.compile("\\b(?:[A-Z][A-Za-z0-9&.-]{2,}|[A-Z]{2,}|[A-Za-z]+\\d+[A-Za-z0-9.-]*)\\b").matcher(s);
        while(m.find()){String v=m.group(); if(!Set.of("The","This","That","After","Before","New").contains(v))r.add(v);}
        return r;
    }
    public static List<String> sentences(String s){
        String x=clean(s); if(x.isBlank())return List.of();
        ArrayList<String> out=new ArrayList<>(); for(String p:x.split("(?<=[.!?])\\s+")){p=clean(p);if(!p.isBlank())out.add(p);} return out;
    }
    public static int words(String s){String x=clean(s);return x.isBlank()?0:x.split("\\s+").length;}
}
