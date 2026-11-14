package at.reolinkpanel.viewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Livebild einer Kamera bzw. Übersicht aller Kameras – Startbildschirm der App. */
public class MainActivity extends Activity implements MotionService.Listener {

    private static final long CONTROLS_TIMEOUT_MS = 8000;
    /** So lange darf es bis zum ersten Bild dauern, sonst gilt der Stream als nicht abspielbar. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 15000;

    private PlayerView playerView;
    private ExoPlayer player;
    private DashboardView dashboard;
    private View controls, sidePanel, topLeft, ptzPanel, talkPanel;
    private TextView statusText, motionBadge, camChip;
    private ImageButton btnSound, btnZoom, btnSiren, btnPtz, btnLight, btnGrid, btnRec;
    private TextView btnStream;
    private DPadView dpad;

    // --- Kameras / Ansicht
    private List<Cam> cams = new ArrayList<>();
    private Cam cam;
    private boolean gridMode;
    private boolean started;
    /** Nach einem Ereignis automatisch zurück zur Übersicht. */
    private boolean returnToGrid;
    private String badgeCamId;

    // --- Stream-Auswahl mit automatischem Rückfall
    /** Eine Verbindungsvariante für das Livebild (Stream, Pfad, Transport). */
    private static class Variant {
        String stream, url, key;
        boolean tcp, vlc;
    }

    // --- VLC als Ersatz-Player
    private org.videolan.libvlc.LibVLC libVlc;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private org.videolan.libvlc.util.VLCVideoLayout vlcLayout;

    private final List<Variant> candidates = new ArrayList<>();
    /** Ab hier gehören die Varianten zum SD-Substream (davor: HD-Versuche). */
    private int firstSubIdx;
    private int candIdx;
    private boolean gotFrame;

    // --- Kamera-Befehle (Sirene, PTZ, Licht) – nacheinander im Hintergrund
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ReolinkApi camApi;
    private boolean sirenOn = false;
    private boolean sirenBusy = false;
    private boolean lightOn = false;
    private boolean ptzMoving = false;
    private boolean ptzErrorShown = false;

    // --- Gegensprechen (Baichuan, Code aus ks-reolink-doorbell-intercom, Apache-2.0)
    private static final int REQ_MIC = 42;
    private static final long TALK_IDLE_CLOSE_MS = 30000;
    /** Kamera-ID -> unterstützt Sprechen (fehlt = noch unbekannt). */
    private static final java.util.Map<String, Boolean> TALK_SUPPORT = new java.util.HashMap<>();
    private final ExecutorService talkExecutor = Executors.newSingleThreadExecutor();
    private io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk talk;
    private io.github.patrick11994.reolinkdoorbell.MicCapture mic;
    private boolean talking;
    private ImageButton btnTalk;
    private TextView tvTalk;
    private final Runnable talkIdleClose = new Runnable() {
        @Override public void run() { closeTalk(); }
    };

    // --- Bildschirm-Timeout
    private boolean screenReleased;    // Display darf ausgeschaltet werden

