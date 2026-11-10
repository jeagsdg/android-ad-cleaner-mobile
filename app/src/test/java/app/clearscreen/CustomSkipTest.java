package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CustomSkipTest {
    private static final String PKG = "test.app";
    private static final String PROMPT = "有新版本可用";
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2400);
    private static final CustomSkipRule POPUP = new CustomSkipRule(PKG, "", "popup", "", "以后再说", PROMPT);

    @Test public void localRulesRoundTripAndRejectUnknownActionsOrExclusions() {
        String json = CustomSkipRule.export(List.of(POPUP));
        assertEquals(json, CustomSkipRule.export(CustomSkipRule.parse(json)));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.parse(json + "{}"));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.parse(json.replace("\"mode\"", "\"action\"")));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.parse(json.replace("\"rules\"", "\"whitelist\": [], \"rules\"")));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.parse(CustomSkipRule.export(List.of(POPUP, POPUP))));
        assertThrows(IllegalArgumentException.class, () -> new CustomSkipRule("com.android.settings", "", "popup", "", "以后再说", PROMPT));
        assertThrows(IllegalArgumentException.class, () -> new CustomSkipRule(PKG, "", "popup", "", "同意", PROMPT));
    }

    @Test public void rejectedRuleBatchLeavesPreviousRulesAndHistorySeparate() {
        Store first = new Store(RuntimeEnvironment.getApplication());
        Store second = new Store(RuntimeEnvironment.getApplication());
        assertFalse(first.popupEnabled());
        assertTrue(second.customSkipRules().isEmpty());
        first.saveCustomSkipRules(List.of(POPUP));
        assertThrows(IllegalArgumentException.class, () -> first.saveCustomSkipRules(List.of(POPUP, POPUP)));
        assertEquals(1, second.customSkipRules().size());
        first.setKeepSkipDetails(true);
        first.recordPopup(PKG, true);
        assertEquals(1L, second.popupCount());
        assertEquals(0L, second.splashCount());
        assertEquals(1, second.read("popup-history").length());
        assertEquals(0, second.read("splash-history").length());
    }

    @Test public void customLaunchRulesKeepPackageActivityAndCornerGuards() {
        CustomSkipRule custom = new CustomSkipRule(PKG, "test.app.Start", "splash", "test.app:id/skip_custom", "略过", "");
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo button = node("略过", true, new Rect(825, 145, 1010, 210));
        button.setViewIdResourceName("test.app:id/skip_custom");
        add(root, button);
        assertNull(SplashSkipper.match(root, SplashRules.EMPTY, "", List.of(custom)));
        assertEquals(button, SplashSkipper.match(root, SplashRules.EMPTY, "test.app.Start", List.of(custom)));
        button.setText("同意");
        assertNull(SplashSkipper.match(root, SplashRules.EMPTY, "test.app.Start", List.of(custom)));
    }

    @Test public void popupRequiresPromptAndButtonInTheSamePanel() {
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(80, 860, 1000, 1510));
        add(root, panel);
        AccessibilityNodeInfo button = node("以后再说", true, new Rect(430, 1320, 680, 1440));
        add(panel, button);
        add(root, node(PROMPT, false, new Rect(80, 450, 1000, 540)));
        assertNull(PopupMatch.find(root, SCREEN, List.of(POPUP)));
        add(panel, node(PROMPT, false, new Rect(80, 920, 1000, 1040)));
        assertEquals(button, PopupMatch.find(root, SCREEN, List.of(POPUP)).target);
        add(panel, node("以后再说", true, new Rect(710, 1320, 950, 1440)));
        assertNull(PopupMatch.find(root, SCREEN, List.of(POPUP)));
    }

    @Test public void advancedSequencePersistsObservationAndBoundedCloseTogether() {
        String page = PKG + ".MainActivity";
        CustomSkipRule wait = CustomSkipRule.advanced(PKG, page, CustomSkipRule.POPUP, "", "~检查版本",
                false, 0, false, 5, 0, 0, null);
        CustomSkipRule close = CustomSkipRule.advanced(PKG, page, CustomSkipRule.POPUP, "关闭广告",
                PKG + ":id/ad_title", false, 120, true, 3, 2, 1, null);
        List<CustomSkipRule> saved = CustomSkipRule.parse(CustomSkipRule.export(List.of(wait, close)));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.parse(CustomSkipRule.export(List.of(wait))));
        assertEquals(0, saved.get(0).times);
        assertEquals(5, saved.get(0).searches);
        assertEquals(120, saved.get(1).delayMs);
        assertEquals(2, saved.get(1).times);
        assertTrue(saved.get(1).gesture);

        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(80, 860, 1000, 1510));
        add(root, panel);
        add(panel, node("正在检查版本", false, new Rect(80, 920, 1000, 1040)));
        PopupMatch observed = PopupMatch.find(root, SCREEN, List.of(saved.get(0)));
        assertNotNull(observed);
        assertNull(observed.target);

        AccessibilityNodeInfo title = node("今日广告", false, new Rect(80, 1100, 1000, 1230));
        title.setViewIdResourceName(PKG + ":id/ad_title");
        add(panel, title);
        AccessibilityNodeInfo button = node("关闭广告并购买", true, new Rect(430, 1320, 680, 1440));
        add(panel, button);
        assertNull(PopupMatch.find(root, SCREEN, List.of(saved.get(1))));
        button.setText("关闭广告");
        assertEquals(button, PopupMatch.find(root, SCREEN, List.of(saved.get(1))).target);
    }

    @Test public void advancedEditingRequiresPageForBoundsAndRejectsSensitiveTargets() {
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.advanced(PKG, "", CustomSkipRule.SPLASH,
                "825,145,1010,210", "", false, 0, true, 1, 1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.advanced(PKG, "", CustomSkipRule.POPUP,
                "+立即购买", "今日活动", false, 0, false, 1, 1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.advanced(PKG, "", CustomSkipRule.SPLASH,
                "x:900y:170", "", false, 0, true, 1, 1, -1, null));
        assertThrows(IllegalArgumentException.class, () -> CustomSkipRule.advanced(PKG, "", CustomSkipRule.SPLASH,
                "关闭广告", "", false, 6000, true, 1, 1, -1, null));

        String page = PKG + ".Start";
        CustomSkipRule scoped = CustomSkipRule.advanced(PKG, page, CustomSkipRule.SPLASH, "825, 145, 1010, 210",
                "", false, 0, true, 1, 1, -1, null);
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo button = node("跳过", true, new Rect(825, 145, 1011, 210));
        add(root, button);
        assertNull(SplashSkipper.match(root, SplashRules.EMPTY, page, List.of(scoped)));
        button.setBoundsInScreen(new Rect(825, 145, 1010, 210));
        assertNull(SplashSkipper.match(root, SplashRules.EMPTY, PKG + ".Other", List.of(scoped)));
        assertEquals(button, SplashSkipper.match(root, SplashRules.EMPTY, page, List.of(scoped)));
    }

    @Test public void popupPanelWithSensitiveContentStaysVisible() {
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(80, 860, 1000, 1510));
        add(root, panel);
        add(panel, node("以后再说", true, new Rect(430, 1320, 680, 1440)));
        add(panel, node(PROMPT, false, new Rect(80, 920, 1000, 1040)));
        add(panel, node("请阅读隐私协议", false, new Rect(80, 1060, 1000, 1160)));
        assertNull(PopupMatch.find(root, SCREEN, List.of(POPUP)));
    }

    @Test public void genericAdNeedsExplicitCloseAndVisibleAdMarkerInOnePanel() {
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(80, 860, 1000, 1510));
        AccessibilityNodeInfo button = node("关闭", true, new Rect(430, 1320, 680, 1440));
        add(root, panel);
        add(panel, button);
        add(root, node("广告", false, new Rect(80, 400, 1000, 500)));
        assertNull(PopupMatch.findGenericAd(root, SCREEN));
        button.setContentDescription("关闭广告");
        assertNull(PopupMatch.findGenericAd(root, SCREEN));
        add(panel, node("广告", false, new Rect(80, 920, 1000, 1040)));
        assertEquals(button, PopupMatch.findGenericAd(root, SCREEN).target);
        AccessibilityNodeInfo description = node("视频", false, new Rect(80, 1070, 1000, 1190));
        description.setContentDescription("Watch the video to earn rewards");
        description.setVisibleToUser(false);
        add(root, description);
        assertNull(PopupMatch.findGenericAd(root, SCREEN));
    }

    @Test public void genericAdPanelCanBeCenteredWithinASplitScreenApp() {
        Rect display = new Rect(0, 0, 2200, 1600);
        AccessibilityNodeInfo root = node("", false, new Rect(1100, 0, 2200, 1600));
        AccessibilityNodeInfo panel = node("", false, new Rect(1370, 470, 1930, 1060));
        AccessibilityNodeInfo button = node("Close ad", true, new Rect(1640, 890, 1900, 1000));
        add(root, panel);
        add(panel, node("Ad", false, new Rect(1400, 520, 1900, 660)));
        add(panel, button);
        assertEquals(button, PopupMatch.findGenericAd(root, display).target);
    }

    @Test public void genericAdAcceptsAnAccessibleBadgeFromTheSameApplication() {
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(80, 860, 1000, 1510));
        AccessibilityNodeInfo button = node("关闭广告", true, new Rect(430, 1320, 680, 1440));
        AccessibilityNodeInfo badge = node("", false, new Rect(80, 920, 1000, 1040));
        badge.setContentDescription("广告");
        add(root, panel);
        add(panel, button);
        add(panel, badge);
        assertEquals(button, PopupMatch.findGenericAd(root, SCREEN).target);
        badge.setPackageName("other.app");
        assertNull(PopupMatch.findGenericAd(root, SCREEN));
        badge.setPackageName(PKG);
        button.setContentDescription("立即购买");
        assertNull(PopupMatch.findGenericAd(root, SCREEN));
    }

    @Test public void importedExactIdsCanCloseAnEdgeCardOnlyWithLocalAnchor() {
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(790, 1600, 1060, 2030));
        AccessibilityNodeInfo marker = node("", false, new Rect(800, 1640, 1040, 1880));
        AccessibilityNodeInfo close = node("", true, new Rect(965, 1900, 1035, 1970));
        marker.setViewIdResourceName(PKG + ":id/banner_anchor");
        close.setViewIdResourceName(PKG + ":id/banner_close");
        add(root, panel);
        add(panel, close);
        CustomSkipRule legacy = new CustomSkipRule(PKG, CustomSkipRule.POPUP,
                "banner_close", "banner_anchor", false, 0, false, 1);
        add(root, marker);
        assertNull(PopupMatch.find(root, SCREEN, List.of(legacy)));
        AccessibilityNodeInfo localMarker = node("", false, new Rect(800, 1640, 1040, 1880));
        localMarker.setViewIdResourceName(PKG + ":id/banner_anchor");
        add(panel, localMarker);
        assertEquals(close, PopupMatch.find(root, SCREEN, List.of(legacy)).target);
        localMarker.setContentDescription("确认付款");
        assertNull(PopupMatch.find(root, SCREEN, List.of(legacy)));
    }

    @Test public void tongchengBuiltInPresetRequiresItsHomePageAndAdContainer() {
        String pkg = "com.tongcheng.android";
        String home = pkg + ".TongchengMainActivity";
        AccessibilityNodeInfo root = node("", false, SCREEN);
        AccessibilityNodeInfo panel = node("", false, new Rect(90, 850, 980, 1530));
        AccessibilityNodeInfo close = node("", true, new Rect(780, 1310, 870, 1400));
        AccessibilityNodeInfo ad = node("", false, new Rect(150, 880, 910, 1250));
        for (AccessibilityNodeInfo part : List.of(root, panel, close, ad)) part.setPackageName(pkg);
        add(root, panel);
        add(panel, close);
        close.setViewIdResourceName(pkg + ":id/iv_home_ad_close");
        ad.setViewIdResourceName(pkg + ":id/home_ad_dialog_pager");
        List<CustomSkipRule> homeRules = new java.util.ArrayList<>();
        for (CustomSkipRule rule : PopupMatch.APP_PRESETS) {
            assertFalse(rule.appliesTo(pkg, pkg + ".OrderActivity", CustomSkipRule.POPUP));
            if (rule.appliesTo(pkg, home, CustomSkipRule.POPUP)) homeRules.add(rule);
        }
        assertNull(PopupMatch.find(root, SCREEN, homeRules));
        add(panel, ad);
        assertEquals(close, PopupMatch.find(root, SCREEN, homeRules).target);
    }

    private static AccessibilityNodeInfo node(String label, boolean clickable, Rect bounds) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(PKG);
        node.setText(label);
        node.setBoundsInScreen(bounds);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setClickable(clickable);
        return node;
    }

    private static void add(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        shadowOf(parent).addChild(child);
    }
}
