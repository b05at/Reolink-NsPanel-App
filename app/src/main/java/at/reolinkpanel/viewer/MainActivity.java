package at.reolinkpanel.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Livebild der Kamera – Startbildschirm der App. */
public class MainActivity extends Activity implements MotionService.Listener {

    private static final long CONTROLS_TIMEOUT_MS = 8000;

    private PlayerView playerView;
    private ExoPlayer player;
    private View controls;
    private TextView statusText, motionBadge;
    private Button btnSound, btnStream, btnZoom, btnSiren;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ReolinkApi sirenApi;
    private boolean sirenOn = false;
    private boolean sirenBusy = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sirenAutoOff = new Runnable() {
        @Override public void run() { setSiren(false); }
    };
    private final Runnable hideControls = new Runnable() {
        @Override public void run() { setControls(false); }
    };
    private final Runnable retry = new Runnable() {
        @Override public void run() { startLive(); }
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
        statusText = findViewById(R.id.status);
        motionBadge = findViewById(R.id.motionBadge);
        btnSound = findViewById(R.id.btnSound);
        btnStream = findViewById(R.id.btnStream);
        btnZoom = findViewById(R.id.btnZoom);
        btnSiren = findViewById(R.id.btnSiren);

        btnSiren.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sirenOn) setSiren(false);   // Ausschalten sofort, ohne Rückfrage
                else confirmSiren();
            }
        });

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
                if ("main".equals(next)) {
                    Toast.makeText(MainActivity.this,
                            "Hauptstream (4K) kann auf dem Panel ruckeln", Toast.LENGTH_LONG).show();
                }
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

        updateButtons();

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
        handler.removeCallbacksAndMessages(null);
        stopLive();   // spart CPU und WLAN, wenn das Display aus ist
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();   // laufende Befehle (z. B. Sirene aus) noch abschließen
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideBars(this);
    }

    @Override
    public void onBackPressed() {
        // Am Panel nicht versehentlich beenden – nur Bedienleiste zeigen.
        showControls();
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
        if (active) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else if (!sirenOn) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ Livebild

    private void startLive() {
        stopLive();
        if (!Prefs.isConfigured(this)) {
            showStatus("Bitte zuerst die Kamera einrichten (⚙)");
            return;
        }
        showStatus("Verbinde…");

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500)   // geringe Verzögerung
                .build();
        player = new ExoPlayer.Builder(this).setLoadControl(loadControl).build();
        player.setVolume(Prefs.muted(this) ? 0f : 1f);
        playerView.setPlayer(player);
        applyZoom();

        String url = ReolinkApi.from(this).rtspUrl(Prefs.liveStream(this));
        MediaSource source = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    handler.removeCallbacks(stallCheck);
                    hideStatus();
                } else if (state == Player.STATE_BUFFERING) {
                    handler.removeCallbacks(stallCheck);
                    handler.postDelayed(stallCheck, 12000);
                } else if (state == Player.STATE_ENDED) {
                    scheduleRetry("Stream beendet – verbinde neu…");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                scheduleRetry("Keine Verbindung zur Kamera – neuer Versuch…");
            }
        });
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private void stopLive() {
        handler.removeCallbacks(retry);
        handler.removeCallbacks(stallCheck);
        if (player != null) {
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
    }

    private void scheduleRetry(String msg) {
        showStatus(msg);
        handler.removeCallbacks(stallCheck);
        handler.removeCallbacks(retry);
        handler.postDelayed(retry, 3000);
    }

    private void applyZoom() {
        playerView.setResizeMode(Prefs.zoom(this)
                ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : AspectRatioFrameLayout.RESIZE_MODE_FIT);
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
        if (sirenApi == null) sirenApi = ReolinkApi.from(this);
        final ReolinkApi api = sirenApi;
        if (!on) {
            // UI sofort zurücksetzen, Befehl läuft im Hintergrund
            sirenOn = false;
            updateSiren();
        }
        executor.execute(new Runnable() {
            @Override public void run() {
                Exception err = null;
                try {
                    api.siren(on);
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
                            handler.postDelayed(sirenAutoOff, Math.max(5, Prefs.sirenSeconds(MainActivity.this)) * 1000L);
                        }
                    }
                });
            }
        });
    }

    private void updateSiren() {
        if (sirenOn) {
            btnSiren.setText("Sirene AUS");
            btnSiren.setBackgroundResource(R.drawable.btn_siren_active);
            btnSiren.setTextColor(0xFF000000);
            btnSiren.setVisibility(View.VISIBLE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            btnSiren.setText("Sirene");
            btnSiren.setBackgroundResource(R.drawable.btn_bg);
            btnSiren.setTextColor(getColor(R.color.text));
            btnSiren.setVisibility(controls.getVisibility());
            if (!MotionService.motionActive) {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        }
    }

    // ------------------------------------------------------------ UI

    /** Bedienleiste und Sirenen-Knopf ein-/ausblenden (aktive Sirene bleibt immer sichtbar). */
    private void setControls(boolean show) {
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        btnSiren.setVisibility(show || sirenOn ? View.VISIBLE : View.GONE);
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
        handler.postDelayed(hideControls, CONTROLS_TIMEOUT_MS);
    }

    private void updateButtons() {
        btnSound.setText(Prefs.muted(this) ? "Ton aus" : "Ton an");
        btnStream.setText("sub".equals(Prefs.liveStream(this)) ? "SD" : "HD");
        btnZoom.setText(Prefs.zoom(this) ? "Füllen" : "Ganz");
    }
}
