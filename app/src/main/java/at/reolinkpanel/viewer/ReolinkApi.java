package at.reolinkpanel.viewer;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** Schlanker Client für die Reolink HTTP-API (/cgi-bin/api.cgi). */
public class ReolinkApi {

    public static class ApiException extends Exception {
        public final int rspCode;
        public ApiException(int rspCode, String msg) { super(msg); this.rspCode = rspCode; }
    }

    public static class Recording {
        public String name;
        public long start;
        public long end;
        public long size;
    }

    /** Rückgabe false = Abbrechen. */
    public interface Progress { boolean onProgress(long done, long total); }

    private final String host, user, pass;
    private final boolean https;
    private final int port, rtspPort;
    private final String rtspPathOverride;

    private String token;
    private long tokenValidUntil;
    private boolean aiSupported = true;

    public ReolinkApi(String host, String user, String pass, boolean https, int port, int rtspPort, String rtspPath) {
        this.host = host;
        this.user = user;
        this.pass = pass;
        this.https = https;
        this.port = port;
        this.rtspPort = rtspPort;
        this.rtspPathOverride = rtspPath == null ? "" : rtspPath;
    }

    /** API der aktuell gewählten Kamera. */
    public static ReolinkApi from(Context c) {
        Cam cam = Prefs.current(c);
        return from(cam != null ? cam : new Cam());
    }

    public static ReolinkApi from(Cam cam) {
        return new ReolinkApi(cam.host.trim(), cam.user.trim(), cam.pass, cam.https,
                cam.httpPort, cam.rtspPort, cam.rtspPath.trim());
    }

    // ---------------------------------------------------------------- Livebild

    /** RTSP-URL, z. B. rtsp://admin:pw@192.168.1.50:554/Preview_01_sub */
    /** Eigener Pfad (z. B. "h264Preview_01_sub") oder vollständige rtsp://-URL. */
    public String rtspUrlCustom(String pathOrUrl) {
        String p = pathOrUrl.trim();
        if (p.toLowerCase(java.util.Locale.ROOT).startsWith("rtsp://")) {
            // Vollständige URL: Zugangsdaten ergänzen, falls keine enthalten sind
            String rest = p.substring(7);
            if (rest.contains("@")) return p;
            return "rtsp://" + enc(user) + ":" + enc(pass) + "@" + rest;
        }
        if (p.startsWith("/")) p = p.substring(1);
        return "rtsp://" + enc(user) + ":" + enc(pass) + "@" + host + ":" + rtspPort + "/" + p;
    }

    public String rtspUrl(String stream) {
        String path = rtspPathOverride.isEmpty() ? "Preview_01_" + stream : rtspPathOverride;
        if (path.startsWith("/")) path = path.substring(1);
        return "rtsp://" + enc(user) + ":" + enc(pass) + "@" + host + ":" + rtspPort + "/" + path;
    }

    // ---------------------------------------------------------------- Basis

    private String base() {
        return (https ? "https" : "http") + "://" + host + (port > 0 ? ":" + port : "") + "/cgi-bin/api.cgi";
    }

