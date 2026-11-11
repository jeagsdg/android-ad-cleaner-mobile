package app.clearscreen;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDownloadManager;
import org.robolectric.shadows.ShadowEnvironment;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReleaseApkDownloadTest {
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

        reopened.cancel();
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
        return ReleaseUpdates.parse("{\"schema\":1,\"versionCode\":100,\"versionName\":\"9.9.9\",\"notes\":\"\"}");
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
}
