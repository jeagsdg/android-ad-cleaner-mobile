package app.clearscreen;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.util.Log;

/** Tracks an explicitly enabled watch session without modifying accessibility settings. */
final class AccessibilityRecovery {
    private static final String ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    enum Resume { ALLOWED, UNVERIFIED, DISARM }

    private AccessibilityRecovery() {}

    static boolean supported() {
        return "xiaomi".equals(PermissionAccess.family());
    }

    static boolean watching(Context context) {
        String value = sessionPrefs(context).getString("state", "");
        return "WATCHING".equals(value) || "RESTORE_ATTEMPTED".equals(value);
    }

    static void takeOwnership(Context context) {
        SharedPreferences.Editor editor = sessionPrefs(context).edit()
                .putString("state", "WATCHING")
                .remove("last-attempt")
                .putInt("boot-count", bootCount(context))
                .putInt("owner-pid", Process.myPid())
                .putLong("owner-start", Process.getStartElapsedRealtime())
                .putLong("owner-since", System.currentTimeMillis());
        if (!editor.commit()) throw new IllegalStateException("Could not save accessibility recovery session");
    }

    static Resume resumeDisposition(Context context) {
        SharedPreferences prefs = sessionPrefs(context);
        int previousBoot = prefs.getInt("boot-count", -1);
        int currentBoot = bootCount(context);
        if (previousBoot >= 0 && currentBoot >= 0 && previousBoot != currentBoot) return Resume.DISARM;
        int ownerPid = prefs.getInt("owner-pid", -1);
        if (ownerPid == Process.myPid()
                && prefs.getLong("owner-start", -1) == Process.getStartElapsedRealtime()) return Resume.ALLOWED;
        if (previousBoot < 0 || currentBoot < 0 || ownerPid <= 0) {
            return Resume.UNVERIFIED;
        }
        return resumeAfterProcessExit(context, ownerPid, prefs.getLong("owner-since", Long.MAX_VALUE));
    }

    private static Resume resumeAfterProcessExit(Context context, int ownerPid, long ownedSince) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Resume.UNVERIFIED;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return Resume.UNVERIFIED;
        try {
            // Check the latest main process exit, including a newer invocation stopped by the user.
            String app = context.getPackageName();
            long now = System.currentTimeMillis();
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(app, 0, 10)) {
                if (!app.equals(exit.getProcessName())) continue;
                if (exit.getTimestamp() < ownedSince || exit.getTimestamp() > now) return Resume.UNVERIFIED;
                switch (exit.getReason()) {
                    case ApplicationExitInfo.REASON_USER_REQUESTED:
                    case ApplicationExitInfo.REASON_USER_STOPPED:
                    case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                    case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                    case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                        return Resume.DISARM;
                    case ApplicationExitInfo.REASON_LOW_MEMORY:
                    case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    case ApplicationExitInfo.REASON_FREEZER:
                        return exit.getPid() == ownerPid ? Resume.ALLOWED : Resume.UNVERIFIED;
                    default:
                        return Resume.UNVERIFIED;
                }
            }
        } catch (SecurityException failure) {
            return Resume.UNVERIFIED;
        }
        return Resume.UNVERIFIED;
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (SecurityException e) {
            return -1;
        }
    }

    static void clearSession(Context context) {
        if (!sessionPrefs(context).edit().clear().commit()) {
            Log.w("AccessibilityRecovery", "Could not clear accessibility recovery session");
        }
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
}
