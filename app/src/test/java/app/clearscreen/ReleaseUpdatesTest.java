package app.clearscreen;

import android.net.Uri;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ReleaseUpdatesTest {
    private static final String VALID = "{\"schema\":1,\"versionCode\":11,\"versionName\":\"0.9.0\","
            + "\"notes\":\"扩充规则\\n支持手动检查更新\"}";

    @Test public void acceptsStableMetadataAndDerivesTheRepositoryApk() throws IOException {
        ReleaseUpdates.Info info = ReleaseUpdates.parse(VALID);
        assertEquals(11, info.versionCode);
        assertEquals("0.9.0", info.versionName);
        assertEquals("扩充规则\n支持手动检查更新", info.notes);
        Uri download = info.rawDownloadUri();
        assertEquals("https", download.getScheme());
        assertEquals("raw.githubusercontent.com", download.getHost());
        assertEquals("/jeagsdg/android-ad-cleaner-mobile/main/dist/ClearScreen-0.9.0.apk",
                download.getPath());
        Uri api = info.apiDownloadUri();
        assertEquals("api.github.com", api.getHost());
        assertEquals("/repos/jeagsdg/android-ad-cleaner-mobile/contents/dist/ClearScreen-0.9.0.apk", api.getPath());
        assertEquals("main", api.getQueryParameter("ref"));
    }

    @Test public void rejectsMalformedOrSubstitutedMetadata() {
        String[] invalid = {
                VALID.replace("\"schema\":1", "\"schema\":2"),
                VALID.replace("\"versionCode\":11", "\"versionCode\":\"11\""),
                VALID.replace("\"versionCode\":11", "\"versionCode\":0"),
                VALID.replace("\"versionCode\":11", "\"versionCode\":11.0"),
                VALID.replace("\"versionCode\":11", "\"versionCode\":9999999999"),
                VALID.replace("0.9.0", "0.9.0-preview"),
                VALID.replace("0.9.0", "0.9.0/../untrusted"),
                VALID.replace("0.9.0", "0.09.0"),
                VALID.replace("\"notes\":", "\"downloadUrl\":"),
                VALID.replace("\"notes\":", "\"versionName\":\"0.9.1\",\"notes\":"),
                VALID.replace("扩充规则", "x".repeat(1201)),
                VALID + "{}",
        };
        for (String candidate : invalid) {
            assertThrows(candidate, IOException.class, () -> ReleaseUpdates.parse(candidate));
        }
    }
}
