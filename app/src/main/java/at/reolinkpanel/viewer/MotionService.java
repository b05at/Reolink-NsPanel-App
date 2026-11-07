package at.reolinkpanel.viewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Hintergrunddienst: fragt die Kamera zyklisch nach Bewegung ab,
 * weckt bei Bewegung den Bildschirm und holt das Livebild nach vorne.
 */
public class MotionService extends Service {

    public static final String ACTION_RELOAD = "at.reolinkpanel.viewer.RELOAD";
    public static final String EXTRA_MOTION = "motion";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;

    public interface Listener { void onMotionChanged(boolean active); }

    private static volatile Listener listener;
    public static volatile boolean motionActive = false;
    public static volatile String status = "Startet…";

    public static void setListener(Listener l) { listener = l; }
    public static void clearListener(Listener l) { if (listener == l) listener = null; }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, MotionService.class));
    }

    public static void reload(Context c) {
        c.startForegroundService(new Intent(c, MotionService.class).setAction(ACTION_RELOAD));
    }

    private volatile boolean running;
    private volatile ReolinkApi api;
    private Thread worker;
    private long lastMotionAt;
    private boolean lastMd;
    private long lastThumbAt;
    private final java.util.concurrent.ExecutorService thumbExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private String shownStatus;

    private PowerManager pm;
    private PowerManager.WakeLock cpuLock;
    private PowerManager.WakeLock screenLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Kamera-Überwachung",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification("Startet…"));

        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // CPU wach halten, damit die Abfrage auch bei ausgeschaltetem Display läuft.
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "reolinkpanel:cpu");
        cpuLock.setReferenceCounted(false);
        cpuLock.acquire();

        //noinspection deprecation – funktioniert auf Android 8.x zuverlässig
        screenLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "reolinkpanel:screen");
        screenLock.setReferenceCounted(false);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "reolinkpanel:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "motion-poll");
        worker.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Bei jedem startForegroundService() muss startForeground() erneut aufgerufen werden.
        startForeground(NOTIF_ID, buildNotification(status));
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            api = null;   // neue Zugangsdaten übernehmen
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        thumbExecutor.shutdownNow();
        if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------------

    private void loop() {
        while (running) {
            try {
                if (!Prefs.isConfigured(this)) {
                    setStatus("Nicht eingerichtet");
                    Thread.sleep(5000);
                    continue;
                }
                String trigger = Prefs.trigger(this);
                boolean wake = !"off".equals(trigger);
                boolean thumbs = Prefs.thumbnails(this);
                if (!wake && !thumbs) {
                    setStatus("Bewegungserkennung ausgeschaltet");
                    handle(false);
                    Thread.sleep(5000);
                    continue;
                }
                ReolinkApi a = api;
                if (a == null) {
                    a = ReolinkApi.from(this);
                    api = a;
                }
                boolean md = a.mdState();
                if (md && !lastMd && thumbs) takeThumbnail(a);
                lastMd = md;

                boolean motion = wake && a.isMotion(trigger, md);
                setStatus(!wake ? "Nur Vorschaubilder aktiv"
                        : motion ? "Bewegung erkannt" : "Aktiv – keine Bewegung");
                handle(motion);
                Thread.sleep(Math.max(300, Prefs.pollMs(this)));
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                setStatus("Kamera nicht erreichbar: " + e.getMessage());
                handle(false);
                lastMd = false;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    /** Beim Beginn einer Bewegung ein kleines Vorschaubild für die Aufnahmenliste sichern. */
    private void takeThumbnail(final ReolinkApi a) {
        final long now = System.currentTimeMillis();
        if (now - lastThumbAt < 20000) return;
        lastThumbAt = now;
        thumbExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] jpg = a.snap(ThumbStore.WIDTH, ThumbStore.HEIGHT);
                    ThumbStore.get(MotionService.this).saveSnapshot(now, jpg);
                } catch (Exception ignored) {
                    // Vorschaubild ist optional
                }
            }
        });
    }

    private void handle(boolean motion) {
        long now = System.currentTimeMillis();
        long hold = Math.max(5, Prefs.wakeSeconds(this)) * 1000L;
        if (motion) {
            lastMotionAt = now;
            boolean wasOn = pm.isInteractive();
            screenLock.acquire(hold);          // Display an bzw. verlängern
            if (!motionActive) {
                motionActive = true;
                onMotionStart(wasOn);
                notifyListener(true);
            }
        } else if (motionActive && now - lastMotionAt > hold) {
            motionActive = false;
            notifyListener(false);
        }
    }

    private void onMotionStart(boolean screenWasOn) {
        if (!Prefs.bringToFront(this)) return;
        // Nicht stören, wenn gerade z. B. Aufnahmen angesehen werden.
        if (screenWasOn && AppState.visible > 0) return;
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_MOTION, true);
        startActivity(i);
    }

    private void notifyListener(boolean active) {
        Listener l = listener;
        if (l != null) l.onMotionChanged(active);
    }

    private void setStatus(String s) {
        status = s;
        if (!s.equals(shownStatus)) {
            shownStatus = s;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification(s));
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cam)
                .setContentTitle("Kamera-Überwachung")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
