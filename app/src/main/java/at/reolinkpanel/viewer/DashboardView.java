package at.reolinkpanel.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Übersicht mehrerer Kameras im Raster.
 * Standard: Standbilder, die alle paar Sekunden aktualisiert werden (sehr wenig CPU).
 * Optional: echtes Livevideo (Substream) für bis zu 4 Kameras.
 */
public class DashboardView extends LinearLayout {

    public interface OnTileClick { void onTile(Cam cam); }

    private static final int MAX_LIVE = 4;

    private class Tile {
        Cam cam;
        ReolinkApi api;
        FrameLayout root;
        ImageView image;
        PlayerView playerView;
        ExoPlayer player;
        TextView label, info;
        View highlight;
        boolean live;
    }

    private final List<Tile> tiles = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnTileClick clickListener;
    private volatile boolean running;
    private Thread snapThread;
    private boolean fill;

    public DashboardView(Context c) { this(c, null); }

    public DashboardView(Context c, AttributeSet a) {
        super(c, a);
        setOrientation(VERTICAL);
    }

    public void setOnTileClick(OnTileClick l) { clickListener = l; }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ------------------------------------------------------------ Aufbau

    public void start(List<Cam> cams, boolean liveVideo, final int intervalSec, boolean fillTiles) {
        stop();
        fill = fillTiles;
        removeAllViews();
        tiles.clear();
        int n = cams.size();
        int cols = n <= 2 ? 1 : n <= 4 ? 2 : 3;
        int rows = (n + cols - 1) / cols;
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < cols; c++) {
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(2), dp(2), dp(2), dp(2));
                if (idx < n) {
                    Tile t = buildTile(cams.get(idx), liveVideo && idx < MAX_LIVE);
                    row.addView(t.root, lp);
                    tiles.add(t);
                } else {
                    row.addView(new View(getContext()), lp);   // leere Zelle
                }
                idx++;
            }
        }
        running = true;
        for (Tile t : tiles) if (t.live) startPlayer(t);
        snapThread = new Thread(new Runnable() {
            @Override public void run() { snapLoop(Math.max(1, intervalSec)); }
        }, "dashboard-snap");
        snapThread.start();
    }

    public void stop() {
        running = false;
        if (snapThread != null) snapThread.interrupt();
        snapThread = null;
        handler.removeCallbacksAndMessages(null);
        for (Tile t : tiles) releasePlayer(t);
    }

    /** Kachel bei Bewegung/Klingeln gelb umranden. */
    public void setActive(String camId, boolean active, boolean ring) {
        for (Tile t : tiles) {
            boolean on = active && t.cam.id.equals(camId);
            t.highlight.setVisibility(on ? VISIBLE : GONE);
            t.label.setText(on ? (ring ? "🔔 " : "● ") + t.cam.displayName() : t.cam.displayName());
        }
    }

    private Tile buildTile(final Cam cam, boolean live) {
        final Tile t = new Tile();
        t.cam = cam;
        t.api = ReolinkApi.from(cam);
        t.live = live;
        t.root = new FrameLayout(getContext());
        t.root.setBackgroundResource(R.drawable.thumb_bg);
        t.root.setClipToOutline(true);

        if (live) {
            t.playerView = (PlayerView) inflate(getContext(), R.layout.tile_player, null);
            t.playerView.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            t.root.addView(t.playerView, new FrameLayout.LayoutParams(-1, -1));
        } else {
            t.image = new ImageView(getContext());
            t.image.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            t.root.addView(t.image, new FrameLayout.LayoutParams(-1, -1));
        }

        t.info = new TextView(getContext());
        t.info.setTextColor(getResources().getColor(R.color.textDim, null));
        t.info.setTextSize(13);
        t.info.setText("Verbinde…");
        t.root.addView(t.info, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        t.label = new TextView(getContext());
        t.label.setTextColor(getResources().getColor(R.color.text, null));
        t.label.setTextSize(12);
        t.label.setSingleLine(true);
        t.label.setBackgroundResource(R.drawable.pill);
        t.label.setPadding(dp(8), dp(2), dp(8), dp(2));
        t.label.setText(cam.displayName());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        t.root.addView(t.label, lp);

        t.highlight = new View(getContext());
        t.highlight.setBackgroundResource(R.drawable.tile_active);
        t.highlight.setVisibility(GONE);
        t.root.addView(t.highlight, new FrameLayout.LayoutParams(-1, -1));

        t.root.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (clickListener != null) clickListener.onTile(cam);
            }
        });
        return t;
    }

    // ------------------------------------------------------------ Livevideo-Kacheln

    private void startPlayer(final Tile t) {
        releasePlayer(t);
        if (!running) return;
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 250, 500).build();
        t.player = new ExoPlayer.Builder(getContext()).setLoadControl(lc).build();
        t.player.setVolume(0f);
        t.playerView.setPlayer(t.player);
        t.player.addListener(new Player.Listener() {
            @Override public void onRenderedFirstFrame() { t.info.setVisibility(GONE); }

            @Override public void onPlayerError(PlaybackException error) {
                t.info.setText("Offline – neuer Versuch…");
                t.info.setVisibility(VISIBLE);
                handler.postDelayed(new Runnable() {
                    @Override public void run() { startPlayer(t); }
                }, 5000);
            }
        });
        t.player.setMediaSource(new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(t.api.rtspUrl("sub")))));
        t.player.prepare();
        t.player.setPlayWhenReady(true);
    }

    private void releasePlayer(Tile t) {
        if (t.player != null) {
            t.player.release();
            t.player = null;
        }
        if (t.playerView != null) t.playerView.setPlayer(null);
    }

    // ------------------------------------------------------------ Standbild-Kacheln

    private void snapLoop(int intervalSec) {
        while (running) {
            long started = System.currentTimeMillis();
            for (final Tile t : new ArrayList<>(tiles)) {
                if (!running) return;
                if (t.live) continue;
                try {
                    byte[] jpg = t.api.snap(640, 360);
                    final Bitmap b = decode(jpg, Math.max(160, t.root.getWidth()));
                    if (b == null) continue;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            if (!running) return;
                            t.image.setImageBitmap(b);
                            t.info.setVisibility(GONE);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            t.info.setText("Offline");
                            t.info.setVisibility(VISIBLE);
                        }
                    });
                }
            }
            long wait = intervalSec * 1000L - (System.currentTimeMillis() - started);
            try {
                Thread.sleep(Math.max(300, wait));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Dekodiert das JPEG nur so groß wie nötig (spart CPU und Speicher). */
    private static Bitmap decode(byte[] jpg, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }
}
