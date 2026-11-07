package at.reolinkpanel.viewer;

/** Welche Bildschirme der App gerade sichtbar sind. */
final class AppState {
    private AppState() {}

    /** Anzahl sichtbarer Activities dieser App. */
    static volatile int visible = 0;
    /** true, wenn das Livebild gerade sichtbar ist. */
    static volatile boolean liveVisible = false;
}
