package at.reolinkpanel.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

/** Kamera bzw. Türklingel hinzufügen oder bearbeiten. */
public class CameraEditActivity extends Activity {

    public static final String EXTRA_ID = "camId";

    private EditText etName, etHost, etUser, etPass, etHttpPort, etRtspPort, etRtspPath;
    private CheckBox cbHttps, cbDoorbell, cbWake;
    private TextView tvTest;
    private Cam cam;
    private boolean isNew;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_edit);

        etName = findViewById(R.id.etName);
        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etHttpPort = findViewById(R.id.etHttpPort);
        etRtspPort = findViewById(R.id.etRtspPort);
        etRtspPath = findViewById(R.id.etRtspPath);
        cbHttps = findViewById(R.id.cbHttps);
        cbDoorbell = findViewById(R.id.cbDoorbell);
        cbWake = findViewById(R.id.cbWake);
        tvTest = findViewById(R.id.tvTest);

        String id = getIntent().getStringExtra(EXTRA_ID);
        cam = Prefs.camera(this, id);
        isNew = cam == null;
        if (isNew) {
            cam = new Cam();
            cam.id = Prefs.newCameraId(this);
            cam.name = Prefs.cameras(this).isEmpty() ? "Kamera" : "Kamera " + (Prefs.cameras(this).size() + 1);
        }
        ((TextView) findViewById(R.id.tvTitle)).setText(isNew ? "Neue Kamera" : cam.displayName());
        findViewById(R.id.btnDelete).setVisibility(isNew ? View.GONE : View.VISIBLE);
        fill();

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

    private void fill() {
        etName.setText(cam.name);
        etHost.setText(cam.host);
        etUser.setText(cam.user);
        etPass.setText(cam.pass);
        cbHttps.setChecked(cam.https);
        etHttpPort.setText(cam.httpPort > 0 ? String.valueOf(cam.httpPort) : "");
        etRtspPort.setText(String.valueOf(cam.rtspPort));
        etRtspPath.setText(cam.rtspPath);
        cbDoorbell.setChecked(cam.doorbell);
        cbWake.setChecked(cam.wake);
    }

    private Cam read() {
        Cam c = new Cam();
        c.id = cam.id;
        c.name = text(etName).isEmpty() ? text(etHost) : text(etName);
        c.host = text(etHost);
        c.user = text(etUser);
        c.pass = etPass.getText().toString();
        c.https = cbHttps.isChecked();
        c.httpPort = num(etHttpPort, 0);
        c.rtspPort = num(etRtspPort, 554);
        c.rtspPath = text(etRtspPath);
        c.doorbell = cbDoorbell.isChecked();
        c.wake = cbWake.isChecked();
        return c;
    }

    private void save() {
        Cam c = read();
        if (c.host.isEmpty()) {
            Toast.makeText(this, "Bitte IP-Adresse der Kamera eingeben", Toast.LENGTH_LONG).show();
            return;
        }
        List<Cam> list = Prefs.cameras(this);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(c.id)) { list.set(i, c); replaced = true; }
        }
        if (!replaced) list.add(c);
        Prefs.saveCameras(this, list);
        if (list.size() == 1) Prefs.setCurrent(this, c.id);
        MotionService.reload(this);
        Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Kamera entfernen?")
                .setMessage("„" + cam.displayName() + "“ wird aus der App entfernt. Die Kamera selbst bleibt unverändert.")
                .setPositiveButton("Entfernen", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        List<Cam> list = Prefs.cameras(CameraEditActivity.this);
                        for (int i = list.size() - 1; i >= 0; i--) {
                            if (list.get(i).id.equals(cam.id)) list.remove(i);
                        }
                        Prefs.saveCameras(CameraEditActivity.this, list);
                        MotionService.reload(CameraEditActivity.this);
                        finish();
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void test() {
        final ReolinkApi api = ReolinkApi.from(read());
        tvTest.setTextColor(getColor(R.color.textDim));
        tvTest.setText("Teste Verbindung…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok;
                boolean bell = false;
                String model = "";
                try {
                    api.login();
                    model = api.model();
                    msg = "✓ Verbunden: " + api.deviceName();
                    bell = model.toLowerCase(Locale.ROOT).contains("doorbell");
                    ok = true;
                } catch (Exception ex) {
                    msg = "⚠ Fehlgeschlagen: " + ex.getMessage()
                            + "\nTipp: HTTPS/HTTP-Einstellung und Port prüfen.";
                    ok = false;
                }
                final String m = msg;
                final boolean good = ok;
                final boolean isBell = bell;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvTest.setTextColor(getColor(good ? R.color.accent : R.color.warn));
                        tvTest.setText(isBell ? m + "\nTürklingel erkannt – Klingel-Erkennung aktiviert." : m);
                        if (isBell) cbDoorbell.setChecked(true);
                    }
                });
            }
        }).start();
    }

    private static String text(EditText e) { return e.getText().toString().trim(); }

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
