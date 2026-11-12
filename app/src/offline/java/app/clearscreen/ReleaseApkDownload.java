package app.clearscreen;

import android.content.Context;
import android.content.Intent;
import java.io.IOException;

final class ReleaseApkDownload {
    enum State { IDLE, DOWNLOADING, PAUSED, PAUSED_WAITING_TO_RETRY, READY, FAILED }

    static final class Status {
        final State state = State.IDLE;
        final int versionCode = 0;
        final String versionName = "";
        final int progress = -1;
    }

    ReleaseApkDownload(Context context) {}

    Status status() { return new Status(); }

    void start(ReleaseUpdates.Info release) throws IOException {
        throw new IOException("纯离线版不提供应用内下载");
    }

    void retry(ReleaseUpdates.Info release) throws IOException { start(release); }

    void retryPaused(ReleaseUpdates.Info release, Status displayed) throws IOException { start(release); }

    void cancel(Status displayed) throws IOException {}

    boolean isInstallRestricted() { return false; }

    boolean canInstall() { return false; }

    Intent installationSettings() { return new Intent(); }

    Intent installIntent() throws IOException {
        throw new IOException("纯离线版不提供应用内安装");
    }
}
