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
import android.os.IBinder;
import android.util.Log;

/** Optional, user-visible foreground service for the connected accessibility service. */
public final class SkipKeepAliveService extends Service {
    private static final String TAG = "SkipKeepAlive";
    private static final String CHANNEL = "skip-keep-alive";
    private static final String START = "app.clearscreen.SKIP_KEEP_ALIVE_START";
    private static final String STOP = "app.clearscreen.SKIP_KEEP_ALIVE_STOP";
    private static final int NOTIFICATION_ID = 40;
    private static volatile boolean foreground;

    private final BroadcastReceiver notificationChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!notificationsAllowed(context)) stopRunning();
        }
    };

    public static boolean running() {
        return foreground;
    }

    /** Call on the main thread after a user setting or accessibility connection changes. */
    public static void sync(Context context) {
        Intent service = new Intent(context, SkipKeepAliveService.class);
        try {
            if (eligible(context)) {
                if (!running()) context.startForegroundService(service.setAction(START));
            } else if (running()) {
                context.stopService(service);
                foreground = false;
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "System declined foreground service change", e);
        }
    }

    private static boolean eligible(Context context) {
        if (CleanerService.get() == null) return false;
        Store store = new Store(context);
        return store.skipKeepAliveEnabled() && (store.splashEnabled() || store.popupEnabled())
                && notificationsAllowed(context);
    }

    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled()) return false;
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
        if (!START.equals(action)) {
            stopRunning();
            return START_NOT_STICKY;
        }

        try {
            // A queued foreground start must be acknowledged even if settings changed in the meantime.
            if (!running()) showNotification();
            if (eligible(this)) foreground = true;
            else stopRunning();
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "System declined foreground service start", e);
            stopRunning();
        }
        return START_NOT_STICKY;
    }

    private void showNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "跳过保活",
                NotificationManager.IMPORTANCE_LOW));
        Intent manage = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("tab", MainActivity.SKIP_MANAGEMENT);
        PendingIntent open = PendingIntent.getActivity(this, 40, manage,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 41,
                new Intent(this, SkipKeepAliveService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("净屏 · 跳过保活")
                .setContentText("点击管理开屏与弹窗跳过")
                .setContentIntent(open)
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
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        foreground = false;
        if (Build.VERSION.SDK_INT >= 28) unregisterReceiver(notificationChanges);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
