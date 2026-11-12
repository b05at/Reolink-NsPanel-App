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
    private View controls, sidePanel, topLeft, ptzPanel, sleepOverlay;
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
    private final List<String> candidates = new ArrayList<>();
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

    // --- Bildschirm-Timeout / Ruhemodus
    private boolean sleeping;          // Ruhemodus aktiv (schwarz, Tipp weckt)
    private boolean screenReleased;    // Display darf vom System ausgeschaltet werden
    private boolean swallowTouch;      // Berührung, die geweckt hat, nicht weiterreichen

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
        sleepOverlay = findViewById(R.id.sleepOverlay);
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
            if (sleeping) leaveSleepUi();
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
        setPtzVisible(false);           // stoppt auch laufende Schwenks
        handler.removeCallbacksAndMessages(null);
        stopLive();   // spart CPU und WLAN, wenn das Display aus ist
        dashboard.stop();
        leaveSleepUi();
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
        btnLight.setVisibility(single && hasCam && !bell ? View.VISIBLE : View.GONE);
        btnSiren.setVisibility(single && hasCam && !bell ? View.VISIBLE : View.GONE);
        btnRec.setVisibility(single && hasCam ? View.VISIBLE : View.GONE);
        camChip.setText(gridMode ? "Alle Kameras ▾" : (cam != null ? cam.displayName() : "Kamera") + (cams.size() >= 2 ? " ▾" : ""));

        if (gridMode) {
            stopLive();
            hideStatus();
            playerView.setVisibility(View.GONE);
            dashboard.setVisibility(View.VISIBLE);
            dashboard.start(cams, Prefs.gridLive(this), Prefs.gridSeconds(this), Prefs.zoom(this));
            dashboard.setActive(MotionService.activeCamId, MotionService.motionActive, MotionService.activeRing);
        } else {
            dashboard.stop();
            dashboard.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            startLive();
            if (hasCam && !bell) queryLight();
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
                if (active && sleeping) wakeUp();
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
        if (managed || sleeping || MotionService.motionActive || sirenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ Livebild

    /**
     * SD = Substream. HD versucht zuerst den mittleren Stream (ext, max. ~1080p),
     * dann den Hauptstream. Kann das Panel nichts davon abspielen (4K/H.265 übersteigt
     * den Decoder), fällt die App automatisch auf SD zurück.
     */
    private void startLive() {
        candidates.clear();
        if (cam == null || !cam.rtspPath.trim().isEmpty() || "sub".equals(Prefs.liveStream(this))) {
            candidates.add("sub");
        } else {
            candidates.addAll(Arrays.asList("ext", "main", "sub"));
        }
        candIdx = 0;
        playCandidate();
    }

    private boolean isHdAttempt() {
        return !"sub".equals(candidates.get(candIdx));
    }

    private void playCandidate() {
        stopLive();
        if (gridMode) return;
        if (cam == null) {
            showStatus("Noch keine Kamera eingerichtet –\nüber ⚙ → Kamera hinzufügen");
            return;
        }
        final String stream = candidates.get(candIdx);
        gotFrame = false;
        showStatus(isHdAttempt() ? "Verbinde (HD)…" : "Verbinde…");

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)   // geringe Verzögerung
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(Prefs.muted(this) ? 0f : 1f);
        playerView.setPlayer(player);
        applyZoom();

        String url = ReolinkApi.from(cam).rtspUrl(stream);
        MediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));

        player.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                gotFrame = true;
                handler.removeCallbacks(firstFrameTimeout);
                handler.removeCallbacks(stallCheck);
                hideStatus();
            }

            @Override
            public void onTracksChanged(Tracks tracks) {
                // Video vorhanden, aber vom Panel nicht dekodierbar (z. B. 4K H.265)
                if (tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, false)) {
                    streamFailed("Format nicht unterstützt");
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
                streamFailed(error.getErrorCodeName());
            }
        });
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
        handler.postDelayed(firstFrameTimeout, FIRST_FRAME_TIMEOUT_MS);
    }

    /** Stream ließ sich nicht abspielen: nächste Variante probieren oder neu verbinden. */
    private void streamFailed(String reason) {
        if (player == null) return;
        handler.removeCallbacks(firstFrameTimeout);
        if (!gotFrame && isHdAttempt() && candIdx < candidates.size() - 1) {
            candIdx++;
            if (!isHdAttempt()) {
                // Alle HD-Varianten gescheitert -> dauerhaft auf SD zurück
                Prefs.sp(this).edit().putString("liveStream", "sub").apply();
                updateButtons();
                Toast.makeText(this, "HD kann am Panel nicht abgespielt werden – zurück auf SD.\n"
                        + "Tipp: In der Reolink-App den Hauptstream auf H.264 bzw. max. 1080p stellen.",
                        Toast.LENGTH_LONG).show();
            }
            playCandidate();
            return;
        }
        scheduleRetry("Keine Verbindung zur Kamera – neuer Versuch…");
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
    }

    // ------------------------------------------------------------ Kamera-Befehle

    private ReolinkApi api() {
        if (camApi == null) camApi = cam != null ? ReolinkApi.from(cam) : ReolinkApi.from(this);
        return camApi;
    }

    // ------------------------------------------------------------ Scheinwerfer

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
        if (!Prefs.tapToWake(this)) return -1;
        // Ruhemodus mit System-Standard: Systemwert übernehmen
        try {
            return Math.max(10000, Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT));
        } catch (Exception e) {
            return 60000;
        }
    }

    private void resetSleepTimer() {
        handler.removeCallbacks(sleepRunnable);
        if (!started) return;
        int ms = effectiveTimeoutMs();
        if (ms > 0 && !sleeping) handler.postDelayed(sleepRunnable, ms);
        updateKeepOn();
    }

    private void goToSleep() {
        if (!started || sleeping) return;
        // Nicht während Bewegung, Sirene oder Schwenken
        if (MotionService.motionActive || sirenOn || ptzPanel.getVisibility() == View.VISIBLE) {
            resetSleepTimer();
            return;
        }
        if (Prefs.tapToWake(this)) {
            // Ruhemodus: Bild schwarz, Helligkeit minimal, Stream aus – Display bleibt berührbar
            sleeping = true;
            stopLive();
            dashboard.stop();
            hideStatus();
            sleepOverlay.setVisibility(View.VISIBLE);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
            getWindow().setAttributes(lp);
            updateKeepOn();
        } else {
            // Echtes Ausschalten: mit Geräteadministrator sofort, sonst über das System-Timeout
            screenReleased = true;
            updateKeepOn();
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            ComponentName admin = new ComponentName(this, AdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(admin)) {
                try { dpm.lockNow(); } catch (SecurityException ignored) {}
            }
        }
    }

    private void wakeUp() {
        boolean wasSleeping = sleeping;
        leaveSleepUi();
        screenReleased = false;
        if (wasSleeping) applyMode();
        showControls();
        resetSleepTimer();
    }

    private void leaveSleepUi() {
        sleeping = false;
        sleepOverlay.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(lp);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (sleeping) {
                swallowTouch = true;   // diese Berührung weckt nur, löst nichts aus
                wakeUp();
                return true;
            }
            swallowTouch = false;
            if (screenReleased) {
                screenReleased = false;
                updateKeepOn();
            }
            resetSleepTimer();
        }
        if (swallowTouch) {
            if (ev.getActionMasked() == MotionEvent.ACTION_UP || ev.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                swallowTouch = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------ UI

    /** Bedienleiste + obere Knöpfe ein-/ausblenden (aktive Sirene bleibt immer sichtbar). */
    private void setControls(boolean show) {
        if (gridMode) show = true;   // in der Übersicht immer bedienbar
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        topLeft.setVisibility(show ? View.VISIBLE : View.GONE);
        sidePanel.setVisibility(show || sirenOn ? View.VISIBLE : View.GONE);
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
        if (ptzPanel.getVisibility() != View.VISIBLE && !gridMode) {
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
