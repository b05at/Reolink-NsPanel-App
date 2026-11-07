package at.reolinkpanel.viewer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hintergrunddienst: fragt alle Kameras zyklisch nach Bewegung bzw. Klingeln ab,
 * weckt dann den Bildschirm und holt das Livebild der betroffenen Kamera nach vorne.
 */
public class MotionService extends Service {

    public static final String ACTION_RELOAD = "at.reolinkpanel.viewer.RELOAD";
    public static final String EXTRA_MOTION = "motion";
    public static final String EXTRA_CAM = "cam";
    public static final String EXTRA_RING = "ring";
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;

    public interface Listener {
        /** active = irgendein Ereignis aktiv; camId/ring = auslösende Kamera bzw. Klingel. */
        void onMotionChanged(boolean active, String camId, boolean ring);
    }

    private static volatile Listener listener;
    public static volatile boolean motionActive = false;
    public static volatile String activeCamId = null;
    public static volatile boolean activeRing = false;
    public static volatile String status = "Startet…";

    public static void setListener(Listener l) { listener = l; }
    public static void clearListener(Listener l) { if (listener == l) listener = null; }

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, MotionService.class));
    }

    public static void reload(Context c) {
        c.startForegroundService(new Intent(c, MotionService.class).setAction(ACTION_RELOAD));
    }

    /** Zustand je Kamera. */
    private static class CamState {
        Cam cam;
        String sig;
        ReolinkApi api;
        boolean lastMd, lastVisitor, eventsUnsupported;
        long lastThumbAt, nextTryAt;
        String error;
    }

    private volatile boolean running;
    private volatile boolean reloadRequested;
    private Thread worker;
    private final Map<String, CamState> states = new HashMap<>();
    private long lastEventAt;
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();
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
            reloadRequested = true;   // neue Zugangsdaten übernehmen
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
                List<Cam> cams = Prefs.cameras(this);
                if (cams.isEmpty()) {
                    setStatus("Nicht eingerichtet");
                    handleEnd();
                    Thread.sleep(5000);
                    continue;
                }
                syncStates(cams);

                long now = System.currentTimeMillis();
                int ok = 0, off = 0;
                String lastError = null;
                for (Cam cam : cams) {
                    CamState st = states.get(cam.id);
                    if (st == null || now < st.nextTryAt) {
                        if (st != null && st.error != null) { off++; lastError = st.error; }
                        continue;
                    }
                    try {
                        poll(st);
                        st.error = null;
                        ok++;
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception e) {
                        st.error = cam.displayName() + ": " + e.getMessage();
                        st.nextTryAt = System.currentTimeMillis() + 5000;
                        st.lastMd = false;
                        st.lastVisitor = false;
                        off++;
                        lastError = st.error;
                    }
                }
                handleEnd();

                if (motionActive) {
                    Cam c = Prefs.camera(this, activeCamId);
                    setStatus((activeRing ? "Es hat geläutet" : "Bewegung") + (c != null ? ": " + c.displayName() : ""));
                } else if (off > 0 && ok == 0) {
                    setStatus("Nicht erreichbar – " + lastError);
                } else if (off > 0) {
                    setStatus("Aktiv – " + off + " Kamera(s) nicht erreichbar");
                } else {
                    setStatus(cams.size() == 1 ? "Aktiv – keine Bewegung" : "Aktiv – " + cams.size() + " Kameras");
                }
                Thread.sleep(Math.max(300, Prefs.pollMs(this)));
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                setStatus("Fehler: " + e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    /** Zustände an die aktuelle Kameraliste anpassen (neue/geänderte/gelöschte Kameras). */
    private void syncStates(List<Cam> cams) {
        boolean reload = reloadRequested;
        reloadRequested = false;
        Map<String, CamState> keep = new HashMap<>();
        for (Cam cam : cams) {
            CamState st = states.get(cam.id);
            String sig = cam.signature();
            if (st == null || reload || !sig.equals(st.sig)) {
                st = new CamState();
                st.cam = cam;
                st.sig = sig;
                st.api = ReolinkApi.from(cam);
            }
            keep.put(cam.id, st);
        }
        states.clear();
        states.putAll(keep);
    }

    private void poll(CamState st) throws Exception {
        Cam cam = st.cam;
        String trigger = Prefs.trigger(this);
        boolean wakeOnMotion = !"off".equals(trigger) && cam.wake;
        boolean thumbs = Prefs.thumbnails(this);
        if (!wakeOnMotion && !thumbs && !cam.doorbell) return;   // für diese Kamera nichts zu tun

        boolean md;
        boolean visitor = false;
        if (cam.doorbell && !st.eventsUnsupported) {
            try {
                ReolinkApi.Events ev = st.api.events();
                md = ev.md;
                visitor = ev.visitor;
                if (!ev.visitorSupported) st.eventsUnsupported = true;
            } catch (ReolinkApi.ApiException e) {
                st.eventsUnsupported = true;   // ältere Firmware: nur Bewegung
                md = st.api.mdState();
            }
        } else {
            md = st.api.mdState();
        }

        if (md && !st.lastMd && thumbs) takeThumbnail(st);
        st.lastMd = md;

        boolean ring = visitor && !st.lastVisitor;
        st.lastVisitor = visitor;
        if (ring) onEvent(cam, true);

        if (wakeOnMotion && st.api.isMotion(trigger, md)) onEvent(cam, false);
    }

    // ------------------------------------------------------------------ Ereignisse

    private void onEvent(Cam cam, boolean ring) {
        long now = System.currentTimeMillis();
        long hold = Math.max(5, Prefs.wakeSeconds(this)) * 1000L;
        lastEventAt = now;
        boolean wasOn = pm.isInteractive();
        screenLock.acquire(hold);          // Display an bzw. verlängern

        boolean isNew = !motionActive;
        // Klingeln hat Vorrang vor Bewegung einer anderen Kamera
        boolean takeOver = ring || isNew || (!activeRing && !cam.id.equals(activeCamId));
        if (!takeOver) return;
        boolean changedCam = !cam.id.equals(activeCamId) || ring != activeRing;
        motionActive = true;
        activeCamId = cam.id;
        activeRing = ring;
        if (ring) playChime();
        if (isNew || ring) bringToFront(cam, ring, wasOn);
        if (isNew || changedCam || ring) notifyListener(true, cam.id, ring);
    }

    private void handleEnd() {
        if (!motionActive) return;
        long hold = Math.max(5, Prefs.wakeSeconds(this)) * 1000L;
        if (System.currentTimeMillis() - lastEventAt > hold) {
            String id = activeCamId;
            motionActive = false;
            activeRing = false;
            notifyListener(false, id, false);
        }
    }

    private void bringToFront(Cam cam, boolean ring, boolean screenWasOn) {
        if (!ring && !Prefs.bringToFront(this)) return;
        // Bei Bewegung nicht stören, wenn gerade z. B. Aufnahmen angesehen werden.
        // Beim Klingeln immer nach vorne holen.
        if (!ring && screenWasOn && AppState.visible > 0) return;
        if (ring && AppState.liveVisible) return;   // Livebild wechselt selbst auf die Klingel
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_MOTION, true)
                .putExtra(EXTRA_CAM, cam.id)
                .putExtra(EXTRA_RING, ring);
        startActivity(i);
    }

    private void playChime() {
        if (!Prefs.chime(this)) return;
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r == null) return;
            r.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            r.play();
        } catch (Exception ignored) {
        }
    }

    /** Beim Beginn einer Bewegung ein kleines Vorschaubild für die Aufnahmenliste sichern. */
    private void takeThumbnail(final CamState st) {
        final long now = System.currentTimeMillis();
        if (now - st.lastThumbAt < 20000) return;
        st.lastThumbAt = now;
        final ReolinkApi a = st.api;
        final String camId = st.cam.id;
        thumbExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    byte[] jpg = a.snap(ThumbStore.WIDTH, ThumbStore.HEIGHT);
                    ThumbStore.get(MotionService.this).saveSnapshot(camId, now, jpg);
                } catch (Exception ignored) {
                    // Vorschaubild ist optional
                }
            }
        });
    }

    private void notifyListener(boolean active, String camId, boolean ring) {
        Listener l = listener;
        if (l != null) l.onMotionChanged(active, camId, ring);
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
