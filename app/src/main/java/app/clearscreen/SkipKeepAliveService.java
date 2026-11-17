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
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import app.clearscreen.AccessibilityRecovery.RestoreResult;
import app.clearscreen.AccessibilityRecovery.Session;

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
    private static final long SYSTEM_SETTLE_MS = 1_500;
    private static volatile SkipKeepAliveService active;
    private static boolean recoveryFailed;
    enum RecoveryConnection { CONNECTED, WAITING_FOR_SYSTEM, RESTORING, WAITING_TO_RETRY, RESTORED }

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private boolean recovering;
    private boolean observingRecovery;
    private boolean eventCheckQueued;
    private long generationBeforeRestore = -1;
    private RecoveryConnection recoveryConnection = RecoveryConnection.CONNECTED;
    private final Runnable recoveryCheck = this::checkRecovery;
    private final Runnable eventCheck = () -> {
        eventCheckQueued = false;
        checkRecovery();
    };
    private final ContentObserver accessibilityChanges = new ContentObserver(notificationHandler) {
        @Override public void onChange(boolean selfChange) {
            scheduleEventCheck();
        }
    };
    private final BroadcastReceiver screenWake = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            scheduleEventCheck();
        }
    };
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

    static RecoveryConnection recoveryConnection() {
        SkipKeepAliveService service = active;
        return service == null ? RecoveryConnection.CONNECTED : service.recoveryConnection;
    }

    static boolean startRecovery(Context context) {
        if (!canRecover(context) || !AccessibilityRecovery.connected(context)) return false;
        recoveryFailed = false;
        try {
            // Deliver an explicit start even if ordinary keepalive is already running.
            ForegroundServices.start(context,
                    new Intent(context, SkipKeepAliveService.class).setAction(START_RECOVERY));
            return true;
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System declined accessibility recovery start", failure);
            recoveryFailed = true;
            return false;
        }
    }

    static void stopRecovery(Context context) {
        if (recoveryRunning()) active.endRecovery(false);
        else AccessibilityRecovery.clearSession(context);
    }

    static void onAccessibilityConnected(Context context) {
        if (recoveryRunning() && AccessibilityRecovery.connected(context)) active.checkRecovery();
        else sync(context);
    }

    /** Call on the main thread after a user setting or accessibility connection changes. */
    public static void sync(Context context) {
        Intent service = new Intent(context, SkipKeepAliveService.class);
        try {
            if (active != null) {
                if (active.recovering) {
                    if (canRecover(context)) active.scheduleEventCheck();
                    else active.endRecovery(false);
                    return;
                }
                if (!eligible(context)) active.stopRunning();
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
        boolean systemRestart = intent == null;
        if (!startRecovery && !START.equals(action) && !systemRestart) {
            stopRunning();
            return START_NOT_STICKY;
        }

        try {
            // A queued foreground start must be acknowledged even if settings changed in the meantime.
            boolean starting = active != this;
            if (startRecovery && canRecover(this) && AccessibilityRecovery.connected(this)) {
                enableRecovery(false);
            } else if (systemRestart && canRecover(this) && new Store(this).skipKeepAliveEnabled()
                    && AccessibilityRecovery.session(this) != Session.STOPPED) {
                enableRecovery(true);
            } else {
                if (systemRestart) AccessibilityRecovery.clearSession(this);
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
            if (startRecovery || systemRestart) recoveryFailed = true;
            stopRunning();
        }
        return recovering ? START_STICKY : START_NOT_STICKY;
    }

    private void enableRecovery(boolean systemRestart) {
        if (recovering) return;
        recovering = true;
        showNotification();
        if (!systemRestart) {
            new Store(this).setSkipKeepAliveEnabled(true);
            AccessibilityRecovery.saveSession(this, Session.WATCHING);
        }
        IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenWake, wake, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenWake, wake);
        observingRecovery = true;
        getContentResolver().registerContentObserver(AccessibilityRecovery.settingUri(), false, accessibilityChanges);
        if (systemRestart) scheduleEventCheck();
        notificationHandler.postDelayed(recoveryCheck, RECOVERY_CHECK_MS);
    }

    private void scheduleEventCheck() {
        if (!recovering || eventCheckQueued) return;
        eventCheckQueued = true;
        notificationHandler.postDelayed(eventCheck, SYSTEM_SETTLE_MS);
    }

    private void checkRecovery() {
        if (active != this || !recovering) return;
        if (!canRecover(this)) {
            endRecovery(false);
            return;
        }
        try {
            boolean configured = AccessibilityRecovery.configured(this);
            boolean attempted = AccessibilityRecovery.session(this) == Session.RESTORE_ATTEMPTED;
            long delay = RECOVERY_CHECK_MS;
            long retryIn = attempted ? RECOVERY_CHECK_MS - AccessibilityRecovery.timeSinceAttempt(this) : 0;
            if (configured && CleanerService.get() != null
                    && (!attempted || generationBeforeRestore != CleanerService.connectionGeneration())) {
                if (attempted) AccessibilityRecovery.saveSession(this, Session.WATCHING);
                if (attempted || recoveryConnection == RecoveryConnection.WAITING_FOR_SYSTEM) {
                    updateRecoveryConnection(RecoveryConnection.RESTORED);
                }
            } else if (configured) {
                // The service is enabled in Settings. Keep watching, and let Android manage binding.
                if (retryIn <= 0 || recoveryConnection != RecoveryConnection.RESTORING) {
                    updateRecoveryConnection(RecoveryConnection.WAITING_FOR_SYSTEM);
                } else delay = retryIn;
            } else if (retryIn > 0) {
                updateRecoveryConnection(RecoveryConnection.WAITING_TO_RETRY);
                delay = retryIn;
            } else {
                generationBeforeRestore = CleanerService.connectionGeneration();
                RestoreResult result = AccessibilityRecovery.restoreIfOff(this);
                switch (result) {
                    case REQUESTED: updateRecoveryConnection(RecoveryConnection.RESTORING); break;
                    case ALREADY_ENABLED: updateRecoveryConnection(RecoveryConnection.WAITING_FOR_SYSTEM); break;
                    case NOT_CONFIRMED: updateRecoveryConnection(RecoveryConnection.WAITING_TO_RETRY); break;
                }
            }
            notificationHandler.removeCallbacks(recoveryCheck);
            notificationHandler.postDelayed(recoveryCheck, delay);
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System did not confirm accessibility recovery", failure);
            endRecovery(true);
        }
    }

    private void updateRecoveryConnection(RecoveryConnection connection) {
        if (connection == recoveryConnection) return;
        recoveryConnection = connection;
        showNotification();
    }

    private void stopWatching() {
        recovering = false;
        recoveryConnection = RecoveryConnection.CONNECTED;
        notificationHandler.removeCallbacks(recoveryCheck);
        notificationHandler.removeCallbacks(eventCheck);
        eventCheckQueued = false;
        if (observingRecovery) {
            unregisterReceiver(screenWake);
            getContentResolver().unregisterContentObserver(accessibilityChanges);
            observingRecovery = false;
        }
    }

    private void endRecovery(boolean failed) {
        recoveryFailed = failed;
        AccessibilityRecovery.clearSession(this);
        stopWatching();
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
                .setContentText(!recovering ? "点击管理开屏与弹窗跳过"
                        : recoveryConnection == RecoveryConnection.WAITING_FOR_SYSTEM ? "无障碍开关已开启，服务未连接"
                        : recoveryConnection == RecoveryConnection.WAITING_TO_RETRY ? "开关未保持开启，等待重试"
                        : "正在检查净屏的无障碍服务")
                .setContentIntent(open)
                .setDeleteIntent(recovering ? stop : null)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
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
        AccessibilityRecovery.clearSession(this);
        stopWatching();
        notificationHandler.removeCallbacks(legacyNotificationCheck);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        stopWatching();
        notificationHandler.removeCallbacks(legacyNotificationCheck);
        if (Build.VERSION.SDK_INT >= 28) unregisterReceiver(notificationChanges);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
