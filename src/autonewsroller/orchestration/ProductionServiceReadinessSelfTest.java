package autonewsroller.orchestration;

import com.sun.net.httpserver.HttpServer;
import autonewsroller.util.Json;
import java.net.*;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
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
    public static void main(String[]args)throws Exception{
        if(args.length>0&&"--scanner-noisy-stderr".equals(args[0])){
            System.err.println("2026-11-17 tensorflow: oneDNN CPU startup diagnostics");
            System.out.println("{\"status\":\"READY\",\"detector\":\"OpenNSFW2\",\"version\":\"0.19.0\"}");
            return;
        }
        if(args.length>0&&"--scanner-error".equals(args[0])){
            System.err.println("failed to load model weights");
            System.exit(2);
        }
        System.out.println("Passed: "+run());
    }
    public static int run()throws Exception{
        passed=0;
        // Regression: TensorFlow emits diagnostics to stderr while the scanner
        // returns one JSON document on stdout. Diagnostics must not poison JSON.
        String javaName=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")
                ?"java.exe":"java";
        String javaExe=Path.of(System.getProperty("java.home"),"bin",javaName).toString();
        String javaClass=ProductionServiceReadinessSelfTest.class.getName();
        String classpath=System.getProperty("java.class.path");
        String scannerJson=ProductionServiceReadiness.run(javaExe,20,"-cp",classpath,
                javaClass,"--scanner-noisy-stderr");
        Map<String,Object>modelHealth=Json.object(Json.parse(scannerJson.trim()));
        ok("READY".equals(modelHealth.get("status"))
                        &&"OpenNSFW2".equals(modelHealth.get("detector"))
                        &&"0.19.0".equals(modelHealth.get("version")),
                "scanner health JSON is isolated from TensorFlow stderr diagnostics");
        reject(()->{
            try{ProductionServiceReadiness.run(javaExe,20,"-cp",classpath,
                    javaClass,"--scanner-error");}
            catch(Exception error){throw new IllegalStateException(error);}
        },"nonzero scanner exit remains a hard failure");
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
