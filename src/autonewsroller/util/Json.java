package autonewsroller.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Tiny dependency-free JSON parser/writer for configuration and sidecar files. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text == null ? "" : text);
        Object value = p.value();
        p.ws();
        if (!p.end()) throw new IllegalArgumentException("Trailing JSON content at offset " + p.i);
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?> m)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) m;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?> l)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) l;
    }

    public static Object read(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public static void write(Path path, Object value) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(path, stringify(value) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    public static String stringify(Object value) {
        StringBuilder b = new StringBuilder();
        writeValue(b, value);
        return b.toString();
    }

    private static void writeValue(StringBuilder b, Object value) {
        if (value == null) { b.append("null"); return; }
        if (value instanceof String s) { string(b, s); return; }
        if (value instanceof Boolean || value instanceof Number) { b.append(value); return; }
        if (value instanceof Map<?,?> m) {
            b.append('{'); boolean first=true;
            for (var e : m.entrySet()) {
                if (!first) b.append(','); first=false;
                string(b, String.valueOf(e.getKey())); b.append(':'); writeValue(b, e.getValue());
            }
            b.append('}'); return;
        }
        if (value instanceof Iterable<?> it) {
            b.append('['); boolean first=true;
            for (Object v : it) { if (!first) b.append(','); first=false; writeValue(b, v); }
            b.append(']'); return;
        }
        if (value.getClass().isArray()) {
            b.append('['); int n=java.lang.reflect.Array.getLength(value);
            for(int i=0;i<n;i++){ if(i>0)b.append(','); writeValue(b, java.lang.reflect.Array.get(value,i)); }
            b.append(']'); return;
        }
        string(b, String.valueOf(value));
    }

    private static void string(StringBuilder b, String s) {
        b.append('"');
        for (int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int)c)); else b.append(c); }
            }
        }
        b.append('"');
    }

    private static final class Parser {
        final String s; int i;
        Parser(String s){this.s=s;}
        boolean end(){return i>=s.length();}
        void ws(){while(!end() && Character.isWhitespace(s.charAt(i))) i++;}
        Object value(){
            ws(); if(end()) throw err("Unexpected end of JSON");
            char c=s.charAt(i);
            return switch(c){
                case '{' -> obj(); case '[' -> arr(); case '"' -> str();
                case 't' -> lit("true", Boolean.TRUE); case 'f' -> lit("false", Boolean.FALSE); case 'n' -> lit("null", null);
                default -> { if(c=='-' || Character.isDigit(c)) yield num(); throw err("Unexpected character '"+c+"'"); }
            };
        }
        Map<String,Object> obj(){
            i++; LinkedHashMap<String,Object> m=new LinkedHashMap<>(); ws(); if(peek('}')){i++;return m;}
            while(true){ ws(); if(end()||s.charAt(i)!='"') throw err("Expected object key"); String k=str(); ws(); expect(':'); m.put(k,value()); ws(); if(peek('}')){i++;break;} expect(','); }
            return m;
        }
        List<Object> arr(){
            i++; ArrayList<Object> a=new ArrayList<>(); ws(); if(peek(']')){i++;return a;}
            while(true){ a.add(value()); ws(); if(peek(']')){i++;break;} expect(','); }
            return a;
        }
        String str(){
            expect('"'); StringBuilder b=new StringBuilder();
            while(!end()){
                char c=s.charAt(i++); if(c=='"') return b.toString();
                if(c=='\\'){
                    if(end()) throw err("Bad escape"); char e=s.charAt(i++);
                    switch(e){
                        case '"'->b.append('"'); case '\\'->b.append('\\'); case '/'->b.append('/'); case 'b'->b.append('\b'); case 'f'->b.append('\f'); case 'n'->b.append('\n'); case 'r'->b.append('\r'); case 't'->b.append('\t');
                        case 'u'->{ if(i+4>s.length()) throw err("Bad unicode escape"); b.append((char)Integer.parseInt(s.substring(i,i+4),16)); i+=4; }
                        default->throw err("Bad escape \\"+e);
                    }
                } else b.append(c);
            }
            throw err("Unterminated string");
        }
        Object num(){
            int st=i; if(peek('-'))i++; while(!end()&&Character.isDigit(s.charAt(i)))i++;
            boolean dec=false; if(!end()&&s.charAt(i)=='.'){dec=true;i++;while(!end()&&Character.isDigit(s.charAt(i)))i++;}
            if(!end()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){dec=true;i++;if(!end()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;while(!end()&&Character.isDigit(s.charAt(i)))i++;}
            String n=s.substring(st,i); return dec?Double.valueOf(n):Long.valueOf(n);
        }
        Object lit(String token,Object v){ if(!s.startsWith(token,i))throw err("Expected "+token); i+=token.length(); return v; }
        boolean peek(char c){return !end()&&s.charAt(i)==c;}
        void expect(char c){ws(); if(end()||s.charAt(i)!=c)throw err("Expected '"+c+"'"); i++;}
        IllegalArgumentException err(String msg){return new IllegalArgumentException(msg+" at offset "+i);}
    }
}
