package autonewsroller.visuals;

import autonewsroller.orchestration.ProductionMode;
import autonewsroller.model.VisualPlan;
import autonewsroller.model.VisualPromptPlan;
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
            // Sensitive stories use source-verified procedural evidence cards,
            // not generated people or fabricated events. The scene evidence
            // still has to pass the complete gate before any video scan.
            for(ProductionMode mode:List.of(ProductionMode.STANDARD,ProductionMode.MEME_RAGEBAIT)){
                VisualAssetGate.requireReady(mode,true,List.of(),0,
                        List.of(graphic),List.of(card),List.of(card),Arrays.asList((Path)null),w,h);
                rejected(()->VisualAssetGate.requireReady(mode,true,List.of(0),1,
                        sources,frames,backs,overlays,w,h));
                rejected(()->VisualAssetGate.requireReady(mode,true,List.of(),0,
                        List.of(generated),List.of(composed),List.of(background),
                        Arrays.asList(overlay),w,h));
            }
            // Diagnostic local drafts can use procedural cards, never unchecked generated visuals.
            for(ProductionMode mode:ProductionMode.values()){
                VisualAssetGate.requireReady(mode,false,List.of(),0,List.of(graphic),
                        List.of(card),List.of(card),Arrays.asList((Path)null),w,h,true);
                rejected(()->VisualAssetGate.requireReady(mode,false,List.of(0),1,
                        sources,frames,backs,overlays,w,h,true));
            }
            standardCoverage(dir,w,h,raw);
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
    private static void standardCoverage(Path dir,int w,int h,Path raw)throws Exception{
        List<VisualPlan.Item> items=new ArrayList<>();
        List<VisualPromptPlan> prompts=new ArrayList<>();
        String[] types={"HOOK","STATISTIC","QUOTE","TIMELINE","FACT_HIGHLIGHT","EDITORIAL_PHOTO","STATISTIC","EDITORIAL_PHOTO"};
        for(int i=0;i<types.length;i++){
            String prompt="Editorial illustration of semiconductor manufacturing equipment";
            items.add(new VisualPlan.Item(i,types[i],"Technology update","Revenue was 25 percent higher.",4,prompt,Map.of("segmentIndex",i)));
            prompts.add(new VisualPromptPlan(i,"OBJECT",List.of("F1"),List.of(),"semiconductor equipment","","factory","","","",
                    List.of(),List.of(),"",prompt,90,true,List.of(),0,false));
        }
        items.add(new VisualPlan.Item(8,"SOURCE_CARD","Sources","Example source",2,"",Map.of("supplemental",true)));
        VisualPlan base=new VisualPlan("coverage-test",items);
        VisualPlan plan=StandardVisualCoverage.annotate(base,prompts,false);
        List<Integer> scheduled=StandardVisualCoverage.schedule(plan,6);
        if(StandardVisualCoverage.eligible(plan).size()!=8||scheduled.size()!=6||scheduled.get(0)!=0||scheduled.contains(8))
            throw new AssertionError("STANDARD target or source attribution failed");
        if(!StandardVisualCoverage.illustratedInformation(plan.items().get(1)))
            throw new AssertionError("Statistic lost illustrated graphic strategy");
        if(!StandardVisualCoverage.schedule(StandardVisualCoverage.annotate(base,prompts,true),6).isEmpty())
            throw new AssertionError("Existing evidence-only route changed");
        List<VisualPromptPlan> invalid=new ArrayList<>(prompts);
        invalid.set(1,prompts.get(1).withValidation(0,false,List.of("unsupported")));
        if(StandardVisualCoverage.eligible(StandardVisualCoverage.annotate(base,invalid,false)).contains(1))
            throw new AssertionError("Unvalidated visual became eligible");
        List<Map<String,Object>> sources=new ArrayList<>();
        for(int i=0;i<items.size();i++)sources.add(scheduled.contains(i)
                ?Map.of("type","comfyui-generated-ai-illustration","generatedPath","synthetic-"+i)
                :Map.of("type","procedural-card"));
        var report=StandardVisualCoverage.report(plan,scheduled,6,sources,
                Map.of("sceneFrames",Collections.nCopies(9,100),"videoFramesAfterRepair",900));
        if(!Objects.equals(report.get("renderTimelineGeneratedFrames"),600L))throw new AssertionError("Frame coverage incorrect");
        rejected(()->StandardVisualCoverage.verify(plan,scheduled,List.of(),sources));
        Path ov=dir.resolve("illustrated-overlay.png"),preview=dir.resolve("illustrated-preview.png");
        CardRenderer renderer=new CardRenderer();
        renderer.renderOverlay(plan.items().get(1),ov,w,h);
        renderer.renderWithImage(plan.items().get(1),raw,preview,w,h);
        BufferedImage overlay=ImageIO.read(ov.toFile()),composed=ImageIO.read(preview.toFile());
        int alpha=overlay.getRGB(w/2,(int)(h*.23))>>>24;
        if(alpha>=255||composed.getWidth()!=w||composed.getHeight()!=h)
            throw new AssertionError("Illustration obscured by opaque graphic");
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
