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
import android.provider.Settings;
import android.util.Log;

import app.clearscreen.AccessibilityRecovery.Resume;
import app.clearscreen.AccessibilityReliability.Kind;

/** User-visible foreground support for ad skipping and optional accessibility recovery. */
public final class SkipKeepAliveService extends Service {
    private static final String TAG = "SkipKeepAlive";
    static final String CHANNEL = "skip-keep-alive";
    private static final String START = "app.clearscreen.SKIP_KEEP_ALIVE_START";
    private static final String START_RECOVERY = "app.clearscreen.START_ACCESSIBILITY_RECOVERY";
    private static final String RESUME_RECOVERY = "app.clearscreen.RESUME_ACCESSIBILITY_RECOVERY";
    private static final String RECOVERY_REQUEST = "recovery-request";
    private static final String STOP = "app.clearscreen.SKIP_KEEP_ALIVE_STOP";
    private static final int NOTIFICATION_ID = 40;
    static final int ACCESSIBILITY_OFF_NOTICE_ID = 42;
    private static final long LEGACY_NOTIFICATION_CHECK_MS = 30_000;
    private static final long RECOVERY_CHECK_MS = 30_000;
    private static final long SYSTEM_SETTLE_MS = 1_500;
    private static volatile SkipKeepAliveService active;
    private static boolean recoveryFailed;
    private static long nextRecoveryRequest;
    private static long pendingRecoveryRequest;
    enum RecoveryConnection { CONNECTED, WAITING_FOR_SYSTEM, RESTORED }

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private boolean recovering;
    private boolean observingRecovery;
    private boolean eventCheckQueued;
    private RecoveryConnection recoveryConnection = RecoveryConnection.CONNECTED;
    private final Runnable recoveryCheck = this::checkRecovery;
    private final Runnable eventCheck = () -> {
        eventCheckQueued = false;
        checkRecovery();
    };
    private final ContentObserver accessibilityChanges = new ContentObserver(notificationHandler) {
        @Override public void onChange(boolean selfChange) {
            checkRecovery();
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
        if (!canRecover(context)) return false;
        recoveryFailed = false;
        try {
            if (!AccessibilityRecovery.configured(context)) return false;
            long request = ++nextRecoveryRequest;
            pendingRecoveryRequest = request;
            ForegroundServices.start(context,
                    new Intent(context, SkipKeepAliveService.class).setAction(START_RECOVERY)
                            .putExtra(RECOVERY_REQUEST, request));
            return true;
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System declined accessibility recovery start", failure);
            pendingRecoveryRequest = 0;
            noteRecoveryFailure(context);
            return false;
        }
    }

    static void stopRecovery(Context context) {
        pendingRecoveryRequest = 0;
        cancelSelectionNotice(context);
        if (recoveryRunning()) active.endRecovery();
        else {
            recoveryFailed = false;
            clearRecovery(context);
        }
    }

    /** Call only from the resumed app activity, where Android allows a new foreground start. */
    static void onForeground(Context context) {
        if (active != null || !AccessibilityRecovery.watching(context)) {
            sync(context);
            return;
        }
        Resume disposition = AccessibilityRecovery.resumeDisposition(context);
        if (disposition == Resume.DISARM) {
            disarmKeepAlive(context);
            return;
        }
        if (!canResume(context)) {
            clearRecovery(context);
            sync(context);
            return;
        }
        try {
            if (!AccessibilityRecovery.configured(context)) {
                clearRecovery(context);
                return;
            }
            if (disposition == Resume.ALLOWED) {
                ForegroundServices.start(context,
                        new Intent(context, SkipKeepAliveService.class).setAction(RESUME_RECOVERY));
            }
        } catch (IllegalStateException | SecurityException failure) {
            Log.w(TAG, "System declined accessibility recovery restart", failure);
            noteRecoveryFailure(context);
        }
    }

    static void onAccessibilityConnected(Context context) {
        cancelSelectionNotice(context);
        if (recoveryRunning()) active.checkRecovery();
        else sync(context);
    }

    /** Call on the main thread after a user setting or accessibility connection changes. */
    public static void sync(Context context) {
        Intent service = new Intent(context, SkipKeepAliveService.class);
        try {
            if (active != null) {
                if (active.recovering) {
                    if (canResume(context)) active.scheduleEventCheck();
                    else active.endRecovery();
                    return;
                }
                if (!eligible(context)) active.stopRunning();
            } else if (!AccessibilityRecovery.watching(context) && eligible(context)) {
                ForegroundServices.start(context, service.setAction(START));
            }
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
        return AccessibilityRecovery.supported() && canKeepAlive(context);
    }

    private static boolean canResume(Context context) {
        return AccessibilityRecovery.watching(context)
                && new Store(context).skipKeepAliveEnabled() && canRecover(context);
    }

    private static void noteRecoveryFailure(Context context) {
        recoveryFailed = true;
        AccessibilityReliability.record(context, Kind.RECOVERY_FAILED);
    }

    private static void clearRecovery(Context context) {
        if (AccessibilityRecovery.watching(context)) {
            AccessibilityRecovery.clearSession(context);
            AccessibilityReliability.record(context, Kind.RECOVERY_STOPPED);
        }
    }

    private static void disarmKeepAlive(Context context) {
        pendingRecoveryRequest = 0;
        new Store(context).setSkipKeepAliveEnabled(false);
        clearRecovery(context);
        cancelSelectionNotice(context);
    }

    private static void cancelSelectionNotice(Context context) {
        context.getSystemService(NotificationManager.class).cancel(ACCESSIBILITY_OFF_NOTICE_ID);
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
        if (STOP.equals(action)) {
            disarmKeepAlive(this);
            stopRunning();
            return START_NOT_STICKY;
        }
        boolean explicitStart = START_RECOVERY.equals(action);
        long request = explicitStart ? intent.getLongExtra(RECOVERY_REQUEST, 0) : 0;
        boolean startRecovery = request != 0 && request == pendingRecoveryRequest;
        if (startRecovery) pendingRecoveryRequest = 0;
        boolean systemRestart = intent == null;
        boolean foregroundRestart = RESUME_RECOVERY.equals(action);
        if (!explicitStart && !START.equals(action) && !systemRestart && !foregroundRestart) {
            stopRunning();
            return START_NOT_STICKY;
        }

        try {
            // A queued foreground start must be acknowledged even if settings changed in the meantime.
            boolean starting = active != this;
            if (startRecovery && canRecover(this) && AccessibilityRecovery.configured(this)) {
                enableRecovery(false);
            } else {
                if ((systemRestart || foregroundRestart) && AccessibilityRecovery.watching(this)) {
                    Resume disposition = AccessibilityRecovery.resumeDisposition(this);
                    if (disposition == Resume.DISARM) disarmKeepAlive(this);
                    else if (!canResume(this)) clearRecovery(this);
                    else if (!AccessibilityRecovery.configured(this)) {
                        if (starting) showNotification();
                        stopRunning();
                        return START_NOT_STICKY;
                    } else if (systemRestart || disposition == Resume.ALLOWED) enableRecovery(true);
                }
                if (starting && !recovering) showNotification();
                if (startRecovery) noteRecoveryFailure(this);
            }
            if (recovering || (!AccessibilityRecovery.watching(this) && eligible(this))) {
                active = this;
                if (starting && Build.VERSION.SDK_INT < 28) {
                    notificationHandler.removeCallbacks(legacyNotificationCheck);
                    notificationHandler.postDelayed(legacyNotificationCheck, LEGACY_NOTIFICATION_CHECK_MS);
                }
            } else stopService();
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "System declined foreground service start", e);
            if (startRecovery || systemRestart || foregroundRestart) noteRecoveryFailure(this);
            stopService();
        }
        return recovering ? START_STICKY : START_NOT_STICKY;
    }

    private void enableRecovery(boolean resuming) {
        if (recovering) return;
        recovering = true;
        showNotification();
        IntentFilter wake = new IntentFilter(Intent.ACTION_SCREEN_ON);
        wake.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenWake, wake, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenWake, wake);
        observingRecovery = true;
        getContentResolver().registerContentObserver(AccessibilityRecovery.settingUri(), false, accessibilityChanges);
        AccessibilityRecovery.takeOwnership(this);
        if (!resuming) new Store(this).setSkipKeepAliveEnabled(true);
        recoveryFailed = false;
        if (resuming) AccessibilityReliability.record(this, Kind.RECOVERY_RESUMED);
        scheduleEventCheck();
        notificationHandler.postDelayed(recoveryCheck, RECOVERY_CHECK_MS);
    }

