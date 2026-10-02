package autonewsroller.visuals;

import java.awt.Color;

/**
 * Centralized AWARE broadcast visual system.
 *
 * Keep the package deliberately broadcast-oriented: near-black newsroom navy,
 * restrained royal/broadcast blue, urgent breaking-news red, white, and neutral
 * gray. Avoid cyan, teal, electric blue, purple, and high-saturation gradients
 * that read as generic AI/SaaS graphics instead of a live news package.
 */
public final class BroadcastTheme {
    private BroadcastTheme(){}

    public static final String PACKAGE_ID="aware-broadcast-v4-motion";

    public static final Color BACKGROUND=new Color(6,10,16);
    public static final Color NAVY=new Color(9,21,36);
    public static final Color NAVY_2=new Color(14,31,49);
    public static final Color BLUE=new Color(30,74,132);
    public static final Color BLUE_LIGHT=new Color(66,103,153);
    public static final Color RED=new Color(185,23,38);
    public static final Color RED_LIGHT=new Color(220,64,76);
    public static final Color WHITE=new Color(255,255,255);
    public static final Color TEXT=new Color(244,246,249);
    public static final Color MUTED=new Color(181,188,196);
    public static final Color RULE=new Color(58,67,78);
    public static final Color TICKER=new Color(8,12,18);
    public static final Color POLITICAL_LEFT=BLUE_LIGHT;
    public static final Color POLITICAL_CENTER=new Color(206,211,217);
    public static final Color POLITICAL_RIGHT=RED_LIGHT;
    public static final Color POLITICAL_UNKNOWN=new Color(117,122,128);
    public static final Color PANEL=new Color(11,22,35);

    public static final int HEADER_HEIGHT=118;
    public static final int TICKER_HEIGHT=80;
    public static final int SAFE_SIDE=48;
    public static final int POLITICAL_PANEL_X=58;
    public static final int POLITICAL_PANEL_Y=185;
    public static final int POLITICAL_PANEL_BOTTOM_SAFE=1660;
}
