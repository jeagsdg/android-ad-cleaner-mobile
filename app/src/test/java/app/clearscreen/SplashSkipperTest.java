package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityService;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = SplashSkipperTest.ActiveRootService.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class SplashSkipperTest {
    private static final String PKG = "test.app";

    @Implements(AccessibilityService.class)
    public static class ActiveRootService extends ShadowAccessibilityService {
        Supplier<AccessibilityNodeInfo> activeRoot;

        @Implementation protected AccessibilityNodeInfo getRootInActiveWindow() {
            return activeRoot == null ? null : activeRoot.get();
        }
    }

    @Test public void genericPopupCanRetryWindowCheckButOnlyAttemptsOneClick() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityNodeInfo root = node("", new Rect(0, 0, display.x, display.y));
        AccessibilityNodeInfo panel = node("", new Rect(display.x / 5, display.y / 3,
                display.x * 4 / 5, display.y * 2 / 3));
        AccessibilityNodeInfo button = node("关闭广告", new Rect(display.x / 3, display.y / 2,
                display.x * 2 / 3, display.y * 3 / 5));
        button.setClickable(true);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        shadowOf(root).addChild(panel);
        shadowOf(panel).addChild(button);
        shadowOf(panel).addChild(node("广告", new Rect(display.x / 4, display.y * 2 / 5,
                display.x * 3 / 4, display.y / 2)));
        new Store(service).setPopupEnabled(true);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(65));

            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> reads.getAndIncrement() == 0 ? root : null;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(180));
            assertEquals(2, reads.get());
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());

            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
        } finally {
            skipper.close();
        }
    }

    @Test public void splashPreviewMatchesWithoutClickingAndReopeningAfterClearSkips() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(true);
        store.setSkipToastEnabled(false);
        long before = store.splashCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityNodeInfo home = node("净屏", screen);
        home.setPackageName(service.getPackageName());
        AccessibilityNodeInfo ad = node("", screen);
        AccessibilityNodeInfo button = node("跳过 5", new Rect(display.x * 3 / 4, display.y / 20,
                display.x * 19 / 20, display.y / 10));
        button.setClickable(true);
        shadowOf(ad).addChild(button);
        shadowOf(ad).addChild(node("广告", new Rect(0, display.y * 3 / 4, display.x / 12, display.y * 4 / 5)));
        AccessibilityNodeInfo content = node("已进入测试内容", screen);
        shadowOf(button).setOnPerformActionListener((action, args) -> {
            shadowService.activeRoot = () -> content;
            return true;
        });

        CustomSkipRule draft = new CustomSkipRule(PKG, "", CustomSkipRule.SPLASH, "", "跳过 5", "");
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> home;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, service.getPackageName());
            assertTrue(skipper.startRulePreview(List.of(draft)));
            shadowService.activeRoot = () -> ad;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(RulePreview.LOCAL.summary(true).contains("规则匹配通过"));
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());
            assertEquals(before, store.splashCount());

            shadowService.activeRoot = () -> home;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, service.getPackageName());
            RulePreview.LOCAL.clear();
            shadowService.activeRoot = () -> ad;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            assertSame(content, service.getRootInActiveWindow());
            assertEquals(before + 1, store.splashCount());
        } finally {
            RulePreview.LOCAL.clear();
            skipper.close();
        }
    }

    private static AccessibilityNodeInfo node(String text, Rect bounds) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(PKG);
        node.setVisibleToUser(true);
        node.setEnabled(true);
        node.setText(text);
        node.setBoundsInScreen(bounds);
        return node;
    }

    private static void sendWindowEvent(SplashSkipper skipper, int type) {
        sendWindowEvent(skipper, type, PKG);
    }

    private static void sendWindowEvent(SplashSkipper skipper, int type, String pkg) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        skipper.event(event);
        event.recycle();
    }
}
