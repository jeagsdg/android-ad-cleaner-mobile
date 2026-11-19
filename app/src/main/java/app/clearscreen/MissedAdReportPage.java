package app.clearscreen;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Package-scoped help, capture consent, and a review step before any export. */
final class MissedAdReportPage {
    private final MainActivity screen;
    private final String pkg, label;
    private final MissedAdCapture capture = MissedAdCapture.LOCAL;

    MissedAdReportPage(MainActivity screen, String pkg, String label) {
        this.screen = screen;
        this.pkg = pkg;
        this.label = label;
    }

    void render() {
        capture.stop(pkg, true);
        screen.header("", "刚才没跳过", label + " · 查看本机结果或重现一次广告。");
        MissedAdCapture.Session session = capture.session(pkg);
        if (session != null) {
            showSession(session);
            if (!session.complete) return;
        }
        CleanerService service = CleanerService.get();
        String disconnect = recentDisconnect();
        if (service == null) {
            LinearLayout access = screen.card(screen.body);
            boolean switchOn = PermissionAccess.state(screen) == PermissionAccess.State.DISCONNECTED;
            access.addView(screen.bold(switchOn ? "开关已开启，服务未连接" : "无障碍服务未连接", 18, MainActivity.INK));
            screen.paragraph(access, disconnect.isEmpty() ? "先连接服务，再打开应用重现广告。" : disconnect);
            if (switchOn && AccessibilityRecovery.supported())
                screen.linkAction(access, "检查并重新连接", screen::openRecoveryPage);
            else screen.linkAction(access, "连接无障碍服务", screen::accessibility);
        } else if (service.busy) {
            screen.paragraph(screen.card(screen.body), "系统设置清理正在执行。结束后再重现广告。");
        } else if (!disconnect.isEmpty()) {
            LinearLayout card = screen.card(screen.body);
            card.addView(screen.bold("近期有过断连", 18, MainActivity.INK));
            screen.paragraph(card, disconnect + " 当前已连接，可重现一次来检查此应用。");
        }
        showMode(MissedAdCapture.Mode.SPLASH, "打开应用时显示", service);
        showMode(MissedAdCapture.Mode.POPUP, "进入应用后出现，例如首页弹窗或内容里的广告", service);

        if(screen.networkFilterAvailable()) {
            LinearLayout draft=screen.card(screen.body);
            draft.addView(screen.bold("记得按钮写了什么？",18,MainActivity.INK));
            screen.paragraph(draft,"为"+label+"起草一条规则。生成前可以核对发送的文字。");
            screen.action(draft,"用 AI 起草规则",false,() -> AiRuleDraft.open(screen,screen.store.customSkipRules(),pkg));
        }
        LinearLayout help = screen.card(screen.body);
        screen.paragraph(help, "广告不再出现时，也可以用应用版本和本机已有结果生成一份反馈。报告由你预览、复制或保存。");
        screen.linkAction(help, "生成基本反馈", () -> new AlertDialog.Builder(screen).setTitle("遇到的是哪种广告？")
                .setItems(new String[]{MissedAdCapture.Mode.SPLASH.label, MissedAdCapture.Mode.POPUP.label}, (dialog, index) -> {
                    MissedAdCapture.Mode mode = MissedAdCapture.Mode.values()[index];
                    String context = recentDisconnect();
                    MissedAdCapture.basicLocal(pkg, screen.store.packageVersion(pkg), versionName(), mode,
                            status(mode) + (context.isEmpty() ? "" : "\n" + context));
                    screen.render();
                }).setNegativeButton("取消", null).show());
    }

    private String recentDisconnect() {
        long time = AccessibilityReliability.lastDisconnectionMillis(screen);
        long age = System.currentTimeMillis() - time;
        if (time <= 0 || age < 0 || age >= MissedAdCapture.RETAIN_MS) return "";
        return android.text.format.DateFormat.getTimeFormat(screen).format(new java.util.Date(time))
                + " 净屏记录到无障碍服务断开。";
    }

