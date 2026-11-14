package app.clearscreen;

import android.Manifest;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;

final class AccessibilityRecoveryPage {
    static final int NOTIFICATION_PERMISSION = 46;
    private static final String ASKED_FOR_NOTIFICATIONS = "recovery-notifications-requested";
    private static final String PLATFORM_TOOLS = "https://developer.android.com/tools/releases/platform-tools";
    private static final String PHONE_TOOL = "https://f-droid.org/packages/in.hridayan.ashell/";
    private static final String PHONE_GUIDE = "https://github.com/DP-Hridayan/aShellYou/wiki/Wireless-Debugging-Setup#own-device-as-target";

    private enum Step { ENABLE_SKIPPING, CONNECT, NOTIFICATIONS, AUTHORIZE, READY, RUNNING }

    private final MainActivity activity;
    private Step shown;
    private boolean showedFailure;
    private AlertDialog authorizationHelp;

    AccessibilityRecoveryPage(MainActivity activity) {
        this.activity = activity;
    }

    private Step step() {
        if (SkipKeepAliveService.recoveryRunning()) return Step.RUNNING;
        if (!activity.store.splashEnabled() && !activity.store.popupEnabled()) return Step.ENABLE_SKIPPING;
        if (!AccessibilityRecovery.connected(activity)) return Step.CONNECT;
        if (!SkipKeepAliveService.notificationsAllowed(activity)) return Step.NOTIFICATIONS;
        if (!AccessibilityRecovery.hasPermission(activity)) return Step.AUTHORIZE;
        return Step.READY;
    }

    boolean changed() {
        return shown != step() || showedFailure != SkipKeepAliveService.recoveryFailed();
    }

    void render() {
        shown = step();
        if (authorizationHelp != null && AccessibilityRecovery.hasPermission(activity)) {
            authorizationHelp.dismiss();
            authorizationHelp = null;
        }
        showedFailure = SkipKeepAliveService.recoveryFailed();
        activity.header("", "自动恢复无障碍",
                "开屏跳过突然失效？净屏可以在小米手机上检查并恢复自己的无障碍开关。");

        LinearLayout panel = activity.card(activity.body);
        if (shown == Step.RUNNING) {
            prompt(panel, "自动恢复运行中", "净屏约每 30 秒检查一次。要手动关闭无障碍，请先停止自动恢复。",
                    "停止自动恢复", () -> {
                        SkipKeepAliveService.stopRecovery();
                        activity.render();
                    });
            activity.paragraph(activity.body, "如果手机重启或净屏被彻底关闭，重新打开本页即可再次开启。");
            return;
        }
        if (showedFailure) {
            activity.paragraph(panel, "上次恢复未成功，检查已停止。按下方提示完成设置后可再试。");
            activity.space(panel, 12);
        }
        switch (shown) {
            case ENABLE_SKIPPING:
                prompt(panel, "先开启开屏跳过", "开启开屏跳过或弹窗处理后，就可以设置自动恢复。",
                        "开启开屏跳过", activity::toggleSplash);
                break;
            case CONNECT:
                prompt(panel, "先连接无障碍", "在手机设置的无障碍服务列表中开启「净屏」，然后回到这里。",
                        "打开无障碍设置", activity::openAccessibilitySettings);
                activity.linkAction(panel, "开启遇到问题", activity::accessibility);
                break;
            case NOTIFICATIONS:
                prompt(panel, "允许保活通知", "通知会显示自动恢复正在运行，也可以从通知停止。",
                        canRequestNotifications() ? "允许通知" : "打开通知设置",
                        this::enableNotifications);
                break;
            case AUTHORIZE:
                if (Build.VERSION.SDK_INT >= 30) {
                    prompt(panel, "完成一次授权", "可以用本机的无线调试授权。完成后回到这里开启自动恢复。",
                            "用手机授权", this::showPhoneHelp);
                    activity.linkAction(panel, "用电脑授权", this::showComputerHelp);
                } else {
                    prompt(panel, "用电脑完成一次授权", "这台手机的 Android 版本较早，请用 USB 连接电脑完成授权。",
                            "查看电脑授权步骤", this::showComputerHelp);
                }
                break;
            case READY:
                prompt(panel, "已准备好", "现在可以让净屏在无障碍开关掉线时自动尝试恢复。",
                        "开启自动恢复", this::confirmStart);
                break;
            default: break;
        }
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

    private void showComputerHelp() {
        String command = "adb -d shell " + grantCommand();
        String stepOne = "1. 手机设置 → 我的设备／关于手机，连续点按系统版本，开启开发者选项。";
        String stepTwo = "2. 设置 → 更多设置 → 开发者选项，打开「USB 调试」。用数据线连电脑，在手机上点「允许」。";
        String stepThree = "3. 在电脑上运行下方命令。Windows 也可以用净屏 USB 授权助手完成。授权后回到本页。";
        String help = "如果电脑提示权限不足，请检查开发者选项里是否还有「USB 调试（安全设置）」。授权完成后可关闭调试。";
        LinearLayout content = activity.skipDialogContent();
        activity.paragraph(content, stepOne);
        activity.space(content, 10);
        activity.paragraph(content, stepTwo);
        activity.space(content, 10);
        activity.paragraph(content, stepThree);
        activity.space(content, 10);
        activity.paragraph(content, command);
        activity.space(content, 10);
        activity.paragraph(content, help);
        activity.linkAction(content, "分享授权步骤和命令", () -> {
            String instructions = String.join("\n\n", "净屏 · 自动恢复无障碍", stepOne, stepTwo, stepThree,
                    command, "在电脑上获取 Android 平台工具\n" + PLATFORM_TOOLS, help);
            Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, instructions);
            if (!activity.tryOpenRule(Intent.createChooser(share, "分享净屏授权步骤"))) {
                activity.toast("无法打开分享，可以复制授权命令");
            }
        });
        showAuthorizationHelp("用电脑完成一次授权", content, command);
    }

