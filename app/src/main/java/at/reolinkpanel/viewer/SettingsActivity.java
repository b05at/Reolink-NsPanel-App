package at.reolinkpanel.viewer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/** Einrichtung: Kamera-Zugang, Bewegungserkennung, Verhalten. */
public class SettingsActivity extends Activity {

    private EditText etHost, etUser, etPass, etHttpPort, etRtspPort, etRtspPath, etWake, etPoll, etSiren;
    private CheckBox cbHttps, cbFront, cbBoot, cbOpenBoot;
    private RadioGroup rgLive, rgRec, rgTrigger;
    private TextView tvTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etHost = findViewById(R.id.etHost);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etHttpPort = findViewById(R.id.etHttpPort);
        etRtspPort = findViewById(R.id.etRtspPort);
        etRtspPath = findViewById(R.id.etRtspPath);
        etWake = findViewById(R.id.etWake);
        etPoll = findViewById(R.id.etPoll);
        etSiren = findViewById(R.id.etSiren);
        cbHttps = findViewById(R.id.cbHttps);
        cbFront = findViewById(R.id.cbFront);
        cbBoot = findViewById(R.id.cbBoot);
        cbOpenBoot = findViewById(R.id.cbOpenBoot);
        rgLive = findViewById(R.id.rgLive);
        rgRec = findViewById(R.id.rgRec);
        rgTrigger = findViewById(R.id.rgTrigger);
        tvTest = findViewById(R.id.tvTest);

        fill();

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        findViewById(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { test(); }
        });
        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { batteryOptimization(); }
        });
    }

    @Override protected void onStart() { super.onStart(); AppState.visible++; }
    @Override protected void onStop() { AppState.visible--; super.onStop(); }

    private void fill() {
        etHost.setText(Prefs.host(this));
        etUser.setText(Prefs.user(this));
        etPass.setText(Prefs.pass(this));
        cbHttps.setChecked(Prefs.https(this));
        int hp = Prefs.httpPort(this);
        etHttpPort.setText(hp > 0 ? String.valueOf(hp) : "");
        etRtspPort.setText(String.valueOf(Prefs.rtspPort(this)));
        etRtspPath.setText(Prefs.rtspPath(this));
        rgLive.check("main".equals(Prefs.liveStream(this)) ? R.id.rbLiveMain : R.id.rbLiveSub);
        rgRec.check("main".equals(Prefs.recStream(this)) ? R.id.rbRecMain : R.id.rbRecSub);
        String t = Prefs.trigger(this);
        rgTrigger.check("off".equals(t) ? R.id.rbTrigOff
                : "person".equals(t) ? R.id.rbTrigPerson
                : "person_vehicle".equals(t) ? R.id.rbTrigPersonVehicle
                : R.id.rbTrigAny);
        etWake.setText(String.valueOf(Prefs.wakeSeconds(this)));
        etPoll.setText(String.valueOf(Prefs.pollMs(this)));
        etSiren.setText(String.valueOf(Prefs.sirenSeconds(this)));
        cbFront.setChecked(Prefs.bringToFront(this));
        cbBoot.setChecked(Prefs.startOnBoot(this));
        cbOpenBoot.setChecked(Prefs.openOnBoot(this));
    }

    private void save() {
        String host = text(etHost);
        if (host.isEmpty()) {
            Toast.makeText(this, "Bitte IP-Adresse der Kamera eingeben", Toast.LENGTH_LONG).show();
            return;
        }
        String trig;
        int id = rgTrigger.getCheckedRadioButtonId();
        if (id == R.id.rbTrigOff) trig = "off";
        else if (id == R.id.rbTrigPerson) trig = "person";
        else if (id == R.id.rbTrigPersonVehicle) trig = "person_vehicle";
        else trig = "any";

        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putString("host", host);
        e.putString("user", text(etUser));
        e.putString("pass", etPass.getText().toString());
        e.putBoolean("https", cbHttps.isChecked());
        e.putInt("httpPort", num(etHttpPort, 0));
        e.putInt("rtspPort", num(etRtspPort, 554));
        e.putString("rtspPath", text(etRtspPath));
        e.putString("liveStream", rgLive.getCheckedRadioButtonId() == R.id.rbLiveMain ? "main" : "sub");
        e.putString("recStream", rgRec.getCheckedRadioButtonId() == R.id.rbRecMain ? "main" : "sub");
        e.putString("trigger", trig);
        e.putInt("wakeSeconds", Math.max(5, num(etWake, 60)));
        e.putInt("pollMs", Math.max(300, num(etPoll, 1000)));
        e.putInt("sirenSeconds", Math.min(300, Math.max(5, num(etSiren, 30))));
        e.putBoolean("bringToFront", cbFront.isChecked());
        e.putBoolean("startOnBoot", cbBoot.isChecked());
        e.putBoolean("openOnBoot", cbOpenBoot.isChecked());
        e.apply();

        MotionService.reload(this);
        Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void test() {
        final ReolinkApi api = new ReolinkApi(text(etHost), text(etUser), etPass.getText().toString(),
                cbHttps.isChecked(), num(etHttpPort, 0), num(etRtspPort, 554), text(etRtspPath));
        tvTest.setTextColor(getColor(R.color.textDim));
        tvTest.setText("Teste Verbindung…");
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok;
                try {
                    api.login();
                    msg = "✓ Verbunden: " + api.deviceName();
                    ok = true;
                } catch (Exception ex) {
                    msg = "⚠ Fehlgeschlagen: " + ex.getMessage()
                            + "\nTipp: HTTPS/HTTP-Einstellung und Port prüfen.";
                    ok = false;
                }
                final String m = msg;
                final boolean good = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvTest.setTextColor(getColor(good ? R.color.accent : R.color.warn));
                        tvTest.setText(m);
                    }
                });
            }
        }).start();
    }

    private void batteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Bereits ausgenommen ✓", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException ex) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "Auf diesem Gerät nicht verfügbar", Toast.LENGTH_SHORT).show();
            }
        }
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
