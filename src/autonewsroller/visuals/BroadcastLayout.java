package autonewsroller.visuals;

import java.awt.Rectangle;
import java.util.*;

/**
 * TikTok-safe 9:16 broadcast geometry. All coordinates are in the 1080x1920
 * reference frame and scale with the actual output dimensions.
 * Platform chrome is NOT part of the encoded video; reserve it here so uploaded
 * posts remain readable with long TikTok descriptions and the right action rail.
 */
public final class BroadcastLayout {
    private BroadcastLayout(){}
    public static int sx(int n,int w){return (int)Math.round(n*w/1080.0);}
    public static int sy(int n,int h){return (int)Math.round(n*h/1920.0);}
    public static int headerTop(int height){return sy(240,height);}
    public static int panelBottom(int height){return sy(1000,height);}
    public static int safeRight(int width){return sx(835,width);}
    public static int safeBottom(int height){return sy(1320,height);}
    public static int graphicBottom(int height){return sy(1080,height);}
    public static int captionFont(int width){return Math.max(18,sx(44,width));}
    public static int captionLeftMargin(int width){return Math.max(20,sx(80,width));}
    public static int captionRightMargin(int width){return Math.max(30,sx(245,width));}
    /** Retained for existing callers; the ASS style uses asymmetric margins. */
    public static int captionHorizontalMargin(int width){return captionLeftMargin(width);}
    public static int captionVerticalMargin(int height){return sy(680,height);}
    public static Rectangle captionArea(int width,int height){
        int left=captionLeftMargin(width),right=captionRightMargin(width);
        int baseline=height-captionVerticalMargin(height);
        int lines=(int)Math.ceil(captionFont(width)*2.8);
        return new Rectangle(left,baseline-lines,Math.max(1,width-left-right),lines);
    }
    public static Rectangle lowerThird(String type,int width,int height){
        return lowerThird(type,width,height,false);
    }
    public static Rectangle lowerThird(String type,int width,int height,boolean classic){
        boolean hook="HOOK".equalsIgnoreCase(type);
        int panel=sy(classic?(hook?430:300):(hook?235:148),height);
        int bottom=panelBottom(height);
        return new Rectangle(0,bottom-panel,safeRight(width),panel);
    }
    public static Map<String,Object> review(Storyboard board,int width,int height,boolean captions){
        if(width<240||height<320)throw new IllegalArgumentException("Output dimensions too small for readable news graphics");
        Rectangle area=captionArea(width,height);
        Rectangle top=new Rectangle(0,0,width,headerTop(height));
        boolean headerCollision=captions&&area.intersects(top);
        boolean sideUiCollision=captions&&area.x+area.width>safeRight(width);
        boolean bottomUiCollision=captions&&area.y+area.height>safeBottom(height);
        List<Integer>panelCollision=new ArrayList<>();
        if(captions)for(Storyboard.Scene scene:board.scenes()){
            String type=scene.type();
            if(Set.of("HOOK","EDITORIAL_PHOTO","DIRECT_EVENT","PERSON","LOCATION","OBJECT","INSTITUTION","PROCESS","BACKGROUND","CONTEXT").contains(type.toUpperCase(Locale.ROOT))
                    &&area.intersects(lowerThird(type,width,height)))panelCollision.add(scene.index());
        }
        boolean geometryValid=lowerThird("HOOK",width,height).y>=headerTop(height)
                &&lowerThird("HOOK",width,height).y+lowerThird("HOOK",width,height).height<area.y
                &&graphicBottom(height)<area.y
                &&area.x+area.width<=safeRight(width)
                &&area.y+area.height<=safeBottom(height);
        boolean okay=geometryValid&&(!captions||(!headerCollision&&!sideUiCollision&&!bottomUiCollision&&panelCollision.isEmpty()));
        Map<String,Object>result=new LinkedHashMap<>();
        result.put("profile","tiktok");
        result.put("captionEnabled",captions);
        result.put("captionBounds",Map.of("x",area.x,"y",area.y,"width",area.width,"height",area.height));
        result.put("safeRight",safeRight(width));result.put("safeBottom",safeBottom(height));
        result.put("headlineLowerThirdCollisionSceneIndexes",panelCollision);
        result.put("headerCollision",headerCollision);result.put("tickerCollision",false);
        result.put("sideUiCollision",sideUiCollision);result.put("bottomUiCollision",bottomUiCollision);
        result.put("geometryValid",geometryValid);
        result.put("status",okay?"passed":"rejected");
        return result;
    }
}
