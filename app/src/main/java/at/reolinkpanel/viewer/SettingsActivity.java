package at.reolinkpanel.viewer;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

/** Einrichtung: Kameraliste, Bewegungserkennung, Verhalten. */
public class SettingsActivity extends Activity {

    private EditText etWake, etPoll, etSiren, etPtzSpeed, etGridSeconds;
    private CheckBox cbFront, cbBoot, cbOpenBoot, cbThumbs, cbChime, cbGridLive;
    private RadioGroup rgLive, rgRec, rgTrigger;
    private LinearLayout camList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        camList = findViewById(R.id.camList);
        etGridSeconds = findViewById(R.id.etGridSeconds);
        cbChime = findViewById(R.id.cbChime);
        cbGridLive = findViewById(R.id.cbGridLive);
        etWake = findViewById(R.id.etWake);
        etPoll = findViewById(R.id.etPoll);
        etSiren = findViewById(R.id.etSiren);
        etPtzSpeed = findViewById(R.id.etPtzSpeed);
        cbFront = findViewById(R.id.cbFront);
        cbBoot = findViewById(R.id.cbBoot);
        cbOpenBoot = findViewById(R.id.cbOpenBoot);
        cbThumbs = findViewById(R.id.cbThumbs);
        rgLive = findViewById(R.id.rgLive);
        rgRec = findViewById(R.id.rgRec);
        rgTrigger = findViewById(R.id.rgTrigger);

        fill();

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        findViewById(R.id.btnAddCam).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, CameraEditActivity.class));
            }
        });
        findViewById(R.id.btnBackSettings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.btnBattery).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { batteryOptimization(); }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppState.visible++;
        fillCameras();
    }

    /** Liste der Kameras – Tippen öffnet die Bearbeitung. */
    private void fillCameras() {
        camList.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        java.util.List<Cam> cams = Prefs.cameras(this);
        for (final Cam c : cams) {
            View row = inf.inflate(R.layout.item_camera, camList, false);
            ((TextView) row.findViewById(R.id.tvCamName)).setText(c.displayName());
            ((TextView) row.findViewById(R.id.tvCamInfo)).setText(
                    (c.doorbell ? "Türklingel · " : "") + c.host + (c.wake ? "" : " · weckt nicht"));
            ((android.widget.ImageView) row.findViewById(R.id.ivCamIcon)).setImageResource(
                    c.doorbell ? R.drawable.ic_doorbell : R.drawable.ic_videocam);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(SettingsActivity.this, CameraEditActivity.class)
                            .putExtra(CameraEditActivity.EXTRA_ID, c.id));
                }
            });
            camList.addView(row);
        }
        if (cams.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Noch keine Kamera eingerichtet.");
            empty.setTextColor(getColor(R.color.textDim));
            empty.setPadding(0, 8, 0, 8);
            camList.addView(empty, new ViewGroup.LayoutParams(-1, -2));
        }
    }
    @Override protected void onStop() { AppState.visible--; super.onStop(); }

    private void fill() {
        cbChime.setChecked(Prefs.chime(this));
        cbGridLive.setChecked(Prefs.gridLive(this));
        etGridSeconds.setText(String.valueOf(Prefs.gridSeconds(this)));
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
        etPtzSpeed.setText(String.valueOf(Prefs.ptzSpeed(this)));
        cbFront.setChecked(Prefs.bringToFront(this));
        cbBoot.setChecked(Prefs.startOnBoot(this));
        cbOpenBoot.setChecked(Prefs.openOnBoot(this));
        cbThumbs.setChecked(Prefs.thumbnails(this));
    }

    private void save() {
        String trig;
        int id = rgTrigger.getCheckedRadioButtonId();
        if (id == R.id.rbTrigOff) trig = "off";
        else if (id == R.id.rbTrigPerson) trig = "person";
        else if (id == R.id.rbTrigPersonVehicle) trig = "person_vehicle";
        else trig = "any";

        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putBoolean("chime", cbChime.isChecked());
        e.putBoolean("gridLive", cbGridLive.isChecked());
        e.putInt("gridSeconds", Math.min(60, Math.max(1, num(etGridSeconds, 2))));
        e.putString("liveStream", rgLive.getCheckedRadioButtonId() == R.id.rbLiveMain ? "main" : "sub");
        e.putString("recStream", rgRec.getCheckedRadioButtonId() == R.id.rbRecMain ? "main" : "sub");
        e.putString("trigger", trig);
        e.putInt("wakeSeconds", Math.max(5, num(etWake, 60)));
        e.putInt("pollMs", Math.max(300, num(etPoll, 1000)));
        e.putInt("ptzSpeed", Math.min(64, Math.max(1, num(etPtzSpeed, 32))));
        e.putInt("sirenSeconds", Math.min(300, Math.max(5, num(etSiren, 30))));
        e.putBoolean("bringToFront", cbFront.isChecked());
        e.putBoolean("startOnBoot", cbBoot.isChecked());
        e.putBoolean("openOnBoot", cbOpenBoot.isChecked());
        e.putBoolean("thumbnails", cbThumbs.isChecked());
        e.apply();

        MotionService.reload(this);
        Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show();
        finish();
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

    private static int num(EditText e, int def) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
