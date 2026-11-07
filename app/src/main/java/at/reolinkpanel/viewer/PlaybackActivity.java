package at.reolinkpanel.viewer;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.io.File;

/** Spielt eine geladene Aufnahme mit Suchleiste ab. */
public class PlaybackActivity extends Activity {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";

    private PlayerView playerView;
    private ExoPlayer player;
    private long position = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_playback);

        playerView = findViewById(R.id.player);
        TextView title = findViewById(R.id.tvTitle);
        String t = getIntent().getStringExtra(EXTRA_TITLE);
        title.setText(t == null ? "Aufnahme" : t);
        findViewById(R.id.btnClose).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        if (savedInstanceState != null) position = savedInstanceState.getLong("pos", 0);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppState.visible++;
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) { finish(); return; }
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
        player.prepare();
        if (position > 0) player.seekTo(position);
        player.setPlayWhenReady(true);
    }

    @Override
    protected void onStop() {
        AppState.visible--;
        if (player != null) {
            position = player.getCurrentPosition();
            player.release();
            player = null;
        }
        playerView.setPlayer(null);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("pos", player != null ? player.getCurrentPosition() : position);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideBars(this);
    }
}
