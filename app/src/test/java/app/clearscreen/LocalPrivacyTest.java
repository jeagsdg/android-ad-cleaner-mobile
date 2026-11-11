package app.clearscreen;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocalPrivacyTest {
    private final Context context = RuntimeEnvironment.getApplication();

    @Test public void foregroundUpdateDefaultFollowsVariantAndHonorsOptOut() {
        Store store = new Store(context);
        boolean full = context.getResources().getBoolean(R.bool.network_filter_available);
        assertEquals(full, store.updateChecksEnabled());
        store.setUpdateChecksEnabled(false);
        assertFalse(new Store(context).updateChecksEnabled());
        store.setUpdateChecksEnabled(true);
        assertEquals(full, new Store(context).updateChecksEnabled());
    }

    @Test public void skipDetailsRequireOptInAndClearIndependentlyOfFeedback() {
        Store store = new Store(context);
        assertTrue(store.skipToastEnabled());
        assertFalse(store.keepsSkipDetails());
        store.recordSplash("com.example.test", true);
        assertEquals(1, store.splashCount());
        assertEquals(0, store.read("splash-history").length());

        store.setKeepSkipDetails(true);
        store.recordSplash("com.example.test", true);
        store.recordPopup("com.example.test", false);
        assertEquals(1, store.read("splash-history").length());
        assertEquals(1, store.read("popup-history").length());

        new Store(context).setKeepSkipDetails(false);
        store.recordPopup("com.example.test", true);
        assertFalse(store.keepsSkipDetails());
        assertEquals(0, store.read("splash-history").length());
        assertEquals(0, store.read("popup-history").length());
        assertEquals(2, store.splashCount());
        assertEquals(1, store.popupCount());
        assertTrue(store.skipToastEnabled());
    }

    @Test public void existingLocalHistoryRemainsVisibleUntilUserTurnsItOff() throws Exception {
        Store store = new Store(context);
        store.save("splash-history", new org.json.JSONArray().put(new org.json.JSONObject()
                .put("package", "com.example.test").put("confirmed", true)));
        assertTrue(store.keepsSkipDetails());
        store.setKeepSkipDetails(false);
        assertFalse(new Store(context).keepsSkipDetails());
        assertEquals(0, store.read("splash-history").length());
    }

    @Test public void domainDetailsCanBeDisabledWhileCountersKeepWorking() {
        FilterStore filter = new FilterStore(context);
        assertFalse(filter.keepsDomainDetails());
        filter.counters(2, 1, Collections.singletonList("ads.example.com"));
        assertTrue(filter.recent().isEmpty());

        filter.setKeepDomainDetails(true);
        filter.counters(3, 2, Collections.singletonList("ads.example.com"));
        assertEquals(Collections.singletonList("ads.example.com"), filter.recent());
        AdFilterService.setKeepDomainDetails(context, false);
        filter.counters(4, 3, Collections.singletonList("another.example.com"));
        assertTrue(filter.recent().isEmpty());
        assertEquals(3, filter.blocked());
    }

    @Test public void localDnsExceptionsPersistAndProtectedHostsCannotBeAdded() {
        FilterStore filter = new FilterStore(context);
        String ad = "ads.example.com";
        filter.allow(ad, true);
        assertFalse(filter.blockHost("connect.rom.miui.com", false));
        assertFalse(filter.blockHost("*.example.com", false));
        assertTrue(filter.blockHost(ad, true));
        assertFalse(filter.allowsHost(ad));
        assertTrue(filter.customHost(ad));
        assertFalse(filter.customHost("sub." + ad));
        filter.setBypassedApps(Collections.singleton("com.example.privateapp"));
        assertEquals(Collections.singleton("com.example.privateapp"), new FilterStore(context).bypassedApps());
        filter.removeCustomHost(ad);
        assertFalse(filter.customHost(ad));
    }

    @Test public void rulePreviewKeepsItsDraftScopedToTheSelectedApp() {
        RulePreview preview = new RulePreview();
        CustomSkipRule draft = new CustomSkipRule("com.example.target", "", CustomSkipRule.SPLASH, "", "跳过", "");
        assertTrue(preview.start(Collections.singletonList(draft)));
        assertFalse(preview.observes("com.example.unrelated"));
        java.util.List<CustomSkipRule> selected = preview.rulesFor(draft.pkg);
        preview.note(selected, 0, SkipDiagnostics.Phase.TARGET_FOUND);
        assertTrue(preview.summary(true).contains("规则匹配通过"));
        preview.clear();
        assertTrue(preview.rulesFor(draft.pkg).isEmpty());
        assertEquals("", preview.summary(true));
    }

    @Test public void launchTraceOnlyTracksSelectedPackageAndExpiresInMemory() {
        AtomicLong elapsed = new AtomicLong(100);
        SkipDiagnostics trace = new SkipDiagnostics(elapsed::get);
        assertEquals("", trace.summary(true, true));
        trace.start("com.example.target", 42);
        assertTrue(trace.summary(false, true).contains("服务未连接"));
        trace.enter("com.example.unrelated", 1);
        assertFalse(trace.observes("com.example.unrelated", 1));
        trace.enter("com.example.target", 2);
        trace.note("com.example.target", 2, SkipDiagnostics.Phase.TARGET_FOUND);
        trace.note("com.example.target", 2, SkipDiagnostics.Phase.SENT);
        trace.note("com.example.target", 2, SkipDiagnostics.Phase.CANCELLED);
        trace.note("com.example.target", 2, SkipDiagnostics.Phase.CONFIRMED);
        String report = trace.summary(true, true);
        assertTrue(report.contains("系统取消了手势"));
        assertTrue(report.contains("找到目标"));
        assertTrue(report.contains("应用版本号 42"));
        assertFalse(report.contains("com.example.unrelated"));
        elapsed.addAndGet(SkipDiagnostics.SESSION_MS);
        assertEquals("", trace.summary(true, true));
    }
}
