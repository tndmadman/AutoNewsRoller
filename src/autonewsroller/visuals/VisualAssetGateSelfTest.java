package autonewsroller.visuals;

import autonewsroller.orchestration.ProductionMode;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** No AI/GPU needed: pre-FFmpeg gates against missing/partial illustration sets. */
public final class VisualAssetGateSelfTest {
    private VisualAssetGateSelfTest() {}
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("anr-visual-gate-");
        try {
            int w=160,h=280;
            Path card=image(dir.resolve("card.png"),w,h,Color.BLUE);
            Path raw=image(dir.resolve("comfy.png"),192,336,Color.GREEN);
            Path composed=image(dir.resolve("composed.png"),w,h,Color.GREEN);
            Path background=image(dir.resolve("background.png"),w,h,Color.GREEN);
            Path overlay=image(dir.resolve("overlay.png"),w,h,Color.RED);
            Map<String,Object> graphic=Map.of("type","procedural-card","path",card.toString(),"visualType","SOURCE_CARD");
            Map<String,Object> generated=Map.of("type","comfyui-generated-ai-illustration",
                    "path",composed.toString(),"generatedPath",raw.toString(),
                    "backgroundPath",background.toString(),"overlayPath",overlay.toString(),
                    "aiIllustrationDisclosure",true);
            List<Path> frames=List.of(composed,card);
            List<Path> backs=List.of(background,card);
            List<Path> overlays=Arrays.asList(overlay,null);
            List<Map<String,Object>>sources=List.of(generated,graphic);

            for(ProductionMode mode:List.of(ProductionMode.STANDARD,ProductionMode.MEME_RAGEBAIT)){
                VisualAssetGate.requireReady(mode,false,List.of(0),1,sources,frames,backs,overlays,w,h);
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(0),0,sources,frames,backs,overlays,w,h));
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(0),1,List.of(graphic,graphic),
                        List.of(card,card),List.of(card,card),Arrays.asList(null,null),w,h));
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(),0,sources,frames,backs,overlays,w,h));
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(0,1),1,sources,frames,backs,overlays,w,h));
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(0,0),2,sources,frames,backs,overlays,w,h));
            }
            // Framing uses truthful procedural evidence cards: ComfyUI is intentionally forbidden.
            VisualAssetGate.requireReady(ProductionMode.FRAMING_EXPOSURE,false,List.of(),0,
                    List.of(graphic),List.of(card),List.of(card),Arrays.asList((Path)null),w,h);
            // Incident sensitive stories keep readable procedural cards instead of synthetic depictions.
            if (!VisualAssetGate.sensitiveTopic("Police respond to a shooting",""))throw new AssertionError("Sensitive story detection failed");
            if (VisualAssetGate.sensitiveTopic("New kitchen appliances","Unrelated consumer news"))throw new AssertionError("False sensitive topic");
            VisualAssetGate.requireReady(ProductionMode.STANDARD,true,List.of(),0,
                    List.of(graphic),List.of(card),List.of(card),Arrays.asList((Path)null),w,h);
            Path corrupt=dir.resolve("corrupt.png");
            Files.writeString(corrupt,"This is not a PNG even though it has a PNG filename");
            rejected(()->VisualAssetGate.requireReady(ProductionMode.FRAMING_EXPOSURE,false,List.of(),0,
                    List.of(Map.of("type","procedural-card","path",corrupt.toString())),
                    List.of(corrupt),List.of(corrupt),Arrays.asList((Path)null),w,h));
            // Loss of the original after acceptance must invalidate the clip.
            Files.delete(raw);
            rejected(()->VisualAssetGate.requireReady(ProductionMode.MEME_RAGEBAIT,false,List.of(0),1,
                    sources,frames,backs,overlays,w,h));
            System.out.println("VisualAssetGateSelfTest PASS (standard/meme mandatory images, all scenes, framing, sensitive, corrupt and deleted sources)");
        }finally{
            try(var walk=Files.walk(dir)){walk.sorted(Comparator.reverseOrder()).forEach(p->{
                try{Files.deleteIfExists(p);}catch(Exception ignored){}
            });}
        }
    }
    private static Path image(Path file,int w,int h,Color color)throws Exception{
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=image.createGraphics();
        g.setColor(color);g.fillRect(0,0,w,h);g.dispose();
        ImageIO.write(image,"png",file.toFile());
        return file;
    }
    private interface Operation{void run()throws Exception;}
    private static void rejected(Operation operation)throws Exception{
        try{operation.run();throw new AssertionError("Missing required visual was accepted");}
        catch(IllegalStateException expected){
            if(!expected.getMessage().startsWith("VISUAL_"))throw expected;
        }
    }
}
