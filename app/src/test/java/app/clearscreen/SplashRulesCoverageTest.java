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
        assertNull(rules.prepare(pkg, activity, 9999, null, true).action(skip, ""));
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

    @Test public void taoteOnlySkipsTheCountdownNextToTheReviewedSplashImage() {
        // Public snapshot 12774851 has a full-screen promotion and a separate clickable countdown.
        String pkg = "com.taobao.litetao";
        String activity = "com.taobao.ltao.maintab.MainFrameActivity";
        Rect countdownBounds = new Rect(840, 59, 1035, 128);
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo container = add(root, frame(pkg, pkg + ":id/root_layout"));
        AccessibilityNodeInfo image = add(container, node(pkg, "android.widget.ImageView", null, true,
                new Rect(0, 0, 1080, 2356)));
        AccessibilityNodeInfo countdown = add(container, node(pkg, "android.widget.TextView", null, true,
                countdownBounds));

        for (String label : new String[]{"还剩1秒", "还剩2秒", "还剩3秒"}) {
            countdown.setText(label);
            assertEquals(countdown, SplashSkipper.match(root, rules, activity));
        }
        assertFalse(rules.matches(pkg, activity, image));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, countdown, 9999));
        assertNull(rules.action(pkg, activity, countdown, 10000));
        assertNull(SplashSkipper.match(root));
        assertNull(SplashSkipper.match(root, rules, "com.taobao.ltao.maintab.OtherActivity"));
        assertFalse(rules.matches("other.app", activity, countdown));

        for (String label : new String[]{"还剩4秒", "还剩A秒", "还剩3秒再开启"}) {
            countdown.setText(label);
            assertNull(SplashSkipper.match(root, rules, activity));
        }
        countdown.setText("还剩3秒");
        image.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        image.setClickable(true);
        countdown.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        countdown.setClickable(true);
        countdown.setBoundsInScreen(new Rect(430, 820, 650, 900));
        assertNull(SplashSkipper.match(root, rules, activity));
        countdown.setBoundsInScreen(countdownBounds);
        add(container, frame(pkg, null));
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void oopzSelectsTheClickableCountdownSiblingOnlyWithTheReviewedAdPrompts() {
        // Public snapshot 21202366 has a readable countdown and a separate native click target.
        String pkg = "com.weilaishanhai.oopz";
        String activity = pkg + ".MainActivity";
        Rect screen = new Rect(0, 0, 1280, 2772);
        Rect skipBounds = new Rect(1045, 175, 1237, 261);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo ad = add(root, node(pkg, "android.widget.FrameLayout", null, true, screen));
        add(ad, node(pkg, "android.view.View", null, false, new Rect())).setVisibleToUser(false);
        add(ad, node(pkg, "android.widget.ImageView", null, false, screen));
        AccessibilityNodeInfo prompts = add(ad, node(pkg, "android.widget.RelativeLayout", null, false, screen));
        add(prompts, node(pkg, "android.widget.FrameLayout", null, true, new Rect(448, 1783, 832, 2167)));
        AccessibilityNodeInfo shake = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(386, 2201, 893, 2272)));
        shake.setText("摇动手机 或 点击图标");
        AccessibilityNodeInfo details = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(322, 2289, 958, 2360)));
        details.setText("跳转至详情页或第三方应用");
        AccessibilityNodeInfo unrelated = add(ad, node(pkg, "android.widget.TextView", null, true, skipBounds));
        add(ad, node(pkg, "android.widget.TextView", null, false, new Rect(384, 160, 896, 378)));
        AccessibilityNodeInfo row = add(ad, node(pkg, "android.widget.FrameLayout", null, false, skipBounds));
        AccessibilityNodeInfo countdown = add(row, node(pkg, "android.widget.TextView", null, false, skipBounds));
        AccessibilityNodeInfo skip = add(row, node(pkg, "android.widget.TextView", null, true, skipBounds));
        add(ad, node(pkg, "android.widget.ImageView", null, false, new Rect(1115, 2688, 1241, 2733)));

        for (String label : new String[]{"跳过 1", "跳过 2", "跳过 3"}) {
            countdown.setText(label);
            assertEquals(skip, SplashSkipper.match(root, rules, activity));
        }
        assertNull(SplashSkipper.match(root));
        assertFalse(rules.matches(pkg, activity, unrelated));
        assertFalse(rules.matches(pkg, activity, countdown));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertNull(SplashSkipper.match(root, rules, pkg + ".OtherActivity"));
        assertFalse(rules.matches("other.app", activity, skip));

        skip.setContentDescription("查看详情");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setContentDescription(null);
        countdown.setText("跳过 4");
        assertNull(SplashSkipper.match(root, rules, activity));
        countdown.setText("跳过 3");
        shake.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        shake.setVisibleToUser(true);
        details.setText("查看详情");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void upupooOnlySkipsTheReviewedNativeControlBesideTheAdBadge() {
        // Public snapshot 14052232 shows 跳过 at the bounds of the unlabeled native View.
        String pkg = "com.upupoo.apu.mobile";
        String activity = pkg + ".MainActivity";
        Rect screen = new Rect(0, 0, 1080, 2340);
        Rect adBounds = new Rect(0, 0, 1080, 2088);
        Rect skipBounds = new Rect(912, 132, 1032, 252);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo container = add(root, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        AccessibilityNodeInfo layout = container;
        for (int depth = 0; depth < 3; depth++) {
            layout = add(layout, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        }
        AccessibilityNodeInfo ad = add(layout, node(pkg, "android.widget.FrameLayout", null, true, adBounds));
        ad.setContentDescription("root:root:0:0");
        add(ad, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        AccessibilityNodeInfo overlay = add(ad, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        AccessibilityNodeInfo inset = add(overlay, node(pkg, "android.widget.LinearLayout", null, false, adBounds));
        AccessibilityNodeInfo prompts = add(inset, node(pkg, "android.widget.LinearLayout", null, false, adBounds));
        add(prompts, node(pkg, "android.widget.LinearLayout", null, true, new Rect(375, 1150, 705, 1480)));
        AccessibilityNodeInfo shake = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(274, 1492, 806, 1557)));
        shake.setText("扭动手机 或 点击图标");
        AccessibilityNodeInfo details = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(288, 1557, 792, 1608)));
        details.setText("跳转至详情页或第三方应用");
        AccessibilityNodeInfo skip = add(container, node(pkg, "android.view.View", null, true, skipBounds));
        AccessibilityNodeInfo badge = add(container, node(pkg, "android.widget.TextView", null, true,
                new Rect(60, 1926, 156, 1968)));
        AccessibilityNodeInfo unrelated = add(root, node(pkg, "android.view.View", null, true, skipBounds));

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertNull(SplashSkipper.match(root, rules, pkg + ".OtherActivity"));
        assertFalse(rules.matches("other.app", activity, skip));
        assertFalse(rules.matches(pkg, activity, unrelated));

        badge.setClassName("android.view.View");
        badge.setBoundsInScreen(skipBounds);
        assertFalse(rules.matches(pkg, activity, badge));
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setClassName("android.widget.TextView");

        // The earlier 4.2.0 sample has a separate clickable ImageView for muting the ad.
        skip.setClassName("android.widget.ImageView");
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClassName("android.view.View");

        ad.setContentDescription("ordinary content");
        assertNull(SplashSkipper.match(root, rules, activity));
        ad.setContentDescription("root:root:0:0");
        shake.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        shake.setVisibleToUser(true);
        details.setText(null);
        assertNull(SplashSkipper.match(root, rules, activity));
        details.setText("跳转至详情页或第三方应用");

        skip.setBoundsInScreen(new Rect(480, 780, 600, 900));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(skipBounds);
        add(container, node(pkg, "android.widget.ImageView", null, true, new Rect(48, 48, 138, 138)));
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void upupooVideoSplashUsesTheNativeSkipBetweenMuteAndTheAdBadge() {
        // Snapshot 13830230 adds a separate mute button to the 4.2.0 video ad.
        String pkg = "com.upupoo.apu.mobile";
        String activity = pkg + ".MainActivity";
        Rect screen = new Rect(0, 0, 1080, 2340);
        Rect adBounds = new Rect(0, 0, 1080, 2088);
        Rect skipBounds = new Rect(912, 48, 1032, 168);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo panel = add(root, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        AccessibilityNodeInfo wrapper = panel;
        for (int depth = 0; depth < 3; depth++) {
            wrapper = add(wrapper, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        }
        AccessibilityNodeInfo ad = add(wrapper, node(pkg, "android.widget.FrameLayout", null, true, adBounds));
        ad.setContentDescription("root:root:0:0");
        AccessibilityNodeInfo overlay = add(ad, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        for (int index = 0; index < 3; index++) {
            add(ad, node(pkg, "android.widget.FrameLayout", null, false, adBounds));
        }
        AccessibilityNodeInfo inset = add(overlay, node(pkg, "android.widget.LinearLayout", null, false, adBounds));
        AccessibilityNodeInfo prompts = add(inset, node(pkg, "android.widget.LinearLayout", null, false, adBounds));
        add(prompts, node(pkg, "android.widget.LinearLayout", null, true, new Rect(375, 1376, 705, 1706)));
        AccessibilityNodeInfo shake = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(282, 1718, 798, 1791)));
        shake.setText("扭动手机 或 点击图标");
        AccessibilityNodeInfo details = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(288, 1791, 792, 1848)));
        details.setText("跳转至详情页或第三方应用");
        AccessibilityNodeInfo mute = add(panel, node(pkg, "android.widget.ImageView", null, true,
                new Rect(48, 48, 138, 138)));
        AccessibilityNodeInfo skip = add(panel, node(pkg, "android.view.View", null, true, skipBounds));
        AccessibilityNodeInfo badge = add(panel, node(pkg, "android.widget.TextView", null, true,
                new Rect(60, 1926, 156, 1968)));

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertNull(SplashSkipper.match(root, rules, activity + "Other"));
        assertFalse(rules.matches(pkg, activity, mute));
        assertFalse(rules.matches(pkg, activity, badge));

        badge.setClassName("android.view.View");
        badge.setBoundsInScreen(skipBounds);
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        badge.setClassName("android.widget.TextView");
        skip.setClickable(true);
        mute.setClassName("android.view.View");
        assertNull(SplashSkipper.match(root, rules, activity));
        mute.setClassName("android.widget.ImageView");

        shake.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        shake.setVisibleToUser(true);
        details.setText("查看详情");
        assertNull(SplashSkipper.match(root, rules, activity));
        details.setText("跳转至详情页或第三方应用");
        skip.setBoundsInScreen(new Rect(480, 780, 600, 900));
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void cellularZChoosesTheNativeSkipInsteadOfTheAdjacentMuteControl() {
        // Public snapshot 13978978 places the unlabeled skip at the right and mute at the left.
        String pkg = "make.more.r2d2.cellular_z";
        String activity = pkg + ".activity.SplashGMActivity";
        Rect skipBounds = new Rect(937, 149, 1039, 251);
        AccessibilityNodeInfo root = frame(pkg, null);
        String splashId = pkg + ":id/splash_container";
        AccessibilityNodeInfo splash = add(root, frame(pkg, splashId));
        AccessibilityNodeInfo content = add(splash, frame(pkg, null));
        add(content, frame(pkg, null)).setVisibleToUser(false);
        AccessibilityNodeInfo panel = add(content, frame(pkg, null));
        AccessibilityNodeInfo wrapper = panel;
        for (int depth = 0; depth < 3; depth++) wrapper = add(wrapper, frame(pkg, null));
        AccessibilityNodeInfo ad = add(wrapper, node(pkg, "android.widget.FrameLayout", null, true, SCREEN));
        ad.setContentDescription("root:root:0:0");
        AccessibilityNodeInfo overlay = add(ad, frame(pkg, null));
        add(ad, frame(pkg, null));
        add(ad, frame(pkg, null));
        add(ad, frame(pkg, null));
        AccessibilityNodeInfo inset = add(overlay, node(pkg, "android.widget.LinearLayout", null, false, SCREEN));
        AccessibilityNodeInfo prompts = add(inset, node(pkg, "android.widget.LinearLayout", null, false, SCREEN));
        add(prompts, node(pkg, "android.widget.LinearLayout", null, true, new Rect(400, 1807, 681, 2088)));
        AccessibilityNodeInfo shake = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(324, 2098, 757, 2153)));
        shake.setText("扭动手机 或 点击图标");
        AccessibilityNodeInfo details = add(prompts, node(pkg, "android.widget.TextView", null, false,
                new Rect(330, 2153, 750, 2196)));
        details.setText("跳转至详情页或第三方应用");
        AccessibilityNodeInfo mute = add(panel, node(pkg, "android.widget.ImageView", null, true,
                new Rect(41, 149, 117, 225)));
        AccessibilityNodeInfo skip = add(panel, node(pkg, "android.view.View", null, true, skipBounds));
        AccessibilityNodeInfo badge = add(panel, node(pkg, "android.widget.TextView", null, true,
                new Rect(51, 2262, 133, 2298)));

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertFalse(rules.matches(pkg, activity, mute));
        assertNull(SplashSkipper.match(root, rules, pkg + ".activity.MainActivity"));
        assertFalse(rules.matches("other.app", activity, skip));

        badge.setClassName("android.view.View");
        badge.setBoundsInScreen(skipBounds);
        skip.setClickable(false);
        assertFalse(rules.matches(pkg, activity, badge));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClickable(true);
        mute.setClassName("android.view.View");
        assertNull(SplashSkipper.match(root, rules, activity));
        mute.setClassName("android.widget.ImageView");

        skip.setBoundsInScreen(new Rect(480, 780, 600, 900));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(skipBounds);
        shake.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        shake.setVisibleToUser(true);
        details.setText(null);
        assertNull(SplashSkipper.match(root, rules, activity));
        details.setText("跳转至详情页或第三方应用");
        splash.setViewIdResourceName(pkg + ":id/ordinary_content");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void weChatMiniAppOnlyTapsSkipInTheReviewedAdCapsuleAndExactActivity() {
        // The reviewed plugin and regular mini-app snapshots have separate skip and exit controls.
        String pkg = "com.tencent.mm";
        String activity = pkg + ".plugin.appbrand.ui.AppBrandPluginUI";
        String miniApp = pkg + ".plugin.appbrand.ui.AppBrandUI00";
        Rect screen = new Rect(0, 0, 1260, 2800);
        Rect capsuleBounds = new Rect(42, 153, 442, 265);
        Rect skipBounds = new Rect(294, 174, 402, 244);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo adLayer = add(root, node(pkg, "android.widget.FrameLayout", null, false,
                new Rect(0, 0, 1260, 2520)));
        for (int index = 0; index < 5; index++) add(adLayer, frame(pkg, null));
        AccessibilityNodeInfo capsule = add(adLayer, node(pkg, "android.widget.FrameLayout", null, false, capsuleBounds));
        add(adLayer, frame(pkg, null));
        add(capsule, node(pkg, "android.widget.TextView", null, false, capsuleBounds));
        AccessibilityNodeInfo skipGroup = add(capsule, node(pkg, "android.widget.FrameLayout", null, false, capsuleBounds));
        add(skipGroup, node(pkg, "android.widget.TextView", null, false, capsuleBounds));
        AccessibilityNodeInfo skipWrapper = add(skipGroup, node(pkg, "android.widget.FrameLayout", null, false, skipBounds));
        add(skipWrapper, node(pkg, "android.widget.TextView", null, false, skipBounds));
        AccessibilityNodeInfo skipSlot = add(skipWrapper, node(pkg, "android.widget.FrameLayout", null, false, skipBounds));
        AccessibilityNodeInfo skip = add(skipSlot, node(pkg, "android.widget.TextView", null, false, skipBounds));
        skip.setText("跳过");
        add(skipGroup, frame(pkg, null));
        AccessibilityNodeInfo badgeGroup = add(capsule, frame(pkg, null));
        add(badgeGroup, node(pkg, "android.widget.TextView", null, false, capsuleBounds));
        AccessibilityNodeInfo badgeBody = add(badgeGroup, frame(pkg, null));
        AccessibilityNodeInfo badgeSlot = add(badgeBody, frame(pkg, null));
        AccessibilityNodeInfo badge = add(badgeSlot, node(pkg, "android.widget.TextView", null, false,
                new Rect(84, 174, 182, 244)));
        badge.setText("广告");
        AccessibilityNodeInfo exit = add(root, node(pkg, "android.widget.ImageButton", pkg + ":id/gn", true,
                new Rect(1064, 152, 1232, 264)));
        exit.setContentDescription("关闭");

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.TAP, rules.action(pkg, activity, skip, 9999));
        assertEquals(SplashRules.Action.TAP, rules.prepare(pkg, activity, 9999, null, true).action(skip, "跳过"));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertFalse(rules.matches(pkg, activity, exit));
        assertNull(SplashSkipper.match(root, rules, activity + "00"));
        assertNull(SplashSkipper.match(root, rules, miniApp, 799));
        assertEquals(skip, SplashSkipper.match(root, rules, miniApp, 800));
        assertEquals(SplashRules.Action.TAP, rules.prepare(pkg, miniApp, 800, null, true).action(skip, "跳过"));
        assertNull(SplashSkipper.match(root, rules, miniApp, 10000));
        assertNull(SplashSkipper.match(root, rules, miniApp.replace("00", "01"), 800));
        assertFalse(rules.matches(pkg, miniApp, exit, 800));
        assertFalse(rules.matches("other.app", activity, skip));

        AccessibilityNodeInfo sdk = node(pkg, "android.widget.ImageView", pkg + ":id/tt_splash_skip_btn", true, CORNER);
        assertEquals(SplashRules.Action.CLICK, rules.prepare(pkg, activity, 0, null).action(sdk, ""));
        assertNull(rules.prepare(pkg, activity, 0, null, true).action(sdk, ""));

        badge.setText("活动");
        AccessibilityNodeInfo unrelated = add(root, node(pkg, "android.widget.TextView", null, false,
                new Rect(45, 500, 120, 545)));
        unrelated.setText("广告");
        assertNull(rules.action(pkg, activity, skip, 0));
        assertNull(SplashSkipper.match(root, rules, activity));
        assertNull(SplashSkipper.match(root, rules, miniApp, 800));
        badge.setText("广告");
        skip.setBoundsInScreen(new Rect(480, 780, 600, 900));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(skipBounds);

        AccessibilityNodeInfo consent = add(root, button(pkg, "android.widget.Button", null));
        consent.setText("同意并继续");
        assertNull(SplashSkipper.match(root, rules, activity));
        consent.setVisibleToUser(false);
        AccessibilityNodeInfo edit = add(root, node(pkg, "android.widget.EditText", null, true,
                new Rect(100, 350, 900, 460)));
        edit.setEditable(true);
        assertNull(SplashSkipper.match(root, rules, activity));
        assertNull(SplashSkipper.match(root, rules, miniApp, 800));
    }

    @Test public void doubanUsesTheNativeVideoSkipInsideItsStartupAd() {
        // Snapshot 26569898 also has a full-screen View named skip that cannot close the ad.
        String pkg = "com.douban.frodo";
        String activity = pkg + ".activity.SplashActivity";
        Rect screen = new Rect(0, 0, 1256, 2760);
        Rect mediaBounds = new Rect(0, 0, 1256, 2346);
        Rect skipBounds = new Rect(1003, 158, 1256, 242);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo splash = add(root, node(pkg, "android.widget.FrameLayout", pkg + ":id/splash", false, screen));
        AccessibilityNodeInfo ad = add(splash, node(pkg, "android.view.ViewGroup", pkg + ":id/ad_parent", true, screen));
        AccessibilityNodeInfo surface = add(ad, node(pkg, "android.view.View", pkg + ":id/skip", false, screen));
        AccessibilityNodeInfo sdk = add(ad, node(pkg, "android.widget.FrameLayout", pkg + ":id/sdk_view", false, mediaBounds));
        AccessibilityNodeInfo wrapper = add(sdk, node(pkg, "android.widget.FrameLayout", null, false, mediaBounds));
        AccessibilityNodeInfo video = add(wrapper, node(pkg, "android.widget.FrameLayout", null, false, mediaBounds));
        AccessibilityNodeInfo close = add(video, node(pkg, "android.widget.FrameLayout",
                pkg + ":id/jad_video_skip_btn", true, skipBounds));
        AccessibilityNodeInfo captionGroup = add(close, node(pkg, "android.widget.LinearLayout", null, false, skipBounds));
        AccessibilityNodeInfo caption = add(captionGroup, node(pkg, "android.widget.TextView",
                pkg + ":id/jad_tv_video_count", false, new Rect(1003, 158, 1203, 242)));
        caption.setText("跳过 5");

        assertNull(SplashSkipper.match(root));
        assertEquals(close, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, close, 9999));
        assertNull(rules.action(pkg, activity, close, 10000));
        assertNull(SplashSkipper.match(root, rules, activity + "Other"));
        assertNull(SplashSkipper.match(root, rules, pkg + ".activity.MainActivity"));
        surface.setClickable(true);
        assertFalse(rules.matches(pkg, activity, surface));
        assertEquals(close, SplashSkipper.match(root, rules, activity));

        ad.setViewIdResourceName(pkg + ":id/ordinary_content");
        assertNull(SplashSkipper.match(root, rules, activity));
        ad.setViewIdResourceName(pkg + ":id/ad_parent");
        caption.setText("去绑定邮箱");
        assertNull(SplashSkipper.match(root, rules, activity));
        caption.setText("跳过 6");
        assertNull(SplashSkipper.match(root, rules, activity));
        caption.setText("跳过 5");
        caption.setClickable(true);
        assertNull(SplashSkipper.match(root, rules, activity));
        caption.setClickable(false);
        close.setBoundsInScreen(screen);
        assertNull(SplashSkipper.match(root, rules, activity));
        close.setBoundsInScreen(skipBounds);

        AccessibilityNodeInfo consent = add(root, node(pkg, "android.widget.Button", null, true,
                new Rect(50, 850, 300, 950)));
        consent.setText("同意用户协议");
        assertNull(SplashSkipper.match(root, rules, activity));
    }

    @Test public void petmarvelVideoSplashUsesTheSmallLowerLeftSkipWithoutWideningOtherRules() {
        // Snapshot 23600688 places the native skip above the lower-left ad badge and below mute.
        String pkg = "com.mxchip.petmarvel";
        String activity = pkg + ".start.LaunchNewActivity";
        Rect screen = new Rect(0, 0, 2200, 2480);
        Rect ad = new Rect(0, 0, 2200, 2066);
        Rect skipBounds = new Rect(52, 1725, 182, 1855);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo container = add(root, node(pkg, "android.widget.FrameLayout", pkg + ":id/ad_content", false, ad));
        AccessibilityNodeInfo outer = add(container, node(pkg, "android.widget.FrameLayout", null, false, ad));
        add(outer, node(pkg, "android.view.View", null, false, new Rect())).setVisibleToUser(false);
        AccessibilityNodeInfo controls = add(outer, node(pkg, "android.widget.FrameLayout", null, false, ad));
        add(controls, node(pkg, "android.widget.FrameLayout", null, false, ad));
        AccessibilityNodeInfo video = add(controls, node(pkg, "android.widget.FrameLayout", null, false, ad));
        video.setContentDescription("NativeVideoAdView");
        AccessibilityNodeInfo learnMore = add(video, node(pkg, "android.widget.TextView", null, false,
                new Rect(939, 1767, 1359, 1816)));
        learnMore.setText("扭一扭或点击了解更多");
        AccessibilityNodeInfo mute = add(controls, node(pkg, "android.widget.ImageView", null, true,
                new Rect(52, 176, 149, 273)));
        AccessibilityNodeInfo skip = add(controls, node(pkg, "android.view.View", null, true, skipBounds));
        AccessibilityNodeInfo badge = add(controls, node(pkg, "android.widget.TextView", null, true,
                new Rect(65, 1890, 169, 1936)));

        assertNull(SplashSkipper.match(root));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertEquals(SplashRules.Action.CLICK_LOWER_LEFT, rules.action(pkg, activity, skip, 9999));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertFalse(rules.matches(pkg, activity, mute));
        assertFalse(rules.matches(pkg, activity, badge));
        assertNull(SplashSkipper.match(root, rules, activity + "Other"));

        skip.setBoundsInScreen(new Rect(52, 176, 182, 306));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(new Rect(939, 1725, 1359, 1855));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(skipBounds);
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClickable(true);
        learnMore.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        learnMore.setVisibleToUser(true);
        video.setContentDescription("ordinary video");
        assertNull(SplashSkipper.match(root, rules, activity));
        video.setContentDescription("NativeVideoAdView");
        container.setViewIdResourceName(pkg + ":id/ordinary_content");
        assertNull(SplashSkipper.match(root, rules, activity));
        container.setViewIdResourceName(pkg + ":id/ad_content");
        AccessibilityNodeInfo input = add(root, node(pkg, "android.widget.EditText", null, false, skipBounds));
        input.setEditable(true);
        assertNull(SplashSkipper.match(root, rules, activity));

        AccessibilityNodeInfo legacyRoot = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo legacySkip = add(legacyRoot, node(pkg, "android.widget.TextView", pkg + ":id/tv_finish", true,
                new Rect(1980, 90, 2140, 170)));
        legacySkip.setText("跳过");
        String legacyActivity = pkg + ".start.AdvertiseActivity";
        assertEquals(legacySkip, SplashSkipper.match(legacyRoot, rules, legacyActivity));
        legacySkip.setBoundsInScreen(skipBounds);
        assertNull(SplashSkipper.match(legacyRoot, rules, legacyActivity));
    }

    @Test public void guangzhouMetroOnlyMatchesTheNativeSkipControlInTheStartupLayout() {
        String pkg = "com.infothinker.gzmetro";
        String activity = pkg + ".ActivityAgent";
        AccessibilityNodeInfo root = frame(pkg, null);
        AccessibilityNodeInfo layout = add(root, node(pkg, "android.widget.RelativeLayout", null, false, SCREEN));
        AccessibilityNodeInfo image = add(layout,
                node(pkg, "android.widget.ImageView", pkg + ":id/img_splash", true, SCREEN));
        AccessibilityNodeInfo skip = add(layout, button(pkg, "android.widget.LinearLayout", pkg + ":id/btn_skip_ad"));
        AccessibilityNodeInfo label = add(skip, node(pkg, "android.widget.TextView", null, false, CORNER));
        label.setText("跳过");
        AccessibilityNodeInfo timer = add(skip,
                node(pkg, "android.widget.TextView", pkg + ":id/tv_time_count", false, CORNER));
        timer.setText("3");

        assertEquals(SplashRules.Action.CLICK, rules.action(pkg, activity, skip, 9999));
        assertEquals(skip, SplashSkipper.match(root, rules, activity));
        assertNull(rules.action(pkg, activity, skip, 10000));
        assertFalse(rules.matches(pkg, activity, image));
        assertFalse(rules.matches(pkg, activity, label));
        assertNull(SplashSkipper.match(root));
        assertNull(SplashSkipper.match(root, rules, pkg + ".TestActivityAgent"));
        assertNull(SplashSkipper.match(root, rules, "org.gioneco.zyhmetrosdk.mvvm.view.QRCodeActivity"));

        image.setVisibleToUser(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        image.setVisibleToUser(true);
        image.setViewIdResourceName(pkg + ":id/img_splash_gone");
        assertNull(SplashSkipper.match(root, rules, activity));
        image.setViewIdResourceName(pkg + ":id/img_splash");
        timer.setText(null);
        assertNull(SplashSkipper.match(root, rules, activity));
        timer.setText("3");
        label.setText("查看详情");
        assertNull(SplashSkipper.match(root, rules, activity));
        label.setText("跳过");
        skip.setClickable(false);
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setClickable(true);
        skip.setBoundsInScreen(new Rect(400, 900, 600, 980));
        assertNull(SplashSkipper.match(root, rules, activity));
        skip.setBoundsInScreen(CORNER);
        add(skip, button(pkg, "android.widget.ImageView", pkg + ":id/iv_ad_close"));
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