    private HttpURLConnection open(String url, int readTimeoutMs) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        if (con instanceof HttpsURLConnection) Trust.apply((HttpsURLConnection) con);
        con.setConnectTimeout(5000);
        con.setReadTimeout(readTimeoutMs);
        con.setUseCaches(false);
        return con;
    }

    private JSONObject post(String cmd, String tok, JSONObject param) throws IOException, ApiException {
        String url = base() + "?cmd=" + cmd + (tok != null ? "&token=" + tok : "");
        JSONArray body = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("cmd", cmd);
            o.put("action", 0);
            o.put("param", param == null ? new JSONObject() : param);
            body.put(o);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection con = open(url, 10000);
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = con.getOutputStream();
            os.write(b);
            os.close();
            int http = con.getResponseCode();
            InputStream is = http >= 400 ? con.getErrorStream() : con.getInputStream();
            String txt = is == null ? "" : readAll(is);
            if (txt.trim().isEmpty()) throw new IOException("HTTP " + http);
            return parse(txt);
        } finally {
            con.disconnect();
        }
    }

    private static JSONObject parse(String txt) throws IOException, ApiException {
        JSONObject o;
        try {
            JSONArray arr = new JSONArray(txt.trim());
            o = arr.getJSONObject(0);
        } catch (JSONException e) {
            throw new IOException("Ungültige Antwort der Kamera");
        }
        if (o.optInt("code", -1) != 0) {
            JSONObject err = o.optJSONObject("error");
            int rsp = err != null ? err.optInt("rspCode", -1) : -1;
            String detail = err != null ? err.optString("detail", "Fehler") : "Fehler";
            throw new ApiException(rsp, detail + " (" + rsp + ")");
        }
        return o;
    }

    public synchronized void login() throws IOException, ApiException {
        try {
            JSONObject u = new JSONObject();
            u.put("Version", "0");
            u.put("userName", user);
            u.put("password", pass);
            JSONObject p = new JSONObject();
            p.put("User", u);
            JSONObject r = post("Login", null, p);
            JSONObject t = r.getJSONObject("value").getJSONObject("Token");
            token = t.getString("name");
            int lease = t.optInt("leaseTime", 3600);
            tokenValidUntil = System.currentTimeMillis() + Math.max(60, lease - 60) * 1000L;
        } catch (JSONException e) {
            throw new IOException("Login-Antwort ungültig");
        }
    }

    private synchronized String token() throws IOException, ApiException {
        if (token == null || System.currentTimeMillis() > tokenValidUntil) login();
        return token;
    }

    private synchronized void invalidateToken() { token = null; }

    /** API-Befehl mit Token; meldet sich bei abgelaufenem Token automatisch neu an. */
    public JSONObject call(String cmd, JSONObject param) throws IOException, ApiException {
        try {
            return post(cmd, token(), param);
        } catch (ApiException e) {
            if (e.rspCode == -6) {              // "please login first"
                invalidateToken();
                return post(cmd, token(), param);
            }
            throw e;
        }
    }

    private static JSONObject channelParam() {
        JSONObject p = new JSONObject();
        try { p.put("channel", 0); } catch (JSONException ignored) {}
        return p;
    }

    // ---------------------------------------------------------------- Infos

    /** RTSP-Status laut Kamera: {aktiv (1/0, -1 = unbekannt), Port}. */
    public int[] rtspSettings() throws IOException, ApiException {
        JSONObject v = call("GetNetPort", channelParam()).optJSONObject("value");
        JSONObject n = v == null ? null : v.optJSONObject("NetPort");
        if (n == null) return new int[]{-1, 554};
        return new int[]{n.optInt("rtspEnable", -1), n.optInt("rtspPort", 554)};
    }

    public String deviceName() throws IOException, ApiException {
        JSONObject v = call("GetDevInfo", channelParam()).optJSONObject("value");
        JSONObject d = v == null ? null : v.optJSONObject("DevInfo");
        if (d == null) return "Reolink-Kamera";
        String model = d.optString("model", "Reolink");
        String name = d.optString("name", "");
        return name.isEmpty() || name.equals(model) ? model : model + " – " + name;
    }

    // ---------------------------------------------------------------- Bewegung

    /** trigger: "any", "person", "person_vehicle" */
    /** Rohzustand der normalen Bewegungserkennung. */
    public boolean mdState() throws IOException, ApiException {
        JSONObject v = call("GetMdState", channelParam()).optJSONObject("value");
        return v != null && v.optInt("state", 0) == 1;
    }

    /**
     * Ob der gewählte Auslöser aktiv ist. md = bereits abgefragter Bewegungszustand.
     * trigger: "any", "person", "person_vehicle"
     */
    public boolean isMotion(String trigger, boolean md) throws IOException, ApiException {
        if (!"any".equals(trigger) && aiSupported) {
            try {
                JSONObject v = call("GetAiState", channelParam()).optJSONObject("value");
                if (v != null) {
                    boolean person = alarm(v, "people");
                    if ("person".equals(trigger)) return person;
                    return person || alarm(v, "vehicle");
                }
            } catch (ApiException e) {
                aiSupported = false;   // Fallback auf normale Bewegungserkennung
            }
        }
        return md;
    }

    // ---------------------------------------------------------------- Schnappschuss

    /** Aktuelles Kamerabild als JPEG (Breite/Höhe werden von neuerer Firmware berücksichtigt). */
    public byte[] snap(int width, int height) throws IOException, ApiException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String url = base() + "?cmd=Snap&channel=0&rs=" + Long.toHexString(System.nanoTime())
                    + "&width=" + width + "&height=" + height + "&token=" + token();
            HttpURLConnection con = open(url, 15000);
            try {
                int http = con.getResponseCode();
                InputStream is = http >= 400 ? con.getErrorStream() : con.getInputStream();
                if (is == null) throw new IOException("HTTP " + http);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                try {
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                } finally {
                    is.close();
                }
                byte[] data = bos.toByteArray();
                boolean jpeg = data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
                if (jpeg) return data;
                try {
                    parse(new String(data, StandardCharsets.UTF_8));
                } catch (ApiException e) {
                    if (e.rspCode == -6 && attempt == 0) { invalidateToken(); continue; }
                    throw e;
                }
                throw new IOException("Kein Bild erhalten");
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("Kein Bild erhalten");
    }

    private static boolean alarm(JSONObject v, String key) {
        JSONObject o = v.optJSONObject(key);
        return o != null && o.optInt("alarm_state", 0) == 1;
    }

    // ---------------------------------------------------------------- Türklingel

    /** Ergebnis von GetEvents (neuere Firmware, u. a. Video Doorbell). */
    public static class Events {
        public boolean md, visitor, visitorSupported;
    }

    public Events events() throws IOException, ApiException {
        JSONObject v = call("GetEvents", channelParam()).optJSONObject("value");
        Events e = new Events();
        if (v == null) return e;
        JSONObject md = v.optJSONObject("md");
        e.md = md != null && md.optInt("alarm_state", 0) == 1;
        JSONObject vis = v.optJSONObject("visitor");
        e.visitorSupported = vis != null;
        e.visitor = vis != null && vis.optInt("alarm_state", 0) == 1;
        return e;
    }

    // ---------------------------------------------------------------- Scheinwerfer

    public boolean whiteLed() throws IOException, ApiException {
        JSONObject v = call("GetWhiteLed", channelParam()).optJSONObject("value");
        JSONObject w = v == null ? null : v.optJSONObject("WhiteLed");
        return w != null && w.optInt("state", 0) == 1;
    }

    public void setWhiteLed(boolean on) throws IOException, ApiException {
        try {
            JSONObject w = new JSONObject();
            w.put("channel", 0);
            w.put("state", on ? 1 : 0);
            JSONObject p = new JSONObject();
            p.put("WhiteLed", w);
            call("SetWhiteLed", p);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Modellname laut Kamera (für die automatische Erkennung der Türklingel). */
    public String model() throws IOException, ApiException {
        JSONObject v = call("GetDevInfo", channelParam()).optJSONObject("value");
        JSONObject d = v == null ? null : v.optJSONObject("DevInfo");
        return d == null ? "" : d.optString("model", "");
    }

    // ---------------------------------------------------------------- PTZ

    /** op: Left, Right, Up, Down, ZoomInc, ZoomDec, Stop. speed 1–64. */
    public void ptz(String op, int speed) throws IOException, ApiException {
        try {
            JSONObject p = new JSONObject();
            p.put("channel", 0);
            p.put("op", op);
            if (!"Stop".equals(op)) p.put("speed", Math.max(1, Math.min(64, speed)));
            call("PtzCtrl", p);
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Sirene

    /** Sirene ein (Dauerton bis zum Ausschalten) oder aus. */
    public void siren(boolean on) throws IOException, ApiException {
        try {
            JSONObject p = new JSONObject();
            p.put("channel", 0);
            p.put("alarm_mode", "manul");          // Schreibweise laut Reolink-API
            p.put("manual_switch", on ? 1 : 0);
            try {
                call("AudioAlarmPlay", p);
            } catch (ApiException e) {
                if (!on) throw e;
                // Fallback für Firmware ohne Dauerton: Sirene einmal abspielen
                JSONObject t = new JSONObject();
                t.put("channel", 0);
                t.put("alarm_mode", "times");
                t.put("times", 1);
                call("AudioAlarmPlay", t);
            }
        } catch (JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- Aufnahmen

    /** Alle Aufnahmen eines Tages, neueste zuerst. */
    public List<Recording> search(Calendar day, String stream) throws IOException, ApiException {
        Map<String, Recording> map = new LinkedHashMap<>();
        try {
            // In 4-Stunden-Blöcken suchen, da die Kamera pro Anfrage nur begrenzt viele Dateien liefert.
            for (int h = 0; h < 24; h += 4) {
                Calendar s = (Calendar) day.clone();
                s.set(Calendar.HOUR_OF_DAY, h);
                s.set(Calendar.MINUTE, 0);
                s.set(Calendar.SECOND, 0);
                s.set(Calendar.MILLISECOND, 0);
                Calendar e = (Calendar) s.clone();
                e.add(Calendar.HOUR_OF_DAY, 4);
                e.add(Calendar.SECOND, -1);

                JSONObject sp = new JSONObject();
                sp.put("channel", 0);
                sp.put("onlyStatus", 0);
                sp.put("streamType", stream);
                sp.put("StartTime", toJson(s));
                sp.put("EndTime", toJson(e));
                JSONObject p = new JSONObject();
                p.put("Search", sp);

                JSONObject v = call("Search", p).optJSONObject("value");
                JSONObject sr = v == null ? null : v.optJSONObject("SearchResult");
                JSONArray files = sr == null ? null : sr.optJSONArray("File");
                if (files == null) continue;
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    Recording r = new Recording();
                    r.name = f.getString("name");
                    r.start = fromJson(f.optJSONObject("StartTime"));
                    r.end = fromJson(f.optJSONObject("EndTime"));
                    r.size = f.optLong("size", 0);
                    map.put(r.name, r);
                }
            }
        } catch (JSONException e) {
            throw new IOException("Suchergebnis ungültig");
        }
        List<Recording> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Recording>() {
            @Override public int compare(Recording a, Recording b) { return Long.compare(b.start, a.start); }
        });
        return list;
    }

    /** Lädt eine Aufnahme von der SD-Karte in eine lokale Datei. */
    public void download(Recording r, File out, Progress cb) throws IOException, ApiException {
        String fname = r.name.substring(r.name.lastIndexOf('/') + 1);
        File tmp = new File(out.getParentFile(), out.getName() + ".part");
        for (int attempt = 0; attempt < 2; attempt++) {
            String url = base() + "?cmd=Download&source=" + enc(r.name) + "&output=" + enc(fname)
                    + "&token=" + token();
            HttpURLConnection con = open(url, 30000);
            try {
                int http = con.getResponseCode();
                String ct = con.getContentType();
                boolean isText = ct != null && (ct.contains("json") || ct.contains("text"));
                if (http >= 400 || isText) {
                    InputStream es = http >= 400 ? con.getErrorStream() : con.getInputStream();
                    String txt = es == null ? "" : readAll(es);
                    try {
                        parse(txt);
                    } catch (ApiException e) {
                        if (e.rspCode == -6 && attempt == 0) { invalidateToken(); continue; }
                        throw e;
                    } catch (IOException ignored) {
                    }
                    throw new IOException("Download fehlgeschlagen (HTTP " + http + ")");
                }
                long total = con.getContentLength();
                if (total <= 0) total = r.size;
                InputStream in = con.getInputStream();
                OutputStream os = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[64 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        done += n;
                        if (!cb.onProgress(done, total)) throw new InterruptedIOException("Abgebrochen");
                    }
                } finally {
                    os.close();
                    in.close();
                }
                if (out.exists()) out.delete();
                if (!tmp.renameTo(out)) throw new IOException("Datei konnte nicht gespeichert werden");
                return;
            } catch (IOException e) {
                tmp.delete();
                throw e;
            } finally {
                con.disconnect();
            }
        }
        throw new IOException("Download fehlgeschlagen");
    }

    // ---------------------------------------------------------------- Hilfen

    private static JSONObject toJson(Calendar c) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("year", c.get(Calendar.YEAR));
        o.put("mon", c.get(Calendar.MONTH) + 1);
        o.put("day", c.get(Calendar.DAY_OF_MONTH));
        o.put("hour", c.get(Calendar.HOUR_OF_DAY));
        o.put("min", c.get(Calendar.MINUTE));
        o.put("sec", c.get(Calendar.SECOND));
        return o;
    }

    private static long fromJson(JSONObject o) {
        if (o == null) return 0;
        Calendar c = Calendar.getInstance();
        c.set(o.optInt("year"), o.optInt("mon") - 1, o.optInt("day"),
                o.optInt("hour"), o.optInt("min"), o.optInt("sec"));
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            is.close();
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
