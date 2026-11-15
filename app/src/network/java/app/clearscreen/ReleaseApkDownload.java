package app.clearscreen;

import android.Manifest;
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
import android.os.UserManager;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.UUID;

/** One explicitly requested update download per installation. */
final class ReleaseApkDownload {
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final long NO_DOWNLOAD = -1;
    private static final long ARCHIVE_ID_FLOOR = 1L << 62;
    private static final Object STATE_LOCK = new Object();
    private static final Object INSTALL_LOCK = new Object();
    private static long archiveWorkerId = NO_DOWNLOAD;
    private static Thread archiveWorker;

    enum State { IDLE, DOWNLOADING, PAUSED, PAUSED_WAITING_TO_RETRY, READY, FAILED }

    static final class Status {
        final State state;
        private final long id;
        final int versionCode;
        final String versionName;
        final int progress;

        Status(State state, long id, int versionCode, String versionName, int progress) {
            this.state = state;
            this.id = id;
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.progress = progress;
        }
    }

    private static final Status IDLE = new Status(State.IDLE, NO_DOWNLOAD, 0, "", -1);
    private final Context context;
    private final DownloadManager manager;
    private final SharedPreferences prefs;
    private final ArchiveFetcher archiveFetcher;

    interface ArchiveFetcher {
        void copy(String path, OutputStream output) throws IOException;
    }

    ReleaseApkDownload(Context context) {
        this(context, (path, output) -> GithubReleaseFiles.readArchiveEntry(path, output, MAX_APK_BYTES, 45000));
    }

