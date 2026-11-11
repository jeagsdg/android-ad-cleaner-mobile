package app.clearscreen;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

/** One explicitly requested update download per installation. */
final class ReleaseApkDownload {
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final long NO_DOWNLOAD = -1;
    private static final Object STATE_LOCK = new Object();
    private static final Object INSTALL_LOCK = new Object();

    enum State { IDLE, DOWNLOADING, READY, FAILED }

    static final class Status {
        final State state;
        final int versionCode;
        final String versionName;
        final int progress;

        Status(State state, int versionCode, String versionName, int progress) {
            this.state = state;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.progress = progress;
        }
    }

    private static final Status IDLE = new Status(State.IDLE, 0, "", -1);
    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences prefs;

    ReleaseApkDownload(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(DownloadManager.class);
        prefs = this.context.getSharedPreferences("release-apk-download", Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            long id = prefs.getLong("id", NO_DOWNLOAD);
            if (id >= 0 && prefs.getInt("version-code", 0) <= installedVersion()) {
                discardQuietly(id);
                prefs.edit().clear().apply();
            }
        }
    }

    Status status() {
        synchronized (STATE_LOCK) {
            long id = prefs.getLong("id", NO_DOWNLOAD);
            if (id < 0) return IDLE;
            return query(id, prefs.getInt("version-code", 0), prefs.getString("version-name", ""));
        }
    }

