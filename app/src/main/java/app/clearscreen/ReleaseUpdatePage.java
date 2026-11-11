package app.clearscreen;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;

final class ReleaseUpdatePage {
    private final MainActivity screen;
    private final ReleaseApkDownload download;
    private ReleaseUpdates.Info release;
    private TextView statusText;
    private Button primary, cancel;
    private boolean preparing;

    ReleaseUpdatePage(MainActivity screen) {
        this.screen = screen;
        download = new ReleaseApkDownload(screen);
    }

    void render(ReleaseUpdates.Info available) {
        release = available;
        screen.header("", "应用更新", "");
        LinearLayout controls = screen.card(screen.body);
        if (release == null) {
            screen.paragraph(controls, "暂无可下载的更新。");
            return;
        }

        controls.addView(screen.heading("净屏 " + release.versionName + " 可更新", 20, MainActivity.INK));
        screen.space(controls, 8);
        statusText = screen.text("", 16, MainActivity.MUTED);
        controls.addView(statusText);
        screen.space(controls, 12);
        primary = screen.button("下载更新", true, this::onPrimary);
        controls.addView(primary, new LinearLayout.LayoutParams(-1, -2));
        cancel = screen.button("取消下载", false, this::cancel);
        LinearLayout.LayoutParams cancelLayout = new LinearLayout.LayoutParams(-1, -2);
        cancelLayout.topMargin = screen.dp(8);
        controls.addView(cancel, cancelLayout);
        refresh();

        if (!release.notes.isEmpty()) {
            LinearLayout details = screen.card(screen.body);
            details.addView(screen.heading("这次更新", 18, MainActivity.INK));
            screen.space(details, 8);
            details.addView(screen.text(release.notes, 16, MainActivity.INK));
        }
        screen.linkAction(screen.card(screen.body), "在 GitHub 给净屏点 Star",
                () -> screen.openUpdateLink(ReleaseUpdates.repositoryUri()));
    }

    private ReleaseApkDownload.State state(ReleaseApkDownload.Status status) {
        return status.versionCode == release.versionCode ? status.state : ReleaseApkDownload.State.IDLE;
    }

    void refresh() {
        if (release == null) return;
        ReleaseApkDownload.Status status = download.status();
        ReleaseApkDownload.State state = state(status);
        String label, detail;
        switch (state) {
            case DOWNLOADING:
                label = "下载中";
                detail = status.progress < 0 ? "正在下载安装包…" : "已下载 " + status.progress + "%";
                break;
            case READY:
                label = preparing ? "正在校验…" : "安装更新";
                detail = "下载完成。请按手机提示安装。";
                break;
            case FAILED:
                label = "重新下载";
                detail = "下载未完成。可以检查网络后重试。";
                break;
            default:
                label = "下载更新";
                detail = "更新会保留设置和记录。";
        }
        screen.updateText(primary, label);
        screen.updateText(statusText, detail);
        primary.setEnabled(!preparing && state != ReleaseApkDownload.State.DOWNLOADING);
        cancel.setVisibility(state == ReleaseApkDownload.State.DOWNLOADING ? View.VISIBLE : View.GONE);
    }

    private void onPrimary() {
        if (release == null || preparing) return;
        try {
            switch (state(download.status())) {
                case IDLE:
                    download.start(release);
                    break;
                case FAILED:
                    download.retry(release);
                    break;
                case READY:
                    install();
                    break;
                default:
                    break;
            }
        } catch (IOException | RuntimeException failure) {
            screen.toast("暂时无法开始下载，请稍后重试");
        }
        refresh();
    }

    private void cancel() {
        try { download.cancel(); }
        catch (IOException | RuntimeException failure) { screen.toast("暂时无法取消，请稍后重试"); }
        refresh();
    }

    private void install() {
        if (!download.canInstall()) {
            new AlertDialog.Builder(screen).setTitle("允许净屏安装更新")
                    .setMessage("打开「允许来自此来源的应用」，返回净屏后再点「安装更新」。")
                    .setPositiveButton("打开手机设置", (dialog, which) ->
                            launch(download.installationSettings(), "暂时无法打开安装设置"))
                    .setNegativeButton("稍后", null).show();
            return;
        }
        preparing = true;
        int version = release.versionCode;
        new Thread(() -> {
            Intent install;
            try { install = download.installIntent(); }
            catch (IOException | RuntimeException failure) { install = null; }
            Intent result = install;
            screen.runOnUiThread(() -> {
                preparing = false;
                if (!screen.isUpdatePageActive() || release == null || release.versionCode != version) return;
                if (result == null) screen.toast("安装包校验未通过，请重新下载");
                else launch(result, "暂时无法打开系统安装页面");
                refresh();
            });
        }, "clearscreen-verify-apk").start();
    }

    private void launch(Intent intent, String failureMessage) {
        try { screen.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException failure) { screen.toast(failureMessage); }
    }
}
