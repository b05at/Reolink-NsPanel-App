package at.reolinkpanel.viewer;

/** Auswählbare Symbole für Webhook-Knöpfe. */
public final class Icons {
    private Icons() {}

    public static final String[] KEYS = {
            "lock_open", "lock", "door", "garage", "home", "light", "power",
            "bolt", "bell", "water", "star", "siren"
    };
    public static final String[] LABELS = {
            "Schloss offen", "Schloss", "Tür", "Auto/Garage", "Haus", "Licht", "Ein/Aus",
            "Blitz", "Glocke", "Wasser", "Stern", "Lautsprecher"
    };

    public static int res(String key) {
        if (key == null) return R.drawable.ic_power;
        switch (key) {
            case "lock_open": return R.drawable.ic_lock_open;
            case "lock": return R.drawable.ic_lock;
            case "door": return R.drawable.ic_door;
            case "garage": return R.drawable.ic_car;
            case "home": return R.drawable.ic_home;
            case "light": return R.drawable.ic_light;
            case "bolt": return R.drawable.ic_bolt;
            case "bell": return R.drawable.ic_doorbell;
            case "water": return R.drawable.ic_water;
            case "star": return R.drawable.ic_star;
            case "siren": return R.drawable.ic_siren;
            case "power":
            default: return R.drawable.ic_power;
        }
    }
}
