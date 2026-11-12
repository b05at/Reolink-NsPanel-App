package at.reolinkpanel.viewer;

import android.app.admin.DeviceAdminReceiver;

/**
 * Geräteadministrator – wird nur benötigt, damit die App das Display nach dem
 * eingestellten Timeout selbst ausschalten darf (Recht "force-lock").
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