    private Status query(long id, int code, String name) {
        Status failed = new Status(State.FAILED, code, name, -1);
        if (manager == null || prefs.getBoolean("invalid", false)) return failed;
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) return failed;
            long total = downloadBytes(cursor, DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
            long downloaded = downloadBytes(cursor, DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
            if (total > MAX_APK_BYTES || downloaded > MAX_APK_BYTES) {
                failDownload(id);
                return failed;
            }
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state == DownloadManager.STATUS_SUCCESSFUL) return new Status(State.READY, code, name, 100);
            if (state == DownloadManager.STATUS_FAILED) return failed;
            int progress = total > 0 && downloaded >= 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
            return new Status(State.DOWNLOADING, code, name, progress);
        } catch (RuntimeException failure) {
            return failed;
        }
    }

    private static long downloadBytes(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 ? cursor.getLong(index) : -1;
    }

    void start(ReleaseUpdates.Info release) throws IOException {
        enqueue(release, false);
    }

    void retry(ReleaseUpdates.Info release) throws IOException {
        enqueue(release, true);
    }

    private void enqueue(ReleaseUpdates.Info release, boolean retry) throws IOException {
        synchronized (STATE_LOCK) {
            if (manager == null) throw new IOException("此设备没有可用的下载服务");
            long installed = installedVersion();
            if (installed < 0 || release.versionCode <= installed) throw new IOException("此更新版本已失效");
            Status previous = status();
            long previousId = prefs.getLong("id", NO_DOWNLOAD);
            if (previous.versionCode > release.versionCode) throw new IOException("已有较新的更新下载");
            boolean sameRelease = previous.versionCode == release.versionCode
                    && previous.versionName.equals(release.versionName);
            if (sameRelease && !retry && previous.state != State.FAILED) return;
            boolean api = sameRelease && !prefs.getBoolean("api", false);
            long id;
            try {
                id = manager.enqueue(request(release, api));
            } catch (RuntimeException failure) {
                throw new IOException("无法开始下载安装包", failure);
            }
            if (id < 0) throw new IOException("无法开始下载安装包");
            if (!prefs.edit().clear().putLong("id", id).putBoolean("api", api)
                    .putInt("version-code", release.versionCode).putString("version-name", release.versionName)
                    .commit()) {
                prefs.edit().clear().apply();
                discardQuietly(id);
                discardQuietly(previousId);
                throw new IOException("无法保存下载状态");
            }
            discardQuietly(previousId);
        }
    }

    private DownloadManager.Request request(ReleaseUpdates.Info release, boolean api) {
        Uri url = api ? release.apiDownloadUri() : release.rawDownloadUri();
        DownloadManager.Request request = new DownloadManager.Request(url)
                .setTitle("正在下载净屏 " + release.versionName)
                .setMimeType(UpdateApkProvider.APK_MIME_TYPE)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setVisibleInDownloadsUi(false)
                .addRequestHeader("User-Agent", "ClearScreen-Update")
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                        "ClearScreen-" + release.versionCode + "-" + UUID.randomUUID() + ".apk");
        if (api) request.addRequestHeader("Accept", "application/vnd.github.raw+json");
        return request;
    }

    void cancel() throws IOException {
        synchronized (STATE_LOCK) {
            long id = prefs.getLong("id", NO_DOWNLOAD);
            if (id < 0) return;
            try {
                discard(id);
            } catch (RuntimeException failure) {
                throw new IOException("无法取消更新下载", failure);
            }
            if (!prefs.edit().clear().commit()) throw new IOException("无法清除下载状态");
        }
    }

    boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    Intent installationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return new Intent(Settings.ACTION_SECURITY_SETTINGS);
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", context.getPackageName(), null));
    }

    Intent installIntent() throws IOException {
        synchronized (INSTALL_LOCK) {
            long id;
            Status ready;
            synchronized (STATE_LOCK) {
                ready = status();
                if (ready.state != State.READY) throw new IOException("安装包还没有下载完成");
                id = prefs.getLong("id", NO_DOWNLOAD);
            }
            File apk = UpdateApkProvider.apkFile(context, id);
            try {
                if (!apk.isFile()) prepareVerifiedCopy(id, ready, apk);
                else verify(apk, ready);
            } catch (IOException | RuntimeException failure) {
                synchronized (STATE_LOCK) {
                    if (prefs.getLong("id", NO_DOWNLOAD) == id) failDownload(id);
                }
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("无法验证安装包，请重新下载", failure);
            }
            synchronized (STATE_LOCK) {
                if (prefs.getLong("id", NO_DOWNLOAD) != id) throw new IOException("下载已取消或替换");
                return new Intent(Intent.ACTION_INSTALL_PACKAGE)
                        .setDataAndType(UpdateApkProvider.apkUri(context, id), UpdateApkProvider.APK_MIME_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
    }

    private void prepareVerifiedCopy(long id, Status ready, File apk) throws IOException {
        File directory = apk.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备安装包存储空间");
        File temporary = new File(directory, "pending.apk");
        try {
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id));
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                long size = 0;
                for (int count; (count = input.read(buffer)) != -1; ) {
                    size += count;
                    if (size > MAX_APK_BYTES) throw new IOException("安装包超过下载大小限制");
                    output.write(buffer, 0, count);
                }
            }
            verify(temporary, ready);
            synchronized (STATE_LOCK) {
                if (prefs.getLong("id", NO_DOWNLOAD) != id) throw new IOException("下载已取消或替换");
                if (!temporary.renameTo(apk)) throw new IOException("无法准备已验证的安装包");
            }
        } finally {
            temporary.delete();
        }
    }

    private void verify(File apk, Status ready) throws IOException {
        PackageManager packages = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        try {
            validateArchive(packages.getPackageInfo(context.getPackageName(), flags),
                    packages.getPackageArchiveInfo(apk.getAbsolutePath(), flags), ready.versionCode, ready.versionName);
        } catch (PackageManager.NameNotFoundException failure) {
            throw new IOException("无法验证当前安装的净屏", failure);
        }
    }

    static void validateArchive(PackageInfo installed, PackageInfo archive, int code, String name) throws IOException {
        if (archive == null || !installed.packageName.equals(archive.packageName)
                || code <= versionCode(installed) || code != versionCode(archive) || !name.equals(archive.versionName))
            throw new IOException("下载的安装包与此更新不符，请重新下载");
        Signature[] current = signers(installed), candidate = signers(archive);
        if (current == null || current.length == 0 || candidate == null
                || !new HashSet<>(Arrays.asList(current)).equals(new HashSet<>(Arrays.asList(candidate))))
            throw new IOException("安装包签名与当前净屏不一致，请重新下载");
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return info.signatures;
        return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private long installedVersion() {
        try {
            return versionCode(context.getPackageManager().getPackageInfo(context.getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException failure) {
            return -1;
        }
    }

    private void failDownload(long id) {
        prefs.edit().putBoolean("invalid", true).apply();
        discardQuietly(id);
    }

    private void discardQuietly(long id) {
        try { discard(id); }
        catch (RuntimeException ignored) { /* An unavailable download service must not block the next attempt. */ }
    }

    private void discard(long id) {
        if (id < 0) return;
        try {
            if (manager != null) manager.remove(id);
        } finally {
            context.revokeUriPermission(UpdateApkProvider.apkUri(context, id), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            UpdateApkProvider.apkFile(context, id).delete();
        }
    }
}
