package at.reolinkpanel.viewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Alle Einstellungen der App (SharedPreferences). */
public final class Prefs {
    private Prefs() {}

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("config", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ Kameras

    /** Alle eingerichteten Kameras. Übernimmt beim ersten Aufruf die Kamera aus Version 1.x. */
    public static synchronized List<Cam> cameras(Context c) {
        SharedPreferences sp = sp(c);
        List<Cam> list = new ArrayList<>();
        String json = sp.getString("cameras", null);
        if (json == null) {
            // Migration: bisherige Einzelkamera übernehmen
            String host = sp.getString("host", "").trim();
            if (!host.isEmpty()) {
                Cam cam = new Cam();
                cam.id = "c1";
                cam.name = "Kamera";
                cam.host = host;
                cam.user = sp.getString("user", "admin").trim();
                cam.pass = sp.getString("pass", "");
                cam.https = sp.getBoolean("https", true);
                cam.httpPort = sp.getInt("httpPort", 0);
                cam.rtspPort = sp.getInt("rtspPort", 554);
                cam.rtspPath = sp.getString("rtspPath", "").trim();
                list.add(cam);
            }
            saveCameras(c, list);
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) list.add(Cam.fromJson(arr.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static synchronized void saveCameras(Context c, List<Cam> list) {
        JSONArray arr = new JSONArray();
        for (Cam cam : list) arr.put(cam.toJson());
        sp(c).edit().putString("cameras", arr.toString()).commit();
    }

    public static Cam camera(Context c, String id) {
        if (id == null) return null;
        for (Cam cam : cameras(c)) if (id.equals(cam.id)) return cam;
        return null;
    }

    /** Die im Livebild gewählte Kamera (oder die erste). */
    public static Cam current(Context c) {
        List<Cam> list = cameras(c);
        if (list.isEmpty()) return null;
        String id = sp(c).getString("currentCam", null);
        for (Cam cam : list) if (cam.id.equals(id)) return cam;
        return list.get(0);
    }

    public static void setCurrent(Context c, String id) {
        sp(c).edit().putString("currentCam", id).apply();
    }

    public static String newCameraId(Context c) {
        int max = 0;
        for (Cam cam : cameras(c)) {
            try { max = Math.max(max, Integer.parseInt(cam.id.substring(1))); } catch (Exception ignored) {}
        }
        return "c" + (max + 1);
    }

    /** "single" oder "grid" (Übersicht). */
    public static String viewMode(Context c)    { return sp(c).getString("viewMode", "single"); }
    public static void setViewMode(Context c, String m) { sp(c).edit().putString("viewMode", m).apply(); }

    // ------------------------------------------------------------ Bild

    /** "sub" oder "main" – Sub ist für das 480x480-Panel ideal. */
    public static String liveStream(Context c)  { return sp(c).getString("liveStream", "sub"); }
    public static String recStream(Context c)   { return sp(c).getString("recStream", "sub"); }

    /** Übersicht: echtes Livevideo statt Standbildern (mehr CPU). */
    public static boolean gridLive(Context c)   { return sp(c).getBoolean("gridLive", false); }
    /** Übersicht: Standbilder alle x Sekunden. */
    public static int gridSeconds(Context c)    { return sp(c).getInt("gridSeconds", 2); }

    // ------------------------------------------------------------ Bewegung / Klingel

    /** "off", "any", "person", "person_vehicle" */
    public static String trigger(Context c)     { return sp(c).getString("trigger", "any"); }
    public static int wakeSeconds(Context c)    { return sp(c).getInt("wakeSeconds", 60); }
    public static int pollMs(Context c)         { return sp(c).getInt("pollMs", 1000); }
    /** Gong am Panel, wenn an der Türklingel geläutet wird. */
    public static boolean chime(Context c)      { return sp(c).getBoolean("chime", true); }

    public static boolean bringToFront(Context c) { return sp(c).getBoolean("bringToFront", true); }
    public static boolean startOnBoot(Context c)  { return sp(c).getBoolean("startOnBoot", true); }
    public static boolean openOnBoot(Context c)   { return sp(c).getBoolean("openOnBoot", false); }

    /** Sirene schaltet sich spätestens nach dieser Zeit selbst ab. */
    public static int sirenSeconds(Context c)   { return sp(c).getInt("sirenSeconds", 30); }

    /** Schwenk-Geschwindigkeit 1–64. */
    public static int ptzSpeed(Context c)       { return sp(c).getInt("ptzSpeed", 32); }

    /** Vorschaubilder bei Bewegung speichern. */
    public static boolean thumbnails(Context c) { return sp(c).getBoolean("thumbnails", true); }

    /** Bildschirm-Timeout im Livebild in Sekunden: -1 = System-Standard, 0 = nie. */
    public static int screenTimeout(Context c)  { return sp(c).getInt("screenTimeout", -1); }
    /** Ruhemodus statt echtem Ausschalten: Berührung weckt das Display. */
    public static boolean tapToWake(Context c)  { return sp(c).getBoolean("tapToWake", false); }

    public static boolean muted(Context c)      { return sp(c).getBoolean("muted", true); }
    public static boolean zoom(Context c)       { return sp(c).getBoolean("zoom", false); }

    public static boolean isConfigured(Context c) {
        return !cameras(c).isEmpty();
    }
}
