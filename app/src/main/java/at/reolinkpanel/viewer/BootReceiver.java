package at.reolinkpanel.viewer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Startet die Überwachung (und optional die App) nach einem Neustart des Panels. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.isConfigured(c)) return;
        if (Prefs.startOnBoot(c) || Prefs.openOnBoot(c)) MotionService.start(c);
        if (Prefs.openOnBoot(c)) {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}
