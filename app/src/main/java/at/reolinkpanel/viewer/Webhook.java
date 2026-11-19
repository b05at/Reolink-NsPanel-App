package at.reolinkpanel.viewer;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/** Ein Webhook-Knopf (z. B. Shelly, Home-Automation, Garagentor) und dessen Ausführung. */
public class Webhook {
    public String id = "";
    public String name = "Webhook";
    public String icon = "power";
    public String url = "";
    public String method = "GET";      // GET oder POST
    public String body = "";
    public boolean confirm = false;    // vor dem Auslösen nachfragen
    public String camId = "";          // leer = bei allen Kameras zeigen

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("icon", icon);
            o.put("url", url);
            o.put("method", method);
            o.put("body", body);
            o.put("confirm", confirm);
            o.put("camId", camId);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static Webhook fromJson(JSONObject o) {
        Webhook w = new Webhook();
        w.id = o.optString("id", "");
        w.name = o.optString("name", "Webhook");
        w.icon = o.optString("icon", "power");
        w.url = o.optString("url", "");
        w.method = o.optString("method", "GET");
        w.body = o.optString("body", "");
        w.confirm = o.optBoolean("confirm", false);
        w.camId = o.optString("camId", "");
        return w;
    }

    public static List<Webhook> listFromJson(String json) {
        List<Webhook> l = new ArrayList<>();
        if (json == null) return l;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) l.add(fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return l;
    }

    public static String listToJson(List<Webhook> l) {
        JSONArray a = new JSONArray();
        for (Webhook w : l) a.put(w.toJson());
        return a.toString();
    }

    // ------------------------------------------------------------ Ausführen

    /**
     * Ruft die URL auf (läuft im Hintergrund-Thread). Benutzer:Passwort in der URL wird als
     * Basic-Auth gesendet (z. B. http://admin:pw@192.168.1.20/relay/0?turn=on).
     * @return HTTP-Statuscode
     */
    public static int call(String rawUrl, String method, String body) throws IOException {
        String url = rawUrl.trim();
        if (!url.matches("(?i)^https?://.*")) url = "http://" + url;
        URL u = new URL(url);
        String auth = null;
        if (u.getUserInfo() != null && !u.getUserInfo().isEmpty()) {
            auth = "Basic " + Base64.encodeToString(
                    java.net.URLDecoder.decode(u.getUserInfo(), "UTF-8").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            // Zugangsdaten aus der URL entfernen
            url = url.replaceFirst("(?i)^(https?://)[^@/]*@", "$1");
            u = new URL(url);
        }
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        if (con instanceof HttpsURLConnection) Trust.apply((HttpsURLConnection) con);   // Geräte im LAN mit eigenem Zertifikat
        con.setConnectTimeout(6000);
        con.setReadTimeout(10000);
        con.setUseCaches(false);
        con.setRequestProperty("User-Agent", "Kamera-Panel");
        if (auth != null) con.setRequestProperty("Authorization", auth);
        try {
            boolean post = "POST".equalsIgnoreCase(method);
            con.setRequestMethod(post ? "POST" : "GET");
            if (post) {
                byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
                String t = body != null && body.trim().startsWith("{") ? "application/json" : "text/plain; charset=utf-8";
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", t);
                OutputStream os = con.getOutputStream();
                os.write(b);
                os.close();
            }
            int code = con.getResponseCode();
            InputStream is = code >= 400 ? con.getErrorStream() : con.getInputStream();
            if (is != null) {
                byte[] buf = new byte[4096];
                //noinspection StatementWithEmptyBody
                while (is.read(buf) > 0) { }
                is.close();
            }
            return code;
        } finally {
            con.disconnect();
        }
    }

    /** Kurze Rückmeldung für einen Statuscode. */
    public static String describe(int code) {
        if (code >= 200 && code < 300) return "ausgeführt";
        if (code == 401 || code == 403) return "Zugriff verweigert (HTTP " + code + ") – Benutzer/Passwort prüfen";
        if (code == 404) return "Adresse nicht gefunden (HTTP 404)";
        return "Antwort HTTP " + code;
    }
}
