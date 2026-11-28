package autonewsroller.video;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class FfmpegRunner {
    public static String run(List<String> cmd,int timeout)throws Exception{
        return run(cmd,timeout,null);
    }
    /** Optional per-invocation local diagnostics, including failed or cancelled commands. */
    public static String run(List<String> cmd,int timeout,Path log)throws Exception{
        ProcessBuilder b=new ProcessBuilder(cmd);
        b.redirectErrorStream(true);
        Process p=b.start();
        StringBuffer out=new StringBuffer();
        Thread reader=new Thread(()->{
            try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))){
                for(String line;(line=r.readLine())!=null;)out.append(line).append('\n');
            }catch(IOException ignored){}
        },"ffmpeg-output-reader");
        reader.setDaemon(true);
        reader.start();
        try{
            if(!p.waitFor(Math.max(1,timeout),TimeUnit.SECONDS))
                throw new IOException("FFmpeg process timed out: "+String.join(" ",cmd));
            reader.join(5000);
            if(reader.isAlive())throw new IOException("FFmpeg output reader did not finish");
            if(p.exitValue()!=0)throw new IOException("FFmpeg failed ("+p.exitValue()+"): "+tail(out.toString()));
            return out.toString();
        }catch(InterruptedException cancelled){
            p.destroyForcibly();
            try{p.waitFor(5,TimeUnit.SECONDS);}
            catch(InterruptedException ignored){}
            Thread.currentThread().interrupt();
            throw cancelled;
        }finally{
            if(p.isAlive()){
                p.destroyForcibly();
                try{p.waitFor(5,TimeUnit.SECONDS);}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            }
            if(log!=null){
                try{
                    Files.writeString(log,"command: "+cmd+System.lineSeparator()
                            +"exit status: "+(p.isAlive()?"not terminated":p.exitValue())+System.lineSeparator()
                            +out,StandardCharsets.UTF_8);
                }catch(IOException logError){System.err.println("FFmpeg diagnostics write failed: "+logError);}
            }
        }
    }
    private static String tail(String s){s=s.trim();return s.length()>3000?s.substring(s.length()-3000):s;}
}
