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
 * - t_<Sekunden>.jpg: Schnappschuss beim Beginn einer Bewegung
 * - r_<Hash>.jpg:     Bild aus einer bereits abgespielten Aufnahme
 */
final class ThumbStore {

    static final int WIDTH = 320, HEIGHT = 180;
    private static final long KEEP_MS = 21L * 24 * 3600 * 1000;   // 3 Wochen
    private static final long MATCH_BEFORE_MS = 15000;              // Vorlaufaufnahme der Kamera
    private static final long MATCH_AFTER_MS = 5000;

    private static ThumbStore instance;

    private final File dir;
    private long[] snapTimes;      // sortierte Zeitpunkte (ms) aller t_-Bilder
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
    synchronized void saveSnapshot(long timeMs, byte[] jpeg) throws IOException {
        Bitmap b = decodeScaled(jpeg);
        if (b == null) throw new IOException("Bild ungültig");
        write(b, new File(dir, "t_" + (timeMs / 1000) + ".jpg"));
        b.recycle();
        indexedAt = -1;
        prune();
    }

    /** Erzeugt ein Vorschaubild aus einer heruntergeladenen Aufnahme (nur falls noch keines existiert). */
    void saveFromVideo(String recName, File video) {
        File out = recFile(recName);
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
    synchronized File find(ReolinkApi.Recording rec) {
        File f = recFile(rec.name);
        if (f.exists()) return f;
        long[] times = index();
        long from = rec.start - MATCH_BEFORE_MS;
        long to = Math.max(rec.end, rec.start) + MATCH_AFTER_MS;
        int i = Arrays.binarySearch(times, from);
        if (i < 0) i = -i - 1;
        // Bevorzugt das Bild, das am nächsten am Aufnahmebeginn liegt
        File best = null;
        long bestDist = Long.MAX_VALUE;
        for (; i < times.length && times[i] <= to; i++) {
            long dist = Math.abs(times[i] - rec.start);
            if (dist < bestDist) {
                bestDist = dist;
                best = new File(dir, "t_" + (times[i] / 1000) + ".jpg");
            }
        }
        return best;
    }

    /** Lädt ein Vorschaubild sparsam (RGB_565 = halber Speicher). */
    static Bitmap load(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    // ------------------------------------------------------------ intern

    private File recFile(String recName) {
        return new File(dir, "r_" + Integer.toHexString(recName.hashCode()) + ".jpg");
    }

    private long[] index() {
        long mod = dir.lastModified();
        if (snapTimes != null && mod == indexedAt) return snapTimes;
        String[] names = dir.list();
        long[] t = new long[names == null ? 0 : names.length];
        int n = 0;
        if (names != null) {
            for (String name : names) {
                if (!name.startsWith("t_") || !name.endsWith(".jpg")) continue;
                try {
                    t[n++] = Long.parseLong(name.substring(2, name.length() - 4)) * 1000L;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        snapTimes = Arrays.copyOf(t, n);
        Arrays.sort(snapTimes);
        indexedAt = mod;
        return snapTimes;
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
