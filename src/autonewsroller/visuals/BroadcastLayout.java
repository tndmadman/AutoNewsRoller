package autonewsroller.visuals;

import java.awt.Rectangle;
import java.util.*;

/**
 * Single dimension-scaled definition for subtitle and panel safety regions.
 * This validates reserved geometry, not the subjective legibility of footage.
 */
public final class BroadcastLayout {
    private BroadcastLayout(){}
    public static int captionFont(int width){return Math.max(18,(int)Math.round(44.0*width/1080));}
    public static int captionHorizontalMargin(int width){return Math.max(20,(int)Math.round(width*.072));}
    public static int captionVerticalMargin(int height){return Math.max(80,(int)Math.round(height*.268));}
    public static Rectangle captionArea(int width,int height){
        int margin=captionHorizontalMargin(width),baseline=height-captionVerticalMargin(height);
        int twoLines=(int)Math.ceil(captionFont(width)*2.8);
        return new Rectangle(margin,baseline-twoLines,Math.max(1,width-margin*2),twoLines);
    }
    public static Rectangle lowerThird(String type,int width,int height){
        double scale=height/1920.0;
        int ticker=(int)Math.round(80*scale);
        int panel=(int)Math.round(("HOOK".equalsIgnoreCase(type)?235:148)*scale);
        return new Rectangle(0,Math.max(0,height-ticker-panel),width,panel);
    }
    public static Map<String,Object> review(Storyboard board,int width,int height,boolean captions){
        if(width<240||height<320)throw new IllegalArgumentException("Output dimensions too small for readable news graphics");
        Rectangle area=captionArea(width,height);
        int topReserved=(int)Math.round(height*118/1920.0);
        int tickerTop=height-(int)Math.round(height*80/1920.0);
        boolean headerCollision=area.y<topReserved;
        boolean tickerCollision=area.y+area.height>tickerTop;
        List<Integer>panelCollision=new ArrayList<>();
        if(captions)for(Storyboard.Scene scene:board.scenes()){
            if(scene.type().equalsIgnoreCase("HOOK")||scene.type().equalsIgnoreCase("EDITORIAL_PHOTO")
                    ||scene.type().equalsIgnoreCase("DIRECT_EVENT")){
                if(area.intersects(lowerThird(scene.type(),width,height)))panelCollision.add(scene.index());
            }
        }
        boolean okay=!captions||(!headerCollision&&!tickerCollision&&panelCollision.isEmpty());
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("captionEnabled",captions);result.put("captionBounds",Map.of("x",area.x,"y",area.y,"width",area.width,"height",area.height));
        result.put("headlineLowerThirdCollisionSceneIndexes",panelCollision);
        result.put("headerCollision",captions&&headerCollision);result.put("tickerCollision",captions&&tickerCollision);
        result.put("status",okay?"passed":"rejected");
        return result;
    }
}
