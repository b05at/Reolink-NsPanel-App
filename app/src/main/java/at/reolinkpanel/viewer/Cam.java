package at.reolinkpanel.viewer;

import org.json.JSONException;
import org.json.JSONObject;

/** Zugangsdaten und Eigenschaften einer Kamera bzw. Türklingel. */
public class Cam {
    public String id = "";
    public String name = "Kamera";
    public String host = "";
    public String user = "admin";
    public String pass = "";
    public boolean https = true;
    public int httpPort = 0;        // 0 = Standard (80/443)
    public int rtspPort = 554;
    public String rtspPath = "";    // leer = automatisch
    public boolean doorbell = false;
    /** Bewegung dieser Kamera schaltet den Bildschirm ein. */
    public boolean wake = true;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("user", user);
            o.put("pass", pass);
            o.put("https", https);
            o.put("httpPort", httpPort);
            o.put("rtspPort", rtspPort);
            o.put("rtspPath", rtspPath);
            o.put("doorbell", doorbell);
            o.put("wake", wake);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Cam fromJson(JSONObject o) {
        Cam c = new Cam();
        c.id = o.optString("id", "");
        c.name = o.optString("name", "Kamera");
        c.host = o.optString("host", "");
        c.user = o.optString("user", "admin");
        c.pass = o.optString("pass", "");
        c.https = o.optBoolean("https", true);
        c.httpPort = o.optInt("httpPort", 0);
        c.rtspPort = o.optInt("rtspPort", 554);
        c.rtspPath = o.optString("rtspPath", "");
        c.doorbell = o.optBoolean("doorbell", false);
        c.wake = o.optBoolean("wake", true);
        return c;
    }

    /** Kennung für "Verbindungsdaten unverändert?". */
    public String signature() { return toJson().toString(); }

    public String displayName() {
        return name == null || name.trim().isEmpty() ? host : name.trim();
    }
}
