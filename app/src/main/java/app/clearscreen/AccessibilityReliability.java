package app.clearscreen;

import android.content.Context;
import android.content.SharedPreferences;

/** Device-wide observations. Stores a few timestamps and event types, without app or screen data. */
final class AccessibilityReliability {
    enum Kind {
        UNKNOWN, CONNECTED, DISCONNECTED, RECOVERY_WAITING, RECOVERED,
        RECOVERY_RESUMED, RECOVERY_STOPPED, RECOVERY_FAILED
    }

    static final class Event {
        final Kind kind;
        final long timeMillis;

        private Event(Kind kind, long timeMillis) {
            this.kind = kind;
            this.timeMillis = timeMillis;
        }
    }

    private static final Event UNKNOWN = new Event(Kind.UNKNOWN, 0);

    private AccessibilityReliability() {}

    static Event recentEvent(Context context) {
        return read(context, "last");
    }

    static Event recentRecovery(Context context) {
        return read(context, "recovery");
    }

    static long lastDisconnectionMillis(Context context) {
        return prefs(context).getLong("disconnected-time", 0);
    }

    static long observedDisconnects(Context context) {
        return prefs(context).getLong("disconnects-observed", 0);
    }

    static long lastRecoveryWaitMillis(Context context) {
        return prefs(context).getLong("last-recovery-wait", -1);
    }

    static String lastWatchResumeTrigger(Context context) {
        return prefs(context).getString("last-watch-resume-trigger", "");
    }

    static void resumed(Context context, SkipKeepAliveService.WatchStart trigger) {
        prefs(context).edit().putString("last-watch-resume-trigger", trigger.name()).apply();
        record(context, Kind.RECOVERY_RESUMED);
    }

    static void recovered(Context context, long observedWaitMillis) {
        record(context, Kind.RECOVERED, observedWaitMillis);
    }

    static void record(Context context, Kind kind) {
        record(context, kind, -1);
    }

    private static void record(Context context, Kind kind, long observedWaitMillis) {
        if (kind == Kind.UNKNOWN) return;
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString("last-kind", kind.name()).putLong("last-time", now);
        switch (kind) {
            case DISCONNECTED:
                editor.putLong("disconnected-time", now)
                        .putLong("disconnects-observed", observedDisconnects(context) + 1);
                break;
            case RECOVERY_WAITING:
            case RECOVERED:
            case RECOVERY_RESUMED:
            case RECOVERY_STOPPED:
            case RECOVERY_FAILED:
                editor.putString("recovery-kind", kind.name()).putLong("recovery-time", now);
                if (kind == Kind.RECOVERED) editor.putLong("last-recovery-wait", observedWaitMillis);
                break;
        }
        editor.apply();
    }

    private static Event read(Context context, String key) {
        SharedPreferences prefs = prefs(context);
        long time = prefs.getLong(key + "-time", 0);
        if (time <= 0) return UNKNOWN;
        try {
            Kind kind = Kind.valueOf(prefs.getString(key + "-kind", Kind.UNKNOWN.name()));
            return kind == Kind.UNKNOWN ? UNKNOWN : new Event(kind, time);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("accessibility-reliability", Context.MODE_PRIVATE);
    }
}
