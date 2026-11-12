package app.clearscreen;

import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, shadows = SplashSkipperTest.ActiveRootService.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShortDramaAdTest {
    private static final String PKG = "com.phoenix.read";
    private static final String ACTIVITY = "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity";
    private static final AccessibilityAction PAGE_DOWN = new AccessibilityAction(ShortDramaAd.PAGE_DOWN, null);

    @Test public void requiresBothAdAnchorsAndThePagersAdvertisedVerticalAction() {
        Fixture f = new Fixture();
        PopupAdRules rules = new PopupAdRules(f.service);
        assertEquals(f.pager, rules.find(f.root, ACTIVITY, f.screen).target);
        assertNull(rules.find(f.root, ACTIVITY + "Other", f.screen));
        assertNull(rules.find(f.root, ACTIVITY, new Rect(0, 0, 2400, 1080)));
        f.pager.removeAction(PAGE_DOWN);
        f.pager.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD);
        assertNull(rules.find(f.root, ACTIVITY, f.screen));
        f.pager.addAction(PAGE_DOWN);
        f.hint.setText("8秒后可继续上滑观看短剧");
        assertNull(rules.find(f.root, ACTIVITY, f.screen));
        f.hint.setText("上滑继续观看短剧");
        f.badge.setVisibleToUser(false);
        assertNull(rules.find(f.root, ACTIVITY, f.screen));
        f.badge.setVisibleToUser(true);
        AccessibilityNodeInfo neighbor = f.add(f.list, "android.widget.FrameLayout", null, null);
        assertNull(rules.find(f.root, ACTIVITY, f.screen));
        neighbor.setVisibleToUser(false);
        f.add(f.root, "android.widget.TextView", null, "选集");
        assertNull(rules.find(f.root, ACTIVITY, f.screen));
    }

    @Test public void sendsOneNativePageActionAndCountsOnlyTheVerifiedPlaybackResult() {
        Fixture f = new Fixture();
        try {
            shadowOf(f.pager).setOnPerformActionListener((action, args) -> true);
            f.open();
            f.idle(250);
            assertEquals(List.of(ShortDramaAd.PAGE_DOWN), shadowOf(f.pager).getPerformedActions());
            assertTrue(shadowOf(f.hint).getPerformedActions().isEmpty());
            assertTrue(shadowOf(f.reward).getPerformedActions().isEmpty());
            f.showPlayback();
            assertTrue(ShortDramaAd.resumed(f.pager, f.screen, UiDriver.walkComplete(f.root)));
            f.idle(1_100);
            assertEquals(1, f.store.popupCount());
            f.showAdAgain();
            assertNotNull(new PopupAdRules(f.service).find(f.root, ACTIVITY, f.screen));
            f.idle(2_000);
            assertEquals(List.of(ShortDramaAd.PAGE_DOWN), shadowOf(f.pager).getPerformedActions());
        } finally {
            f.skipper.close();
        }
    }

    @Test public void matchesWhenAndroidOmitsTheInternalRecyclerView() {
        Fixture f = new Fixture(false);
        assertEquals(f.pager, new PopupAdRules(f.service).find(f.root, ACTIVITY, f.screen).target);
    }

    @Test public void respectsScrollingBeforeDispatchAndDoesNotRetryAnActionTheAppRejected() {
        Fixture f = new Fixture();
        try {
            shadowOf(f.pager).setOnPerformActionListener((action, args) -> false);
            f.open();
            f.idle(80);
            f.send(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            f.idle(1_100);
            assertTrue(shadowOf(f.pager).getPerformedActions().isEmpty());
            f.send(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            f.idle(1_000);
            f.send(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            f.idle(1_000);
            assertEquals(List.of(ShortDramaAd.PAGE_DOWN), shadowOf(f.pager).getPerformedActions());
            assertEquals(0, f.store.popupCount());
        } finally {
            f.skipper.close();
        }
    }

    @Test public void doesNotCountPlaybackAfterAnUnattributedScroll() {
        Fixture f = new Fixture();
        try {
            shadowOf(f.pager).setOnPerformActionListener((action, args) -> true);
            f.open();
            f.idle(250);
            f.send(AccessibilityEvent.TYPE_VIEW_SCROLLED);
            f.showPlayback();
            f.idle(1_100);
            assertEquals(List.of(ShortDramaAd.PAGE_DOWN), shadowOf(f.pager).getPerformedActions());
            assertEquals(0, f.store.popupCount());
        } finally {
            f.skipper.close();
        }
    }

    private static final class Fixture {
        final CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        final Store store = new Store(service);
        final AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        final Rect screen;
        final AccessibilityNodeInfo root, pager, list, ad, badge, hint, reward;
        AccessibilityNodeInfo playback, episodes, fullScreen;
        SplashSkipper skipper;

        Fixture() {
            this(true);
        }

        Fixture(boolean includesList) {
            Point size = new Point();
            service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size);
            screen = new Rect(0, 0, size.x, size.y);
            shadowOf(window).setId(42);
            root = add(null, "android.widget.FrameLayout", null, null);
            pager = add(root, "androidx.viewpager.widget.ViewPager", "l0", null);
            pager.setScrollable(true);
            pager.addAction(PAGE_DOWN);
            list = includesList ? add(pager, "androidx.recyclerview.widget.RecyclerView", null, null) : pager;
            ad = add(list, "android.widget.FrameLayout", null, null);
            AccessibilityNodeInfo wrap = add(ad, "android.widget.FrameLayout", null, null);
            AccessibilityNodeInfo card = add(wrap, "android.view.ViewGroup", null, null);
            AccessibilityNodeInfo artwork = add(card, "android.widget.FrameLayout", "container", null);
            for (int i = 0; i < 6; i++) artwork = add(artwork, "android.view.ViewGroup", null, null);
            badge = add(artwork, "android.view.ViewGroup", null, null);
            badge.setContentDescription("广告");
            AccessibilityNodeInfo prompt = add(card, "android.widget.FrameLayout", "hi", null);
            for (int i = 0; i < 3; i++) prompt = add(prompt, "android.widget.FrameLayout", null, null);
            hint = add(prompt, "android.widget.TextView", "ank", "上滑继续观看短剧");
            reward = add(root, "android.widget.TextView", null, "立即领取");
            reward.setClickable(true);
        }

        AccessibilityNodeInfo add(AccessibilityNodeInfo parent, String cls, String id, String text) {
            AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
            node.setClassName(cls);
            node.setPackageName(PKG);
            if (id != null) node.setViewIdResourceName(PKG + ":id/" + id);
            node.setText(text);
            node.setVisibleToUser(true);
            node.setEnabled(true);
            node.setBoundsInScreen(screen);
            shadowOf(node).setAccessibilityWindowInfo(window);
            if (parent != null) shadowOf(parent).addChild(node);
            return node;
        }

        void open() {
            store.setSplashEnabled(false);
            store.setPopupEnabled(true);
            store.setSkipToastEnabled(false);
            shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(PKG, ACTIVITY));
            SplashSkipperTest.ActiveRootService shadow = Shadow.extract(service);
            shadow.activeRoot = () -> root;
            skipper = new SplashSkipper(service);
            send(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }

        void showPlayback() {
            setAdVisible(false);
            playback = add(list, "android.widget.FrameLayout", null, null);
            episodes = add(playback, "android.widget.TextView", "k2p", "选集");
            fullScreen = add(playback, "android.widget.TextView", "css", "全屏观看");
            send(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }

        void showAdAgain() {
            playback.setVisibleToUser(false);
            episodes.setVisibleToUser(false);
            fullScreen.setVisibleToUser(false);
            setAdVisible(true);
            send(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }

        private void setAdVisible(boolean visible) {
            ad.setVisibleToUser(visible);
            badge.setVisibleToUser(visible);
            hint.setVisibleToUser(visible);
        }

        void send(int type) {
            AccessibilityEvent event = AccessibilityEvent.obtain(type);
            event.setPackageName(PKG);
            event.setClassName(ACTIVITY);
            shadowOf(event).setWindowId(window.getId());
            skipper.event(event);
            event.recycle();
        }

        void idle(long millis) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
        }
    }
}
