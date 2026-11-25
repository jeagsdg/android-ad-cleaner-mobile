package app.clearscreen;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
    private byte[] legacy;
    private int clientVersion;

    @SuppressWarnings("deprecation")
    @Before public void loadPublishedSnapshot() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        bundled = RulePack.bundled(new Store(app));
        clientVersion = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionCode;
        File project = new File(System.getProperty("clearscreen.artifacts")).getParentFile();
        official = Files.readAllBytes(new File(project, RulePack.OFFICIAL_PATH).toPath());
        legacy = Files.readAllBytes(new File(project, "0-update/rules.json").toPath());
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

    @Test public void upgradingFromTheOlderFeedKeepsBundledAdditionsAndRuleToggles() throws Exception {
        RulePack release = verify(official);
        RulePack prior = RulePacks.verify(legacy, bundled, 21, RulePack.CERTIFICATE_SHA256);
        assertEquals(3, prior.version);
        assertTrue(release.version > prior.version);
        assertTrue(release.entries.size() > prior.entries.size());
        List<String> bundledIds = bundled.entries.stream().map(row -> row.id).collect(Collectors.toList());
        assertEquals(bundledIds, release.entries.stream().map(row -> row.id).collect(Collectors.toList()));
        Set<String> previousIds = prior.entries.stream().map(row -> row.id).collect(Collectors.toSet());
        assertEquals(Set.of("https://i.gkd.li/i/26328668", "https://i.gkd.li/i/23097140",
                        "https://i.gkd.li/i/19526000", "https://i.gkd.li/i/14322017"),
                release.entries.stream().filter(row -> !previousIds.contains(row.id))
                        .map(row -> row.sourceUrl).collect(Collectors.toSet()));
        assertThrows(IOException.class, () -> verify(legacy));
        assertThrows(IOException.class, () -> RulePacks.verify(official, bundled, 21, RulePack.CERTIFICATE_SHA256));

        File storage = new File(directory.getRoot(), "history.bin");
        RulePacks older = new RulePacks(storage, bundled, decoder -> decoder.verify(legacy),
                bytes -> RulePacks.verify(bytes, bundled, 21, RulePack.CERTIFICATE_SHA256));
        assertEquals(RulePacks.Refresh.UPDATED, older.refresh());
        String disabled = popupEntry(older).id;
        assertTrue(older.setRuleEnabled(disabled, false));

        RulePacks upgraded = new RulePacks(storage, bundled, decoder -> decoder.verify(official), this::verify);
        assertEquals(0, upgraded.state().version);
        assertEquals(bundledIds.size(), upgraded.state().rules.size());
        assertFalse(popupEntry(upgraded).enabled);
        assertEquals(RulePacks.Refresh.UPDATED, upgraded.refresh());
        assertEquals(release.version, upgraded.state().version);
        assertEquals(bundledIds.size(), upgraded.state().rules.size());
        assertFalse(popupEntry(upgraded).enabled);
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

    @Test public void upgradingFromVersions22Through24KeepsThePublishedFeedAndDisabledRules() throws IOException {
        assertThrows(IOException.class, () -> RulePacks.verify(official, bundled, 26, RulePack.CERTIFICATE_SHA256));
        for (int previousVersion : new int[]{22, 23, 24}) {
            File storage = new File(directory.getRoot(), "version-" + previousVersion + ".bin");
            RulePacks.Feed feed = decoder -> decoder.verify(official);
            RulePacks installed = new RulePacks(storage, bundled, feed,
                    bytes -> RulePacks.verify(bytes, bundled, previousVersion, RulePack.CERTIFICATE_SHA256));
            assertEquals(RulePacks.Refresh.UPDATED, installed.refresh());
            assertEquals(5, installed.state().version);
            assertTrue(installed.setRuleEnabled(popupEntry(installed).id, false));

            RulePacks upgraded = new RulePacks(storage, bundled, feed, this::verify);
            assertEquals(5, upgraded.state().version);
            assertFalse(popupEntry(upgraded).enabled);
            assertEquals(RulePacks.Refresh.CURRENT, upgraded.refresh());
            assertTrue(upgraded.rollback());

            RulePacks restarted = new RulePacks(storage, bundled, feed, this::verify);
            assertEquals(RulePacks.Refresh.ROLLED_BACK, restarted.refresh());
            assertFalse(popupEntry(restarted).enabled);
        }
    }

    private RulePack verify(byte[] bytes) throws IOException {
        return RulePacks.verify(bytes, bundled, clientVersion, RulePack.CERTIFICATE_SHA256);
    }

    private static RulePacks.CatalogRule popupEntry(RulePacks store) {
        return store.state().rules.stream().filter(row -> APP.equals(row.packageName) && !row.splash).findFirst().get();
    }
}
