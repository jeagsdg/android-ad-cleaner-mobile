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

    @Test public void clearingLegacyDnsHistoryPreservesDomainDetailsAndCounters() {
        context.getSharedPreferences("network-filter", Context.MODE_PRIVATE).edit()
                .remove("keep-domain-details")
                .putString("recent", "old-ads.example.com")
                .putLong("queried", 3)
                .putLong("blocked", 2)
                .apply();
        FilterStore filter = new FilterStore(context);
        assertTrue(filter.keepsDomainDetails());
        assertEquals(Collections.singletonList("old-ads.example.com"), filter.recent());

        filter.clearHistory();

        assertTrue(filter.recent().isEmpty());
        assertTrue(filter.keepsDomainDetails());
        assertEquals(3, filter.queried());
        assertEquals(2, filter.blocked());

        filter.counters(4, 3, Collections.singletonList("new-ads.example.com"));
        assertEquals(Collections.singletonList("new-ads.example.com"), new FilterStore(context).recent());
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
        Object owner = new Object();
        assertNull(trace.begin("com.example.unrelated", owner));
        SkipDiagnostics.Attempt attempt = trace.begin("com.example.target", owner);
        trace.note("com.example.target", attempt, SkipDiagnostics.Phase.TARGET_FOUND);
        trace.note("com.example.target", attempt, SkipDiagnostics.Phase.SENT);
        trace.note("com.example.target", attempt, SkipDiagnostics.Phase.CANCELLED);
        trace.note("com.example.target", attempt, SkipDiagnostics.Phase.CONFIRMED);
        String report = trace.summary(true, true);
        assertTrue(report.startsWith("净屏开屏诊断\n系统取消了手势\n最近检测 找到目标，等待操作\n应用 "));
        assertTrue(report.contains("应用版本号 42"));
        assertFalse(report.contains("com.example.unrelated"));
        elapsed.addAndGet(SkipDiagnostics.SESSION_MS);
        assertEquals("", trace.summary(true, true));
    }

    @Test public void launchTraceIsolatesLateReadbackAndServiceDisconnects() {
        String pkg = "com.example.target";
        SkipDiagnostics trace = new SkipDiagnostics(() -> 100L);
        Object oldService = new Object(), newService = new Object();
        trace.start(pkg, 42);
        SkipDiagnostics.Attempt first = trace.begin(pkg, oldService);
        trace.note(pkg, first, SkipDiagnostics.Phase.SENT);
        SkipDiagnostics.Attempt second = trace.begin(pkg, newService);
        trace.note(pkg, first, SkipDiagnostics.Phase.CONFIRMED);
        trace.note(pkg, second, SkipDiagnostics.Phase.SENT);
        trace.disconnected(oldService);
        assertTrue(trace.summary(true, true).startsWith("净屏开屏诊断\n" + SkipDiagnostics.Phase.SENT.label));
        assertFalse(trace.summary(true, true).contains("CONFIRMED"));

        trace.disconnected(newService);
        trace.note(pkg, second, SkipDiagnostics.Phase.CONFIRMED);
        String report = trace.summary(false, true);
        assertTrue(report.startsWith("净屏开屏诊断\n" + SkipDiagnostics.Phase.SERVICE_DISCONNECTED.label));
        assertFalse(report.contains("CONFIRMED"));
    }
}
