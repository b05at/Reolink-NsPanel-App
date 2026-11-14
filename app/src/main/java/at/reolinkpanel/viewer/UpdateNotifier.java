package at.reolinkpanel.viewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

/** Prüft im Hintergrund (höchstens alle 12 h) auf neue Versionen und meldet sie per Benachrichtigung. */
public final class UpdateNotifier {

    private static final long INTERVAL_MS = 12L * 3600 * 1000;
    private static final String CHANNEL = "updates";
    private static volatile boolean running;

    private UpdateNotifier() {}

    public static boolean autoUpdate(Context c) { return Prefs.sp(c).getBoolean("autoUpdate", true); }

    /** Bekannte neuere Version (aus der letzten Prüfung) oder null. */
    public static String availableVersion(Context c) {
        String v = Prefs.sp(c).getString("updateVersion", null);
        return v != null && Updater.isNewer(v, Updater.currentVersion(c)) ? v : null;
    }

    public static void maybeCheck(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        if (!autoUpdate(c) || running) return;
        long last = Prefs.sp(c).getLong("lastUpdateCheck", 0);
        if (System.currentTimeMillis() - last < INTERVAL_MS) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Updater.Info info = Updater.check(c);
                    Prefs.sp(c).edit()
                            .putLong("lastUpdateCheck", System.currentTimeMillis())
                            .putString("updateVersion", info != null ? info.version : null)
                            .apply();
                    if (info != null) notifyUpdate(c, info.version);
                } catch (Exception ignored) {
                    // kein Netz o. Ä. – beim nächsten Mal wieder
                } finally {
                    running = false;
                }
            }
        }, "update-check").start();
    }

    static void notifyUpdate(Context c, String version) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent pi = PendingIntent.getActivity(c, 3, new Intent(c, SettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cam)
                .setContentTitle("Kamera " + version + " verfügbar")
                .setContentText("Antippen zum Installieren")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(2, n);
    }
}
