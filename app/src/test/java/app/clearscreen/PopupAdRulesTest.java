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

    private static AccessibilityNodeInfo frame(String pkg) {
        return node(pkg, "android.widget.FrameLayout", null, false, SCREEN);
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
