package autonewsroller.visuals;

import java.awt.Color;

/**
 * Centralized AWARE broadcast visual system.
 *
 * Keep the package deliberately restrained: deep navy, broadcast blue, news red,
 * white, and cool gray. Avoid neon/cyan/yellow UI accents that make frames read
 * like generic AI/SaaS graphics instead of a news broadcast.
 */
public final class BroadcastTheme {
    private BroadcastTheme(){}

    public static final String PACKAGE_ID="aware-broadcast-v2";

    public static final Color BACKGROUND=new Color(5,17,30);
    public static final Color NAVY=new Color(7,26,43);
    public static final Color NAVY_2=new Color(11,35,57);
    public static final Color BLUE=new Color(22,66,116);
    public static final Color BLUE_LIGHT=new Color(67,121,178);
    public static final Color RED=new Color(196,31,48);
    public static final Color RED_LIGHT=new Color(235,91,101);
    public static final Color WHITE=new Color(255,255,255);
    public static final Color TEXT=new Color(246,249,252);
    public static final Color MUTED=new Color(188,199,210);
    public static final Color RULE=new Color(54,78,99);
    public static final Color TICKER=new Color(4,15,27);

    public static final int HEADER_HEIGHT=170;
    public static final int TICKER_HEIGHT=105;
    public static final int SAFE_SIDE=48;
}