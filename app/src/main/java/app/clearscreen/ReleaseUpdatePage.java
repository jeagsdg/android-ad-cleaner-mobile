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
    private ReleaseApkDownload.Status displayedDownload;
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
        cancel = screen.button("取消下载", false, () -> {});
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
        displayedDownload = status;
        ReleaseApkDownload.State state = state(status);
        boolean newerDownload = status.versionCode > release.versionCode;
        boolean installRestricted = state == ReleaseApkDownload.State.READY && download.isInstallRestricted();
        String label, detail;
        switch (state) {
            case DOWNLOADING:
                label = "下载中";
                detail = status.progress < 0 ? "正在下载安装包…" : "已下载 " + status.progress + "%";
                break;
            case PAUSED:
                label = "下载已暂停";
                detail = "可以检查网络，也可以取消后重试。";
                break;
            case PAUSED_WAITING_TO_RETRY:
                label = "换个地址重试";
                detail = "下载暂时中断，可以等待系统重试，或换个地址。";
                break;
            case READY:
                label = preparing ? "正在校验…" : "安装更新";
                detail = installRestricted ? "设备设置禁止安装这次更新。安装包已保留。"
                        : "下载完成。请按手机提示安装。";
                break;
            case FAILED:
                label = "重新下载";
                detail = "下载未完成。可以检查网络后重试。";
                break;
            default:
                label = "下载更新";
                detail = newerDownload ? "已有较新版本的下载。取消后可下载当前提供的版本。" : "更新会保留设置和记录。";
        }
        screen.updateText(primary, label);
        screen.updateText(statusText, detail);
        boolean inProgress = state == ReleaseApkDownload.State.DOWNLOADING || state == ReleaseApkDownload.State.PAUSED;
        primary.setEnabled(!preparing && !inProgress && !newerDownload && !installRestricted);
        boolean canCancel = newerDownload || inProgress || state == ReleaseApkDownload.State.PAUSED_WAITING_TO_RETRY;
        screen.updateText(cancel, newerDownload ? "取消较新下载" : "取消下载");
        cancel.setOnClickListener(canCancel ? view -> cancel(status) : null);
        cancel.setVisibility(canCancel ? View.VISIBLE : View.GONE);
    }

    private void onPrimary() {
        if (release == null || preparing || displayedDownload == null
                || displayedDownload.versionCode > release.versionCode) return;
        try {
            if (state(displayedDownload) == ReleaseApkDownload.State.PAUSED_WAITING_TO_RETRY) {
                download.retryPaused(release, displayedDownload);
            } else {
                ReleaseApkDownload.Status latest = download.status();
                if (latest.versionCode > release.versionCode) {
                    refresh();
                    return;
                }
                switch (state(latest)) {
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
            }
        } catch (IOException | RuntimeException failure) {
            screen.toast("暂时无法开始下载，请稍后重试");
        }
        refresh();
    }

    private void cancel(ReleaseApkDownload.Status displayed) {
        try { download.cancel(displayed); }
        catch (IOException | RuntimeException failure) { screen.toast("暂时无法取消，请稍后重试"); }
        refresh();
    }

    private void install() {
        if (download.isInstallRestricted()) return;
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
                if (result != null) launch(result, "暂时无法打开系统安装页面");
                else if (!download.isInstallRestricted()) screen.toast("安装包校验未通过，请重新下载");
                refresh();
            });
        }, "clearscreen-verify-apk").start();
    }

    private void launch(Intent intent, String failureMessage) {
        if (download.isInstallRestricted()) {
            refresh();
            return;
        }
        try { screen.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException failure) { screen.toast(failureMessage); }
    }
}
