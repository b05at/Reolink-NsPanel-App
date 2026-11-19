package at.reolinkpanel.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Webhook-Knopf anlegen/bearbeiten: Name, Symbol, URL, Methode, Kamera. */
public class WebhookEditActivity extends Activity {

    public static final String EXTRA_ID = "webhookId";

    private Webhook hook;
    private boolean isNew;
    private String icon;
    private EditText etName, etUrl, etBody;
    private RadioGroup rgMethod;
    private CheckBox cbConfirm;
    private Spinner spCam;
    private final List<ImageButton> iconButtons = new ArrayList<>();
    private final List<Cam> cams = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webhook_edit);
        ((ScrollRail) findViewById(R.id.scrollRail)).attach((android.widget.ScrollView) findViewById(R.id.scroll));

        etName = findViewById(R.id.etName);
        etUrl = findViewById(R.id.etUrl);
        etBody = findViewById(R.id.etBody);
        rgMethod = findViewById(R.id.rgMethod);
        cbConfirm = findViewById(R.id.cbConfirm);
        spCam = findViewById(R.id.spCam);

        hook = Prefs.webhook(this, getIntent().getStringExtra(EXTRA_ID));
        isNew = hook == null;
        if (isNew) {
            hook = new Webhook();
            hook.id = Prefs.newWebhookId(this);
            hook.name = "";
        }
        ((TextView) findViewById(R.id.tvTitle)).setText(isNew ? "Neuer Webhook-Knopf" : hook.name);
        findViewById(R.id.btnDelete).setVisibility(isNew ? View.GONE : View.VISIBLE);

        // Kamera-Auswahl
        cams.addAll(Prefs.cameras(this));
        List<String> labels = new ArrayList<>();
        labels.add("Allen Kameras und der Übersicht");
        int sel = 0;
        for (int i = 0; i < cams.size(); i++) {
            labels.add("Nur bei: " + cams.get(i).displayName());
            if (cams.get(i).id.equals(hook.camId)) sel = i + 1;
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spCam.setAdapter(ad);
        spCam.setSelection(sel);

        etName.setText(hook.name);
        etUrl.setText(hook.url);
        etBody.setText(hook.body);
        cbConfirm.setChecked(hook.confirm);
        rgMethod.check("POST".equalsIgnoreCase(hook.method) ? R.id.rbPost : R.id.rbGet);
        icon = hook.icon;
        buildIconGrid();

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { test(); }
        });
        findViewById(R.id.btnDelete).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmDelete(); }
        });
    }

    @Override protected void onStart() { super.onStart(); AppState.visible++; }
    @Override protected void onStop() { AppState.visible--; super.onStop(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Symbole in Reihen zu je 6 – das gewählte ist blau hinterlegt. */
    private void buildIconGrid() {
        LinearLayout grid = findViewById(R.id.iconGrid);
        grid.removeAllViews();
        iconButtons.clear();
        LinearLayout row = null;
        for (int i = 0; i < Icons.KEYS.length; i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row);
            }
            final String key = Icons.KEYS[i];
            ImageButton b = new ImageButton(this);
            b.setImageResource(Icons.res(key));
            b.setBackgroundResource(R.drawable.icon_btn);
            b.setImageTintList(getColorStateList(R.color.icon_tint));
            b.setContentDescription(Icons.LABELS[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { icon = key; updateIcons(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.setMargins(dp(1), dp(1), dp(1), dp(1));
            row.addView(b, lp);
            iconButtons.add(b);
        }
        updateIcons();
    }

    private void updateIcons() {
        for (int i = 0; i < iconButtons.size(); i++) {
            boolean on = Icons.KEYS[i].equals(icon);
            iconButtons.get(i).setActivated(on);
            if (on) ((TextView) findViewById(R.id.tvIconName)).setText("Gewählt: " + Icons.LABELS[i]);
        }
    }

    private Webhook read() {
        Webhook w = new Webhook();
        w.id = hook.id;
        w.name = etName.getText().toString().trim();
        w.icon = icon;
        w.url = etUrl.getText().toString().trim();
        w.method = rgMethod.getCheckedRadioButtonId() == R.id.rbPost ? "POST" : "GET";
        w.body = etBody.getText().toString();
        w.confirm = cbConfirm.isChecked();
        int pos = spCam.getSelectedItemPosition();
        w.camId = pos <= 0 || pos > cams.size() ? "" : cams.get(pos - 1).id;
        return w;
    }

    private void save() {
        Webhook w = read();
        if (w.url.isEmpty()) {
            Toast.makeText(this, "Bitte eine URL eintragen", Toast.LENGTH_LONG).show();
            return;
        }
        if (w.name.isEmpty()) w.name = Icons.LABELS[java.util.Arrays.asList(Icons.KEYS).indexOf(w.icon) < 0 ? 0
                : java.util.Arrays.asList(Icons.KEYS).indexOf(w.icon)];
        List<Webhook> list = Prefs.webhooks(this);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(w.id)) { list.set(i, w); replaced = true; }
        }
        if (!replaced) list.add(w);
        Prefs.saveWebhooks(this, list);
        Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Knopf entfernen?")
                .setMessage("„" + hook.name + "“ wird entfernt.")
                .setPositiveButton("Entfernen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        List<Webhook> list = Prefs.webhooks(WebhookEditActivity.this);
                        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).id.equals(hook.id)) list.remove(i);
                        Prefs.saveWebhooks(WebhookEditActivity.this, list);
                        finish();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void test() {
        final Webhook w = read();
        final TextView tv = findViewById(R.id.tvTest);
        if (w.url.isEmpty()) { tv.setText("Bitte zuerst eine URL eintragen."); return; }
        tv.setTextColor(getColor(R.color.textDim));
        tv.setText("Sende…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    int code = Webhook.call(w.url, w.method, w.body);
                    ok = code >= 200 && code < 300;
                    msg = (ok ? "✓ " : "⚠ ") + Webhook.describe(code);
                } catch (Exception e) {
                    msg = "⚠ Nicht erreichbar: " + e.getMessage();
                }
                final String m = msg;
                final boolean good = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tv.setTextColor(getColor(good ? R.color.accent : R.color.warn));
                        tv.setText(m);
                    }
                });
            }
        }).start();
    }
}
