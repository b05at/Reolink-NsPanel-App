package at.reolinkpanel.viewer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Prüft GitHub-Releases auf eine neuere Version, lädt die APK und startet die Installation. */
public final class Updater {

    public static final String REPO = "b05at/Reolink-NsPanel-App";

    public static class Info {
        public String version, notes, url;
        public long size;
    }

    public interface Progress { boolean onProgress(long done, long total); }

    private Updater() {}

    /** Installierte Version (versionName). */
    public static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** Neuere Version auf GitHub oder null. */
    public static Info check(Context c) throws IOException {
        HttpURLConnection con = open("https://api.github.com/repos/" + REPO + "/releases/latest");
        con.setRequestProperty("Accept", "application/vnd.github+json");
        con.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            int code = con.getResponseCode();
            if (code == 404) throw new IOException("Kein Release gefunden");
            if (code != 200) throw new IOException("GitHub antwortet mit HTTP " + code);
            JSONObject o = new JSONObject(readAll(con.getInputStream()));
            String tag = o.optString("tag_name", "");
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) return null;
            JSONObject apk = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) { apk = a; break; }
            }
            if (apk == null || !isNewer(tag, currentVersion(c))) return null;
            Info info = new Info();
            info.version = tag.replaceAll("-build\\d+$", "");
            info.notes = o.optString("body", "");
            info.url = apk.optString("browser_download_url");
            info.size = apk.optLong("size", 0);
            return info;
        } catch (org.json.JSONException e) {
            throw new IOException("Antwort von GitHub ungültig");
        } finally {
            con.disconnect();
        }
    }

    /** Lädt die APK in den Cache (cache/updates/). */
    public static File download(Context c, Info info, Progress cb) throws IOException {
        File dir = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        File target = new File(dir, "Kamera-" + info.version + ".apk");
        HttpURLConnection con = open(info.url);
        con.setReadTimeout(30000);
        try {
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("Download fehlgeschlagen: HTTP " + code);
            long total = con.getContentLength() > 0 ? con.getContentLength() : info.size;
            InputStream in = con.getInputStream();
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (cb != null && !cb.onProgress(done, total)) throw new InterruptedIOException("Abgebrochen");
                }
            } finally {
                out.close();
                in.close();
            }
            return target;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw e;
        } finally {
            con.disconnect();
        }
    }

    public static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    public static void openInstallPermissionSettings(Context c) {
        c.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Startet den Android-Installer. false = auf diesem Gerät kein Installer vorhanden. */
    public static boolean install(Context c, File apk) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /** Vergleicht Versionen wie "2.5.2" bzw. "2.5.2-build19". */
    static boolean isNewer(String remote, String local) {
        int[] a = parts(remote), b = parts(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        // Build-Nummer ignorieren – maßgeblich ist die Versionsnummer
        v = v.replaceAll("-build\\d+$", "");
        String[] p = v.split("[.\\-+]");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            int j = 0;
            while (j < p[i].length() && Character.isDigit(p[i].charAt(j))) j++;
            try { r[i] = j == 0 ? 0 : Integer.parseInt(p[i].substring(0, j)); } catch (NumberFormatException e) { r[i] = 0; }
        }
        return r;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(15000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "Kamera-Updater");
        return con;
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
}
