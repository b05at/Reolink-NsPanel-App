package at.reolinkpanel.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Kleine Vorschaubilder (320×180 JPEG) im App-Speicher.
 * - t_<Kamera>_<Sekunden>.jpg: Schnappschuss beim Beginn einer Bewegung
 * - r_<Hash>.jpg:              Bild aus einer bereits abgespielten Aufnahme
 * (Dateien ohne Kamera-Kennung stammen aus Version 1.x und gehören zur ersten Kamera "c1".)
 */
final class ThumbStore {

    static final int WIDTH = 320, HEIGHT = 180;
    private static final long KEEP_MS = 21L * 24 * 3600 * 1000;   // 3 Wochen
    private static final long MATCH_BEFORE_MS = 15000;              // Vorlaufaufnahme der Kamera
    private static final long MATCH_AFTER_MS = 5000;

    private static ThumbStore instance;

    private final File dir;
    /** Kamera -> sortierte Zeitpunkte (ms) aller t_-Bilder. */
    private final java.util.Map<String, long[]> snapTimes = new java.util.HashMap<>();
    private long indexedAt = -1;

    static synchronized ThumbStore get(Context c) {
        if (instance == null) instance = new ThumbStore(c.getApplicationContext());
        return instance;
    }

    private ThumbStore(Context c) {
        dir = new File(c.getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    // ------------------------------------------------------------ Speichern

    /** JPEG der Kamera verkleinern und ablegen. */
    synchronized void saveSnapshot(String camId, long timeMs, byte[] jpeg) throws IOException {
        Bitmap b = decodeScaled(jpeg);
        if (b == null) throw new IOException("Bild ungültig");
        write(b, new File(dir, "t_" + camId + "_" + (timeMs / 1000) + ".jpg"));
        b.recycle();
        indexedAt = -1;
        prune();
    }

    /** Erzeugt ein Vorschaubild aus einer heruntergeladenen Aufnahme (nur falls noch keines existiert). */
    void saveFromVideo(String camId, String recName, File video) {
        File out = recFile(camId, recName);
        if (out.exists()) return;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            Bitmap frame = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return;
            Bitmap small = Bitmap.createScaledBitmap(frame, WIDTH,
                    Math.max(1, frame.getHeight() * WIDTH / Math.max(1, frame.getWidth())), true);
            if (small != frame) frame.recycle();
            synchronized (this) { write(small, out); }
            small.recycle();
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ Suchen

    /** Passendes Vorschaubild für eine Aufnahme oder null. */
    synchronized File find(String camId, ReolinkApi.Recording rec) {
        File f = recFile(camId, rec.name);
        if (f.exists()) return f;
        if ("c1".equals(camId)) {
            File legacy = new File(dir, "r_" + Integer.toHexString(rec.name.hashCode()) + ".jpg");
            if (legacy.exists()) return legacy;
        }
        long[] times = index(camId);
        long from = rec.start - MATCH_BEFORE_MS;
        long to = Math.max(rec.end, rec.start) + MATCH_AFTER_MS;
        int i = Arrays.binarySearch(times, from);
        if (i < 0) i = -i - 1;
        // Bevorzugt das Bild, das am nächsten am Aufnahmebeginn liegt
        long best = -1;
        long bestDist = Long.MAX_VALUE;
        for (; i < times.length && times[i] <= to; i++) {
            long dist = Math.abs(times[i] - rec.start);
            if (dist < bestDist) {
                bestDist = dist;
                best = times[i];
            }
        }
        if (best < 0) return null;
        File cur = new File(dir, "t_" + camId + "_" + (best / 1000) + ".jpg");
        if (cur.exists()) return cur;
        File legacy = new File(dir, "t_" + (best / 1000) + ".jpg");
        return legacy.exists() ? legacy : null;
    }

    /** Lädt ein Vorschaubild sparsam (RGB_565 = halber Speicher). */
    static Bitmap load(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    // ------------------------------------------------------------ intern

    private File recFile(String camId, String recName) {
        return new File(dir, "r_" + Integer.toHexString((camId + "/" + recName).hashCode()) + ".jpg");
    }

    private long[] index(String camId) {
        long mod = dir.lastModified();
        if (mod != indexedAt) {
            snapTimes.clear();
            indexedAt = mod;
        }
        long[] cached = snapTimes.get(camId);
        if (cached != null) return cached;
        String[] names = dir.list();
        long[] t = new long[names == null ? 0 : names.length];
        int n = 0;
        String prefix = "t_" + camId + "_";
        boolean legacyOwner = "c1".equals(camId);
        if (names != null) {
            for (String name : names) {
                if (!name.startsWith("t_") || !name.endsWith(".jpg")) continue;
                String num;
                if (name.startsWith(prefix)) num = name.substring(prefix.length(), name.length() - 4);
                else if (legacyOwner && name.indexOf('_', 2) < 0) num = name.substring(2, name.length() - 4);
                else continue;
                try {
                    t[n++] = Long.parseLong(num) * 1000L;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long[] res = Arrays.copyOf(t, n);
        Arrays.sort(res);
        snapTimes.put(camId, res);
        return res;
    }

    private void prune() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long limit = System.currentTimeMillis() - KEEP_MS;
        for (File f : files) {
            if (f.lastModified() < limit) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static Bitmap decodeScaled(byte[] jpeg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= WIDTH) sample *= 2;   // 4K -> grob vorskalieren, spart CPU
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, o);
        if (b == null || b.getWidth() <= WIDTH) return b;
        Bitmap s = Bitmap.createScaledBitmap(b, WIDTH,
                Math.max(1, b.getHeight() * WIDTH / b.getWidth()), true);
        if (s != b) b.recycle();
        return s;
    }

    private static void write(Bitmap b, File out) throws IOException {
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        OutputStream os = new FileOutputStream(tmp);
        try {
            b.compress(Bitmap.CompressFormat.JPEG, 78, os);
        } finally {
            os.close();
        }
        if (!tmp.renameTo(out)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("Speichern fehlgeschlagen");
        }
    }
}
