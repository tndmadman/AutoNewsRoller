package autonewsroller.orchestration;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Local loopback fake: proves model inventory checks without loading Ollama. */
public final class ProductionServiceReadinessSelfTest {
    private static int passed;
    private ProductionServiceReadinessSelfTest(){}
    private static void ok(boolean value,String label){
        if(!value)throw new AssertionError(label);
        passed++;System.out.println("PASS readiness: "+label);
    }
    private static void reject(Runnable action,String label){
        boolean denied=false;
        try{action.run();}catch(Exception expected){denied=true;}
        ok(denied,label);
    }
    public static void main(String[]args)throws Exception{System.out.println("Passed: "+run());}
    public static int run()throws Exception{
        passed=0;
        AtomicInteger tags=new AtomicInteger(),inference=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/tags",x->{
            tags.incrementAndGet();
            byte[]response="{\"models\":[{\"name\":\"llama3.1:8b\"}]}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,response.length);
            try(var stream=x.getResponseBody()){stream.write(response);}
        });
        server.createContext("/api/generate",x->{inference.incrementAndGet();x.sendResponseHeaders(503,-1);x.close();});
        server.start();
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/api/generate";
        try{
            ProductionServiceReadiness.checkOllama(url,"llama3.1:8b");
            ok(tags.get()==1&&inference.get()==0,"health uses model tags without loading inference");
            reject(()->{
                try{ProductionServiceReadiness.checkOllama(url,"not-installed:1");}
                catch(Exception e){throw new IllegalStateException(e);}
            },"missing configured model is blocked");
            ok(!FailurePolicy.classify("VERIFY","CONFIGURATION_INVALID: OLLAMA_MODEL_MISSING").automaticRetry(),
                    "absent model is a terminal configuration error");
            reject(()->{
                try{ProductionServiceReadiness.checkOllama(url.replace("/api/generate","/v1/chat"),"llama3.1:8b");}
                catch(Exception e){throw new IllegalStateException(e);}
            },"incorrect API endpoint is rejected");
            reject(()->{
                try{ProductionServiceReadiness.checkOllama("http://127.0.0.1:1/api/generate","llama3.1:8b");}
                catch(Exception e){throw new IllegalStateException(e);}
            },"offline service fails before generation");
            ok(FailurePolicy.classify("VERIFY","SERVICE_UNAVAILABLE: Ollama model listing unreachable").automaticRetry(),
                    "temporary unreachable service remains a bounded retry");
            ok(!FailurePolicy.classify("VERIFY","SAFETY_SCANNER_UNAVAILABLE: detector offline").automaticRetry(),
                    "mandatory safety scanner failure cannot auto-retry past approval");
        }finally{server.stop(0);}
        return passed;
    }
}