    private void scheduleEventCheck() {
        if (!recovering || eventCheckQueued) return;
        eventCheckQueued = true;
        notificationHandler.postDelayed(eventCheck, SYSTEM_SETTLE_MS);
    }

    private void checkRecovery() {
        if (active != this || !recovering) return;
        if (!canResume(this)) {
            endRecovery();
            return;
        }
        try {
            if (!AccessibilityRecovery.configured(this)) {
                // A missing selection may be a deliberate revocation. Wait for a new opt-in.
                stopRunning();
                showSelectionNotice();
                return;
            }
            if (CleanerService.get() == null) {
                if (recoveryConnection != RecoveryConnection.WAITING_FOR_SYSTEM) {
                    AccessibilityReliability.record(this, Kind.RECOVERY_WAITING);
                    updateRecoveryConnection(RecoveryConnection.WAITING_FOR_SYSTEM);
                }
            } else if (recoveryConnection == RecoveryConnection.WAITING_FOR_SYSTEM) {
                AccessibilityReliability.record(this, Kind.RECOVERED);
                updateRecoveryConnection(RecoveryConnection.RESTORED);
            }
            recoveryFailed = false;
        } catch (IllegalStateException | SecurityException failure) {
            if (!recoveryFailed) {
                Log.w(TAG, "Could not check accessibility connection", failure);
                noteRecoveryFailure(this);
            }
        }
        notificationHandler.removeCallbacks(recoveryCheck);
        notificationHandler.postDelayed(recoveryCheck, RECOVERY_CHECK_MS);
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

    private void endRecovery() {
        recoveryFailed = false;
        clearRecovery(this);
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
                .setContentTitle(recovering ? "净屏 · 无障碍连接守护" : "净屏 · 跳过保活")
                .setContentText(!recovering ? "点击管理开屏与弹窗跳过"
                        : recoveryConnection == RecoveryConnection.WAITING_FOR_SYSTEM ? "无障碍已开启，等待系统连接"
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

    private void showSelectionNotice() {
        if (!notificationsAllowed(this)) return;
        Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        PendingIntent open = PendingIntent.getActivity(this, ACCESSIBILITY_OFF_NOTICE_ID, settings,
                PendingIntent.FLAG_IMMUTABLE);
        Notification notice = ForegroundServices.lowPriorityNotification(this, CHANNEL, "跳过保活")
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("净屏的无障碍开关已关闭")
                .setContentText("开屏或弹窗处理已暂停。点此查看系统设置。")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            getSystemService(NotificationManager.class).notify(ACCESSIBILITY_OFF_NOTICE_ID, notice);
        } catch (SecurityException failure) {
            Log.w(TAG, "System declined accessibility switch notice", failure);
        }
    }

    private void stopRunning() {
        clearRecovery(this);
        stopService();
    }

    private void stopService() {
        if (active == this) active = null;
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
