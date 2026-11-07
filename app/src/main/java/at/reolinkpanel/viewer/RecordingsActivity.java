package at.reolinkpanel.viewer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Aufnahmen von der SD-Karte, nach Tag. */
public class RecordingsActivity extends Activity {

    private static String cachedName;   // zuletzt geladene Aufnahme (Cache)

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<ReolinkApi.Recording> items = new ArrayList<>();
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EE, dd.MM.", Locale.GERMAN);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.GERMAN);

    private Calendar day;
    private ReolinkApi api;
    private int loadSeq;
    private volatile boolean cancel;

    private TextView dateText, emptyText, progressText;
    private View progressPanel;
    private ProgressBar progressBar;
    private Button btnNext;
    private RecAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        setContentView(R.layout.activity_recordings);

        api = ReolinkApi.from(this);
        day = midnight(Calendar.getInstance());

        dateText = findViewById(R.id.tvDate);
        emptyText = findViewById(R.id.tvEmpty);
        progressPanel = findViewById(R.id.progressPanel);
        progressText = findViewById(R.id.progressText);
        progressBar = findViewById(R.id.progressBar);
        btnNext = findViewById(R.id.btnNext);

        ListView list = findViewById(R.id.list);
        adapter = new RecAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                play(items.get(position));
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnPrev).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { day.add(Calendar.DAY_OF_MONTH, -1); load(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isToday()) { day.add(Calendar.DAY_OF_MONTH, 1); load(); }
            }
        });
        findViewById(R.id.btnToday).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { day = midnight(Calendar.getInstance()); load(); }
        });
        findViewById(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cancel = true; progressPanel.setVisibility(View.GONE); }
        });

        load();
    }

    @Override protected void onStart() { super.onStart(); AppState.visible++; }
    @Override protected void onStop() { AppState.visible--; super.onStop(); }

    @Override
    protected void onDestroy() {
        cancel = true;
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Ui.hideBars(this);
    }

    // ------------------------------------------------------------

    private void load() {
        dateText.setText(isToday() ? "Heute" : dayFmt.format(day.getTime()));
        btnNext.setEnabled(!isToday());
        btnNext.setAlpha(isToday() ? 0.35f : 1f);
        items.clear();
        adapter.notifyDataSetChanged();
        emptyText.setText("Lade Aufnahmen…");
        emptyText.setVisibility(View.VISIBLE);

        final int seq = ++loadSeq;
        final Calendar d = (Calendar) day.clone();
        final String stream = Prefs.recStream(this);
        executor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<ReolinkApi.Recording> result = api.search(d, stream);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            items.clear();
                            items.addAll(result);
                            adapter.notifyDataSetChanged();
                            emptyText.setText("Keine Aufnahmen an diesem Tag");
                            emptyText.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (seq != loadSeq) return;
                            emptyText.setText("Fehler: " + e.getMessage());
                            emptyText.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        });
    }

    private void play(final ReolinkApi.Recording rec) {
        final File out = new File(getCacheDir(), "clip.mp4");
        if (rec.name.equals(cachedName) && out.exists()) {
            openPlayer(out, rec);
            return;
        }
        cancel = false;
        progressBar.setProgress(0);
        progressText.setText("Lade Aufnahme…");
        progressPanel.setVisibility(View.VISIBLE);

        executor.execute(new Runnable() {
            int lastPct = -1;

            @Override public void run() {
                try {
                    api.download(rec, out, new ReolinkApi.Progress() {
                        @Override public boolean onProgress(final long done, final long total) {
                            final int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
                            if (pct != lastPct) {
                                lastPct = pct;
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        progressBar.setProgress(pct);
                                        progressText.setText("Lade Aufnahme… " + mb(done)
                                                + (total > 0 ? " / " + mb(total) : ""));
                                    }
                                });
                            }
                            return !cancel;
                        }
                    });
                    cachedName = rec.name;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progressPanel.setVisibility(View.GONE);
                            if (!cancel) openPlayer(out, rec);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            progressPanel.setVisibility(View.GONE);
                            if (!cancel) {
                                Toast.makeText(RecordingsActivity.this,
                                        "Download fehlgeschlagen: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                }
            }
        });
    }

    private void openPlayer(File f, ReolinkApi.Recording rec) {
        Intent i = new Intent(this, PlaybackActivity.class)
                .putExtra(PlaybackActivity.EXTRA_PATH, f.getAbsolutePath())
                .putExtra(PlaybackActivity.EXTRA_TITLE,
                        dayFmt.format(new Date(rec.start)) + "  " + timeFmt.format(new Date(rec.start)));
        startActivity(i);
    }

    // ------------------------------------------------------------

    private boolean isToday() {
        return day.getTimeInMillis() >= midnight(Calendar.getInstance()).getTimeInMillis();
    }

    private static Calendar midnight(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c;
    }

    private static String mb(long bytes) {
        return String.format(Locale.GERMAN, "%.1f MB", bytes / 1048576.0);
    }

    private static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.GERMAN, "%d:%02d min", s / 60, s % 60);
    }

    private class RecAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(RecordingsActivity.this).inflate(R.layout.item_recording, parent, false);
            ReolinkApi.Recording r = items.get(position);
            TextView time = v.findViewById(R.id.tvTime);
            TextView info = v.findViewById(R.id.tvInfo);
            time.setText(timeFmt.format(new Date(r.start)) + "  –  " + timeFmt.format(new Date(r.end)));
            String extra = duration(r.end - r.start);
            if (r.size > 0) extra += "  ·  " + mb(r.size);
            info.setText(extra);
            return v;
        }
    }
}
