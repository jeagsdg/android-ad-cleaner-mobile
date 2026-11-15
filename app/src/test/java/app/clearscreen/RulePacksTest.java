package app.clearscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RulePacksTest {
    private static final String APP = "com.byfen.market";
    private static final String ACTIVITY = "com.byfen.market.ui.activity.MainActivity";
    @Rule public TemporaryFolder directory = new TemporaryFolder();
    private RulePack bundled;
    private byte[] official;
    private int clientVersion;

    @SuppressWarnings("deprecation")
    @Before public void loadPublishedSnapshot() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        bundled = RulePack.bundled(new Store(app));
        clientVersion = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        File project = new File(System.getProperty("clearscreen.artifacts")).getParentFile();
        official = Files.readAllBytes(new File(project, "0-update/rules.json").toPath());
    }

    @Test public void shippedFeedMatchesTheReleaseCertificateAndClient() throws Exception {
        RulePack release = verify(official);
        assertTrue(release.version > 0);
        assertThrows(IOException.class, () -> RulePacks.verify(official, bundled, 0, RulePack.CERTIFICATE_SHA256));
        assertThrows(IOException.class, () -> RulePacks.verify(official, bundled, clientVersion, "wrong certificate"));
        assertThrows(IOException.class, () -> verify(new byte[RulePack.MAX_ENVELOPE_BYTES + 1]));

        JSONObject wrapper = new JSONObject(new String(official, StandardCharsets.UTF_8));
        JSONObject payload = new JSONObject(new String(Base64.decode(wrapper.getString("payload"), Base64.NO_WRAP),
                StandardCharsets.UTF_8));
        payload.put("version", release.version == 1 ? 2 : 1);
        wrapper.put("payload", Base64.encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        assertThrows(IOException.class, () -> verify(wrapper.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void acceptedFeedSurvivesRestartFailureAndRollbackBlocksReplay() throws Exception {
        File storage = new File(directory.getRoot(), "rules.bin");
        RulePacks store = new RulePacks(storage, bundled, decoder -> decoder.verify(official), this::verify);
        assertEquals(RulePacks.Refresh.UPDATED, store.refresh());
        assertEquals(RulePacks.Refresh.CURRENT, store.refresh());
        long accepted = store.state().version;
        assertTrue(accepted > 0);
        assertTrue(store.state().canRollback);

        RulePacks restarted = new RulePacks(storage, bundled, decoder -> decoder.verify(new byte[32]), this::verify);
        assertEquals(accepted, restarted.state().version);
        assertEquals(RulePacks.Refresh.FAILED, restarted.refresh());
        assertEquals(accepted, restarted.state().version);
        assertTrue(restarted.rollback());
        assertEquals(0, restarted.state().version);
        assertEquals(accepted, restarted.state().newestVersion);
        RulePacks replay = new RulePacks(storage, bundled, decoder -> decoder.verify(official), this::verify);
        assertEquals(RulePacks.Refresh.ROLLED_BACK, replay.refresh());
        assertEquals(0, replay.state().version);
    }

    @Test public void individualSelectionChangesMatcherAndSurvivesOfflineSwitch() {
        File storage = new File(directory.getRoot(), "rules.bin");
        RulePacks online = new RulePacks(storage, bundled, decoder -> decoder.verify(official), this::verify);
        RulePacks.CatalogRule rule = popupEntry(online);
        assertTrue(online.state().popupRules.hasRules(APP, ACTIVITY));
        assertTrue(online.setRuleEnabled(rule.id, false));
        assertFalse(popupEntry(online).enabled);
        assertFalse(online.state().popupRules.hasRules(APP, ACTIVITY));
        assertEquals(RulePacks.Refresh.UPDATED, online.refresh());
        long accepted = online.state().version;

        RulePacks offline = new RulePacks(storage, bundled, null, this::verify);
        assertEquals(RulePacks.Refresh.UNAVAILABLE, offline.refresh());
        assertEquals(0, offline.state().version);
        assertFalse(popupEntry(offline).enabled);
        assertTrue(offline.setRuleEnabled(rule.id, true));

        RulePacks again = new RulePacks(storage, bundled, decoder -> decoder.verify(official), this::verify);
        assertEquals(accepted, again.state().version);
        assertTrue(again.rollback());
        assertTrue(popupEntry(again).enabled);
        assertTrue(again.state().popupRules.hasRules(APP, ACTIVITY));
    }

    private RulePack verify(byte[] bytes) throws IOException {
        return RulePacks.verify(bytes, bundled, clientVersion, RulePack.CERTIFICATE_SHA256);
    }

    private static RulePacks.CatalogRule popupEntry(RulePacks store) {
        return store.state().rules.stream().filter(row -> APP.equals(row.packageName) && !row.splash).findFirst().get();
    }
}