    private void showMode(MissedAdCapture.Mode mode, String subtitle, CleanerService service) {
        LinearLayout card = screen.card(screen.body);
        card.addView(screen.bold(mode.label, 18, MainActivity.INK));
        screen.space(card, 6);
        screen.paragraph(card, subtitle + "\n" + status(mode));
        if (service != null && !service.busy && allowed(mode))
            screen.linkAction(card, mode == MissedAdCapture.Mode.SPLASH ? "重现开屏广告" : "重现应用内广告", () -> consent(mode));
        else if (!allowed(mode)) screen.linkAction(card, "检查此应用的处理设置",
                () -> screen.showPage(MainActivity.APP_AD_DETAIL));
    }

    private boolean allowed(MissedAdCapture.Mode mode) {
        return settingIssue(mode).isEmpty();
    }

    private String settingIssue(MissedAdCapture.Mode mode) {
        if (screen.store.splashExcluded().contains(pkg)) return "此应用已暂停广告处理。";
        if (screen.store.skipExcluded(mode.key).contains(pkg)) return "已停用此应用的" + mode.label + "处理。";
        boolean enabled = mode == MissedAdCapture.Mode.SPLASH ? screen.store.splashEnabled() : screen.store.popupEnabled();
        return enabled ? "" : "全局" + mode.label + "处理尚未开启。";
    }

    private String status(MissedAdCapture.Mode mode) {
        String issue = settingIssue(mode);
        if (!issue.isEmpty()) return issue;
        if (mode == MissedAdCapture.Mode.SPLASH) {
            String recent = SkipDiagnostics.LOCAL.recentResult(pkg, screen.store.packageVersion(pkg));
            if (!recent.isEmpty()) return "本次开屏诊断：" + recent + "。";
        }
        if (screen.store.keepsSkipDetails()) {
            try {
                JSONArray history = screen.store.read(mode.key + "-history");
                long now = System.currentTimeMillis();
                for (int i = 0; i < history.length(); i++) {
                    JSONObject entry = history.optJSONObject(i);
                    if (entry == null || !pkg.equals(entry.optString("package"))) continue;
                    long time = entry.optLong("time");
                    if (time <= 0 || now < time || now - time >= MissedAdCapture.RETAIN_MS) continue;
                    return entry.optBoolean("confirmed")
                            ? "最近 5 分钟有一次已确认关闭的记录，可能与你看到的广告不同。"
                            : "最近 5 分钟有一次处理记录，未确认广告已关闭。";
                }
            } catch (IllegalStateException ignored) { /* Do not give a result from unreadable history. */ }
        }
        return "本机没有这次广告的处理结果。";
    }

