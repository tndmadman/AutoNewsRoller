package autonewsroller.visuals;

import java.awt.Rectangle;
import java.util.*;

/**
 * Single dimension-scaled definition for subtitle and panel safety regions.
 * This validates reserved geometry, not the subjective legibility of footage.
 */
public final class BroadcastLayout {
    private BroadcastLayout(){}
    /** Dark footer keeps TikTok's own post description readable. Scales from 220px at 1080x1920. */
    public static int postTextSafeFooter(int height){return (int)Math.round(height*220.0/1920.0);}
    public static int tickerTop(int height){return height-postTextSafeFooter(height)-(int)Math.round(height*80.0/1920.0);}
    public static int captionFont(int width){return Math.max(18,(int)Math.round(44.0*width/1080));}
    public static int captionHorizontalMargin(int width){return Math.max(20,(int)Math.round(width*.072));}
    public static int captionVerticalMargin(int height){return captionVerticalMargin(height,false);}
    public static int captionVerticalMargin(int height,boolean classic){
        // Raise normal captions only 48px to clear the relocated hook headline.
        // Classic's taller panel requires captions slightly higher.
        return classic?(int)Math.round(height*.405):
                Math.max(80,(int)Math.round(height*.268))+(int)Math.round(height*48.0/1920.0);
    }
    public static Rectangle captionArea(int width,int height){return captionArea(width,height,false);}
    public static Rectangle captionArea(int width,int height,boolean classic){
        int margin=captionHorizontalMargin(width),baseline=height-captionVerticalMargin(height,classic);
        int twoLines=(int)Math.ceil(captionFont(width)*2.8);
        return new Rectangle(margin,baseline-twoLines,Math.max(1,width-margin*2),twoLines);
    }
    public static Rectangle lowerThird(String type,int width,int height){
        return lowerThird(type,width,height,false);
    }
    public static Rectangle lowerThird(String type,int width,int height,boolean classic){
        double scale=height/1920.0;
        int ticker=(int)Math.round(80*scale);
        int panel=(int)Math.round((classic?("HOOK".equalsIgnoreCase(type)?430:300):
                ("HOOK".equalsIgnoreCase(type)?235:148))*scale);
        return new Rectangle(0,Math.max(0,height-postTextSafeFooter(height)-ticker-panel),width,panel);
    }
    public static Map<String,Object> review(Storyboard board,int width,int height,boolean captions){
        return review(board,width,height,captions,false);
    }
    public static Map<String,Object> review(Storyboard board,int width,int height,boolean captions,boolean classic){
        if(width<240||height<320)throw new IllegalArgumentException("Output dimensions too small for readable news graphics");
        Rectangle area=captionArea(width,height,classic);
        int topReserved=(int)Math.round(height*118/1920.0);
        int tickerTop=tickerTop(height);
        boolean headerCollision=area.y<topReserved;
        boolean tickerCollision=area.y+area.height>tickerTop;
        List<Integer>panelCollision=new ArrayList<>();
        if(captions)for(Storyboard.Scene scene:board.scenes()){
            if(scene.type().equalsIgnoreCase("HOOK")||scene.type().equalsIgnoreCase("EDITORIAL_PHOTO")
                    ||scene.type().equalsIgnoreCase("DIRECT_EVENT")){
                if(area.intersects(lowerThird(scene.type(),width,height,classic)))panelCollision.add(scene.index());
            }
        }
        boolean footerCollision=area.y+area.height>height-postTextSafeFooter(height);
        boolean okay=!captions||(!headerCollision&&!tickerCollision&&!footerCollision&&panelCollision.isEmpty());
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("profile","tiktok-footer-220");result.put("footerHeight",postTextSafeFooter(height));
        result.put("classicCaptions",classic);
        result.put("captionEnabled",captions);result.put("captionBounds",Map.of("x",area.x,"y",area.y,"width",area.width,"height",area.height));
        result.put("headlineLowerThirdCollisionSceneIndexes",panelCollision);
        result.put("headerCollision",captions&&headerCollision);result.put("tickerCollision",captions&&tickerCollision);
        result.put("footerCollision",captions&&footerCollision);
        result.put("status",okay?"passed":"rejected");
        return result;
    }
}
