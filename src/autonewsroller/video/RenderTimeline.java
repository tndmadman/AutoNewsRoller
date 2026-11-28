package autonewsroller.video;

import java.math.*;
import java.nio.file.Path;
import java.util.*;

/** Exact source-audio clock and deterministic cumulative scene boundaries. */
public record RenderTimeline(double audioDuration, int sampleRate, long audioSamples,
                             int fps, int totalFrames, List<Integer> sceneFrames) {
    public double videoDuration(){return (double)totalFrames/fps;}
    public double audioTolerance(){return 1024.0/sampleRate+0.002;}

    static Map<String,String> probe(String ffprobe,Path path,String stream,String entries)throws Exception{
        String out=FfmpegRunner.run(List.of(ffprobe,"-v","error","-select_streams",stream,
                "-show_entries","stream="+entries,"-of","default=noprint_wrappers=1",path.toString()),30);
        Map<String,String> values=new HashMap<>();
        for(String line:out.split("\\R")){
            int i=line.indexOf('=');
            if(i>0)values.put(line.substring(0,i),line.substring(i+1));
        }
        return values;
    }
    public static RenderTimeline fromAudio(String ffprobe,Path audio,int fps,int count,List<Double> weights)throws Exception{
        Map<String,String> p=probe(ffprobe,audio,"a:0","duration_ts,time_base,duration,sample_rate");
        int rate=Integer.parseInt(p.get("sample_rate"));
        if(rate<=0||fps<=0)throw new IllegalArgumentException("Invalid sample or frame rate");
        BigInteger num,den;
        if(p.containsKey("duration_ts")&&!"N/A".equals(p.get("duration_ts"))){
            String[] base=p.get("time_base").split("/");
            num=new BigInteger(p.get("duration_ts")).multiply(new BigInteger(base[0]));
            den=new BigInteger(base[1]);
        }else{
            BigDecimal d=new BigDecimal(p.get("duration"));
            num=d.unscaledValue();den=BigInteger.TEN.pow(Math.max(0,d.scale()));
            if(d.scale()<0)num=num.multiply(BigInteger.TEN.pow(-d.scale()));
        }
        if(num.signum()<=0||den.signum()<=0)throw new IllegalArgumentException("Invalid audio timeline");
        int frames=num.multiply(BigInteger.valueOf(fps)).add(den).subtract(BigInteger.ONE).divide(den).intValueExact();
        BigInteger[] samples=num.multiply(BigInteger.valueOf(rate)).divideAndRemainder(den);
        return new RenderTimeline(num.doubleValue()/den.doubleValue(),rate,
                samples[1].signum()==0?samples[0].longValueExact():-1,
                fps,frames,allocate(frames,count,weights));
    }
    public static List<Integer> allocate(int frames,int count,List<Double> weights){
        if(count<=0||frames<count)throw new IllegalArgumentException("Each scene requires at least one video frame");
        List<BigDecimal> w=new ArrayList<>();
        BigDecimal sum=BigDecimal.ZERO;
        for(int i=0;i<count;i++){
            Double value=weights!=null&&i<weights.size()?weights.get(i):null;
            BigDecimal weight=value==null||!Double.isFinite(value)||value<=0?BigDecimal.ONE:BigDecimal.valueOf(value);
            w.add(weight);sum=sum.add(weight);
        }
        List<Integer> out=new ArrayList<>();
        BigDecimal cumulative=BigDecimal.ZERO;
        int previous=0;
        for(int i=0;i<count;i++){
            cumulative=cumulative.add(w.get(i));
            int ideal=cumulative.multiply(BigDecimal.valueOf(frames)).divide(sum,0,RoundingMode.HALF_UP).intValueExact();
            int boundary=Math.max(previous+1,Math.min(frames-(count-i-1),ideal));
            out.add(boundary-previous);previous=boundary;
        }
        return List.copyOf(out);
    }
}
