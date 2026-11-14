package app.clearscreen;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** User-visible foreground support for ad skipping and optional accessibility recovery. */
public final class SkipKeepAliveService extends Service {
    private static final String TAG = "SkipKeepAlive";
    static final String CHANNEL = "skip-keep-alive";
    private static final String START = "app.clearscreen.SKIP_KEEP_ALIVE_START";
    private static final String START_RECOVERY = "app.clearscreen.START_ACCESSIBILITY_RECOVERY";
    private static final String STOP = "app.clearscreen.SKIP_KEEP_ALIVE_STOP";
    private static final int NOTIFICATION_ID = 40;
    private static final long LEGACY_NOTIFICATION_CHECK_MS = 30_000;
    private static final long RECOVERY_CHECK_MS = 30_000;
    private static volatile SkipKeepAliveService active;
    private static boolean recoveryFailed;

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private boolean recovering;
    private boolean awaitingConnection;
    private final Runnable recoveryCheck = this::checkRecovery;
    private final Runnable legacyNotificationCheck = new Runnable() {
        @Override public void run() {
            if (!running()) return;
            if (notificationsAllowed(SkipKeepAliveService.this)) {
                notificationHandler.postDelayed(this, LEGACY_NOTIFICATION_CHECK_MS);
            } else stopRunning();
        }
    };
    private final BroadcastReceiver notificationChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!notificationsAllowed(context)) stopRunning();
        }
    };

    public static boolean running() {
        return active != null;
    }

    static boolean recoveryRunning() {
        return active != null && active.recovering;
    }

    static boolean recoveryFailed() {
        return recoveryFailed;
    }

    static boolean startRecovery(Context context) {
        if (!canRecover(context) || !AccessibilityRecovery.connected(context)) return false;
        recoveryFailed = false;
        try {
            if (active != null) active.enableRecovery();
            else ForegroundServices.start(context,
                    new Intent(context, SkipKeepAliveService.class).setAction(START_RECOVERY));
            return true;
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System declined accessibility recovery start", failure);
            recoveryFailed = true;
            return false;
        }
    }

    static void stopRecovery() {
        if (recoveryRunning()) active.endRecovery(false);
    }

    /** Call on the main thread after a user setting or accessibility connection changes. */
    public static void sync(Context context) {
        Intent service = new Intent(context, SkipKeepAliveService.class);
        try {
            if (active != null) {
                if (active.recovering && canRecover(context)) {
                    if (active.awaitingConnection && AccessibilityRecovery.connected(context)) {
                        active.awaitingConnection = false;
                    }
                    return;
                }
                if (!eligible(context)) active.stopRunning();
                else if (active.recovering) active.endRecovery(false);
            } else if (eligible(context)) ForegroundServices.start(context, service.setAction(START));
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "System declined foreground service change", e);
        }
    }

    private static boolean eligible(Context context) {
        return CleanerService.get() != null && new Store(context).skipKeepAliveEnabled()
                && canKeepAlive(context);
    }

    private static boolean canKeepAlive(Context context) {
        Store store = new Store(context);
        return (store.splashEnabled() || store.popupEnabled())
                && notificationsAllowed(context);
    }

    private static boolean canRecover(Context context) {
        return AccessibilityRecovery.supported() && AccessibilityRecovery.hasPermission(context)
                && canKeepAlive(context);
    }

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 28) {
            IntentFilter filter = new IntentFilter(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED);
            filter.addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(notificationChanges, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(notificationChanges, filter);
            }
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (STOP.equals(action)) new Store(this).setSkipKeepAliveEnabled(false);
        boolean startRecovery = START_RECOVERY.equals(action);
        if (!startRecovery && !START.equals(action)) {
            stopRunning();
            return START_NOT_STICKY;
        }

        try {
            // A queued foreground start must be acknowledged even if settings changed in the meantime.
            boolean starting = active != this;
            if (startRecovery && canRecover(this) && AccessibilityRecovery.connected(this)) {
                enableRecovery();
            } else {
                if (starting) showNotification();
                if (startRecovery) recoveryFailed = true;
            }
            if (recovering || eligible(this)) {
                active = this;
                if (starting && Build.VERSION.SDK_INT < 28) {
                    notificationHandler.removeCallbacks(legacyNotificationCheck);
                    notificationHandler.postDelayed(legacyNotificationCheck, LEGACY_NOTIFICATION_CHECK_MS);
                }
            } else stopRunning();
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "System declined foreground service start", e);
            if (startRecovery) recoveryFailed = true;
            stopRunning();
        }
        return START_NOT_STICKY;
    }

    private void enableRecovery() {
        if (recovering) return;
        recovering = true;
        try {
            showNotification();
        } catch (IllegalStateException | SecurityException failure) {
            recovering = false;
            throw failure;
        }
        new Store(this).setSkipKeepAliveEnabled(true);
        notificationHandler.postDelayed(recoveryCheck, RECOVERY_CHECK_MS);
    }

    private void checkRecovery() {
        if (active != this || !recovering) return;
        if (!canRecover(this)) {
            endRecovery(false);
            return;
        }
        try {
            boolean configured = AccessibilityRecovery.configured(this);
            boolean connected = configured && CleanerService.get() != null;
            if (awaitingConnection && !connected) {
                endRecovery(true);
                return;
            }
            awaitingConnection = !configured && AccessibilityRecovery.restoreIfOff(this);
            notificationHandler.postDelayed(recoveryCheck, RECOVERY_CHECK_MS);
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System did not confirm accessibility recovery", failure);
            endRecovery(true);
        }
    }

    private void endRecovery(boolean failed) {
        recoveryFailed = failed;
        recovering = false;
        awaitingConnection = false;
        notificationHandler.removeCallbacks(recoveryCheck);
        if (eligible(this)) {
            try {
                showNotification();
                return;
            } catch (IllegalStateException | SecurityException failure) {
                Log.w(TAG, "System declined foreground notification update", failure);
            }
        }
        stopRunning();
    }

    private void showNotification() {
        Intent manage = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("tab", recovering ? MainActivity.ACCESSIBILITY_RECOVERY : MainActivity.SKIP_MANAGEMENT);
        PendingIntent open = PendingIntent.getActivity(this, 40, manage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 41,
                new Intent(this, SkipKeepAliveService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = ForegroundServices.lowPriorityNotification(this, CHANNEL, "跳过保活")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle(recovering ? "净屏 · 自动恢复无障碍" : "净屏 · 跳过保活")
                .setContentText(recovering ? "正在检查净屏的无障碍服务" : "点击管理开屏与弹窗跳过")
                .setContentIntent(open)
                .setDeleteIntent(recovering ? stop : null)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止保活", stop).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopRunning() {
        if (active == this) active = null;
        recovering = false;
        awaitingConnection = false;
        notificationHandler.removeCallbacks(recoveryCheck);
        notificationHandler.removeCallbacks(legacyNotificationCheck);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        notificationHandler.removeCallbacks(recoveryCheck);
        notificationHandler.removeCallbacks(legacyNotificationCheck);
        if (Build.VERSION.SDK_INT >= 28) unregisterReceiver(notificationChanges);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