    ReleaseApkDownload(Context context, ArchiveFetcher archiveFetcher) {
        this.context = context.getApplicationContext();
        this.archiveFetcher = archiveFetcher;
        manager = this.context.getSystemService(DownloadManager.class);
        prefs = this.context.getSharedPreferences("release-apk-download", Context.MODE_PRIVATE);
        synchronized (STATE_LOCK) {
            long id = prefs.getLong("id", NO_DOWNLOAD);
            if (id >= 0 && prefs.getInt("version-code", 0) <= installedVersion()) {
                discardQuietly(id);
                prefs.edit().clear().apply();
            } else if (isArchive(id) && id != archiveWorkerId && !prefs.getBoolean("archive-ready", false)) {
                archiveTemporary(id).delete();
                UpdateApkProvider.apkFile(this.context, id).delete();
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
        Status failed = new Status(State.FAILED, id, code, name, -1);
        if (isArchive(id)) {
            if (prefs.getBoolean("archive-ready", false) && UpdateApkProvider.apkFile(context, id).isFile())
                return new Status(State.READY, id, code, name, 100);
            return archiveWorkerId == id ? new Status(State.DOWNLOADING, id, code, name, -1) : failed;
        }
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
            if (state == DownloadManager.STATUS_SUCCESSFUL) return new Status(State.READY, id, code, name, 100);
            if (state == DownloadManager.STATUS_FAILED) return failed;
            int progress = total > 0 && downloaded >= 0 ? (int) Math.min(100, downloaded * 100 / total) : -1;
            if (state == DownloadManager.STATUS_PAUSED) {
                int reason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                State paused = reason >= 0 && cursor.getInt(reason) == DownloadManager.PAUSED_WAITING_TO_RETRY
                        ? State.PAUSED_WAITING_TO_RETRY : State.PAUSED;
                return new Status(paused, id, code, name, progress);
            }
            return new Status(State.DOWNLOADING, id, code, name, progress);
        } catch (RuntimeException failure) {
            return failed;
        }
    }

    private static long downloadBytes(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 ? cursor.getLong(index) : -1;
    }

    void start(ReleaseUpdates.Info release) throws IOException {
        enqueue(release, NO_DOWNLOAD);
    }

    void retry(ReleaseUpdates.Info release) throws IOException {
        enqueue(release, NO_DOWNLOAD);
    }

    void retryPaused(ReleaseUpdates.Info release, Status displayed) throws IOException {
        if (displayed.state == State.PAUSED_WAITING_TO_RETRY) enqueue(release, displayed.id);
    }

    private void enqueue(ReleaseUpdates.Info release, long pausedId) throws IOException {
        synchronized (STATE_LOCK) {
            long installed = installedVersion();
            if (installed < 0 || release.versionCode <= installed) throw new IOException("此更新版本已失效");
            Status previous = status();
            boolean sameRelease = previous.versionCode == release.versionCode
                    && previous.versionName.equals(release.versionName);
            if (pausedId != NO_DOWNLOAD && (!sameRelease || previous.id != pausedId
                    || previous.state != State.PAUSED_WAITING_TO_RETRY)) return;
            if (previous.versionCode > release.versionCode) throw new IOException("已有较新的更新下载");
            if (sameRelease && pausedId == NO_DOWNLOAD && previous.state != State.FAILED) return;
            ReleaseUpdates.Source previousSource = ReleaseUpdates.Source.restore(
                    prefs.getString("source", ""), prefs.getBoolean("api", false));
            int attempted = sameRelease ? prefs.getInt("attempted", previousSource.flag()) : 0;
            ReleaseUpdates.Source source = nextSource(release.source, attempted);
            if (!sameRelease || source == null) {
                attempted = 0;
                source = release.source;
            }
            if (manager == null) source = ReleaseUpdates.Source.ARCHIVE;
            long id;
            try {
                id = source == ReleaseUpdates.Source.ARCHIVE
                        ? archiveId()
                        : manager.enqueue(request(release, source == ReleaseUpdates.Source.API));
            } catch (RuntimeException failure) {
                attempted |= source.flag();
                source = ReleaseUpdates.Source.ARCHIVE;
                id = archiveId();
            }
            if (id < 0) {
                attempted |= source.flag();
                source = ReleaseUpdates.Source.ARCHIVE;
                id = archiveId();
            }
            // The old task can resume while DownloadManager enqueues the replacement.
            if (pausedId != NO_DOWNLOAD
                    && query(pausedId, previous.versionCode, previous.versionName).state != State.PAUSED_WAITING_TO_RETRY) {
                discardQuietly(id);
                return;
            }
            if (!prefs.edit().clear().putLong("id", id).putString("source", source.name())
                    .putInt("attempted", attempted | source.flag())
                    .putBoolean("api", source == ReleaseUpdates.Source.API)
                    .putInt("version-code", release.versionCode).putString("version-name", release.versionName)
                    .commit()) {
                prefs.edit().clear().apply();
                discardQuietly(id);
                discardQuietly(previous.id);
                throw new IOException("无法保存下载状态");
            }
            discardQuietly(previous.id);
            if (isArchive(id)) startArchiveWorker(id, release);
        }
    }

    private static ReleaseUpdates.Source nextSource(ReleaseUpdates.Source preferred, int attempted) {
        if ((attempted & preferred.flag()) == 0) return preferred;
        for (ReleaseUpdates.Source source : ReleaseUpdates.Source.values())
            if ((attempted & source.flag()) == 0) return source;
        return null;
    }

    private void startArchiveWorker(long id, ReleaseUpdates.Info release) {
        Thread worker = new Thread(() -> fetchArchiveApk(id, release), "clearscreen-download-apk");
        archiveWorkerId = id;
        archiveWorker = worker;
        worker.start();
    }

    private void fetchArchiveApk(long id, ReleaseUpdates.Info release) {
        File apk = UpdateApkProvider.apkFile(context, id);
        File temporary = archiveTemporary(id);
        try {
            FileOutputStream file;
            synchronized (STATE_LOCK) {
                if (prefs.getLong("id", NO_DOWNLOAD) != id) return;
                ensureDirectory(apk.getParentFile());
                file = new FileOutputStream(temporary);
            }
            try (FileOutputStream output = file) {
                archiveFetcher.copy(release.archiveApkPath(), output);
            }
            verify(temporary, new Status(State.DOWNLOADING, id, release.versionCode, release.versionName, -1));
            synchronized (STATE_LOCK) {
                if (prefs.getLong("id", NO_DOWNLOAD) != id) return;
                if (!temporary.renameTo(apk)) throw new IOException("无法准备已验证的安装包");
                if (!prefs.edit().putBoolean("archive-ready", true).commit()) {
                    apk.delete();
                    throw new IOException("无法保存下载状态");
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // status() exposes this attempt as failed so the user can switch routes or retry.
        } finally {
            temporary.delete();
            synchronized (STATE_LOCK) {
                if (archiveWorkerId == id) {
                    archiveWorkerId = NO_DOWNLOAD;
                    archiveWorker = null;
                }
            }
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
        if (api) request.addRequestHeader("Accept", GithubReleaseFiles.API_ACCEPT);
        return request;
    }

    void cancel(Status displayed) throws IOException {
        synchronized (STATE_LOCK) {
            long id = prefs.getLong("id", NO_DOWNLOAD);
            if (id < 0 || displayed.id != id) return;
            try {
                discard(id);
            } catch (RuntimeException failure) {
                throw new IOException("无法取消更新下载", failure);
            }
            if (!prefs.edit().clear().commit()) throw new IOException("无法清除下载状态");
        }
    }

    boolean isInstallRestricted() {
        UserManager users = context.getSystemService(UserManager.class);
        return users != null && (users.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
                || users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        && users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)));
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
                if (isArchive(id) && !apk.isFile()) throw new IOException("安装包已失效");
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
        ensureDirectory(directory);
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
            PackageInfo candidate = packages.getPackageArchiveInfo(apk.getAbsolutePath(), flags | PackageManager.GET_PERMISSIONS);
            validateArchive(packages.getPackageInfo(context.getPackageName(), flags), candidate, ready.versionCode, ready.versionName);
            if (candidate.requestedPermissions == null
                    || !Arrays.asList(candidate.requestedPermissions).containsAll(Arrays.asList(
                            Manifest.permission.INTERNET, Manifest.permission.REQUEST_INSTALL_PACKAGES)))
                throw new IOException("此安装包无法覆盖完整版，请重新下载");
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
        prefs.edit().putBoolean("invalid", true).remove("archive-ready").apply();
        discardQuietly(id);
    }

    private void discardQuietly(long id) {
        try { discard(id); }
        catch (RuntimeException ignored) { /* An unavailable download service must not block the next attempt. */ }
    }

    private void discard(long id) {
        if (id < 0) return;
        try {
            if (isArchive(id)) {
                if (archiveWorkerId == id && archiveWorker != null) archiveWorker.interrupt();
                archiveTemporary(id).delete();
            } else if (manager != null) manager.remove(id);
        } finally {
            context.revokeUriPermission(UpdateApkProvider.apkUri(context, id), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            UpdateApkProvider.apkFile(context, id).delete();
        }
    }

    private File archiveTemporary(long id) {
        File apk = UpdateApkProvider.apkFile(context, id);
        return new File(apk.getParentFile(), id + ".part.apk");
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
            throw new IOException("无法准备安装包存储空间");
    }

    private static boolean isArchive(long id) { return id >= ARCHIVE_ID_FLOOR; }

    private static long archiveId() {
        return ARCHIVE_ID_FLOOR | (UUID.randomUUID().getLeastSignificantBits() & (ARCHIVE_ID_FLOOR - 1));
    }
}
