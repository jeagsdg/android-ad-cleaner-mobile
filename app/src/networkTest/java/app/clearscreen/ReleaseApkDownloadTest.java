package app.clearscreen;

import android.Manifest;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Pair;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowUserManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReleaseApkDownloadTest {
    private static final String FEED = "{\"schema\":1,\"versionCode\":100,\"versionName\":\"9.9.9\",\"notes\":\"\"}";
    private final Context app = RuntimeEnvironment.getApplication();

    @Test public void downloadSurvivesNewControllerAndRetrySwitchesToRawApi() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));
        ReleaseApkDownload first = new ReleaseApkDownload(app);
        ReleaseUpdates.Info release = release();
        first.start(release);
        long rawId = downloadId();
        assertTrue(rawId >= 0);
        ShadowDownloadManager.ShadowRequest raw = shadowOf(manager.getRequest(rawId));
        assertEquals(release.rawDownloadUri(), raw.getUri());
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE, raw.getNotificationVisibility());
        assertFalse(raw.getVisibleInDownloadsUi());
        raw.setStatus(DownloadManager.STATUS_RUNNING);
        raw.setTotalSize(4096);
        raw.setBytesSoFar(1024);

        ReleaseApkDownload reopened = new ReleaseApkDownload(app);
        ReleaseApkDownload.Status downloading = reopened.status();
        assertEquals(ReleaseApkDownload.State.DOWNLOADING, downloading.state);
        assertEquals(release.versionCode, downloading.versionCode);
        assertEquals(release.versionName, downloading.versionName);
        assertEquals(25, downloading.progress);
        reopened.start(release);
        assertEquals(rawId, downloadId());

        raw.setStatus(DownloadManager.STATUS_FAILED);
        assertEquals(ReleaseApkDownload.State.FAILED, reopened.status().state);
        reopened.retry(release);
        long apiId = downloadId();
        assertNotEquals(rawId, apiId);
        assertNull(manager.getRequest(rawId));
        ShadowDownloadManager.ShadowRequest api = shadowOf(manager.getRequest(apiId));
        assertEquals(release.apiDownloadUri(), api.getUri());
        assertTrue(api.getRequestHeaders().contains(new Pair<>("Accept", "application/vnd.github.raw+json")));
        api.setStatus(DownloadManager.STATUS_SUCCESSFUL);
        assertEquals(ReleaseApkDownload.State.READY, reopened.status().state);

        reopened.cancel(reopened.status());
        assertEquals(ReleaseApkDownload.State.IDLE, new ReleaseApkDownload(app).status().state);
        assertNull(manager.getRequest(apiId));
    }

    @Test public void badArchiveReturnsToRetryAndCannotBeHandedToInstaller() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        download.start(release());
        long id = downloadId();
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));
        shadowOf(manager.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        File invalid = UpdateApkProvider.apkFile(app, id);
        assertTrue(invalid.getParentFile().isDirectory() || invalid.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(invalid)) {
            output.write(0);
        }
        assertThrows(IOException.class, download::installIntent);
        assertEquals(ReleaseApkDownload.State.FAILED, download.status().state);
        assertFalse(invalid.exists());
        assertNull(manager.getRequest(id));
    }

    @Test @Config(sdk = {24, 28})
    public void installPolicyRecognizesLegacyUserRestrictions() {
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        ShadowUserManager users = shadowOf(app.getSystemService(UserManager.class));
        assertFalse(download.isInstallRestricted());
        for (String restriction : new String[]{UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES}) {
            users.setUserRestriction(Process.myUserHandle(), restriction, true);
            assertTrue(download.isInstallRestricted());
            users.setUserRestriction(Process.myUserHandle(), restriction, false);
            assertFalse(download.isInstallRestricted());
        }
    }

    @Test @Config(sdk = 34)
    public void deviceInstallPolicyKeepsTheReadyDownloadAndBlocksStaleAuthorization() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ReleaseUpdates.Info release = release();
        Store store = new Store(app);
        store.cacheRelease(release);
        store.noteUpdateCheck(System.currentTimeMillis());
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        download.start(release);
        long id = downloadId();
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));
        shadowOf(manager.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        File apk = UpdateApkProvider.apkFile(app, id);
        assertTrue(apk.getParentFile().isDirectory() || apk.getParentFile().mkdirs());
        assertTrue(apk.createNewFile());
        shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "查看更新");
            ScreenTest.clickFeature(activity, "安装更新");
            AlertDialog authorization = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(authorization);
            assertTrue(authorization.isShowing());

            ShadowUserManager users = shadowOf(app.getSystemService(UserManager.class));
            String restriction = UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY;
            users.setUserRestriction(Process.myUserHandle(), restriction, true);
            shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(true);
            authorization.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(activity).getNextStartedActivity());

            View page = activity.getWindow().getDecorView();
            assertNotNull(ScreenTest.find(page, "设备设置禁止安装这次更新。安装包已保留。"));
            assertFalse(ScreenTest.find(page, "安装更新").isEnabled());
            assertEquals(ReleaseApkDownload.State.READY, download.status().state);
            assertEquals(id, downloadId());
            assertTrue(apk.isFile());

            users.setUserRestriction(Process.myUserHandle(), restriction, false);
            shadowOf(app.getPackageManager()).setCanRequestPackageInstalls(false);
            controller.pause().resume();
            assertTrue(ScreenTest.find(page, "安装更新").isEnabled());
            ScreenTest.clickFeature(activity, "安装更新");
            AlertDialog normalAuthorization = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(normalAuthorization);
            normalAuthorization.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent settings = shadowOf(activity).getNextStartedActivity();
            assertNotNull(settings);
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
            assertEquals(app.getPackageName(), settings.getData().getSchemeSpecificPart());
            assertEquals(id, downloadId());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void firstDownloadUsesTheSourceThatReturnedTheCachedUpdate() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        new Store(app).cacheRelease(ReleaseUpdates.parse(FEED, true));
        ReleaseUpdates.Info cached = new Store(app).cachedRelease();
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));

        download.start(cached);
        long apiId = downloadId();
        ShadowDownloadManager.ShadowRequest api = shadowOf(manager.getRequest(apiId));
        assertEquals(cached.apiDownloadUri(), api.getUri());
        assertTrue(api.getRequestHeaders().contains(new Pair<>("Accept", "application/vnd.github.raw+json")));

        api.setStatus(DownloadManager.STATUS_FAILED);
        download.retry(cached);
        assertNull(manager.getRequest(apiId));
        assertEquals(cached.rawDownloadUri(), shadowOf(manager.getRequest(downloadId())).getUri());
    }

    @Test public void lowerFeedCanRecoverFromNewerDownloadWithoutCancellingAReplacement() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ReleaseUpdates.Info newer = release();
        ReleaseUpdates.Info available = ReleaseUpdates.parse(FEED.replace("\"versionCode\":100", "\"versionCode\":99"));
        ReleaseUpdates.Info replacement = ReleaseUpdates.parse(FEED.replace("\"versionCode\":100", "\"versionCode\":101"));
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));
        Store store = new Store(app);
        store.cacheRelease(newer);
        download.start(newer);
        long newerId = downloadId();
        shadowOf(manager.getRequest(newerId)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        store.cacheRelease(available);
        store.noteUpdateCheck(System.currentTimeMillis());

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "查看更新");
            View page = activity.getWindow().getDecorView();
            assertNotNull(ScreenTest.find(page, "已有较新版本的下载。取消后可下载当前提供的版本。"));
            assertFalse(ScreenTest.find(page, "下载更新").isEnabled());
            assertNull(ScreenTest.find(page, "安装更新"));
            View oldCancel = ScreenTest.find(page, "取消较新下载");
            assertEquals(View.VISIBLE, oldCancel.getVisibility());
            assertEquals(newerId, downloadId());

            download.start(replacement);
            long replacementId = downloadId();
            assertNotEquals(newerId, replacementId);
            assertTrue(oldCancel.performClick());
            assertEquals(replacementId, downloadId());
            assertNotNull(manager.getRequest(replacementId));

            ScreenTest.clickFeature(activity, "取消较新下载");
            assertNull(manager.getRequest(replacementId));
            assertEquals(-1, downloadId());
            assertTrue(ScreenTest.find(page, "下载更新").isEnabled());
            ScreenTest.clickFeature(activity, "下载更新");
            assertEquals(available.versionCode, download.status().versionCode);
            assertEquals(available.rawDownloadUri(), shadowOf(manager.getRequest(downloadId())).getUri());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(shadows = DownloadManagerWithPauseReason.class)
    public void updatePageLetsSystemResumeNetworkAndWifiPauses() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        Store store = new Store(app);
        store.cacheRelease(release());
        store.noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "查看更新");
            ScreenTest.clickFeature(activity, "下载更新");
            long id = downloadId();
            DownloadManagerWithPauseReason manager = Shadow.extract(app.getSystemService(DownloadManager.class));
            ShadowDownloadManager.ShadowRequest request = shadowOf(manager.getRequest(id));
            request.setTotalSize(4096);
            request.setBytesSoFar(1024);
            request.setStatus(DownloadManager.STATUS_PAUSED);

            for (int reason : new int[]{DownloadManager.PAUSED_WAITING_FOR_NETWORK,
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI, DownloadManager.PAUSED_UNKNOWN}) {
                manager.pauseReason = reason;
                controller.pause().resume();
                View page = activity.getWindow().getDecorView();
                View paused = ScreenTest.find(page, "下载已暂停");
                assertNotNull(paused);
                assertFalse(paused.isEnabled());
                assertNull(ScreenTest.find(page, "换个地址重试"));
                assertEquals(View.VISIBLE, ScreenTest.find(page, "取消下载").getVisibility());
                assertEquals(id, downloadId());
            }

            request.setStatus(DownloadManager.STATUS_RUNNING);
            controller.pause().resume();
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "已下载 25%"));
            assertEquals(id, downloadId());

            request.setStatus(DownloadManager.STATUS_PAUSED);
            controller.pause().resume();
            ScreenTest.clickFeature(activity, "取消下载");
            assertTrue(ScreenTest.find(activity.getWindow().getDecorView(), "下载更新").isEnabled());
            assertEquals(-1, downloadId());
            assertNull(manager.getRequest(id));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(shadows = DownloadManagerWithPauseReason.class)
    public void updatePageSwitchesPausedSourceWithoutInstallingFromAStaleButton() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ReleaseUpdates.Info release = release();
        Store store = new Store(app);
        store.cacheRelease(release);
        store.noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "查看更新");
            ScreenTest.clickFeature(activity, "下载更新");
            DownloadManagerWithPauseReason manager = Shadow.extract(app.getSystemService(DownloadManager.class));
            long rawId = downloadId();
            ShadowDownloadManager.ShadowRequest raw = shadowOf(manager.getRequest(rawId));
            assertEquals(release.rawDownloadUri(), raw.getUri());
            raw.setStatus(DownloadManager.STATUS_PAUSED);
            manager.pauseReason = DownloadManager.PAUSED_WAITING_TO_RETRY;

            controller.pause().resume();
            assertEquals(View.VISIBLE, ScreenTest.find(activity.getWindow().getDecorView(), "取消下载").getVisibility());
            ScreenTest.clickFeature(activity, "换个地址重试");
            long apiId = downloadId();
            assertNotEquals(rawId, apiId);
            assertNull(manager.getRequest(rawId));
            ShadowDownloadManager.ShadowRequest api = shadowOf(manager.getRequest(apiId));
            assertEquals(release.apiDownloadUri(), api.getUri());
            assertTrue(api.getRequestHeaders().contains(new Pair<>("Accept", "application/vnd.github.raw+json")));

            api.setStatus(DownloadManager.STATUS_PAUSED);
            controller.pause().resume();
            api.setStatus(DownloadManager.STATUS_SUCCESSFUL);
            ScreenTest.clickFeature(activity, "换个地址重试");
            assertEquals(apiId, downloadId());
            assertEquals(1, manager.getRequestCount());
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "安装更新"));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(shadows = DownloadManagerWithPauseReason.class)
    public void sourceSwitchKeepsOriginalTaskIfItCompletesDuringReplacementEnqueue() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        ReleaseUpdates.Info release = release();
        DownloadManagerWithPauseReason manager = Shadow.extract(app.getSystemService(DownloadManager.class));
        download.start(release);
        long originalId = downloadId();
        ShadowDownloadManager.ShadowRequest original = shadowOf(manager.getRequest(originalId));
        original.setStatus(DownloadManager.STATUS_PAUSED);
        manager.pauseReason = DownloadManager.PAUSED_WAITING_TO_RETRY;
        ReleaseApkDownload.Status paused = download.status();
        assertEquals(ReleaseApkDownload.State.PAUSED_WAITING_TO_RETRY, paused.state);

        manager.afterEnqueue = () -> original.setStatus(DownloadManager.STATUS_SUCCESSFUL);
        download.retryPaused(release, paused);

        assertNull(manager.afterEnqueue);
        assertEquals(originalId, downloadId());
        assertEquals(1, manager.getRequestCount());
        assertEquals(ReleaseApkDownload.State.READY, download.status().state);
    }

    @Test public void verifiedApkOpensSystemInstallerAndIsRemovedAfterUpgrade() throws Exception {
        ShadowEnvironment.setExternalStorageState(Environment.MEDIA_MOUNTED);
        String packageName = app.getPackageName();
        UpdateApkProvider provider = Robolectric.setupContentProvider(UpdateApkProvider.class);
        shadowOf(app.getPackageManager()).installPackage(packageInfo(packageName, 12, "1.0.0", "aa"));
        ReleaseApkDownload download = new ReleaseApkDownload(app);
        ReleaseUpdates.Info release = release();
        download.start(release);
        long id = downloadId();
        ShadowDownloadManager manager = shadowOf(app.getSystemService(DownloadManager.class));
        shadowOf(manager.getRequest(id)).setStatus(DownloadManager.STATUS_SUCCESSFUL);
        File apk = UpdateApkProvider.apkFile(app, id);
        assertTrue(apk.getParentFile().isDirectory() || apk.getParentFile().mkdirs());
        byte[] contents = {11, 22, 33, 44};
        try (FileOutputStream output = new FileOutputStream(apk)) {
            output.write(contents);
        }
        PackageInfo upgraded = packageInfo(packageName, release.versionCode, release.versionName, "aa");
        shadowOf(app.getPackageManager()).setPackageArchiveInfo(apk.getAbsolutePath(), upgraded);

        Intent installer = download.installIntent();

        assertEquals(Intent.ACTION_INSTALL_PACKAGE, installer.getAction());
        assertEquals("content", installer.getData().getScheme());
        assertEquals(UpdateApkProvider.apkUri(app, id), installer.getData());
        assertEquals(UpdateApkProvider.APK_MIME_TYPE, installer.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, installer.getFlags());
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(provider.openFile(installer.getData(), "r"))) {
            assertArrayEquals(contents, input.readAllBytes());
        }
        assertThrows(IOException.class, () -> provider.openFile(installer.getData(), "rw"));

        shadowOf(app.getPackageManager()).installPackage(upgraded);
        ReleaseApkDownload reopened = new ReleaseApkDownload(app);
        assertEquals(-1, downloadId());
        assertNull(manager.getRequest(id));
        assertFalse(apk.exists());
        assertEquals(ReleaseApkDownload.State.IDLE, reopened.status().state);
    }

    @Test @Config(sdk = {26, 28}) public void verifiedUpgradeMustMatchPackageVersionAndCurrentSigner() throws Exception {
        PackageInfo installed = packageInfo("app.clearscreen", 12, "1.0.0", "aa");
        PackageInfo archive = packageInfo("app.clearscreen", 13, "1.0.0", "aa");
        ReleaseApkDownload.validateArchive(installed, archive, 13, "1.0.0");
        assertThrows(IOException.class, () -> ReleaseApkDownload.validateArchive(installed, archive, 14, "1.0.0"));
        assertThrows(IOException.class, () -> ReleaseApkDownload.validateArchive(installed, archive, 13, "1.0.1"));
        assertThrows(IOException.class, () -> ReleaseApkDownload.validateArchive(archive, installed, 12, "1.0.0"));
        PackageInfo otherApp = packageInfo("app.other", 13, "1.0.0", "aa");
        assertThrows(IOException.class, () -> ReleaseApkDownload.validateArchive(installed, otherApp, 13, "1.0.0"));
        PackageInfo otherSigner = packageInfo("app.clearscreen", 13, "1.0.0", "bb");
        assertThrows(IOException.class, () -> ReleaseApkDownload.validateArchive(installed, otherSigner, 13, "1.0.0"));
    }

    @Test public void installerProviderIsPrivateAndSupportsTemporaryReadGrants() throws Exception {
        ProviderInfo provider = app.getPackageManager().getProviderInfo(
                new ComponentName(app, UpdateApkProvider.class), 0);
        assertEquals(app.getPackageName() + ".release-apk", provider.authority);
        assertFalse(provider.exported);
        assertTrue(provider.grantUriPermissions);
        PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue(java.util.Arrays.asList(info.requestedPermissions).contains(Manifest.permission.REQUEST_INSTALL_PACKAGES));
    }

    private long downloadId() {
        return app.getSharedPreferences("release-apk-download", Context.MODE_PRIVATE).getLong("id", -1);
    }

    private static ReleaseUpdates.Info release() throws IOException {
        return ReleaseUpdates.parse(FEED);
    }

    private static PackageInfo packageInfo(String packageName, int code, String name, String signer) {
        PackageInfo result = new PackageInfo();
        result.packageName = packageName;
        result.versionCode = code;
        result.versionName = name;
        Signature[] signatures = {new Signature(signer)};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.signingInfo = new SigningInfo();
            shadowOf(result.signingInfo).setSignatures(signatures);
        } else {
            result.signatures = signatures;
        }
        return result;
    }

    @Implements(DownloadManager.class)
    public static class DownloadManagerWithPauseReason extends ShadowDownloadManager {
        private static final int REASON_COLUMN = 100;
        int pauseReason = DownloadManager.PAUSED_UNKNOWN;
        Runnable afterEnqueue;

        @Override @Implementation protected long enqueue(DownloadManager.Request request) {
            long id = super.enqueue(request);
            if (afterEnqueue != null) {
                Runnable action = afterEnqueue;
                afterEnqueue = null;
                action.run();
            }
            return id;
        }

        @Override @Implementation protected Cursor query(DownloadManager.Query query) {
            return new CursorWrapper(super.query(query)) {
                @Override public int getColumnIndex(String name) {
                    return DownloadManager.COLUMN_REASON.equals(name) ? REASON_COLUMN : super.getColumnIndex(name);
                }

                @Override public int getInt(int column) {
                    return column == REASON_COLUMN ? pauseReason : super.getInt(column);
                }
            };
        }
    }
}
