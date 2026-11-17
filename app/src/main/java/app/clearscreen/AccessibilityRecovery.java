package app.clearscreen;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;

/** Restores the app's previously enabled service after an explicit opt-in and ADB grant. */
final class AccessibilityRecovery {
    static final String PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS;
    private static final String ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    enum Session { STOPPED, WATCHING, RESTORE_ATTEMPTED }
    enum RestoreResult { ALREADY_ENABLED, REQUESTED, NOT_CONFIRMED }

    private AccessibilityRecovery() {}

    static boolean supported() {
        return "xiaomi".equals(PermissionAccess.family());
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static Session session(Context context) {
        String value = sessionPrefs(context).getString("state", "");
        if (Session.WATCHING.name().equals(value)) return Session.WATCHING;
        if (Session.RESTORE_ATTEMPTED.name().equals(value)) return Session.RESTORE_ATTEMPTED;
        return Session.STOPPED;
    }

    static void saveSession(Context context, Session session) {
        SharedPreferences.Editor editor = sessionPrefs(context).edit().putString("state", session.name());
        if (session == Session.RESTORE_ATTEMPTED) editor.putLong("last-attempt", SystemClock.elapsedRealtime());
        else editor.remove("last-attempt");
        if (!editor.commit()) {
            throw new IllegalStateException("Could not save accessibility recovery session");
        }
    }

    static long timeSinceAttempt(Context context) {
        long now = SystemClock.elapsedRealtime();
        return Math.max(0, now - sessionPrefs(context).getLong("last-attempt", now));
    }

    static void clearSession(Context context) {
        sessionPrefs(context).edit().clear().apply();
    }

    private static SharedPreferences sessionPrefs(Context context) {
        return context.getSharedPreferences("accessibility-recovery-session", Context.MODE_PRIVATE);
    }

    static Uri settingUri() {
        return Settings.Secure.getUriFor(ENABLED_SERVICES);
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

    static RestoreResult restoreIfOff(Context context) {
        ContentResolver resolver = context.getContentResolver();
        ComponentName own = PermissionAccess.component(context);
        String current = Settings.Secure.getString(resolver, ENABLED_SERVICES);
        if (PermissionAccess.containsService(current, own)) return RestoreResult.ALREADY_ENABLED;
        // Record the attempt before writing so the retry delay survives a process restart.
        saveSession(context, Session.RESTORE_ATTEMPTED);
        // Read again after the disk write to preserve settings other services may have changed.
        current = Settings.Secure.getString(resolver, ENABLED_SERVICES);
        if (PermissionAccess.containsService(current, own)) {
            saveSession(context, Session.WATCHING);
            return RestoreResult.ALREADY_ENABLED;
        }
        String component = own.flattenToShortString();
        String updated = current == null || current.isEmpty() ? component : current + ":" + component;
        // Android updates the global accessibility flag when it processes the service list.
        return Settings.Secure.putString(resolver, ENABLED_SERVICES, updated) && configured(context)
                ? RestoreResult.REQUESTED : RestoreResult.NOT_CONFIRMED;
    }
}
