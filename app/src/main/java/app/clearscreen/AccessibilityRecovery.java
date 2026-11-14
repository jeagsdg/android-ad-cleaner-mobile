package app.clearscreen;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** Restores the app's previously enabled service after an explicit opt-in and ADB grant. */
final class AccessibilityRecovery {
    static final String PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;

    private AccessibilityRecovery() {}

    static boolean supported() {
        return "xiaomi".equals(PermissionAccess.family());
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean configured(Context context) {
        return PermissionAccess.containsService(Settings.Secure.getString(context.getContentResolver(),
                ENABLED_SERVICES), PermissionAccess.component(context));
    }

    static boolean connected(Context context) {
        try {
            return CleanerService.get() != null && configured(context);
        } catch (SecurityException failure) {
            return false;
        }
    }

    static boolean restoreIfOff(Context context) {
        ContentResolver resolver = context.getContentResolver();
        ComponentName own = PermissionAccess.component(context);
        String current = Settings.Secure.getString(resolver, ENABLED_SERVICES);
        if (PermissionAccess.containsService(current, own)) return false;
        String component = own.flattenToShortString();
        String updated = current == null || current.isEmpty() ? component : current + ":" + component;
        // Android updates the global accessibility flag when it processes the service list.
        if (!Settings.Secure.putString(resolver, ENABLED_SERVICES, updated) || !configured(context)) {
            throw new IllegalStateException("Android did not confirm the accessibility setting");
        }
        return true;
    }
}
