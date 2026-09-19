package autonewsroller.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class ArticleFetcher {
    private final HttpClient client; private final Duration timeout; private final String userAgent;
    public ArticleFetcher(int timeoutSeconds,String userAgent){this.timeout=Duration.ofSeconds(Math.max(3,timeoutSeconds));this.userAgent=userAgent;this.client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();}
    public String fetch(String url) throws Exception {
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            try{
                HttpRequest req=HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("User-Agent",userAgent).header("Accept","text/html,application/xhtml+xml").GET().build();
                HttpResponse<String> r=client.send(req,HttpResponse.BodyHandlers.ofString());
                if(r.statusCode()>=200&&r.statusCode()<300)return r.body();
                if(r.statusCode()!=429&&r.statusCode()<500)throw new IOException("Article HTTP "+r.statusCode()+" for "+url);
                last=new IOException("Article HTTP "+r.statusCode()+" for "+url);
            }catch(IOException e){last=e;}
            if(attempt<3) Thread.sleep(250L*(1L<<(attempt-1)));
        }
        throw last==null?new IOException("Article fetch failed for "+url):last;
    }
}
