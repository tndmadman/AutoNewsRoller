package autonewsroller.visuals;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Conservative deterministic image checks; not a substitute for factual verification. */
public final class ImageQualityInspector {
    public record Result(boolean accepted,String reason,int width,int height,double contrast,long perceptualHash){
        public Map<String,Object> toMap(){return Map.of("accepted",accepted,"reason",reason,"width",width,
                "height",height,"contrast",Math.round(contrast*100.0)/100.0,
                "perceptualHash",Long.toUnsignedString(perceptualHash,16));}
    }
    public Result inspect(Path image,int expectedWidth,int expectedHeight,List<Long>acceptedHashes){
        BufferedImage img;
        try{img=ImageIO.read(image.toFile());}
        catch(Exception bad){return new Result(false,"corrupt-image",0,0,0,0);}
        if(img==null)return new Result(false,"unsupported-image",0,0,0,0);
        int w=img.getWidth(),h=img.getHeight();
        if(w<256||h<256)return new Result(false,"dimensions-too-small",w,h,0,0);
        double ratio=w/(double)h,expected=expectedWidth/(double)expectedHeight;
        if(Math.abs(ratio-expected)>.18)return new Result(false,"unexpected-aspect-ratio",w,h,0,0);
        int n=0;double sum=0,squares=0;double[]cells=new double[64];int ci=0;
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){
            int rgb=img.getRGB(Math.min(w-1,(int)((x+.5)*w/8)),Math.min(h-1,(int)((y+.5)*h/8)));
            double val=(((rgb>>16)&255)*.2126+((rgb>>8)&255)*.7152+(rgb&255)*.0722);
            cells[ci++]=val;sum+=val;squares+=val*val;n++;
        }
        double mean=sum/n;
        double variance=Math.max(0,squares/n-mean*mean);
        long hash=0;for(int i=0;i<64;i++)if(cells[i]>=mean)hash|=1L<<i;
        if(variance<16)return new Result(false,"nearly-blank-or-flat-image",w,h,Math.sqrt(variance),hash);
        if(acceptedHashes!=null)for(Long previous:acceptedHashes)
            if(previous!=null&&Long.bitCount(previous^hash)<=3)
                return new Result(false,"visually-duplicated-image",w,h,Math.sqrt(variance),hash);
        return new Result(true,"deterministic-checks-passed-not-fact-verified",w,h,Math.sqrt(variance),hash);
    }
}