    private void showPhoneHelp() {
        String command = grantCommand();
        LinearLayout content = activity.skipDialogContent();
        activity.paragraph(content, "1. 手机连上 Wi-Fi，安装开源工具 aShell You。打开工具，选择「配对 → 本机」，允许它发配对通知。");
        activity.linkAction(content, "获取 aShell You", () -> openGuide(PHONE_TOOL));
        activity.paragraph(content, "2. 在「设置 → 关于手机」连续点按系统版本以开启开发者选项。到「更多设置 → 开发者选项」打开「无线调试」，选择使用配对码配对。在 aShell You 的通知中输入 6 位配对码。");
        activity.linkAction(content, "查看配对图解", () -> openGuide(PHONE_GUIDE));
        activity.paragraph(content, "3. 配对成功后，在 aShell You 的无线调试终端粘贴并执行授权命令。回到净屏，看到「已准备好」后即可开启。");
        activity.space(content, 10);
        activity.paragraph(content, "完成后可以关闭无线调试。若手机没有无线调试或授权被系统拒绝，请尝试电脑方式。");
        showAuthorizationHelp("用手机完成一次授权", content, command);
    }

    private String grantCommand() {
        return "pm grant " + activity.getPackageName() + " " + AccessibilityRecovery.PERMISSION;
    }

    private void openGuide(String url) {
        if (!activity.tryOpenRule(new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            activity.toast("无法打开链接，请使用浏览器");
        }
    }

    private void showAuthorizationHelp(String title, LinearLayout content, String command) {
        authorizationHelp = new AlertDialog.Builder(activity).setTitle(title)
                .setView(activity.scrollDialogContent(content))
                .setPositiveButton("复制授权命令", (dialog, which) -> {
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("净屏授权命令", command));
                    activity.toast("已复制授权命令");
                }).setNegativeButton("关闭", null).create();
        authorizationHelp.setOnDismissListener(dialog -> {
            if (authorizationHelp == dialog) authorizationHelp = null;
        });
        authorizationHelp.show();
    }

    private void confirmStart() {
        if (step() != Step.READY) {
            activity.render();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("开启自动恢复无障碍？")
                .setMessage("运行期间，即使你在系统设置手动关闭净屏的无障碍开关，净屏也会尝试恢复。要关闭无障碍，请先在本页停止自动恢复，或从通知停止保活。")
                .setPositiveButton("开启", (dialog, which) -> {
                    if (!SkipKeepAliveService.startRecovery(activity)) {
                        activity.toast("暂时无法开启，请检查授权和通知");
                    }
                    activity.render();
                }).setNegativeButton("取消", null).show();
    }
}
