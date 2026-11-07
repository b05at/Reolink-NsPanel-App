package at.reolinkpanel.viewer;

import android.content.Context;
import android.content.SharedPreferences;

/** Alle Einstellungen der App (SharedPreferences). */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    public static String host(Context c)        { return sp(c).getString("host", "").trim(); }
    public static String user(Context c)        { return sp(c).getString("user", "admin").trim(); }
    public static String pass(Context c)        { return sp(c).getString("pass", ""); }
    /** Neuere Reolink-Firmware hat HTTP aus und HTTPS an – daher Standard HTTPS. */
    public static boolean https(Context c)      { return sp(c).getBoolean("https", true); }
    /** 0 = Standardport (80 bzw. 443). */
    public static int httpPort(Context c)       { return sp(c).getInt("httpPort", 0); }
    public static int rtspPort(Context c)       { return sp(c).getInt("rtspPort", 554); }
    /** Leer = automatisch (Preview_01_sub / Preview_01_main). */
    public static String rtspPath(Context c)    { return sp(c).getString("rtspPath", "").trim(); }

    /** "sub" oder "main" – Sub ist für das 480x480-Panel ideal. */
    public static String liveStream(Context c)  { return sp(c).getString("liveStream", "sub"); }
    public static String recStream(Context c)   { return sp(c).getString("recStream", "sub"); }

    /** "off", "any", "person", "person_vehicle" */
    public static String trigger(Context c)     { return sp(c).getString("trigger", "any"); }
    public static int wakeSeconds(Context c)    { return sp(c).getInt("wakeSeconds", 60); }
    public static int pollMs(Context c)         { return sp(c).getInt("pollMs", 1000); }

    public static boolean bringToFront(Context c) { return sp(c).getBoolean("bringToFront", true); }
    public static boolean startOnBoot(Context c)  { return sp(c).getBoolean("startOnBoot", true); }
    public static boolean openOnBoot(Context c)   { return sp(c).getBoolean("openOnBoot", false); }

    /** Sirene schaltet sich spätestens nach dieser Zeit selbst ab. */
    public static int sirenSeconds(Context c)   { return sp(c).getInt("sirenSeconds", 30); }

    public static boolean muted(Context c)    { return sp(c).getBoolean("muted", true); }
    public static boolean zoom(Context c)       { return sp(c).getBoolean("zoom", false); }

    public static boolean isConfigured(Context c) {
        return !host(c).isEmpty() && !user(c).isEmpty();
    }
}
