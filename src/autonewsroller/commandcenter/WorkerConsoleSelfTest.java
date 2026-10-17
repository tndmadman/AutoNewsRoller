package autonewsroller.commandcenter;

import autonewsroller.config.NewsConfig;
import autonewsroller.util.Json;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Dependency-free worker/console smoke and lease-ownership tests; no AI/GPU services required. */
public final class WorkerConsoleSelfTest {
    private static final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private static void await(BooleanSupplier condition,String reason)throws Exception{
        for(int i=0;i<75;i++){if(condition.getAsBoolean())return;Thread.sleep(100);}
        throw new AssertionError("Timed out: "+reason);
    }
    private static void send(HttpExchange x,int status,String body)throws IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type","application/json");
        if(status==204){x.sendResponseHeaders(204,-1);x.close();return;}
        x.sendResponseHeaders(status,bytes.length);
        try(OutputStream o=x.getResponseBody()){o.write(bytes);}
    }
    private static class MockController implements AutoCloseable{
        final HttpServer server;
        final AtomicInteger claims=new AtomicInteger(),heartbeats=new AtomicInteger(),fails=new AtomicInteger(),biasFails=new AtomicInteger();
        final AtomicReference<Map<String,Object>> lastFail=new AtomicReference<>();
        final AtomicBoolean issueJob=new AtomicBoolean(),issueAnalysis=new AtomicBoolean();
        MockController()throws Exception{
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/jobs/claim",x->{
                claims.incrementAndGet();
                if(issueJob.compareAndSet(true,false)){
                    send(x,200,Json.stringify(Map.of("jobId","bad-video","jobType","VIDEO","topic","invalid fixture",
                            "claimId","claim-test-123","candidate",Map.of())));
                }else if(issueAnalysis.compareAndSet(true,false)){
                    send(x,200,Json.stringify(Map.of("jobId","bad-bias","jobType","POLITICAL_ANALYSIS","topic","invalid fixture",
                            "candidate",Map.of())));
                }else send(x,204,"");
            });
            server.createContext("/api/workers/heartbeat",x->{heartbeats.incrementAndGet();send(x,200,"{}");});
            server.createContext("/api/jobs/",x->{
                String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                if(x.getRequestURI().getPath().endsWith("/bias-fail"))biasFails.incrementAndGet();
                if(x.getRequestURI().getPath().endsWith("/fail")&&!x.getRequestURI().getPath().endsWith("/bias-fail")){
                    fails.incrementAndGet();lastFail.set(Json.object(Json.parse(body)));
                }
                send(x,200,"{}");
            });
            server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"mock-http");t.setDaemon(true);return t;}));server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);}
    }
    private static void workerLifecycle(Path root)throws Exception{
        NewsConfig cfg=NewsConfig.load(root);
        try(MockController mock=new MockController()){
            List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
            RemoteWorker worker=new RemoteWorker(root,cfg,mock.url(),"test-worker","",events::add,()->Map.of("gpu",Map.of("available",false)));
            Thread thread=new Thread(worker::runForever);
            thread.start();
            await(()->mock.claims.get()>0,"claim loop begins");
            await(()->mock.heartbeats.get()>0,"heartbeats begin");
            AtomicBoolean duplicateRejected=new AtomicBoolean(false);
            RemoteWorker duplicate=new RemoteWorker(root,cfg,mock.url(),"test-worker","",null,()->Map.of());
            Thread duplicateThread=new Thread(()->{
                try{duplicate.runForever();}catch(IllegalStateException expected){duplicateRejected.set(true);}
            });
            duplicateThread.start();duplicateThread.join(2500);
            if(duplicateThread.isAlive()){duplicate.drain();duplicateThread.join(5000);}
            require(duplicateRejected.get(),"OS lock rejects simultaneous duplicate worker ID");
            worker.pause();Thread.sleep(2000);
            int pausedClaims=mock.claims.get();
            Thread.sleep(1800);
            require(pausedClaims==mock.claims.get(),"no new claims while paused");
            worker.resume();await(()->mock.claims.get()>pausedClaims,"resumed claiming");
            mock.issueJob.set(true);
            await(()->mock.fails.get()==1,"failed video callback");
            require("claim-test-123".equals(mock.lastFail.get().get("claimId")),"claimId retained in failure callback");
            require(events.stream().anyMatch(e->"job-finish".equals(e.get("type"))&&"FAILED".equals(e.get("result"))),"job failure event");
            mock.issueAnalysis.set(true);
            await(()->mock.biasFails.get()==1,"political analysis uses bias-fail route");
            worker.drain();thread.join(8000);
            require(!thread.isAlive(),"graceful drain terminates loop");
            require(mock.heartbeats.get()>0,"heartbeat delivered");
            RemoteWorker again=new RemoteWorker(root,cfg,mock.url(),"test-worker","",null,()->Map.of());
            again.drain();Thread second=new Thread(again::runForever);second.start();second.join(5000);
            require(!second.isAlive(),"fresh worker can stop cleanly");
        }
    }
    private static int freePort()throws Exception{
        try(ServerSocket sock=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))){return sock.getLocalPort();}
    }
    private static HttpResponse<String> call(int port,String path,String method,String json,Map<String,String> headers)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(4));
        headers.forEach(b::header);
        if(method.equals("GET"))b.GET();else b.method(method,HttpRequest.BodyPublishers.ofString(json));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void consoleSecurity(Path root)throws Exception{
        int port=freePort();
        Json.write(root.resolve("output/runtime/worker-ui-settings.json"),Map.of("port",port,"autoStart",false,"autoOpenBrowser",false));
        WorkerWebConsole console=new WorkerWebConsole(root,NewsConfig.load(root),"http://127.0.0.1:8787","unit-node","");
        try{
            console.start();
            require(call(port,"/api/worker/health","GET","",Map.of()).statusCode()==200,"health works");
            require(call(port,"/api/worker/status","GET","",Map.of()).body().contains("STOPPED"),"initially stopped");
            require(call(port,"/api/worker/control","POST","{\"action\":\"start\"}",
                Map.of("Content-Type","application/json","Origin","http://evil.example","X-Worker-Request","1")).statusCode()==403,
                "cross-origin mutation rejected");
            require(call(port,"/api/worker/control","POST","{\"action\":\"start\"}",
                Map.of("Content-Type","application/json")).statusCode()==403,"custom CSRF header required");
            require(call(port,"/api/worker/settings","POST","{\"port\":2}",
                Map.of("Content-Type","application/json","X-Worker-Request","1")).statusCode()==400,"invalid port rejected");
            require(call(port,"/api/worker/settings","POST","{\"workerId\":\"bad space\"}",
                Map.of("Content-Type","application/json","X-Worker-Request","1")).statusCode()==400,"invalid worker ID rejected");
            require(call(port,"/api/worker/control","POST","{\"action\":\"pause\"}",
                Map.of("Content-Type","application/json","X-Worker-Request","1")).statusCode()==409,"cannot pause absent worker");
            require(call(port,"/api/worker/jobs","GET","",Map.of()).statusCode()==200,"job history available");
        }finally{console.close();}
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("autonews-worker-test-");
        try{
            workerLifecycle(root);
            consoleSecurity(root);
            System.out.println("WorkerConsoleSelfTest PASS: lifecycle, claims, failure callbacks, political path, CSRF, validation, status");
        }finally{
            // Do not remove test runtime while any background worker is active.
        }
    }
}
