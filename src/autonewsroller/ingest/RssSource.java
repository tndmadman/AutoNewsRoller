package autonewsroller.ingest;

import autonewsroller.config.SourceConfig;
import autonewsroller.model.Article;
import autonewsroller.util.Hashing;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.*;
import java.util.*;

public final class RssSource implements NewsSource {
    private final SourceConfig cfg; private final HttpClient client; private final int timeoutSeconds; private final String userAgent; private final FeedCache cache;
    public RssSource(SourceConfig cfg,int timeoutSeconds,String userAgent,Path cachePath){this.cfg=cfg;this.timeoutSeconds=Math.max(3,timeoutSeconds);this.userAgent=userAgent;this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(this.timeoutSeconds)).build();this.cache=new FeedCache(cachePath);}
    @Override public List<Article> discover() throws Exception {
        Map<String,String> c=cache.get(cfg.url()); HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(cfg.url())).timeout(Duration.ofSeconds(timeoutSeconds)).header("User-Agent",userAgent).header("Accept","application/rss+xml,application/atom+xml,application/xml,text/xml");
        if(c.containsKey("etag"))b.header("If-None-Match",c.get("etag")); if(c.containsKey("lastModified"))b.header("If-Modified-Since",c.get("lastModified"));
        HttpResponse<byte[]> r=null; Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            try{
                r=client.send(b.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                if(r.statusCode()==304)return List.of();
                if(r.statusCode()>=200&&r.statusCode()<300)break;
                if(r.statusCode()!=429&&r.statusCode()<500)throw new IOException("Feed HTTP "+r.statusCode()+" for "+cfg.url());
                last=new IOException("Feed HTTP "+r.statusCode()+" for "+cfg.url()); r=null;
            }catch(IOException e){last=e;r=null;}
            if(attempt<3)Thread.sleep(250L*(1L<<(attempt-1)));
        }
        if(r==null)throw last==null?new IOException("Feed request failed for "+cfg.url()):last;
        cache.put(cfg.url(),r.headers().firstValue("ETag").orElse(null),r.headers().firstValue("Last-Modified").orElse(null));
        return parse(new ByteArrayInputStream(r.body()),cfg,Instant.now());
    }
    public static List<Article> parse(InputStream input,SourceConfig cfg,Instant discoveredAt) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true); try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){} try{f.setFeature("http://xml.org/sax/features/external-general-entities",false);}catch(Exception ignored){} try{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){};
        var builder=f.newDocumentBuilder(); builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}}); Document d=builder.parse(input); d.getDocumentElement().normalize(); NodeList entries=d.getElementsByTagName("item"); boolean atom=false; if(entries.getLength()==0){entries=d.getElementsByTagNameNS("*","entry");atom=true;}
        List<Article> out=new ArrayList<>();
        for(int i=0;i<entries.getLength();i++){Element e=(Element)entries.item(i);String title=text(e,"title");String url=atom?atomLink(e):text(e,"link");String desc=firstNonBlank(text(e,"description"),textNs(e,"summary"),textNs(e,"content"));String author=firstNonBlank(text(e,"author"),textNs(e,"creator"));String ds=firstNonBlank(text(e,"pubDate"),textNs(e,"published"),textNs(e,"updated"));Instant published=parseDate(ds,discoveredAt); if(title.isBlank()||url.isBlank())continue;String canon=canonical(url);String id=Hashing.sha256(cfg.name()+"|"+canon);out.add(new Article(id,cfg.name(),title,url,canon,published,discoveredAt,author,cfg.category(),strip(desc),"","en",cfg.trustTier(),cfg.authoritativePrimary()));}
        return out;
    }
    private static String canonical(String u){try{URI x=URI.create(u.trim());return new URI(x.getScheme(),x.getAuthority(),x.getPath(),x.getQuery(),null).toString();}catch(Exception e){return u.trim();}}
    private static String strip(String x){return x==null?"":x.replaceAll("<[^>]+>"," ").replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'").replaceAll("\\s+"," ").trim();}
    private static String text(Element e,String tag){NodeList n=e.getElementsByTagName(tag);return n.getLength()==0?"":n.item(0).getTextContent().trim();}
    private static String textNs(Element e,String local){NodeList n=e.getElementsByTagNameNS("*",local);return n.getLength()==0?"":n.item(0).getTextContent().trim();}
    private static String atomLink(Element e){NodeList n=e.getElementsByTagNameNS("*","link");for(int i=0;i<n.getLength();i++){Element x=(Element)n.item(i);String rel=x.getAttribute("rel"),href=x.getAttribute("href");if(!href.isBlank()&&(rel.isBlank()||"alternate".equals(rel)))return href;}return "";}
    private static String firstNonBlank(String...v){for(String x:v)if(x!=null&&!x.isBlank())return x;return "";}
    private static Instant parseDate(String s,Instant fallback){if(s==null||s.isBlank())return fallback;List<DateTimeFormatter> fs=List.of(DateTimeFormatter.RFC_1123_DATE_TIME,DateTimeFormatter.ISO_OFFSET_DATE_TIME,DateTimeFormatter.ISO_INSTANT);for(DateTimeFormatter f:fs)try{return ZonedDateTime.parse(s,f).toInstant();}catch(Exception ignored){try{return OffsetDateTime.parse(s,f).toInstant();}catch(Exception ignored2){try{return Instant.parse(s);}catch(Exception ignored3){}}}return fallback;}
}
