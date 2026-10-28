package autonewsroller.safety;

import autonewsroller.orchestration.ProductionMode;
import autonewsroller.video.AigcDisclosureOverlay;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;

/** Pure JVM regressions, no GPU or explicit-image fixtures. Detector integration is separate. */
public final class ContentSafetySelfTest {
    private ContentSafetySelfTest(){}
    public static void main(String[]args)throws Exception{
        if(!ContentSafetyPolicy.negative("").contains("exposed breasts"))
            throw new AssertionError("Mandatory negative conditioning missing");
        ContentSafetyPolicy.validatePositive("Fully clothed editorial illustration of a family at work");
        rejects(()->ContentSafetyPolicy.validatePositive("A nude person"));
        rejects(()->ContentSafetyPolicy.validatePositive("Authentic wire-service photograph of today's event"));
        if(!ContentSafetyPolicy.sensitive("A sexual assault case is under investigation"))
            throw new AssertionError("Sex crime not classified as sensitive");
        if(!ContentSafetyPolicy.sensitive("An earthquake causes evacuations"))
            throw new AssertionError("Disaster not classified as sensitive");
        Path dir=Files.createTempDirectory("anr-safety-test-");
        try{
            Path overlay=AigcDisclosureOverlay.create(dir.resolve("overlay.png"),ProductionMode.MEME_RAGEBAIT,1080,1920);
            BufferedImage image=ImageIO.read(overlay.toFile());
            if(image==null || image.getWidth()!=1080 || image.getHeight()!=1920)
                throw new AssertionError("Missing disclosure overlay");
            int color=image.getRGB(364,137);
            if(((color>>>16)&255)<125 || (color&255)<90)
                throw new AssertionError("Disclosure marker missing");
            Path video=dir.resolve("video.mp4");
            Files.writeString(video,"synthetic placeholder bytes");
            Map<String,Object>audit=new LinkedHashMap<>();
            audit.put("status","SAFE");audit.put("policyVersion",ContentSafetyService.POLICY);
            audit.put("framesDecoded",1);audit.put("framesInspected",1);audit.put("framesExpected",1);
            audit.put("disclosureFramesVerified",1);audit.put("disclosureVerified",true);
            audit.put("detector","NudeNet");audit.put("version","3.4.2");
            audit.put("coverage","every decoded frame; full and three overlapping crops");
            audit.put("sha256",ContentSafetyService.hash(video));
        audit.put("contextAudit",Map.of("status","SAFE","detector","OpenNSFW2",
                "version","0.19.0","framesDecoded",1,"framesInspected",1,
                "framesExpected",1,"coverage",
                "every decoded frame; complete frame; upper/lower crops on still images"));
            Map<String,Object>manifest=new LinkedHashMap<>();
            manifest.put("status","APPROVED_FOR_EXPORT");
            manifest.put("policyVersion",ContentSafetyService.POLICY);
            manifest.put("videoSha256",ContentSafetyService.hash(video));
            manifest.put("finalVideoAudit",audit);
            manifest.put("inputs",List.of(Map.of("path",overlay.toString(),"sha256",ContentSafetyService.hash(overlay))));
            ContentSafetyService.verify(video,manifest,true);
            audit.put("disclosureFramesVerified",0);
            rejects(()->ContentSafetyService.verify(video,manifest,false));
            audit.put("disclosureFramesVerified",1);
            audit.remove("detector");
            rejects(()->ContentSafetyService.verify(video,manifest,false));
            audit.put("detector","NudeNet");
            Files.writeString(video,"changed bytes invalidate approval");
            rejects(()->ContentSafetyService.verify(video,manifest,false));
            Files.writeString(video,"synthetic placeholder bytes");
            Files.writeString(overlay,"changed source bytes");
            rejects(()->ContentSafetyService.verify(video,manifest,true));
            manifest.put("status","NOT_SCANNED");
            rejects(()->ContentSafetyService.verify(video,manifest,false));
            System.out.println("ContentSafetySelfTest PASS (policy, disclosure artifact, hash invalidation, no legacy approval)");
            System.out.println("ContentSafetyDetectorIntegration SKIPPED (requires NudeNet, licensed fixtures and local GPU-worker validation)");
        }finally{
            try(var files=Files.walk(dir)){files.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}
        }
    }
    private interface Checked {void run()throws Exception;}
    private static void rejects(Checked test)throws Exception{
        boolean rejected=false;
        try{test.run();}catch(IllegalStateException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Unsafe or stale material was accepted");
    }
}