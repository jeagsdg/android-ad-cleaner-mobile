package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SplashRulesCoverageTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2400);
    private static final Rect CORNER = new Rect(820, 90, 1000, 170);
    private final SplashRules rules = new SplashRules(RuntimeEnvironment.getApplication());

    @Test public void structuralRuleKeepsChildPositionsScopeAndTime() {
        String pkg = "com.coolapk.market";
        String activity = pkg + ".view.main.MainActivity";
        AccessibilityNodeInfo root = frame(pkg, pkg + ":id/ad_container");
        AccessibilityNodeInfo group = add(root, frame(pkg, null));
        add(group, frame(pkg, null));
        AccessibilityNodeInfo row = add(group, frame(pkg, null));
        AccessibilityNodeInfo wrongSlot = add(row, button(pkg, "android.view.View", null));
        AccessibilityNodeInfo skip = add(row, button(pkg, "android.view.View", null));

        assertFalse(rules.matches(pkg, activity, wrongSlot));
        assertTrue(rules.matches(pkg, activity, skip, 9999));
        assertFalse(rules.matches(pkg, activity, skip, 10000));
        assertFalse(rules.matches(pkg, pkg + ".view.feed.TestActivity", skip));
        assertFalse(rules.matches(pkg, "", skip));
        assertFalse(rules.matches("other.app", activity, skip));
        root.setViewIdResourceName(pkg + ":id/normal_content");
        assertFalse(rules.matches(pkg, activity, skip));
    }

    @Test public void markedMiddleNodeRequiresTheSiblingAndContainer() {
        String pkg = "com.cyl.musiccy.ou";
        AccessibilityNodeInfo root = frame(pkg, pkg + ":id/ksad_splash_root_container");
        AccessibilityNodeInfo row = add(root, frame(pkg, null));
        AccessibilityNodeInfo bar = add(row, node(pkg, "android.widget.TextView", null, false, CORNER));
        AccessibilityNodeInfo skip = add(row, button(pkg, "android.widget.ImageView", null));
        add(row, frame(pkg, null));
        bar.setText("|");

        assertTrue(rules.matches(pkg, "", skip));
        assertFalse(rules.matches(pkg, "", bar));
        bar.setText("ordinary content");
        assertFalse(rules.matches(pkg, "", skip));
        bar.setText("|");
        add(row, frame(pkg, null));
        assertFalse(rules.matches(pkg, "", skip));
    }

    @Test public void precedingSiblingAnchorSelectsTheImageOnly() {
        String pkg = "com.qq.qcloud";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo marker = add(root, node(pkg, "android.widget.TextView", pkg + ":id/gdt_ad_text", false, CORNER));
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.widget.ImageView", null));

        assertTrue(rules.matches(pkg, "", skip));
        marker.setVisibleToUser(false);
        assertFalse(rules.matches(pkg, "", skip));
    }

    @Test public void indexedDescendantCanBeMatchedFromTheAncestor() throws Exception {
        String pkg = "test.app";
        AccessibilityNodeInfo root = frame(pkg, null);
        add(root, frame(pkg, null)).setVisibleToUser(false);
        AccessibilityNodeInfo branch = add(root, frame(pkg, null));
        AccessibilityNodeInfo label = add(branch, node(pkg, "android.widget.TextView", null, false, CORNER));
        JSONObject selector = new JSONObject(
                "{\"path\":[{\"class\":\"TextView\",\"tests\":[]},{\"class\":\"FrameLayout\",\"tests\":[]}],"
                        + "\"relations\":[[\"<<\",[3]]],\"target\":1}");

        assertTrue(SplashRules.targetMatches(pkg, root, selector, true));
        label.setVisibleToUser(false);
        assertFalse(SplashRules.targetMatches(pkg, root, selector, true));
    }

    @Test public void pagePrerequisiteMustBeVisibleAndSameTree() {
        String pkg = "com.dubox.drive";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.widget.ImageView", pkg + ":id/ivClose"));
        assertNull(SplashSkipper.match(root, rules, ""));
        AccessibilityNodeInfo marker = add(root, node(pkg, "android.webkit.WebView", "inmobi-ad", false, SCREEN));
        assertEquals(skip, SplashSkipper.match(root, rules, ""));
        marker.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, ""));
    }

    @Test public void huaweiResourceSuffixMustBeExactAndNativelyClickable() {
        String pkg = "com.huawei.browser";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.widget.TextView", "com.huawei.hms.ads.uiengine:id/not_hiad_skip_text"));
        assertNull(SplashSkipper.match(root, rules, ""));
        skip.setViewIdResourceName("com.huawei.hms.ads.uiengine:id/hiad_skip_text");
        assertEquals(skip, SplashSkipper.match(root, rules, ""));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, "", skip, 0));
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, ""));
    }

    @Test public void huaweiSdkExactIdWorksInOtherwiseUnlistedApps() {
        String pkg = "test.app";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.widget.TextView", pkg + ":id/hiad_skip_text"));
        assertNull(SplashSkipper.match(root, rules, ""));
        skip.setViewIdResourceName("com.huawei.hms.ads.uiengine:id/hiad_skip_text");
        assertEquals(skip, SplashSkipper.match(root, rules, ""));
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, ""));
    }

    @Test public void kugouRequiresTheExactSdkDescription() {
        String pkg = "com.kugou.android";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.view.View", null));
        skip.setContentDescription("跳过");
        assertNull(SplashSkipper.match(root, rules, ""));
        skip.setContentDescription("GdtCountDownView");
        assertEquals(skip, SplashSkipper.match(root, rules, ""));
        assertFalse(rules.matches("other.app", "", skip));
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, ""));
    }

    @Test public void honorMarketNewCountdownKeepsActivityAndClickGuards() {
        String pkg = "com.hihonor.appmarket";
        String activity = pkg + ".module.main.MainActivity";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, button(pkg, "android.widget.Button", pkg + ":id/btn_skip"));
        skip.setText("跳过 5");

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertNull(SplashSkipper.match(root, rules, pkg + ".module.splash.Splash"));
        assertFalse(rules.matches(pkg, activity, skip, 10_000));

        skip.setText("继续");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setText("跳过 5");
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClickable(true);
        skip.setViewIdResourceName(pkg + ":id/btn_skip_quick_game_center");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setViewIdResourceName(pkg + ":id/btn_skip");
        AccessibilityNodeInfo consent = add(root, button(pkg, "android.widget.Button", null));
        consent.setText("同意");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void tencentVideoHomeRecognizesTouchOnlySkipWithNativeAdBadges() {
        String pkg = "com.tencent.qqlive";
        String activity = pkg + ".ona.activity.SplashHomeActivity";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo skip = add(root, node(pkg, "android.widget.TextView", null, false, CORNER));
        skip.setText("跳过");
        assertNull(SplashSkipper.match(root, rules, activity));

        AccessibilityNodeInfo badge = add(root, node(pkg, "android.widget.TextView", null, false, CORNER));
        assertNull(SplashSkipper.match(root));
        for (String text : new String[]{"广告", "广告 | 已Wi-Fi预加载", "已Wi-Fi预加载"}) {
            badge.setText(text);
            assertEquals(skip, SplashSkipper.match(root, rules, activity));
            assertEquals(SplashRules.Action.TAP, rules.action(pkg, activity, skip, 0));
        }
        assertNull(SplashSkipper.match(root, rules, pkg + ".ona.activity.VideoDetailActivity"));
        assertFalse(rules.matches(pkg, activity, skip, 10_000));
        badge.setPackageName("other.app");
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setPackageName(pkg);
        badge.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setVisibleToUser(true);
        badge.setText("广告推荐");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void tencentVideoDedicatedSplashNeedsAnActualSkipControl() {
        String pkg = "com.tencent.qqlive";
        String activity = pkg + ".qadsplash.template.QAdHotSplashActivity";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo container = add(root, button(pkg, "android.widget.FrameLayout", null));
        AccessibilityNodeInfo skip = add(container, button(pkg, "android.widget.TextView", null));
        skip.setText("跳过");
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.TAP, rules.action(pkg, activity, skip, 0));
        skip.setClickable(false);
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        skip.setViewIdResourceName(pkg + ":id/intro_skip");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setViewIdResourceName(null);
        container.setContentDescription("立即购买");
        assertNull(SplashSkipper.match(root, rules, activity));
        container.setContentDescription(null);
        skip.setText("跳过片头");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void neteaseSkipRequiresItsVisibleAdContainerAndBadge() {
        String pkg = "com.netease.cloudmusic";
        String activity = pkg + ".activity.MainActivity";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo container = add(root, node(pkg, "android.widget.RelativeLayout",
                pkg + ":id/adContainer", false, SCREEN));
        AccessibilityNodeInfo skip = add(container, button(pkg, "android.widget.TextView", pkg + ":id/skipBtn"));
        skip.setText("跳过 5");
        assertFalse(rules.matches(pkg, activity, skip));

        AccessibilityNodeInfo badge = add(container, node(pkg, "android.widget.TextView",
                pkg + ":id/adCompatTag", false, CORNER));
        badge.setText("广告");
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertFalse(rules.matches(pkg, pkg + ".music.biz.setting.activity.SettingActivity", skip));
        assertFalse(rules.matches(pkg, activity, skip, 10_000));
        badge.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setVisibleToUser(true);
        container.setViewIdResourceName(pkg + ":id/content");
        assertFalse(rules.matches(pkg, activity, skip));
    }

    @Test public void iqiyiWrapperRequiresItsFullSplashShapeAndVisibleAdPrompt() {
        String pkg = "com.qiyi.video";
        String activity = "org.qiyi.android.video.MainActivity";
        String anonymized = pkg + ":id/unused_res_a";
        AccessibilityNodeInfo root = frame(pkg, null);
        add(root, node(pkg, "android.widget.ImageView", anonymized, false, SCREEN));
        add(root, node(pkg, "android.widget.ImageView", anonymized, false, CORNER));
        AccessibilityNodeInfo corner = add(root, frame(pkg, null));
        AccessibilityNodeInfo close = add(corner, button(pkg, "android.widget.FrameLayout", anonymized));
        AccessibilityNodeInfo row = add(close, node(pkg, "android.widget.LinearLayout", null, false, CORNER));
        add(row, frame(pkg, anonymized));
        AccessibilityNodeInfo label = add(row, node(pkg, "android.widget.TextView", anonymized, false, CORNER));
        label.setText("关闭");
        AccessibilityNodeInfo adPrompt = add(root, node(pkg, "android.widget.TextView", anonymized, false, SCREEN));
        adPrompt.setText("点击跳转至视频播放页");

        assertEquals(close, SplashSkipper.match(root, rules, activity));
        assertNull(SplashSkipper.match(root, rules, "org.iqiyi.video.activity.PlayerActivity"));
        adPrompt.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        adPrompt.setVisibleToUser(true);
        close.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        close.setClickable(true);
        label.setText("继续");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void qunarCountdownCloseRequiresTheAdSiblingAndAValidCountdown() {
        String pkg = "com.Qunar";
        String activity = "com.mqunar.atom.alexhome.ui.activity.MainActivity";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo host = add(root, node(pkg, "android.widget.RelativeLayout", null, false, SCREEN));
        AccessibilityNodeInfo ad = add(host, node(pkg, "android.widget.RelativeLayout", null, false, SCREEN));
        add(ad, frame(pkg, null));
        add(ad, frame(pkg, null));
        add(ad, frame(pkg, null));
        AccessibilityNodeInfo marker = add(ad, node(pkg, "android.widget.LinearLayout",
                pkg + ":id/mery_splash_ad_resource", false, SCREEN));
        AccessibilityNodeInfo label = add(marker, node(pkg, "android.widget.TextView", null, false, SCREEN));
        label.setText("广告");
        AccessibilityNodeInfo close = add(host, button(pkg, "android.widget.TextView", null));
        close.setText("关闭 1");
        assertEquals(close, SplashSkipper.match(root, rules, activity));

        close.setText("关闭 123");
        assertNull(SplashSkipper.match(root, rules, activity));
        close.setText("关闭 1");
        marker.setViewIdResourceName(pkg + ":id/ordinary_panel");
        assertNull(SplashSkipper.match(root, rules, activity));
        close.setText("关闭");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void railwaySplashClosesTheUnlabeledSkipOnlyInItsAdContainer() {
        // Public snapshot 17656103, SHA-256 98e3559b281c628ee5d368451a75ae4cb51dde730551cd471f02cac3fbc75a60.
        String pkg = "com.MobileTicket";
        String activity = pkg + ".ui.activity.MainActivity";
        Rect screen = new Rect(0, 0, 1080, 2340);
        Rect adBounds = new Rect(0, 0, 1080, 2040);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo container = add(root, node(pkg, "android.widget.LinearLayout",
                pkg + ":id/ll_splash_ad_container", false, screen));
        AccessibilityNodeInfo content = add(container, node(pkg, "android.widget.FrameLayout",
                pkg + ":id/fl_adContent_container", false, adBounds));
        AccessibilityNodeInfo ad = add(content, node(pkg, "android.widget.ImageView", pkg + ":id/img_adContent",
                true, adBounds));
        ad.setContentDescription("广告");
        AccessibilityNodeInfo wrapper = add(content, node(pkg, "android.widget.FrameLayout", pkg + ":id/fl_skip_wrong",
                true, new Rect(750, 0, 1080, 358)));
        AccessibilityNodeInfo skip = add(wrapper, node(pkg, "android.widget.TextView", pkg + ":id/tv_skip",
                true, new Rect(882, 138, 1014, 207)));

        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertFalse(rules.matches(pkg, activity, wrapper));
        assertFalse(rules.matches(pkg, activity, ad));
        assertNull(SplashSkipper.match(root));
        assertNull(SplashSkipper.match(root, rules, pkg + ".ui.activity.OtherActivity"));
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClickable(true);
        ad.setContentDescription(null);
        assertNull(SplashSkipper.match(root, rules, activity));
        ad.setContentDescription("广告");
        container.setViewIdResourceName(pkg + ":id/ordinary_container");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void railwaySplashStillRecognizesTheEarlierTemplate() {
        // Public snapshot 17580273, SHA-256 cd85606f19536ae7137ff5f9e6a64bb5c69002e704761233153930e68d1f4141.
        String pkg = "com.MobileTicket";
        String activity = pkg + ".ui.activity.MainActivity";
        Rect screen = new Rect(0, 0, 1220, 2712);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo ad = add(root, node(pkg, "android.view.ViewGroup",
                pkg + ":id/cl_image_splash", false, screen));
        AccessibilityNodeInfo skip = add(ad, node(pkg, "android.widget.TextView", pkg + ":id/tv_main_splash_skip",
                true, new Rect(1016, 150, 1160, 225)));

        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        ad.setViewIdResourceName(pkg + ":id/ordinary_container");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void ahVideoRecognizesTheLeftSkipInTheDocumentedSdkLayout() {
        String pkg = "com.chunqiu.ah";
        String activity = "com.androlua.LuaActivity";
        Rect leftCorner = new Rect(56, 168, 168, 280);
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo area = add(root, node(pkg, "android.widget.LinearLayout",
                pkg + ":id/ksad_splash_left_top_root", false, SCREEN));
        AccessibilityNodeInfo wrapper = add(area, node(pkg, "android.widget.LinearLayout",
                pkg + ":id/ksad_preload_left_container", false, leftCorner));
        AccessibilityNodeInfo skip = add(wrapper, node(pkg, "android.widget.FrameLayout",
                pkg + ":id/ksad_splash_circle_skip_left_view", true, leftCorner));
        AccessibilityNodeInfo label = add(skip, node(pkg, "android.widget.TextView", null, false, leftCorner));
        label.setText("跳过");
        AccessibilityNodeInfo badge = add(root, node(pkg, "android.widget.TextView",
                pkg + ":id/ksad_logo_text", false, SCREEN));
        badge.setText("广告");

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertNull(SplashSkipper.match(root, rules, "com.androlua.MainActivity"));
        label.setText("继续");
        assertNull(SplashSkipper.match(root, rules, activity));
        label.setText("跳过");
        badge.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void yuandaoRecognizesTheClickableSiblingOfItsLeftSkipLabel() {
        // Public snapshot 20896758 contains the Kwai splash and its native skip control.
        String pkg = "com.ydtx.camera";
        String activity = pkg + ".activity.SplashActivitynew";
        Rect screen = new Rect(0, 0, 1280, 2772);
        Rect skipBounds = new Rect(52, 186, 169, 303);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo sdk = add(root, frame(pkg, "com.kwad.dy.sdk:id/ksad_splash_root_container"));
        AccessibilityNodeInfo playCard = add(sdk, frame(pkg, "com.kwad.dy.sdk:id/splash_full_tk_play_card_view"));
        AccessibilityNodeInfo content = playCard;
        for (int depth = 0; depth < 4; depth++) content = add(content, frame(pkg, null));
        AccessibilityNodeInfo row = add(content, node(pkg, "android.view.ViewGroup", null, false, skipBounds));
        AccessibilityNodeInfo skip = add(row, node(pkg, "android.widget.ImageView", null, true, skipBounds));
        skip.setContentDescription("skip_button");
        AccessibilityNodeInfo labelGroup = add(row, node(pkg, "android.view.ViewGroup", null, false, skipBounds));
        AccessibilityNodeInfo label = add(labelGroup, node(pkg, "android.widget.TextView", null, false, skipBounds));
        label.setText("跳过");
        AccessibilityNodeInfo badge = add(content, node(pkg, "android.widget.TextView", null, false,
                new Rect(1154, 2672, 1218, 2715)));
        badge.setText("广告");

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertNull(SplashSkipper.match(root, rules, pkg + ".activity.MainActivity"));
        assertFalse(rules.matches("other.app", activity, skip));

        skip.setContentDescription("red_packet");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setContentDescription("skip_button");
        label.setText("继续");
        assertNull(SplashSkipper.match(root, rules, activity));
        label.setText("跳过");
        badge.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setVisibleToUser(true);
        playCard.setViewIdResourceName(null);
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    private static AccessibilityNodeInfo frame(String pkg, String id) {
        return node(pkg, "android.widget.FrameLayout", id, false, SCREEN);
    }

    private static AccessibilityNodeInfo button(String pkg, String cls, String id) {
        return node(pkg, cls, id, true, CORNER);
    }

    private static AccessibilityNodeInfo node(String pkg, String cls, String id, boolean clickable, Rect bounds) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(pkg);
        node.setClassName(cls);
        node.setViewIdResourceName(id);
        node.setBoundsInScreen(bounds);
        node.setEnabled(true);
        node.setVisibleToUser(true);
        node.setClickable(clickable);
        return node;
    }

    private static AccessibilityNodeInfo add(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        shadowOf(parent).addChild(child);
        return child;
    }
}
