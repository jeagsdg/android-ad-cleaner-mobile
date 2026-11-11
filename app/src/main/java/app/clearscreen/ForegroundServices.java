package app.clearscreen;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class ForegroundServices {
    private ForegroundServices() {}

    static void start(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static Notification.Builder lowPriorityNotification(
            Context context, String channelId, CharSequence channelName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(
                    channelId, channelName, NotificationManager.IMPORTANCE_LOW));
            return new Notification.Builder(context, channelId);
        }
        return new Notification.Builder(context).setPriority(Notification.PRIORITY_LOW);
    }
}
