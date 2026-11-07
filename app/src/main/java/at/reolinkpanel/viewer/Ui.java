package at.reolinkpanel.viewer;

import android.app.Activity;
import android.view.View;
import android.view.WindowManager;

/** Gemeinsame Helfer für Vollbild auf dem Panel. */
final class Ui {
    private Ui() {}

    static void fullscreen(Activity a) {
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideBars(a);
    }

    static void hideBars(Activity a) {
        a.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
