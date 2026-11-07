package at.reolinkpanel.viewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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

/** Livebild der Kamera – Startbildschirm der App. */
public class MainActivity extends Activity implements MotionService.Listener {

    private static final long CONTROLS_TIMEOUT_MS = 8000;
    /** So lange darf es bis zum ersten Bild dauern, sonst gilt der Stream als nicht abspielbar. */
    private static final long FIRST_FRAME_TIMEOUT_MS = 15000;

    private PlayerView playerView;
    private ExoPlayer player;
    private View controls, sidePanel, ptzPanel;
    private TextView statusText, motionBadge;
    private Button btnSound, btnStream, btnZoom, btnSiren, btnPtz;
    private DPadView dpad;

    // --- Stream-Auswahl mit automatischem Rückfall
    private final List<String> candidates = new ArrayList<>();
    private int candIdx;
    private boolean gotFrame;

    // --- Kamera-Befehle (Sirene, PTZ) – nacheinander im Hintergrund
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ReolinkApi camApi;
    private boolean sirenOn = false;
    private boolean sirenBusy = false;
    private boolean ptzMoving = false;
    private boolean ptzErrorShown = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
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
        controls = findViewById(R.id.controls);
        sidePanel = findViewById(R.id.sidePanel);
        ptzPanel = findViewById(R.id.ptzPanel);
        statusText = findViewById(R.id.status);
        motionBadge = findViewById(R.id.motionBadge);
        btnSound = findViewById(R.id.btnSound);
        btnStream = findViewById(R.id.btnStream);
        btnZoom = findViewById(R.id.btnZoom);
        btnSiren = findViewById(R.id.btnSiren);
        btnPtz = findViewById(R.id.btnPtz);
        dpad = findViewById(R.id.dpad);

        findViewById(R.id.touch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleControls(); }
        });
        findViewById(R.id.btnRec).setOnClickListener(new View.OnClickListener() {
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
                showControls();
            }
        });
        btnSiren.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sirenOn) setSiren(false);   // Ausschalten sofort, ohne Rückfrage
                else confirmSiren();
            }
        });
        btnPtz.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPtzVisible(ptzPanel.getVisibility() != View.VISIBLE); }
        });
        findViewById(R.id.btnPtzClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setPtzVisible(false); }
        });
        dpad.setListener(new DPadView.Listener() {
            @Override public void onDirection(String op) { ptz(op); }
        });
        holdButton(findViewById(R.id.btnZoomIn), "ZoomInc");
        holdButton(findViewById(R.id.btnZoomOut), "ZoomDec");

        updateButtons();
        updateSiren();

        if (!Prefs.isConfigured(this)) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            MotionService.start(this);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(MotionService.EXTRA_MOTION, false)) {
            handler.removeCallbacks(hideControls);
            setControls(false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppState.visible++;
        AppState.liveVisible = true;
        camApi = null;   // Einstellungen könnten sich geändert haben
        MotionService.setListener(this);
        applyMotion(MotionService.motionActive);
        startLive();
        if (getIntent() != null && getIntent().getBooleanExtra(MotionService.EXTRA_MOTION, false)) {
            setControls(false);
            getIntent().removeExtra(MotionService.EXTRA_MOTION);
        } else {
            showControls();
        }
    }

    @Override
    protected void onStop() {
        AppState.visible--;
        AppState.liveVisible = false;
        MotionService.clearListener(this);
        if (sirenOn) setSiren(false);   // Sirene nie unbeaufsichtigt weiterlaufen lassen
        setPtzVisible(false);           // stoppt auch laufende Schwenks
        handler.removeCallbacksAndMessages(null);
        stopLive();   // spart CPU und WLAN, wenn das Display aus ist
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
        else showControls();
    }

    // ------------------------------------------------------------ Bewegung

    @Override
    public void onMotionChanged(final boolean active) {
        runOnUiThread(new Runnable() {
            @Override public void run() { applyMotion(active); }
        });
    }

    private void applyMotion(boolean active) {
        motionBadge.setVisibility(active ? View.VISIBLE : View.GONE);
        updateKeepOn();
    }

    private void updateKeepOn() {
        if (MotionService.motionActive || sirenOn) {
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
        if (!Prefs.rtspPath(this).isEmpty() || "sub".equals(Prefs.liveStream(this))) {
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
        if (!Prefs.isConfigured(this)) {
            showStatus("Bitte zuerst die Kamera einrichten (⚙)");
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

        String url = ReolinkApi.from(this).rtspUrl(stream);
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
        if (camApi == null) camApi = ReolinkApi.from(this);
        return camApi;
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
            btnSiren.setText("Sirene\nAUS");
            btnSiren.setBackgroundResource(R.drawable.btn_siren_active);
            btnSiren.setTextColor(0xFF000000);
        } else {
            btnSiren.setText("Sirene");
            btnSiren.setBackgroundResource(R.drawable.btn_bg);
            btnSiren.setTextColor(getColor(R.color.text));
        }
        setControls(controls.getVisibility() == View.VISIBLE);
        updateKeepOn();
    }

    // ------------------------------------------------------------ UI

    /** Bedienleiste + Seitenknöpfe ein-/ausblenden (aktive Sirene bleibt immer sichtbar). */
    private void setControls(boolean show) {
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
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
        if (ptzPanel.getVisibility() != View.VISIBLE) {
            handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
        }
    }

    private void updateButtons() {
        btnSound.setText(Prefs.muted(this) ? "Ton aus" : "Ton an");
        btnStream.setText("sub".equals(Prefs.liveStream(this)) ? "SD" : "HD");
        btnZoom.setText(Prefs.zoom(this) ? "Füllen" : "Ganz");
    }
}
