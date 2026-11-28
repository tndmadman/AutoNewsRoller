package autonewsroller.orchestration;

import autonewsroller.config.NewsConfig;
import autonewsroller.model.FactPackage;
import autonewsroller.visuals.VisualAssetGate;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** Regression for broad political-sensitive preflight and graphics-only safe route. */
public final class ProductionSensitiveVisualRouteSelfTest {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("anr-sensitive-graphics-");
        try{
            NewsConfig cfg=NewsConfig.load(root);
            FactPackage political=facts("Election results require Senate review");
            FactPackage violent=facts("Police investigate fatal shooting");
            FactPackage benign=facts("Public library opens new study room");
            for(FactPackage f:List.of(political,violent)){
                if(ProductionPreflight.requiresEvidenceGraphics(f,ProductionMode.STANDARD))
                    throw new AssertionError("STANDARD should use neutral editorial artwork");
                if(!ProductionPreflight.requiresEvidenceGraphics(f,ProductionMode.MEME_RAGEBAIT))
                    throw new AssertionError("Sensitive MEME evidence route changed");
                if(!ProductionPreflight.checkPolicy(f,ProductionMode.STANDARD).eligible())
                    throw new AssertionError("safe evidence route was rejected");
                if(!"CONTEXT_ILLUSTRATIONS_REQUIRED".equals(
                        ProductionPreflight.checkPolicy(f,ProductionMode.STANDARD).code()))
                    throw new AssertionError("missing explicit safety route");
            }
            if(ProductionPreflight.requiresEvidenceGraphics(benign,ProductionMode.STANDARD))
                throw new AssertionError("ordinary news should require AI illustrations");
            if(ProductionPreflight.requiresEvidenceGraphics(political,ProductionMode.FRAMING_EXPOSURE))
                throw new AssertionError("framing evidence route is already graphics-only");

            Path image=root.resolve("evidence.png");
            BufferedImage png=new BufferedImage(320,480,BufferedImage.TYPE_INT_RGB);
            if(!ImageIO.write(png,"png",image.toFile()))
                throw new AssertionError("PNG fixture unavailable");
            List<Path>images=List.of(image);
            List<Path>overlays=Collections.singletonList(null);
            List<Map<String,Object>>graphic=List.of(Map.of(
                    "type","procedural-card","path",image.toString()));
            VisualAssetGate.requireReady(ProductionMode.STANDARD,true,List.of(),0,
                    graphic,images,images,overlays,320,480);
            VisualAssetGate.requireReady(ProductionMode.MEME_RAGEBAIT,true,List.of(),0,
                    graphic,images,images,overlays,320,480);
            rejects(()->VisualAssetGate.requireReady(ProductionMode.STANDARD,false,
                    List.of(),0,graphic,images,images,overlays,320,480));
            rejects(()->VisualAssetGate.requireReady(ProductionMode.STANDARD,true,
                    List.of(0),1,graphic,images,images,overlays,320,480));
            rejects(()->VisualAssetGate.requireReady(ProductionMode.STANDARD,true,
                    List.of(),0,List.of(Map.of("type","comfyui-generated-ai-illustration",
                            "path",image.toString())),images,images,overlays,320,480));
            Files.delete(image);
            rejects(()->VisualAssetGate.requireReady(ProductionMode.STANDARD,true,
                    List.of(),0,graphic,images,images,overlays,320,480));
            System.out.println("ProductionSensitiveVisualRouteSelfTest PASS");
        }finally{
            try(var walk=Files.walk(root)){
                walk.sorted(Comparator.reverseOrder()).forEach(file->{
                    try{Files.deleteIfExists(file);}catch(Exception ignored){}
                });
            }
        }
    }
    private static FactPackage facts(String headline){
        return new FactPackage("fixture",headline,headline,List.of(),List.of(),
                List.of(),1,1,.9,false);
    }
    private interface Test{void run()throws Exception;}
    private static void rejects(Test test)throws Exception{
        try{test.run();}catch(IllegalStateException valid){return;}
        throw new AssertionError("unsafe image substitution was accepted");
    }
}
