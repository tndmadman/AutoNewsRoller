package autonewsroller.ingest;

import autonewsroller.config.SourceConfig;
import autonewsroller.model.Article;
import autonewsroller.util.Hashing;
import org.w3c.dom.*;
import javax.net.ssl.SSLHandshakeException;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class RssSource implements NewsSource {
    private static final String ACCEPT="application/rss+xml,application/atom+xml,application/xml,text/xml";
    private final SourceConfig cfg;
    private final HttpClient client;
    private final int timeoutSeconds;
    private final String userAgent;
    private final FeedCache cache;

    public RssSource(SourceConfig cfg,int timeoutSeconds,String userAgent,Path cachePath){
        this.cfg=cfg;
        this.timeoutSeconds=Math.max(3,timeoutSeconds);
        this.userAgent=userAgent;
        this.client=HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cache=new FeedCache(cachePath);
    }

    @Override public List<Article> discover() throws Exception {
        Map<String,String> validators=cache.validators(cfg.url());
        byte[] cachedBody=cache.body(cfg.url());
        Exception last=null;

        for(int attempt=1;attempt<=3;attempt++){
            try{
                HttpResponse<byte[]> r=client.send(request(validators,true),HttpResponse.BodyHandlers.ofByteArray());

                if(r.statusCode()==304){
                    if(cachedBody!=null&&cachedBody.length>0)
                        return parse(new ByteArrayInputStream(cachedBody),cfg,Instant.now());

                    // A validator without a cached representation is incomplete cache state.
                    // Re-fetch once without validators so a 304 can never turn into "zero news".
                    r=client.send(request(Map.of(),false),HttpResponse.BodyHandlers.ofByteArray());
                }

                if(r.statusCode()>=200&&r.statusCode()<300){
                    byte[] body=r.body();
                    if(body==null||body.length==0)throw new IOException("Feed returned an empty body for "+cfg.url());
                    cache.put(cfg.url(),r.headers().firstValue("ETag").orElse(null),r.headers().firstValue("Last-Modified").orElse(null),body);
                    return parse(new ByteArrayInputStream(body),cfg,Instant.now());
                }

                if(r.statusCode()!=429&&r.statusCode()<500)
                    throw new IOException("Feed HTTP "+r.statusCode()+" for "+cfg.url());

                last=new IOException("Feed HTTP "+r.statusCode()+" for "+cfg.url());
            }catch(IOException e){
                last=e;
                if(isTlsTrustFailure(e)){
                    try{
                        byte[] body=fetchWithCurl();
                        cache.put(cfg.url(),validators.get("etag"),validators.get("lastModified"),body);
                        return parse(new ByteArrayInputStream(body),cfg,Instant.now());
                    }catch(Exception fallback){
                        IOException combined=new IOException("Java TLS trust failed and curl fallback failed for "+cfg.url()+": "+fallback.getMessage(),e);
                        combined.addSuppressed(fallback);
                        last=combined;
                        break;
                    }
                }
            }

            if(attempt<3)Thread.sleep(250L*(1L<<(attempt-1)));
        }

        // If a transient request fails but we have a prior feed snapshot, keep the
        // current polling cycle useful. NewsPipeline still age-filters every entry.
        if(cachedBody!=null&&cachedBody.length>0)
            return parse(new ByteArrayInputStream(cachedBody),cfg,Instant.now());

        throw last==null?new IOException("Feed request failed for "+cfg.url()):last;
    }

    private HttpRequest request(Map<String,String> validators,boolean conditional){
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create(cfg.url()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent",userAgent)
                .header("Accept",ACCEPT);
        if(conditional){
            String etag=validators.get("etag"),last=validators.get("lastModified");
            if(etag!=null&&!etag.isBlank())b.header("If-None-Match",etag);
            if(last!=null&&!last.isBlank())b.header("If-Modified-Since",last);
        }
        return b.GET().build();
    }

    /**
     * Windows-first fallback for a Java trust-store mismatch. curl.exe normally
     * uses the platform TLS trust configuration on Windows. Certificate checks
     * remain enabled; this never uses -k/--insecure.
     */
    private byte[] fetchWithCurl() throws Exception {
        String curl=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")?"curl.exe":"curl";
        Path tmp=Files.createTempFile("autonews-feed-",".xml");
        try{
            List<String> cmd=List.of(
                    curl,"-L","--fail","--silent","--show-error",
                    "--connect-timeout",String.valueOf(timeoutSeconds),
                    "--max-time",String.valueOf(Math.max(timeoutSeconds+10,timeoutSeconds*2)),
                    "-A",userAgent,
                    "-H","Accept: "+ACCEPT,
                    "-o",tmp.toString(),
                    cfg.url()
            );
            Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String diagnostics=new String(p.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();
            if(!p.waitFor(Math.max(20L,timeoutSeconds*2L+10L),TimeUnit.SECONDS)){
                p.destroyForcibly();
                throw new IOException("curl timed out");
            }
            if(p.exitValue()!=0)throw new IOException("curl exit "+p.exitValue()+(diagnostics.isBlank()?"":": "+diagnostics));
            byte[] body=Files.readAllBytes(tmp);
            if(body.length==0)throw new IOException("curl returned an empty feed");
            return body;
        }finally{
            try{Files.deleteIfExists(tmp);}catch(Exception ignored){}
        }
    }

    private static boolean isTlsTrustFailure(Throwable t){
        while(t!=null){
            if(t instanceof SSLHandshakeException)return true;
            String m=t.getMessage();
            if(m!=null){
                String x=m.toLowerCase(Locale.ROOT);
                if(x.contains("pkix path building failed")||x.contains("unable to find valid certification path"))return true;
            }
            t=t.getCause();
        }
        return false;
    }

    public static List<Article> parse(InputStream input,SourceConfig cfg,Instant discoveredAt) throws Exception {
        DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try{f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-general-entities",false);}catch(Exception ignored){}
        try{f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);}catch(Exception ignored){}

        var builder=f.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
            @Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
            @Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
        });
        Document d=builder.parse(input);
        d.getDocumentElement().normalize();
        NodeList entries=d.getElementsByTagName("item");
        boolean atom=false;
        if(entries.getLength()==0){entries=d.getElementsByTagNameNS("*","entry");atom=true;}

        List<Article> out=new ArrayList<>();
        for(int i=0;i<entries.getLength();i++){
            Element e=(Element)entries.item(i);
            String title=text(e,"title");
            String url=atom?atomLink(e):text(e,"link");
            String desc=firstNonBlank(text(e,"description"),textNs(e,"summary"),textNs(e,"content"));
            String author=firstNonBlank(text(e,"author"),textNs(e,"creator"));
            String ds=firstNonBlank(text(e,"pubDate"),textNs(e,"published"),textNs(e,"updated"));
            Instant published=parseDate(ds,discoveredAt);
            if(title.isBlank()||url.isBlank())continue;
            String canon=canonical(url);
            String id=Hashing.sha256(cfg.name()+"|"+canon);
            out.add(new Article(id,cfg.name(),title,url,canon,published,discoveredAt,author,cfg.category(),strip(desc),"","en",cfg.trustTier(),cfg.authoritativePrimary()));
        }
        return out;
    }

    private static String canonical(String u){
        try{
            URI x=URI.create(u.trim());
            return new URI(x.getScheme(),x.getAuthority(),x.getPath(),x.getQuery(),null).toString();
        }catch(Exception e){return u.trim();}
    }

    private static String strip(String x){
        return x==null?"":x.replaceAll("<[^>]+>"," ")
                .replace("&amp;","&")
                .replace("&quot;","\"")
                .replace("&#39;","'")
                .replaceAll("\\s+"," ")
                .trim();
    }

    private static String text(Element e,String tag){
        NodeList n=e.getElementsByTagName(tag);
        return n.getLength()==0?"":n.item(0).getTextContent().trim();
    }

    private static String textNs(Element e,String local){
        NodeList n=e.getElementsByTagNameNS("*",local);
        return n.getLength()==0?"":n.item(0).getTextContent().trim();
    }

    private static String atomLink(Element e){
        NodeList n=e.getElementsByTagNameNS("*","link");
        for(int i=0;i<n.getLength();i++){
            Element x=(Element)n.item(i);
            String rel=x.getAttribute("rel"),href=x.getAttribute("href");
            if(!href.isBlank()&&(rel.isBlank()||"alternate".equals(rel)))return href;
        }
        return "";
    }

    private static String firstNonBlank(String...v){
        for(String x:v)if(x!=null&&!x.isBlank())return x;
        return "";
    }

    private static Instant parseDate(String s,Instant fallback){
        if(s==null||s.isBlank())return fallback;
        List<DateTimeFormatter> fs=List.of(DateTimeFormatter.RFC_1123_DATE_TIME,DateTimeFormatter.ISO_OFFSET_DATE_TIME,DateTimeFormatter.ISO_INSTANT);
        for(DateTimeFormatter f:fs)
            try{return ZonedDateTime.parse(s,f).toInstant();}
            catch(Exception ignored){
                try{return OffsetDateTime.parse(s,f).toInstant();}
                catch(Exception ignored2){
                    try{return Instant.parse(s);}catch(Exception ignored3){}
                }
            }
        return fallback;
    }
}
