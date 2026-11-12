package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PopupAdRulesTest {
    private static final Rect SCREEN = new Rect(0, 0, 1080, 2400);
    private static final Rect BUTTON = new Rect(890, 500, 960, 570);
    private static final String DIDI = "com.sdu.didi.psnger";
    private static final String DIDI_MAIN = "com.didi.sdk.app.MainActivity";
    private final PopupAdRules rules = new PopupAdRules(RuntimeEnvironment.getApplication());

    @Test public void didiRequiresTheAdSiblingAndScopedActivity() {
        AccessibilityNodeInfo root = frame(DIDI);
        AccessibilityNodeInfo card = add(root, frame(DIDI));
        AccessibilityNodeInfo banner = add(card, image(DIDI, "second_entrance_img", false));
        AccessibilityNodeInfo close = add(card, image(DIDI, "left_close_img", true));

        assertTrue(rules.hasRules(DIDI, DIDI_MAIN));
        assertFalse(rules.hasRules(DIDI, ""));
        assertNull(rules.find(root, "com.didi.sdk.app.OrderDetailActivity", SCREEN));
        PopupAdRules.Match match = rules.find(root, DIDI_MAIN, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertSame(match.rule, rules.find(root, DIDI_MAIN, SCREEN).rule);
        assertTrue(rules.visible(root, DIDI_MAIN, match.rule));

        banner.setPackageName("other.app");
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
        banner.setPackageName(DIDI);
        banner.setVisibleToUser(false);
        assertFalse(rules.visible(root, DIDI_MAIN, match.rule));
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
    }

    @Test public void didiRejectsDuplicateOffscreenAndProtectedControls() {
        AccessibilityNodeInfo root = frame(DIDI);
        add(root, image(DIDI, "second_entrance_img", false));
        AccessibilityNodeInfo close = add(root, image(DIDI, "left_close_img", true));
        AccessibilityNodeInfo anotherBanner = add(root, image(DIDI, "second_entrance_img", false));
        AccessibilityNodeInfo anotherClose = add(root, image(DIDI, "left_close_img", true));
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
        anotherBanner.setVisibleToUser(false);
        anotherClose.setVisibleToUser(false);
        close.setBoundsInScreen(new Rect(1040, 500, 1150, 570));
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
        close.setBoundsInScreen(BUTTON);
        assertNotNull(rules.find(root, DIDI_MAIN, SCREEN));
        AccessibilityNodeInfo account = add(root, frame(DIDI));
        account.setContentDescription("请输入支付密码");
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
        account.setContentDescription("观看广告领取奖励");
        assertNull(rules.find(root, DIDI_MAIN, SCREEN));
    }

    @Test public void foodPromoNeedsThePinnedContext() {
        String pkg = "me.ele";
        String activity = "me.ele.newretail.pack.ui.activity.PackActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo promo = add(root, frame(pkg));
        add(root, frame(pkg));
        AccessibilityNodeInfo close = add(root, image(pkg, null, true));
        close.setContentDescription("关闭");
        assertNull(rules.find(root, activity, SCREEN));
        promo.setContentDescription("点击查看今日红包");
        assertEquals(close, rules.find(root, activity, SCREEN).target);
        close.setText("立即领取");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void meituanPromoKeepsTheNeighborAndRejectsLargeClickAreas() {
        String pkg = "com.sankuai.meituan";
        String activity = "com.meituan.android.pt.homepage.activity.MainActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo close = add(root, image(pkg, null, true));
        AccessibilityNodeInfo promo = add(root, image(pkg, null, false));
        promo.setContentDescription("资质与规则");
        assertEquals(close, rules.find(root, activity, SCREEN).target);
        promo.setContentDescription("资质与规则说明");
        assertNull(rules.find(root, activity, SCREEN));
        promo.setContentDescription("资质与规则");
        close.setBoundsInScreen(SCREEN);
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void ctripHomeRuleNeedsTheAdBadgeAndClaimSiblingWithinItsWindow() {
        String pkg = "ctrip.android.view";
        String activity = "ctrip.android.publicproduct.home.view.CtripHomeActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo closeBranch = add(root, node(pkg, "android.widget.RelativeLayout", null, false, SCREEN));
        AccessibilityNodeInfo close = add(closeBranch, image(pkg, null, true));
        AccessibilityNodeInfo adBranch = add(root, node(pkg, "android.widget.RelativeLayout", null, false, SCREEN));
        for (int depth = 0; depth < 4; depth++) adBranch = add(adBranch, frame(pkg));
        AccessibilityNodeInfo badge = add(adBranch, frame(pkg));
        badge.setContentDescription("广告·商家促销");
        AccessibilityNodeInfo claim = add(adBranch, frame(pkg));
        claim.setText("立即领取");

        PopupAdRules.Match match = rules.find(root, activity, SCREEN, 9_999);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertEquals(1, match.rule.maxPerVisit());
        assertNull(rules.find(root, activity, SCREEN, 10_000));
        claim.setText("查看订单");
        assertNull(rules.find(root, activity, SCREEN, 1_000));
    }

    @Test public void iqiyiPlayerRequiresTheLabeledCloseControlInsideTheKnownWrapper() {
        String pkg = "com.qiyi.video";
        String activity = "org.iqiyi.video.activity.PlayerActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo close = add(root, node(pkg, "android.widget.RelativeLayout", "unused_res_a", true, BUTTON));
        AccessibilityNodeInfo label = add(close, node(pkg, "android.widget.TextView", null, false, BUTTON));
        label.setText("关闭广告");

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, "org.iqiyi.video.activity.VideoDetailActivity", SCREEN));

        label.setText("关闭");
        assertNull(rules.find(root, activity, SCREEN));
        label.setText("关闭广告");
        label.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void neteasePromoRequiresAnAdLabelAndAClickableAdjacentClose() {
        String pkg = "com.netease.cloudmusic";
        String activity = "com.netease.cloudmusic.activity.MainActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo promo = add(root, node(pkg, "android.view.ViewGroup", null, false, SCREEN));
        AccessibilityNodeInfo label = add(promo, node(pkg, "android.widget.TextView", null, false, BUTTON));
        label.setText("广告");
        AccessibilityNodeInfo close = add(root, image(pkg, null, true));

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        label.setText("通知");
        assertNull(rules.find(root, activity, SCREEN));
        label.setText("广告");
        close.setClickable(false);
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void neteasePlayerRequiresAnExplicitShortAdSkipLabel() {
        String pkg = "com.netease.cloudmusic";
        String activity = "com.netease.cloudmusic.activity.PlayerActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo button = add(root, node(pkg, "android.widget.TextView", null, true, BUTTON));
        button.setText("跳过广告 5");
        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(button, match.target);

        button.setText("跳过");
        assertNull(rules.find(root, activity, SCREEN));
        button.setText("跳过广告说明，请点这里查看更多");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void mangoTvRequiresThePromoAndCloseTogetherOnItsHomePage() {
        String pkg = "com.hunantv.imgo.activity";
        String activity = pkg + ".MainActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo promo = add(root, image(pkg, "imgPromotion", false));
        AccessibilityNodeInfo close = add(root, image(pkg, "vClosePromotionView", true));

        assertEquals(close, rules.find(root, activity, SCREEN).target);
        assertNull(rules.find(root, pkg + ".PlayerActivity", SCREEN));
        promo.setViewIdResourceName(pkg + ":id/general_image");
        assertNull(rules.find(root, activity, SCREEN));
        promo.setViewIdResourceName(pkg + ":id/imgPromotion");
        close.setContentDescription("确认支付");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void busAdRequiresTheVisibleAdMarkerAndCloseGlyph() {
        String pkg = "com.mygolbs.mybus";
        String activity = pkg + ".NewHomePageActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo adBranch = add(root, node(pkg, "android.view.View", null, false, SCREEN));
        add(adBranch, frame(pkg));
        add(adBranch, frame(pkg));
        AccessibilityNodeInfo adLabel = add(adBranch, node(pkg, "android.widget.TextView", null, false, BUTTON));
        adLabel.setText("广告");
        AccessibilityNodeInfo close = add(root, node(pkg, "android.view.View", null, true, BUTTON));
        AccessibilityNodeInfo glyph = add(add(close, frame(pkg)),
                node(pkg, "android.widget.TextView", null, false, BUTTON));
        glyph.setText("×");

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, pkg + ".OtherActivity", SCREEN));
        adLabel.setText("路线提示");
        assertNull(rules.find(root, activity, SCREEN));
        adLabel.setText("广告");
        glyph.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void youtubeClosesOnlyTheExplicitAdPanelOnThePlayer() {
        String pkg = "com.google.android.youtube";
        String activity = "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo options = add(root, image(pkg, null, false));
        options.setContentDescription("更多选项");
        AccessibilityNodeInfo close = add(root, image(pkg, null, true));
        close.setContentDescription("关闭广告面板");

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, pkg + ".OtherActivity", SCREEN));
        options.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
        options.setVisibleToUser(true);
        options.setContentDescription("More options");
        close.setContentDescription("Close panel");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void wallstreetNewsRequiresAnAdLabelBesideItsOwnCloseButton() {
        String pkg = "com.wallstreetcn.news";
        String activity = pkg + ".ProxyMainActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo label = add(root, node(pkg, "android.widget.TextView", null, false, BUTTON));
        label.setText("广告");
        AccessibilityNodeInfo close = add(root, image(pkg, "close", true));

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, pkg + ".OtherActivity", SCREEN));
        label.setText("活动");
        assertNull(rules.find(root, activity, SCREEN));
        label.setText("广告");
        close.setClickable(false);
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void weiboDetailAdRequiresItsAdjacentTitleAndExactCloseId() {
        String pkg = "com.sina.weibo";
        String activity = pkg + ".feed.DetailWeiboActivity";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo title = add(root, node(pkg, "android.widget.TextView",
                "tvTrendsTitle", false, BUTTON));
        AccessibilityNodeInfo close = add(root, image(pkg, "iv_ad_x", true));

        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, pkg + ".MainTabActivity", SCREEN));
        title.setViewIdResourceName(pkg + ":id/ordinary_title");
        assertNull(rules.find(root, activity, SCREEN));
        title.setViewIdResourceName(pkg + ":id/tvTrendsTitle");
        close.setViewIdResourceName(pkg + ":id/iv_close");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void idlefishNightMarketUsesTheFourthPreorderDescendant() {
        String pkg = "com.taobao.idlefish";
        String activity = "com.idlefish.flutterbridge.flutterboost.boost.FishFlutterBoostActivity";
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", "fish_layer_container_id", false, SCREEN);
        add(root, frame(pkg)).setVisibleToUser(false);
        add(root, frame(pkg));
        add(root, frame(pkg));
        AccessibilityNodeInfo close = idlefishNightMarketClose(root, pkg);
        PopupAdRules.Match match = rules.find(root, activity, SCREEN);
        assertNotNull(match);
        assertEquals(close, match.target);
        assertNull(rules.find(root, "com.taobao.idlefish.OtherActivity", SCREEN));

        AccessibilityNodeInfo wrongRoot = node(pkg, "android.widget.FrameLayout", "fish_layer_container_id", false, SCREEN);
        add(wrongRoot, frame(pkg));
        AccessibilityNodeInfo deeper = wrongRoot;
        for (int depth = 0; depth < 3; depth++) deeper = add(deeper, frame(pkg));
        idlefishNightMarketClose(deeper, pkg);
        assertNull(rules.find(wrongRoot, activity, SCREEN));
    }

    @Test public void honorMarketAdRuleNeverRunsOnTheLauncher() {
        String pkg = "com.hihonor.appmarket";
        String activity = pkg + ".module.main.MainActivity";
        String launcher = "com.hihonor.android.launcher.unihome.UniHomeLauncher";
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo close = add(root, image(pkg, "iv_dialog_operation_close", true));

        assertEquals(close, rules.find(root, activity, SCREEN).target);
        assertFalse(rules.hasRules(pkg, launcher));
        assertNull(rules.find(root, launcher, SCREEN));
        close.setViewIdResourceName(pkg + ":id/close");
        assertNull(rules.find(root, activity, SCREEN));
    }

    @Test public void sfExpressClosesTheHomePromotionOnlyInsideItsFullScreenPanel() {
        // Snapshot 17869485 keeps the native close separate from the promotional CTA.
        String pkg = "com.sf.activity";
        String activity = pkg + ".MainActivity";
        Rect screen = new Rect(0, 0, 1200, 2670);
        Rect closeBounds = new Rect(546, 1861, 654, 2074);
        AccessibilityNodeInfo root = node(pkg, "android.widget.FrameLayout", null, false, screen);
        AccessibilityNodeInfo host = add(root, node(pkg, "android.widget.FrameLayout", "rnRootLayout", false, screen));
        AccessibilityNodeInfo outer = host;
        for (int depth = 0; depth < 3; depth++) {
            outer = add(outer, node(pkg, "android.view.ViewGroup", null, false, screen));
        }
        AccessibilityNodeInfo fullScreen = add(outer, node(pkg, "android.view.ViewGroup", null, false, screen));
        fullScreen.setViewIdResourceName("FullScreenPage");
        AccessibilityNodeInfo layers = add(fullScreen, node(pkg, "android.view.ViewGroup", null, false, screen));
        add(layers, node(pkg, "android.view.ViewGroup", null, false, screen));
        AccessibilityNodeInfo content = add(layers, node(pkg, "android.view.ViewGroup", null, false, screen));
        AccessibilityNodeInfo panel = add(content, node(pkg, "android.view.ViewGroup", null, false, screen));
        Rect promotionBounds = new Rect(83, 596, 1118, 1861);
        AccessibilityNodeInfo promotion = add(panel, node(pkg, "android.widget.Button", null, true, promotionBounds));
        add(promotion, node(pkg, "android.view.ViewGroup", null, false, promotionBounds));
        AccessibilityNodeInfo close = add(panel, node(pkg, "android.widget.Button", null, true, closeBounds));
        close.setViewIdResourceName("BmsAdDialogViewCloseButton");
        add(close, node(pkg, "com.horcrux.svg.SvgView", null, false, new Rect(546, 1966, 654, 2074)));

        assertTrue(rules.hasRules(pkg, activity));
        assertEquals(close, rules.find(root, activity, screen).target);
        promotion.setContentDescription("");
        close.setContentDescription("");
        assertEquals(close, rules.find(root, activity, screen).target);
        assertFalse(rules.hasRules(pkg, activity + "Other"));
        assertNull(rules.find(root, activity + "Other", screen));

        close.setViewIdResourceName(null);
        assertNull(rules.find(root, activity, screen));
        promotion.setViewIdResourceName("BmsAdDialogViewCloseButton");
        promotion.setBoundsInScreen(closeBounds);
        assertNull(rules.find(root, activity, screen));
        promotion.setViewIdResourceName(null);
        promotion.setBoundsInScreen(promotionBounds);
        close.setViewIdResourceName("BmsAdDialogViewCloseButton");

        fullScreen.setViewIdResourceName("OrdinaryPage");
        assertNull(rules.find(root, activity, screen));
        fullScreen.setViewIdResourceName("FullScreenPage");
        host.setViewIdResourceName(pkg + ":id/otherRoot");
        assertNull(rules.find(root, activity, screen));
        host.setViewIdResourceName(pkg + ":id/rnRootLayout");

        AccessibilityNodeInfo consent = add(root, node(pkg, "android.widget.Button", null, true, BUTTON));
        consent.setText("同意用户协议");
        assertNull(rules.find(root, activity, screen));
    }

    @Test public void weiboVideoAdRequiresBothMarkersAndKeepsTheNewsExceptionScoped() {
        // Snapshot 13635551 shows a video ad between the post body and comments.
        String pkg = "com.sina.weibo";
        String activity = pkg + ".feed.DetailWeiboActivity";
        String news = "支付宝也将全面启动鸿蒙原生应用开发了！支付宝将基于HarmonyOS NEXT版本开发应用";
        Rect articleBounds = new Rect(33, 201, 1047, 233);
        Rect videoBounds = new Rect(33, 1130, 497, 1391);
        Rect closeBounds = new Rect(1019, 1130, 1047, 1158);
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo post = add(root, node(pkg, "android.widget.LinearLayout", "weibo_content", false, articleBounds));
        AccessibilityNodeInfo article = add(post, node(pkg, "android.view.View", "tweet_message", false, articleBounds));
        article.setContentDescription(news);
        AccessibilityNodeInfo unrelatedClose = add(post, node(pkg, "android.widget.ImageView", "close", true, closeBounds));
        AccessibilityNodeInfo card = add(root, node(pkg, "android.widget.LinearLayout", null, true,
                new Rect(33, 1097, 1047, 1424)));
        AccessibilityNodeInfo video = add(card, node(pkg, "android.widget.FrameLayout", "left_video_container", false, videoBounds));
        add(video, node(pkg, "android.view.View", null, false, videoBounds));
        add(video, node(pkg, "android.widget.FrameLayout", "left_video", true, videoBounds));
        AccessibilityNodeInfo badgeSlot = add(video, node(pkg, "android.widget.LinearLayout", "left_img_ad_container", false,
                new Rect(33, 1130, 497, 1182)));
        AccessibilityNodeInfo badge = add(badgeSlot, node(pkg, "android.widget.ImageView", "left_img_ad_tag", false,
                new Rect(403, 1130, 497, 1182)));
        AccessibilityNodeInfo details = add(card, node(pkg, "android.widget.LinearLayout", null, false,
                new Rect(497, 1130, 1047, 1391)));
        AccessibilityNodeInfo header = add(details, node(pkg, "android.widget.RelativeLayout", null, false,
                new Rect(497, 1130, 1047, 1158)));
        AccessibilityNodeInfo close = add(header, node(pkg, "android.widget.ImageView", "close", true, closeBounds));
        add(details, node(pkg, "android.widget.LinearLayout", "content_text_view_container", false,
                new Rect(497, 1158, 1008, 1314)));
        AccessibilityNodeInfo footer = add(details, node(pkg, "android.widget.RelativeLayout", "desc_container", false,
                new Rect(497, 1314, 1047, 1391)));
        add(footer, node(pkg, "android.widget.TextView", "desc", false, new Rect(536, 1314, 871, 1374)));
        AccessibilityNodeInfo download = add(footer, node(pkg, "android.widget.ImageView", "ad_tag", false,
                new Rect(882, 1314, 1047, 1374)));

        assertEquals(close, rules.find(root, activity, SCREEN).target);
        assertNull(rules.find(root, activity + "Other", SCREEN));
        close.setEnabled(false);
        assertNull(rules.find(root, activity, SCREEN));
        close.setEnabled(true);
        unrelatedClose.setVisibleToUser(false);

        badge.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
        badge.setVisibleToUser(true);
        download.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
        download.setVisibleToUser(true);
        video.setViewIdResourceName(pkg + ":id/ordinary_video");
        assertNull(rules.find(root, activity, SCREEN));
        video.setViewIdResourceName(pkg + ":id/left_video_container");

        article.setContentDescription(news + "。请确认支付");
        assertNull(rules.find(root, activity, SCREEN));
        article.setContentDescription(news);
        article.setClickable(true);
        assertNull(rules.find(root, activity, SCREEN));
        article.setClickable(false);
        article.setEditable(true);
        assertNull(rules.find(root, activity, SCREEN));
        article.setEditable(false);
        article.setFocusable(true);
        assertNull(rules.find(root, activity, SCREEN));
        article.setFocusable(false);
        article.setBoundsInScreen(closeBounds);
        assertNull(rules.find(root, activity, SCREEN));
        article.setBoundsInScreen(articleBounds);

        AccessibilityNodeInfo otherText = add(root, node(pkg, "android.view.View", null, false, articleBounds));
        otherText.setContentDescription(news);
        assertNull(rules.find(root, activity, SCREEN));
        otherText.setVisibleToUser(false);
        close.setEnabled(false);
        add(root, node(pkg, "android.widget.TextView", "tvTrendsTitle", false, BUTTON));
        AccessibilityNodeInfo existingClose = add(root, image(pkg, "iv_ad_x", true));
        assertNull(rules.find(root, activity, SCREEN));
        article.setContentDescription("普通博文");
        assertEquals(existingClose, rules.find(root, activity, SCREEN).target);
    }

    @Test public void weiboImageAdClosesOnlyItsMarkedImageCard() {
        // Snapshot 12673043 shows the native X next to a sponsored image.
        String pkg = "com.sina.weibo";
        String activity = pkg + ".feed.DetailWeiboActivity";
        Rect cardBounds = new Rect(33, 1230, 1047, 1557);
        Rect imageBounds = new Rect(33, 1263, 494, 1524);
        Rect closeBounds = new Rect(1019, 1263, 1047, 1291);
        AccessibilityNodeInfo root = frame(pkg);
        AccessibilityNodeInfo card = add(root, node(pkg, "android.widget.LinearLayout", null, true, cardBounds));
        AccessibilityNodeInfo picture = add(card, node(pkg, "android.widget.FrameLayout", "left_img_container", false, imageBounds));
        add(picture, node(pkg, "android.widget.ImageView", "left_img", false, imageBounds));
        add(picture, node(pkg, "android.view.View", null, false, imageBounds));
        AccessibilityNodeInfo badgeSlot = add(picture, node(pkg, "android.widget.LinearLayout", "left_img_ad_container", false, imageBounds));
        AccessibilityNodeInfo badge = add(badgeSlot, node(pkg, "android.widget.ImageView", "left_img_ad_tag", false,
                new Rect(400, 1263, 494, 1315)));
        AccessibilityNodeInfo details = add(card, node(pkg, "android.widget.LinearLayout", null, false,
                new Rect(494, 1263, 1047, 1524)));
        AccessibilityNodeInfo header = add(details, node(pkg, "android.widget.RelativeLayout", null, false,
                new Rect(494, 1263, 1047, 1291)));
        AccessibilityNodeInfo close = add(header, node(pkg, "android.widget.ImageView", "close", true, closeBounds));
        add(details, node(pkg, "android.widget.LinearLayout", "content_text_view_container", false,
                new Rect(494, 1291, 1008, 1447)));
        AccessibilityNodeInfo footer = add(details, node(pkg, "android.widget.RelativeLayout", "desc_container", false,
                new Rect(494, 1447, 1047, 1524)));
        add(footer, node(pkg, "android.widget.TextView", "desc", false, new Rect(533, 1447, 871, 1507)));
        AccessibilityNodeInfo download = add(footer, node(pkg, "android.widget.ImageView", "ad_tag", false,
                new Rect(882, 1447, 1047, 1507)));
        AccessibilityNodeInfo unrelatedClose = add(root, node(pkg, "android.widget.ImageView", "close", true, closeBounds));

        assertEquals(close, rules.find(root, activity, SCREEN).target);
        assertNull(rules.find(root, activity + "Other", SCREEN));
        close.setEnabled(false);
        assertNull(rules.find(root, activity, SCREEN));
        close.setEnabled(true);
        unrelatedClose.setVisibleToUser(false);
        badge.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
        badge.setVisibleToUser(true);
        download.setVisibleToUser(false);
        assertNull(rules.find(root, activity, SCREEN));
        download.setVisibleToUser(true);
        picture.setViewIdResourceName(pkg + ":id/ordinary_image");
        assertNull(rules.find(root, activity, SCREEN));
        picture.setViewIdResourceName(pkg + ":id/left_img_container");

        Rect articleBounds = new Rect(33, 205, 1047, 237);
        AccessibilityNodeInfo post = add(root, node(pkg, "android.widget.LinearLayout", "weibo_content", false, articleBounds));
        AccessibilityNodeInfo article = add(post, node(pkg, "android.view.View", "tweet_message", false, articleBounds));
        article.setContentDescription("支付宝将基于HarmonyOS NEXT版本开发应用");
        assertNull(rules.find(root, activity, SCREEN));
    }

    private static AccessibilityNodeInfo frame(String pkg) {
        return node(pkg, "android.widget.FrameLayout", null, false, SCREEN);
    }

    private static AccessibilityNodeInfo idlefishNightMarketClose(AccessibilityNodeInfo parent, String pkg) {
        AccessibilityNodeInfo web = add(parent, node(pkg, "android.webkit.WebView", null, false, SCREEN));
        web.setText("夜市氛围弹框");
        AccessibilityNodeInfo content = add(web, node(pkg, "android.view.View", null, false, SCREEN));
        content.setViewIdResourceName("root");
        add(content, frame(pkg));
        AccessibilityNodeInfo row = add(content, node(pkg, "android.view.View", null, false, SCREEN));
        AccessibilityNodeInfo wrapper = add(row, node(pkg, "android.view.View", null, false, SCREEN));
        AccessibilityNodeInfo close = add(wrapper, node(pkg, "android.widget.TextView", null, true, BUTTON));
        close.setText("");
        return close;
    }

    private static AccessibilityNodeInfo image(String pkg, String id, boolean clickable) {
        return node(pkg, "android.widget.ImageView", id, clickable, BUTTON);
    }

    private static AccessibilityNodeInfo node(String pkg, String cls, String id, boolean clickable, Rect bounds) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(pkg);
        node.setClassName(cls);
        node.setViewIdResourceName(id == null ? null : pkg + ":id/" + id);
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