    private void consent(MissedAdCapture.Mode mode) {
        Intent target = launchIntent();
        LinearLayout content = screen.column();
        content.setPadding(screen.dp(24), screen.dp(8), screen.dp(24), screen.dp(8));
        screen.paragraph(content, (mode == MissedAdCapture.Mode.SPLASH
                ? "先退出目标应用。开始后打开它，等出现开屏广告，再直接切回净屏。"
                : "打开应用并走到出现广告的页面。广告出现后直接切回净屏，尽量不要先关闭它。")
                + "\n\n只观察这一个应用的一次使用，最多 90 秒。基本报告含应用版本和处理结果。"
                + "不会截屏或自动发送。勾选下方选项会临时保留控件树、资源 ID 和可识别的广告按钮文字；其他正文会隐去。报告在本机内存中保留 5 分钟。");
        CheckBox controls = new CheckBox(screen);
        controls.setText("记录脱敏页面样本，供检查控件和回放规则");
        controls.setMinHeight(screen.dp(48));
        content.addView(controls, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(screen);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(screen).setTitle(mode.label + " · 采集一次").setView(scroll)
                .setPositiveButton(target == null ? "开始采集" : "开始并打开", (d, w) -> {
                    CleanerService service = CleanerService.get();
                    if (service == null || service.busy || !allowed(mode)) { screen.render(); return; }
                    MissedAdCapture.startLocal(pkg, screen.store.packageVersion(pkg), versionName(), mode, controls.isChecked());
                    if (target != null) {
                        try { screen.startActivity(target); return; }
                        catch (ActivityNotFoundException | SecurityException ignored) { }
                    }
                    screen.render();
                }).setNegativeButton("取消", null).show();
    }

    private Intent launchIntent() {
        try { return screen.getPackageManager().getLaunchIntentForPackage(pkg); }
        catch (SecurityException ignored) { return null; }
    }

    private String versionName() {
        try {
            String name = screen.getPackageManager().getPackageInfo(pkg, 0).versionName;
            if (name != null && name.matches("[0-9A-Za-z._+ -]{1,48}")) return name.trim();
        } catch (PackageManager.NameNotFoundException | SecurityException ignored) { }
        return "";
    }

    private void showSession(MissedAdCapture.Session session) {
        LinearLayout card = screen.card(screen.body);
        card.addView(screen.bold(session.complete ? "本机反馈报告" : "等待打开应用", 18, MainActivity.INK));
        screen.space(card, 8);
        if (!session.complete) {
            screen.paragraph(card, "从桌面或最近任务打开「" + label + "」。复现" + session.mode.label + "后切回这里。采集最多持续 90 秒。");
            screen.linkAction(card, "打开应用", () -> screen.openInstalledApp(pkg));
            screen.linkAction(card, "停止采集", () -> { capture.stop(pkg, false); screen.render(); });
            return;
        }
        screen.paragraph(card, session.mode.label + " · " + session.outcome());
        card.addView(screen.text(session.reference, 14, MainActivity.MUTED));
        screen.space(card, 6);
        screen.action(card, "预览、复制或保存报告", true, () -> preview(session));
        if(session.sample!=null) screen.action(card,"打开样本实验室",false,() -> {
            if(valid(session)) SampleInspector.open(screen,session);
        });
        screen.linkAction(card, "打开官方反馈与进度", () -> officialFeedback(session));
        screen.linkAction(card, "清除本机报告", () -> { capture.clear(pkg); screen.render(); });
    }

    private void preview(MissedAdCapture.Session session) {
        if (!valid(session)) return;
        String report = session.report(screen.store.appVersion(), Build.VERSION.SDK_INT);
        AlertDialog dialog = new AlertDialog.Builder(screen).setTitle("反馈报告预览")
                .setMessage(report)
                .setPositiveButton("复制", (d, w) -> {
                    if (valid(session)) screen.copyDiagnosticText("净屏广告反馈", report);
                }).setNeutralButton("保存文件", (d, w) -> {
                    if (valid(session)) screen.saveDiagnosticFile(report);
                }).setNegativeButton("关闭", null).create();
        Handler expiry = new Handler(Looper.getMainLooper());
        Runnable dismiss = () -> { dialog.dismiss(); valid(session); };
        dialog.setOnDismissListener(ignored -> expiry.removeCallbacks(dismiss));
        dialog.show();
        expiry.postDelayed(dismiss, Math.max(0, session.started + MissedAdCapture.RETAIN_MS - SystemClock.elapsedRealtime()));
    }

    private void officialFeedback(MissedAdCapture.Session session) {
        if (!valid(session)) return;
        new AlertDialog.Builder(screen).setTitle("官方反馈与进度")
                .setMessage("这会用浏览器打开项目的公开问题区，不会附带报告。发帖时可自行贴上预览过的内容和编号 "
                        + session.reference + "，再按此编号查找回复和修复进度。请只发布你愿意公开的信息。")
                .setPositiveButton("打开问题区", (d, w) -> screen.openUpdateLink(
                        ReleaseUpdates.repositoryUri().buildUpon().appendPath("issues").build()))
                .setNegativeButton("取消", null).show();
    }

    private boolean valid(MissedAdCapture.Session session) {
        if (capture.session(pkg) == session) return true;
        screen.toast("报告已过期，可以重新生成");
        screen.render();
        return false;
    }
}
