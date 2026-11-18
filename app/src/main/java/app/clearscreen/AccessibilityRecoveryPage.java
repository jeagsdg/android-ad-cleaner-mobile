package app.clearscreen;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

final class AccessibilityRecoveryPage {
    static final int NOTIFICATION_PERMISSION = 46;
    private static final String ASKED_FOR_NOTIFICATIONS = "recovery-notifications-requested";
    private enum Step { ENABLE_SKIPPING, CONNECT, NOTIFICATIONS, READY, RUNNING }
    private final MainActivity activity;
    private Step shown;
    private PermissionAccess.State shownAccess;
    private SkipKeepAliveService.RecoveryConnection shownConnection;
    private boolean showedFailure;

    AccessibilityRecoveryPage(MainActivity activity) {
        this.activity = activity;
    }

    private Step step(PermissionAccess.State state) {
        if (SkipKeepAliveService.recoveryRunning()) return Step.RUNNING;
        if (!activity.store.splashEnabled() && !activity.store.popupEnabled()) return Step.ENABLE_SKIPPING;
        if (state != PermissionAccess.State.READY && state != PermissionAccess.State.DISCONNECTED) return Step.CONNECT;
        if (!SkipKeepAliveService.notificationsAllowed(activity)) return Step.NOTIFICATIONS;
        return Step.READY;
    }

    boolean changed() {
        PermissionAccess.State access = PermissionAccess.state(activity);
        return shownAccess != access || shown != step(access)
                || showedFailure != SkipKeepAliveService.recoveryFailed()
                || shownConnection != SkipKeepAliveService.recoveryConnection();
    }

    void render() {
        shownAccess = PermissionAccess.state(activity);
        shown = step(shownAccess);
        showedFailure = SkipKeepAliveService.recoveryFailed();
        shownConnection = SkipKeepAliveService.recoveryConnection();
        activity.header("", "无障碍连接", "查看系统开关和服务连接。开启守护后，可从通知查看状态。");

        LinearLayout panel = activity.card(activity.body);
        switch (shown) {
            case ENABLE_SKIPPING:
                prompt(panel, "先开启开屏跳过", "开屏跳过或弹窗处理开启后，就可以为它们开启连接守护。",
                        "开启开屏跳过", activity::toggleSplash);
                break;
            case CONNECT:
                prompt(panel, shownAccess == PermissionAccess.State.OFF ? "无障碍开关已关闭" : "前往系统确认开关",
                        "在系统设置中找到「净屏」并开启。回到这里就能看到连接状态。", "打开无障碍设置", activity::openAccessibilitySettings);
                break;
            case NOTIFICATIONS:
                prompt(panel, "允许显示守护通知", "通知会显示服务是否连接，你可以随时从通知停止守护。",
                        canRequestNotifications() ? "允许通知" : "打开通知设置", this::enableNotifications);
                break;
            case READY:
                boolean waiting = shownAccess == PermissionAccess.State.DISCONNECTED;
                prompt(panel, waiting ? "开关已开启，服务未连接" : "服务已连接",
                        waiting ? "可以开启守护，等待系统重新连接。若长时间没有恢复，可前往系统设置检查。"
                                : "开启后，亮屏、解锁和返回净屏时会检查连接。后台运行时，状态会显示在通知中。",
                        "开启连接守护", this::confirmStart);
                if (waiting) activity.linkAction(panel, "打开无障碍设置", activity::openAccessibilitySettings);
                break;
            case RUNNING:
                String title = shownAccess == PermissionAccess.State.DISCONNECTED ? "开关已开启，服务未连接"
                        : shownAccess == PermissionAccess.State.OFF ? "无障碍开关已关闭"
                        : shownAccess == PermissionAccess.State.UNKNOWN ? "暂时无法读取开关"
                        : shownConnection == SkipKeepAliveService.RecoveryConnection.RESTORED ? "已恢复无障碍连接" : "服务已连接";
                prompt(panel, title, shownAccess == PermissionAccess.State.READY
                                ? "连接守护已开启。你可以在这里或通知中停止。"
                                : "连接守护会检查服务状态。若开关已关或等待时间较长，可在系统设置中查看「净屏」。",
                        "停止连接守护", () -> {
                            SkipKeepAliveService.stopRecovery(activity);
                            activity.render();
                        });
                if (shownAccess != PermissionAccess.State.READY)
                    activity.linkAction(panel, "打开无障碍设置", activity::openAccessibilitySettings);
                break;
        }
        if (showedFailure) activity.paragraph(panel, "上次守护已中断，请检查通知和系统设置。");
        troubleshooting();
    }

    private void troubleshooting() {
        activity.linkAction(activity.body, "检查后台限制", activity::backgroundHelp);
        activity.linkAction(activity.body, "查看诊断报告", activity::diagnostics);
    }

    private void prompt(LinearLayout panel, String title, String description, String button, Runnable next) {
        panel.addView(activity.bold(title, 18, MainActivity.INK));
        activity.space(panel, 8);
        activity.paragraph(panel, description);
        activity.action(panel, button, true, next);
    }

    private boolean canRequestNotifications() {
        return PermissionAccess.needsNotificationPermission(activity)
                && (!activity.getPreferences(Context.MODE_PRIVATE).getBoolean(ASKED_FOR_NOTIFICATIONS, false)
                || activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS));
    }

    private void enableNotifications() {
        if (canRequestNotifications()) {
            activity.getPreferences(Context.MODE_PRIVATE).edit().putBoolean(ASKED_FOR_NOTIFICATIONS, true).apply();
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
            return;
        }
        if (openKeepAliveChannel()) return;
        activity.openNotificationSettings();
    }

    private boolean openKeepAliveChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager manager = activity.getSystemService(NotificationManager.class);
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(SkipKeepAliveService.CHANNEL) == null) {
            return false;
        }
        return activity.tryOpenRule(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID, SkipKeepAliveService.CHANNEL));
    }

    private void confirmStart() {
        if (step(PermissionAccess.state(activity)) != Step.READY) {
            activity.render();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("开启无障碍连接守护？")
                .setMessage("净屏将显示常驻通知，在服务断开后持续检查系统重连。系统开关关闭后，需要你在系统设置中重新开启。")
                .setPositiveButton("开启", (dialog, which) -> {
                    if (!SkipKeepAliveService.startRecovery(activity)) activity.toast("暂时无法开启，请检查系统开关和通知");
                    activity.render();
                }).setNegativeButton("取消", null).show();
    }
}