    // --- Ersatz-Standbilder, falls RTSP nicht lädt
    private android.widget.ImageView snapView;
    private volatile boolean snapRunning;
    private Thread snapThread;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sleepRunnable = new Runnable() {
        @Override public void run() { goToSleep(); }
    };
    private final Runnable sirenAutoOff = new Runnable() {
        @Override public void run() { setSiren(false); }
    };
    private final Runnable hideControls = new Runnable() {
        @Override public void run() { setControls(false); }
    };
    private final Runnable retry = new Runnable() {
        @Override public void run() { playCandidate(); }
    };
    private final Runnable firstFrameTimeout = new Runnable() {
        @Override public void run() { streamFailed("Kein Bild"); }
    };
    private final Runnable stallCheck = new Runnable() {
        @Override public void run() { scheduleRetry("Bild hängt – verbinde neu…"); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Über dem Sperrbildschirm anzeigen und Display einschalten (Android 8.0 kompatibel).
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        Ui.fullscreen(this);
        setContentView(R.layout.activity_main);

        playerView = findViewById(R.id.player);
        dashboard = findViewById(R.id.dashboard);
        controls = findViewById(R.id.controls);
        sidePanel = findViewById(R.id.sidePanel);
        topLeft = findViewById(R.id.topLeft);
        ptzPanel = findViewById(R.id.ptzPanel);
        talkPanel = findViewById(R.id.talkPanel);
        btnTalk = findViewById(R.id.btnTalk);
        tvTalk = findViewById(R.id.tvTalk);
        snapView = findViewById(R.id.snapView);
        vlcLayout = findViewById(R.id.vlcLayout);
        statusText = findViewById(R.id.status);
        motionBadge = findViewById(R.id.motionBadge);
        camChip = findViewById(R.id.camChip);
        btnRec = findViewById(R.id.btnRec);
        btnSound = findViewById(R.id.btnSound);
        btnStream = findViewById(R.id.btnStream);
        btnZoom = findViewById(R.id.btnZoom);
        btnSiren = findViewById(R.id.btnSiren);
        btnPtz = findViewById(R.id.btnPtz);
        btnLight = findViewById(R.id.btnLight);
        btnGrid = findViewById(R.id.btnGrid);
        dpad = findViewById(R.id.dpad);

        findViewById(R.id.touch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleControls(); }
        });
        btnRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RecordingsActivity.class));
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        btnSound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean muted = !Prefs.muted(MainActivity.this);
                Prefs.sp(MainActivity.this).edit().putBoolean("muted", muted).apply();
                if (player != null) player.setVolume(muted ? 0f : 1f);
                if (vlcPlayer != null) vlcPlayer.setVolume(muted ? 0 : 100);
                updateButtons();
                showControls();
            }
        });
        btnStream.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String next = "sub".equals(Prefs.liveStream(MainActivity.this)) ? "main" : "sub";
                Prefs.sp(MainActivity.this).edit().putString("liveStream", next).apply();
                updateButtons();
                startLive();
                showControls();
            }
        });
        btnZoom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.sp(MainActivity.this).edit().putBoolean("zoom", !Prefs.zoom(MainActivity.this)).apply();
                applyZoom();
                updateButtons();
                if (gridMode) applyMode(); else showControls();
            }
        });
        btnSiren.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sirenOn) setSiren(false);   // Ausschalten sofort, ohne Rückfrage
                else confirmSiren();
            }
        });
        btnLight.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setLight(!lightOn); showControls(); }
        });
        btnPtz.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPtzVisible(ptzPanel.getVisibility() != View.VISIBLE); }
        });
        btnGrid.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (gridMode) switchToCam(cam != null ? cam.id : null, true);
                else showGrid();
            }
        });
        camChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickCamera(); }
        });
        motionBadge.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (badgeCamId != null && (gridMode || cam == null || !badgeCamId.equals(cam.id))) {
                    switchToCam(badgeCamId, false);
                }
            }
        });
        findViewById(R.id.btnPtzClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPtzVisible(false); }
        });
        dpad.setListener(new DPadView.Listener() {
            @Override public void onDirection(String op) { ptz(op); }
        });
        btnTalk.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override public boolean onTouch(View v, MotionEvent e) {
                boolean hold = !"toggle".equals(Prefs.talkMode(MainActivity.this));
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        if (hold) talkDown();
                        else if (talking) talkUp(); else talkDown();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (hold) talkUp();
                        return true;
                    default:
                        return true;
                }
            }
        });
        holdButton(findViewById(R.id.btnZoomIn), "ZoomInc");
        holdButton(findViewById(R.id.btnZoomOut), "ZoomDec");
        dashboard.setOnTileClick(new DashboardView.OnTileClick() {
            @Override public void onTile(Cam c) { switchToCam(c.id, true); }
        });

        updateButtons();
        updateSiren();
        updateLight();

        if (!Prefs.isConfigured(this)) {
            startActivity(new Intent(this, CameraEditActivity.class));
        } else {
            MotionService.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (started && handleEventIntent(intent)) {
            screenReleased = false;
            applyMode();
            resetSleepTimer();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppState.visible++;
        AppState.liveVisible = true;
        started = true;
        cams = Prefs.cameras(this);
        cam = Prefs.current(this);
        camApi = null;   // Einstellungen könnten sich geändert haben
        gridMode = cams.size() >= 2 && "grid".equals(Prefs.viewMode(this));
        boolean event = handleEventIntent(getIntent());
        MotionService.setListener(this);
        applyMode();
        applyMotion(MotionService.motionActive, MotionService.activeCamId, MotionService.activeRing);
        if (event) setControls(false); else showControls();
        resetSleepTimer();
    }

    @Override
    protected void onStop() {
        started = false;
        AppState.visible--;
        AppState.liveVisible = false;
        MotionService.clearListener(this);
        if (sirenOn) setSiren(false);   // Sirene nie unbeaufsichtigt weiterlaufen lassen
        closeTalk();                    // Mikrofon nie unbeaufsichtigt offen lassen
        setPtzVisible(false);           // stoppt auch laufende Schwenks
        handler.removeCallbacksAndMessages(null);
        stopLive();   // spart CPU und WLAN, wenn das Display aus ist
        dashboard.stop();
        stopSnapFallback();
        screenReleased = false;
        if (returnToGrid) {
            returnToGrid = false;
            Prefs.setViewMode(this, "grid");
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();   // laufende Befehle (z. B. Stopp) noch abschließen
        if (libVlc != null) { libVlc.release(); libVlc = null; }
        talkExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideBars(this);
    }

    @Override
    public void onBackPressed() {
        // Am Panel nicht versehentlich beenden.
        if (ptzPanel.getVisibility() == View.VISIBLE) setPtzVisible(false);
        else if (!gridMode && cams.size() >= 2) showGrid();
        else showControls();
    }

    // ------------------------------------------------------------ Ansicht

    /** Ereignis-Intent (Bewegung/Klingel) auswerten. true = Ansicht wurde umgestellt. */
    private boolean handleEventIntent(Intent i) {
        if (i == null || !i.getBooleanExtra(MotionService.EXTRA_MOTION, false)) return false;
        String id = i.getStringExtra(MotionService.EXTRA_CAM);
        i.removeExtra(MotionService.EXTRA_MOTION);
        Cam c = Prefs.camera(this, id);
        if (c == null) return false;
        if (gridMode) returnToGrid = true;
        gridMode = false;
        selectCam(c);
        return true;
    }

    private void selectCam(Cam c) {
        if (cam != null && !cam.id.equals(c.id)) {
            closeTalk();
            if (sirenOn) setSiren(false);
            setPtzVisible(false);
        }
        cam = c;
        camApi = null;
        lightOn = false;
        Prefs.setCurrent(this, c.id);
    }

    private void switchToCam(String id, boolean userChoice) {
        Cam c = Prefs.camera(this, id);
        if (c == null) return;
        if (userChoice) {
            returnToGrid = false;
            Prefs.setViewMode(this, "single");
        } else if (gridMode) {
            returnToGrid = true;
        }
        gridMode = false;
        selectCam(c);
        applyMode();
        showControls();
    }

    private void showGrid() {
        if (cams.size() < 2) return;
        closeTalk();
        if (sirenOn) setSiren(false);
        setPtzVisible(false);
        returnToGrid = false;
        gridMode = true;
        Prefs.setViewMode(this, "grid");
        applyMode();
        showControls();
    }

    private void applyMode() {
        cams = Prefs.cameras(this);
        if (cams.size() < 2) gridMode = false;
        if (cam == null) cam = Prefs.current(this);
        btnGrid.setVisibility(cams.size() >= 2 ? View.VISIBLE : View.GONE);
        btnGrid.setActivated(gridMode);
        boolean single = !gridMode;
        boolean hasCam = cam != null;
        boolean bell = hasCam && cam.doorbell;
        btnRec.setVisibility(single ? View.VISIBLE : View.GONE);
        btnSound.setVisibility(single ? View.VISIBLE : View.GONE);
        btnStream.setVisibility(single ? View.VISIBLE : View.GONE);
        btnPtz.setVisibility(single && hasCam && !bell ? View.VISIBLE : View.GONE);
        // Scheinwerfer/Sirene: sofort zeigen (außer Klingel), dann anhand der Kamera bestätigen
        btnLight.setVisibility(single && hasCam && !bell ? View.VISIBLE : View.GONE);
        btnSiren.setVisibility(single && hasCam && !bell ? View.VISIBLE : View.GONE);
        btnRec.setVisibility(single && hasCam ? View.VISIBLE : View.GONE);
        camChip.setText(gridMode ? "Alle Kameras ▾" : (cam != null ? cam.displayName() : "Kamera") + (cams.size() >= 2 ? " ▾" : ""));

        stopSnapFallback();
        if (gridMode) {
            stopLive();
            hideStatus();
            playerView.setVisibility(View.GONE);
            vlcLayout.setVisibility(View.GONE);
            dashboard.setVisibility(View.VISIBLE);
            dashboard.start(cams, Prefs.gridLive(this), Prefs.gridSeconds(this), Prefs.zoom(this));
            dashboard.setActive(MotionService.activeCamId, MotionService.motionActive, MotionService.activeRing);
        } else {
            dashboard.stop();
            dashboard.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            startLive();
            if (hasCam) {
                probeCapabilities();
                probeTalk();
            }
        }
        updateLight();
        setControls(controls.getVisibility() == View.VISIBLE);
    }

    private void pickCamera() {
        if (cams.size() < 2) { showControls(); return; }
        handler.removeCallbacks(hideControls);
        final List<String> labels = new ArrayList<>();
        labels.add("▦  Alle Kameras (Übersicht)");
        for (Cam c : cams) labels.add((c.doorbell ? "🔔  " : "◉  ") + c.displayName());
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kamera wählen")
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) showGrid();
                        else switchToCam(cams.get(which - 1).id, true);
                    }
                })
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) {
                        Ui.hideBars(MainActivity.this);
                        showControls();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------ Bewegung / Klingel

    @Override
    public void onMotionChanged(final boolean active, final String camId, final boolean ring) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!started) return;
                // Klingeln: sofort auf die Türklingel umschalten
                if (active && ring && camId != null && (gridMode || cam == null || !camId.equals(cam.id))) {
                    switchToCam(camId, false);
                }
                if (active) screenReleased = false;
                applyMotion(active, camId, ring);
                // Ereignis vorbei -> zurück zur Übersicht, falls von dort gekommen
                if (!active && returnToGrid) showGrid();
                resetSleepTimer();
            }
        });
    }

    private void applyMotion(boolean active, String camId, boolean ring) {
        badgeCamId = active ? camId : null;
        if (active) {
            Cam c = Prefs.camera(this, camId);
            String name = c != null && cams.size() >= 2 ? " – " + c.displayName() : "";
            motionBadge.setText((ring ? "🔔 ES HAT GELÄUTET" : "● BEWEGUNG") + name);
            motionBadge.setVisibility(View.VISIBLE);
        } else {
            motionBadge.setVisibility(View.GONE);
        }
        if (gridMode) dashboard.setActive(camId, active, ring);
        updateKeepOn();
    }

    private void updateKeepOn() {
        boolean managed = effectiveTimeoutMs() != -1 && !screenReleased;
        if (managed || MotionService.motionActive || sirenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ Livebild

    /**
     * Baut die Liste der Verbindungsvarianten. Reolink-Firmwares unterscheiden sich beim
     * RTSP-Pfad (Preview_01_sub / h264Preview_01_sub / h265Preview_01_sub) und manche
     * liefern nur per UDP bzw. nur per TCP zuverlässig. Die zuletzt funktionierende
     * Variante wird pro Kamera gespeichert und zuerst probiert.
     * SD = Substream. HD versucht zuerst ext, dann main; klappt das nicht, geht es auf SD zurück.
     */
    private void startLive() {
        candidates.clear();
        if (cam == null) {
            candIdx = 0;
            playCandidate();
            return;
        }
        ReolinkApi a = ReolinkApi.from(cam);
        String custom = cam.rtspPath.trim();
        if (!custom.isEmpty()) {
            // Eigene URL bzw. eigener Pfad aus den Kamera-Einstellungen
            addVariant("sub", a.rtspUrlCustom(custom), true, false);
            addVariant("sub", a.rtspUrlCustom(custom), false, false);
            addVariant("sub", a.rtspUrlCustom(custom), true, true);
            addVariant("sub", a.rtspUrlCustom(custom), false, true);
            firstSubIdx = 0;
        } else {
            boolean hd = !"sub".equals(Prefs.liveStream(this));
            if (hd) {
                for (String st : new String[]{"ext", "main"}) addPathVariants(a, st);
            }
            firstSubIdx = candidates.size();
            addPathVariants(a, "sub");
            // VLC-Engine als Ersatz (verträgt mehr Reolink-Firmwares)
            addVariant("sub", a.rtspUrlCustom("Preview_01_sub"), true, true);
            addVariant("sub", a.rtspUrlCustom("h264Preview_01_sub"), true, true);
            addVariant("sub", a.rtspUrlCustom("Preview_01_sub"), false, true);
            // bewährte Variante nach vorne holen (nur innerhalb der SD-Varianten bzw. als erste HD-Variante)
            String good = Prefs.sp(this).getString("rtspOk_" + cam.id, null);
            if (good != null) {
                for (int i = 0; i < candidates.size(); i++) {
                    Variant v = candidates.get(i);
                    if (good.equals(v.key) && "sub".equals(v.stream) && i >= firstSubIdx) {
                        candidates.remove(i);
                        candidates.add(firstSubIdx, v);
                        break;
                    }
                }
            }
        }
        candIdx = 0;
        playCandidate();
    }

    private void addPathVariants(ReolinkApi a, String stream) {
        for (String prefix : new String[]{"", "h264", "h265"}) {
            String url = a.rtspUrlCustom(prefix + "Preview_01_" + stream);
            addVariant(stream, url, true, false);
            addVariant(stream, url, false, false);
        }
    }

    private void addVariant(String stream, String url, boolean tcp, boolean vlc) {
        Variant v = new Variant();
        v.stream = stream;
        v.url = url;
        v.tcp = tcp;
        v.vlc = vlc;
        // Schlüssel ohne Zugangsdaten
        v.key = (vlc ? "vlc:" : "") + url.substring(url.indexOf('@') + 1) + (tcp ? "|tcp" : "|udp");
        candidates.add(v);
    }

    private boolean isHdAttempt() {
        return candIdx < firstSubIdx;
    }

    private void playCandidate() {
        stopLive();
        if (gridMode) return;
        if (cam == null || candidates.isEmpty()) {
            showStatus("Noch keine Kamera eingerichtet –\nüber ⚙ → Kamera hinzufügen");
            return;
        }
        final Variant v = candidates.get(candIdx);
        gotFrame = false;
        if (!snapRunning) {
            showStatus((isHdAttempt() ? "Verbinde (HD)…" : "Verbinde…")
                    + (candIdx > 0 ? "  Variante " + (candIdx + 1) + "/" + candidates.size() : ""));
        }

        playerView.setVisibility(v.vlc ? View.GONE : View.VISIBLE);
        vlcLayout.setVisibility(v.vlc ? View.VISIBLE : View.GONE);
        if (v.vlc) {
            playVlc(v);
            return;
        }

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)   // geringe Verzögerung
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(Prefs.muted(this) ? 0f : 1f);
        playerView.setPlayer(player);
        applyZoom();

        MediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(v.tcp)          // false = UDP, bei Bedarf automatisch TCP
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(v.url)));

        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() { onFirstFrame(v); }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // Video vorhanden, aber vom Panel nicht dekodierbar (z. B. 4K H.265)
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false)) {
                    streamFailed("Videoformat nicht unterstützt");
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(stallCheck);
                } else if (state == Player.STATE_BUFFERING && gotFrame) {
                    handler.removeCallbacks(stallCheck);
                    handler.postDelayed(stallCheck, 12000);
                } else if (state == Player.STATE_ENDED) {
                    scheduleRetry("Stream beendet – verbinde neu…");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                streamFailed(error.getErrorCodeName().replace("ERROR_CODE_", ""));
            }
        });
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        handler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
    }

    /** Erstes Bild ist da – Variante merken, Ersatzbilder und Meldung weg. */
    private void onFirstFrame(Variant v) {
        gotFrame = true;
        stopSnapFallback();
        handler.removeCallbacks(firstFrameTimeout);
        handler.removeCallbacks(stallCheck);
        handler.removeCallbacks(retry);
        hideStatus();
        if (cam != null) Prefs.sp(this).edit().putString("rtspOk_" + cam.id, v.key).apply();
    }

    /** Wiedergabe über VLC (libVLC) – hardwarebeschleunigt, wenig CPU. */
    private void playVlc(final Variant v) {
        try {
            if (libVlc == null) {
                java.util.ArrayList<String> opts = new java.util.ArrayList<>();
                opts.add("--network-caching=400");
                opts.add("--no-stats");
                opts.add("--no-sub-autodetect-file");
                libVlc = new org.videolan.libvlc.LibVLC(this, opts);
            }
            vlcPlayer = new org.videolan.libvlc.MediaPlayer(libVlc);
            vlcPlayer.attachViews(vlcLayout, null, false, false);
            applyZoom();
            org.videolan.libvlc.Media m = new org.videolan.libvlc.Media(libVlc, Uri.parse(v.url));
            m.setHWDecoderEnabled(true, false);
            m.addOption(":network-caching=400");
            if (v.tcp) m.addOption(":rtsp-tcp");
            vlcPlayer.setMedia(m);
            m.release();
            vlcPlayer.setVolume(Prefs.muted(this) ? 0 : 100);
            final org.videolan.libvlc.MediaPlayer mp = vlcPlayer;
            vlcPlayer.setEventListener(new org.videolan.libvlc.MediaPlayer.EventListener() {
                @Override
                public void onEvent(org.videolan.libvlc.MediaPlayer.Event ev) {
                    if (mp != vlcPlayer) return;   // veralteter Player
                    switch (ev.type) {
                        case org.videolan.libvlc.MediaPlayer.Event.Vout:
                            if (ev.getVoutCount() > 0 && !gotFrame) onFirstFrame(v);
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                            streamFailed("VLC_FEHLER");
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                        case org.videolan.libvlc.MediaPlayer.Event.Stopped:
                            if (gotFrame) scheduleRetry("Verbindung unterbrochen – verbinde neu…");
                            else streamFailed("VLC_ENDE");
                            break;
                        default:
                            break;
                    }
                }
            });
            vlcPlayer.play();
            handler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
        } catch (Throwable t) {
            // VLC nicht verfügbar (z. B. Architektur) – nächste Variante
            streamFailed("VLC: " + t.getClass().getSimpleName());
        }
    }

    /** Stream ließ sich nicht abspielen: nächste Variante probieren oder neu verbinden. */
    private void streamFailed(String reason) {
        if (player == null && vlcPlayer == null) return;
        handler.removeCallbacks(firstFrameTimeout);
        if (gotFrame) {
            // lief schon – gleiche Variante erneut verbinden
            scheduleRetry("Verbindung unterbrochen – verbinde neu…");
            return;
        }
        if (candIdx < candidates.size() - 1) {
            boolean wasHd = isHdAttempt();
            candIdx++;
            if (wasHd && !isHdAttempt()) {
                // Alle HD-Varianten gescheitert -> dauerhaft auf SD zurück
                Prefs.sp(this).edit().putString("liveStream", "sub").apply();
                updateButtons();
                Toast.makeText(this, "HD kann am Panel nicht abgespielt werden – zurück auf SD.",
                        Toast.LENGTH_LONG).show();
            }
            playCandidate();
            return;
        }
        // Alle Varianten gescheitert: Standbilder zeigen, später von vorne probieren
        startSnapFallback();
        candIdx = firstSubIdx;
        String hint = reason.contains("CONNECTION") || reason.contains("TIMEOUT")
                ? "RTSP-Port nicht erreichbar."
                : reason.contains("BAD_HTTP_STATUS") || reason.contains("AUTH") || reason.contains("NOT_FOUND")
                ? "Kamera lehnt den Stream ab (Pfad/Zugangsdaten)."
                : "";
        showStatus("Kein Livebild (" + reason + ") – zeige Standbilder. " + hint
                + "\nTipp: Funktionierende RTSP-URL unter ⚙ → Kamera → Erweitert eintragen.");
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 15000);
    }

    private void stopLive() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(stallCheck);
        handler.removeCallbacks(firstFrameTimeout);
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
        if (vlcPlayer != null) {
            org.videolan.libvlc.MediaPlayer mp = vlcPlayer;
            vlcPlayer = null;   // Events des alten Players ignorieren
            try {
                mp.stop();
                mp.detachViews();
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void scheduleRetry(String msg) {
        showStatus(msg);
        handler.removeCallbacks(stallCheck);
        handler.removeCallbacks(firstFrameTimeout);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 3000);
    }

    private void applyZoom() {
        playerView.setResizeMode(Prefs.zoom(this)
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
        if (vlcPlayer != null) {
            vlcPlayer.setVideoScale(Prefs.zoom(this)
                    ? org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    : org.videolan.libvlc.MediaPlayer.ScaleType.SURFACE_BEST_FIT);
        }
    }

    // ------------------------------------------------------------ Kamera-Befehle

    private ReolinkApi api() {
        if (camApi == null) camApi = cam != null ? ReolinkApi.from(cam) : ReolinkApi.from(this);
        return camApi;
    }

    // ------------------------------------------------------------ Scheinwerfer

    /**
     * Fragt die Kamera, ob sie Scheinwerfer und Sirene hat, und blendet die Knöpfe
     * entsprechend ein – unabhängig davon, ob die Kamera als Türklingel markiert ist.
     */
    private void probeCapabilities() {
        if (cam == null) return;
        final ReolinkApi a = api();
        final String camId = cam.id;
        executor.execute(new Runnable() {
            @Override public void run() {
                // null = unbekannt (Kamera offline) -> Anzeige nicht ändern
                Boolean light = null, lightState = null, siren = null;
                try {
                    lightState = a.whiteLed();
                    light = true;
                } catch (ReolinkApi.ApiException e) {
                    light = false;
                } catch (Exception ignored) {
                }
                try {
                    siren = a.hasSiren();
                } catch (Exception ignored) {
                }
                final Boolean fLight = light, fState = lightState, fSiren = siren;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (cam == null || !camId.equals(cam.id) || gridMode || isDestroyed()) return;
                        if (fLight != null) btnLight.setVisibility(fLight ? View.VISIBLE : View.GONE);
                        if (fState != null) { lightOn = fState; updateLight(); }
                        if (fSiren != null) btnSiren.setVisibility(fSiren ? View.VISIBLE : View.GONE);
                    }
                });
            }
        });
    }

    private void queryLight() {
        if (cam == null) return;
        final ReolinkApi a = api();
        final String camId = cam.id;
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final boolean on = a.whiteLed();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (cam != null && camId.equals(cam.id)) { lightOn = on; updateLight(); }
                        }
                    });
                } catch (Exception ignored) {
                    // Kamera ohne Scheinwerfer oder offline – Knopf bleibt aus
                }
            }
        });
    }

    private void setLight(final boolean on) {
        lightOn = on;   // sofort anzeigen
        updateLight();
        final ReolinkApi a = api();
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    a.setWhiteLed(on);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isDestroyed()) return;
                            lightOn = !on;
                            updateLight();
                            Toast.makeText(MainActivity.this, "Scheinwerfer: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void updateLight() {
        if (lightOn) {
            btnLight.setBackgroundResource(R.drawable.fab_light_on);
            btnLight.setImageTintList(ColorStateList.valueOf(0xFF000000));
        } else {
            btnLight.setBackgroundResource(R.drawable.fab_dark);
            btnLight.setImageTintList(ColorStateList.valueOf(getColor(R.color.text)));
        }
    }

    // ------------------------------------------------------------ PTZ

    private void setPtzVisible(boolean show) {
        if (!show) {
            dpad.reset();
            if (ptzMoving) ptz(null);
        }
        ptzPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        btnPtz.setActivated(show);
        if (show) {
            setControls(true);
            handler.removeCallbacks(hideControls);   // Bedienung bleibt offen, solange PTZ aktiv
        } else if (controls.getVisibility() == View.VISIBLE) {
            showControls();
        }
    }

    /** op == null -> Stopp. */
    private void ptz(String op) {
        final String cmd = op == null ? "Stop" : op;
        ptzMoving = op != null;
        final ReolinkApi a = api();
        final int speed = Prefs.ptzSpeed(this);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    a.ptz(cmd, speed);
                } catch (final Exception e) {
                    if ("Stop".equals(cmd)) {
                        try { a.ptz("Stop", speed); } catch (Exception ignored) {}   // zweiter Versuch
                    }
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (!ptzErrorShown && !isDestroyed()) {
                                ptzErrorShown = true;
                                Toast.makeText(MainActivity.this, "Steuerung: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        });
    }

    /** Knopf, der einen Befehl sendet, solange er gedrückt ist (Zoom). */
    @SuppressLint("ClickableViewAccessibility")
    private void holdButton(View b, final String op) {
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setPressed(true);
                        ptz(op);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        ptz(null);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    // ------------------------------------------------------------ Gegensprechen

    private boolean talkAllowed() {
        if (gridMode || cam == null) return false;
        Boolean ok = TALK_SUPPORT.get(cam.id);
        return ok == null || ok;
    }

    /** Fragt einmal pro Kamera per Baichuan (Port 9000) nach, ob sie Sprechen unterstützt. */
    private void probeTalk() {
        if (cam == null || TALK_SUPPORT.containsKey(cam.id)) {
            setControls(controls.getVisibility() == View.VISIBLE);
            return;
        }
        final Cam c = cam;
        talkExecutor.execute(new Runnable() {
            @Override public void run() {
                Boolean ok;
                try {
                    String fmt = io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk
                            .probe(c.host.trim(), 9000, c.user.trim(), c.pass, 0);
                    ok = fmt != null;
                } catch (Exception e) {
                    ok = null;   // nicht erreichbar – Knopf zeigen, Fehler erst beim Sprechen melden
                }
                final Boolean result = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (result != null) TALK_SUPPORT.put(c.id, result);
                        if (!isDestroyed()) setControls(controls.getVisibility() == View.VISIBLE);
                    }
                });
            }
        });
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Mikrofon öffnen und zur Kamera sprechen. */
    private void talkDown() {
        if (cam == null) return;
        if (!hasMicPermission()) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        handler.removeCallbacks(talkIdleClose);
        handler.removeCallbacks(hideControls);
        if (talk == null) {
            final String camId = cam.id;
            talk = new io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk(
                    cam.host.trim(), 9000, cam.user.trim(), cam.pass, 0,
                    new io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk.Listener() {
                        @Override public void onTalkState(final String state) {
                            runOnUiThread(new Runnable() {
                                @Override public void run() { onTalkState(camId, state); }
                            });
                        }
                    });
            talk.start();   // sendet Stille, bis das Mikrofon Ton liefert
            tvTalk.setText("Verbinde…");
        }
        if (mic == null) {
            mic = new io.github.patrick11994.reolinkdoorbell.MicCapture(talk, Prefs.micSource(this), "Automatic", false,
                    new io.github.patrick11994.reolinkdoorbell.MicCapture.Listener() {
                        @Override public void onMicState(final String state) {
                            if (state.startsWith("live")) return;
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(MainActivity.this, "Mikrofon: " + state, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
        }
        talking = true;
        muteLive(true);   // Kameraton aus, damit die Kamera sich nicht selbst hört
        updateTalkUi();
    }

    /** Mikrofon schließen; die Sitzung bleibt kurz offen, damit der nächste Druck sofort klappt. */
    private void talkUp() {
        if (!talking) return;
        talking = false;
        final io.github.patrick11994.reolinkdoorbell.MicCapture m = mic;
        mic = null;
        if (m != null) {
            talkExecutor.execute(new Runnable() {
                @Override public void run() { m.release(); }
            });
        }
        if (talk != null) talk.clear();
        muteLive(false);
        updateTalkUi();
        handler.removeCallbacks(talkIdleClose);
        if (talk != null) handler.postDelayed(talkIdleClose, TALK_IDLE_CLOSE_MS);
        showControls();
    }

    /** Gespräch ganz beenden (Kamera-Lautsprecher wieder frei). */
    private void closeTalk() {
        handler.removeCallbacks(talkIdleClose);
        if (talking) {
            talking = false;
            muteLive(false);
        }
        final io.github.patrick11994.reolinkdoorbell.MicCapture m = mic;
        final io.github.patrick11994.reolinkdoorbell.baichuan.BaichuanTalk t = talk;
        mic = null;
        talk = null;
        if (m != null || t != null) {
            talkExecutor.execute(new Runnable() {
                @Override public void run() {
                    if (m != null) m.release();
                    if (t != null) t.stop();
                }
            });
        }
        updateTalkUi();
    }

    private void onTalkState(String camId, String state) {
        if (isDestroyed()) return;
        if ("open".equals(state)) {
            updateTalkUi();
            return;
        }
        if (state.startsWith("talk refused")) {
            TALK_SUPPORT.put(camId, false);
            Toast.makeText(this, "Diese Kamera unterstützt Sprechen über die App nicht.", Toast.LENGTH_LONG).show();
            closeTalk();
            setControls(controls.getVisibility() == View.VISIBLE);
        } else if (state.startsWith("talk failed") || state.startsWith("talk ended")) {
            String why = state.substring(state.indexOf(':') + 1).trim();
            Toast.makeText(this, "Gegensprechen beendet: " + why, Toast.LENGTH_LONG).show();
            closeTalk();
        }
    }

    private void updateTalkUi() {
        if (talking) {
            btnTalk.setBackgroundResource(R.drawable.fab_talk_on);
            btnTalk.setImageTintList(ColorStateList.valueOf(0xFF000000));
            tvTalk.setText("Sprechen…");
        } else {
            btnTalk.setBackgroundResource(R.drawable.fab_dark);
            btnTalk.setImageTintList(ColorStateList.valueOf(getColor(R.color.text)));
            tvTalk.setText("toggle".equals(Prefs.talkMode(this)) ? "Sprechen" : "Halten");
        }
        setControls(controls.getVisibility() == View.VISIBLE);
    }

    /** Kameraton während des Sprechens stumm (verhindert Rückkopplung). */
    private void muteLive(boolean mute) {
        float vol = mute || Prefs.muted(this) ? 0f : 1f;
        if (player != null) player.setVolume(vol);
        if (vlcPlayer != null) vlcPlayer.setVolume(vol > 0 ? 100 : 0);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mikrofon erlaubt – jetzt Sprechen-Knopf benutzen.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Ohne Mikrofon-Berechtigung ist kein Gegensprechen möglich.", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------ Sirene

    private void confirmSiren() {
        handler.removeCallbacks(hideControls);
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Sirene auslösen?")
                .setMessage("Die Sirene der Kamera ertönt für bis zu "
                        + Prefs.sirenSeconds(this) + " Sekunden. Erneut tippen schaltet sie sofort ab.")
                .setPositiveButton("Auslösen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) { setSiren(true); }
                })
                .setNegativeButton("Abbrechen", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) {
                        Ui.hideBars(MainActivity.this);
                        showControls();
                    }
                })
                .create();
        d.show();
    }

    private void setSiren(final boolean on) {
        if (sirenBusy && on) return;
        sirenBusy = true;
        handler.removeCallbacks(sirenAutoOff);
        final ReolinkApi a = api();
        if (!on) {
            // UI sofort zurücksetzen, Befehl läuft im Hintergrund
            sirenOn = false;
            updateSiren();
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                Exception err = null;
                try {
                    a.siren(on);
                } catch (Exception e) {
                    err = e;
                }
                final Exception error = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        sirenBusy = false;
                        if (isDestroyed()) return;
                        if (error != null) {
                            Toast.makeText(MainActivity.this, "Sirene: " + error.getMessage(),
                                    Toast.LENGTH_LONG).show();
                            if (on) { sirenOn = false; updateSiren(); }
                            return;
                        }
                        if (on) {
                            sirenOn = true;
                            updateSiren();
                            handler.postDelayed(sirenAutoOff,
                                    Math.max(5, Prefs.sirenSeconds(MainActivity.this)) * 1000L);
                        }
                    }
                });
            }
        });
    }

    private void updateSiren() {
        if (sirenOn) {
            btnSiren.setBackgroundResource(R.drawable.fab_siren_on);
            btnSiren.setImageTintList(ColorStateList.valueOf(0xFF000000));
        } else {
            btnSiren.setBackgroundResource(R.drawable.fab_dark);
            btnSiren.setImageTintList(ColorStateList.valueOf(getColor(R.color.text)));
        }
        setControls(controls.getVisibility() == View.VISIBLE);
        updateKeepOn();
    }

    // ------------------------------------------------------------ Bildschirm-Timeout

    /** -1 = System regelt das Ausschalten, 0 = nie, sonst Millisekunden. */
    private int effectiveTimeoutMs() {
        int t = Prefs.screenTimeout(this);
        if (t == 0) return 0;
        if (t > 0) return t * 1000;
        return -1;
    }

    private void resetSleepTimer() {
        handler.removeCallbacks(sleepRunnable);
        if (!started) return;
        int ms = effectiveTimeoutMs();
        if (ms > 0 && !screenReleased) handler.postDelayed(sleepRunnable, ms);
        updateKeepOn();
    }

    /** Display nach dem Timeout wirklich ausschalten. */
    private void goToSleep() {
        if (!started) return;
        // Nicht während Bewegung, Sirene oder Schwenken
        if (MotionService.motionActive || sirenOn || talk != null || ptzPanel.getVisibility() == View.VISIBLE) {
            resetSleepTimer();
            return;
        }
        screenReleased = true;
        updateKeepOn();
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            // sofort aus – Bewegung, Klingel oder Näherungssensor schalten wieder ein
            try { dpm.lockNow(); } catch (SecurityException ignored) {}
        }
        // ohne Geräteadministrator schaltet Android nach seinem eigenen Timeout ab
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (screenReleased) {
                screenReleased = false;
                updateKeepOn();
            }
            resetSleepTimer();
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ Ersatz-Standbilder

    /** Zeigt Standbilder über die HTTP-API, solange das RTSP-Livebild nicht lädt. */
    private void startSnapFallback() {
        if (snapRunning || cam == null || gridMode) return;
        snapRunning = true;
        final ReolinkApi a = ReolinkApi.from(cam);
        snapView.setScaleType(Prefs.zoom(this) ? android.widget.ImageView.ScaleType.CENTER_CROP
                : android.widget.ImageView.ScaleType.FIT_CENTER);
        snapThread = new Thread(new Runnable() {
            @Override public void run() {
                while (snapRunning) {
                    try {
                        byte[] jpg = a.snap(640, 360);
                        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                        o.inPreferredConfig = android.graphics.Bitmap.Config.RGB_565;
                        o.inSampleSize = 1;
                        android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
                        b.inJustDecodeBounds = true;
                        android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, b);
                        while (b.outWidth / (o.inSampleSize * 2) >= 640) o.inSampleSize *= 2;
                        final android.graphics.Bitmap bmp =
                                android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (!snapRunning || bmp == null) return;
                                snapView.setImageBitmap(bmp);
                                snapView.setVisibility(View.VISIBLE);
                            }
                        });
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                    }
                }
            }
        }, "snap-fallback");
        snapThread.start();
    }

    private void stopSnapFallback() {
        snapRunning = false;
        if (snapThread != null) snapThread.interrupt();
        snapThread = null;
        snapView.setVisibility(View.GONE);
        snapView.setImageBitmap(null);
    }

    // ------------------------------------------------------------ UI

    /** Bedienleiste + obere Knöpfe ein-/ausblenden (aktive Sirene bleibt immer sichtbar). */
    private void setControls(boolean show) {
        if (gridMode) show = true;   // in der Übersicht immer bedienbar
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        topLeft.setVisibility(show ? View.VISIBLE : View.GONE);
        sidePanel.setVisibility(show || sirenOn ? View.VISIBLE : View.GONE);
        talkPanel.setVisibility((show || talking) && talkAllowed() ? View.VISIBLE : View.GONE);
        if (!show && ptzPanel.getVisibility() == View.VISIBLE) setPtzVisible(false);
    }

    private void showStatus(String s) {
        statusText.setText(s);
        statusText.setVisibility(View.VISIBLE);
    }

    private void hideStatus() { statusText.setVisibility(View.GONE); }

    private void toggleControls() {
        if (controls.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(hideControls);
            setControls(false);
        } else {
            showControls();
        }
    }

    private void showControls() {
        setControls(true);
        handler.removeCallbacks(hideControls);
        if (ptzPanel.getVisibility() != View.VISIBLE && !gridMode && !talking) {
            handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
        }
    }

    private void updateButtons() {
        boolean muted = Prefs.muted(this);
        btnSound.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        btnSound.setActivated(!muted);
        boolean hd = !"sub".equals(Prefs.liveStream(this));
        btnStream.setText(hd ? "HD" : "SD");
        btnStream.setActivated(hd);
        boolean fill = Prefs.zoom(this);
        btnZoom.setImageResource(fill ? R.drawable.ic_fit : R.drawable.ic_fill);
        btnZoom.setActivated(fill);
    }
}
