package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
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
@Config(sdk = {24, 25, 28}, shadows = SplashSkipperTest.ActiveRootService.class)
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

    @Test public void catalogPopupCanRetryWindowCheckButDoesNotRetryARejectedClick() {
        String pkg = "com.sdu.didi.psnger";
        String activity = "com.didi.sdk.app.MainActivity";
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(pkg, activity));
        ActiveRootService shadowService = Shadow.extract(service);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect closeBounds = new Rect(display.x * 3 / 4, display.y / 4,
                display.x * 9 / 10, display.y / 4 + 36);
        AccessibilityNodeInfo root = node("", new Rect(0, 0, display.x, display.y));
        root.setPackageName(pkg);
        AccessibilityNodeInfo banner = node("", closeBounds);
        banner.setPackageName(pkg);
        banner.setClassName("android.widget.ImageView");
        banner.setViewIdResourceName(pkg + ":id/second_entrance_img");
        AccessibilityNodeInfo button = node("", closeBounds);
        button.setPackageName(pkg);
        button.setClassName("android.widget.ImageView");
        button.setViewIdResourceName(pkg + ":id/left_close_img");
        button.setClickable(true);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        shadowOf(root).addChild(banner);
        shadowOf(root).addChild(button);
        new Store(service).setPopupEnabled(true);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            AccessibilityEvent opened = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            opened.setPackageName(pkg);
            opened.setClassName(activity);
            skipper.event(opened);
            opened.recycle();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(65));

            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> reads.getAndIncrement() == 0 ? root : null;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));
            assertEquals(2, reads.get());
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());

            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg);
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
            SkipDiagnostics.LOCAL.start(PKG, 1);
            shadowService.activeRoot = () -> ad;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertTrue(RulePreview.LOCAL.summary(true).contains("规则匹配通过"));
            assertTrue(SkipDiagnostics.LOCAL.summary(true, true)
                    .startsWith("净屏开屏诊断\n" + SkipDiagnostics.Phase.PREVIEW_ACTIVE.label));
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
            assertTrue(SkipDiagnostics.LOCAL.summary(true, true)
                    .startsWith("净屏开屏诊断\n" + SkipDiagnostics.Phase.CONFIRMED.label));
        } finally {
            RulePreview.LOCAL.clear();
            SkipDiagnostics.LOCAL.clear();
            skipper.close();
        }
    }

    @Test public void delayedLaunchWindowCanConfirmAfterPollingEnds() {
        assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), clicksAfterDelayedWindow(42, 42, 550, 0, false));
    }

    @Test public void returnRuleNeedsAVerifiedWindowInPreviewAndExecution() {
        verifyReturnRuleWindow(-1, false);
        verifyReturnRuleWindow(42, true);
    }

    private static void verifyReturnRuleWindow(int windowId, boolean eligible) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityNodeInfo home = node("净屏", new Rect(0, 0, display.x, display.y));
        home.setPackageName(service.getPackageName());
        AccessibilityNodeInfo modal = node("", new Rect(display.x / 5, display.y / 3,
                display.x * 4 / 5, display.y * 2 / 3));
        AccessibilityNodeInfo prompt = node("发现新版本", new Rect(display.x / 4, display.y * 2 / 5,
                display.x * 3 / 4, display.y / 2));
        if (windowId >= 0) {
            AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
            shadowOf(window).setId(windowId);
            shadowOf(modal).setAccessibilityWindowInfo(window);
            shadowOf(prompt).setAccessibilityWindowInfo(window);
        }
        shadowOf(modal).addChild(prompt);
        CustomSkipRule rule = new CustomSkipRule(PKG, CustomSkipRule.POPUP, CustomSkipRule.BACK,
                "=发现新版本", false, 0, false, 1);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.saveCustomSkipRules(List.of(rule));
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            assertTrue(skipper.startRulePreview(List.of(rule)));
            shadowService.activeRoot = () -> modal;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, windowId);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
            String report = RulePreview.LOCAL.summary(true);
            assertEquals(eligible, report.contains("规则匹配通过"));
            if (!eligible) assertTrue(report.contains(SkipDiagnostics.Phase.WINDOW_UNAVAILABLE.label));
            assertTrue(shadowService.getGlobalActionsPerformed().isEmpty());

            shadowService.activeRoot = () -> home;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, service.getPackageName());
            RulePreview.LOCAL.clear();
            shadowService.activeRoot = () -> modal;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, windowId);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
            assertEquals(eligible ? List.of(AccessibilityService.GLOBAL_ACTION_BACK) : List.of(),
                    shadowService.getGlobalActionsPerformed());
        } finally {
            RulePreview.LOCAL.clear();
            skipper.close();
        }
    }

    @Test public void userClickBeforeSplashReadbackDoesNotCountAsAutomaticDismissal() throws Exception {
        assertDismissalAfterClick(true, false);
    }

    @Test public void userClickBeforePopupReadbackDoesNotCountAsAutomaticDismissal() throws Exception {
        assertDismissalAfterClick(false, false);
    }

    @Test public void popupServiceOwnClickEventStillAllowsConfirmation() throws Exception {
        assertDismissalAfterClick(false, true);
    }

    private static void assertDismissalAfterClick(boolean splash, boolean ownClick) throws Exception {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(splash);
        store.setPopupEnabled(!splash);
        store.setSkipToastEnabled(false);
        store.setKeepSkipDetails(true);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityNodeInfo root = node("", screen);
        AccessibilityNodeInfo panel = splash ? root : node("", new Rect(display.x / 5, display.y / 3,
                display.x * 4 / 5, display.y * 2 / 3));
        if (!splash) {
            shadowOf(root).addChild(panel);
            shadowOf(panel).addChild(node("广告", new Rect(display.x / 4, display.y * 2 / 5,
                    display.x * 3 / 4, display.y / 2)));
        }
        Rect bounds = splash ? new Rect(display.x * 3 / 4, display.y / 20, display.x * 19 / 20, display.y / 10)
                : new Rect(display.x / 3, display.y / 2, display.x * 2 / 3, display.y * 3 / 5);
        AccessibilityNodeInfo button = node("关闭广告", bounds);
        button.setClickable(true);
        shadowOf(panel).addChild(button);
        shadowOf(button).setOnPerformActionListener((action, args) -> true);
        AccessibilityNodeInfo content = node("普通内容", screen);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            AccessibilityEvent clicked = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
            clicked.setPackageName(PKG);
            shadowOf(clicked).setWindowId(button.getWindowId());
            shadowOf(clicked).setSourceNode(ownClick ? button : content);
            skipper.event(clicked);
            clicked.recycle();
            shadowService.activeRoot = () -> content;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(ownClick ? 1 : 0, splash ? store.splashCount() : store.popupCount());
            assertEquals(ownClick, store.read(splash ? "splash-history" : "popup-history")
                    .getJSONObject(0).getBoolean("confirmed"));
        } finally {
            skipper.close();
        }
    }

    @Test public void delayedContentNeedsAFreshSignalForTheSameWindow() {
        assertTrue(clicksAfterDelayedWindow(null, 42, 550, 0, false).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 43, 550, 0, false).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 42, 1_500, 0, false).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 42, 550, 0, true).isEmpty());
    }

    @Test public void delayedWindowDoesNotRestartLaunchDeadline() {
        assertTrue(clicksAfterDelayedWindow(42, 42, 550, SplashSkipper.WINDOW_MS - 450, false).isEmpty());
    }

    private static List<Integer> clicksAfterDelayedWindow(Integer stateWindow, int rootWindow,
                                                          long rootDelay, long buttonDelay, boolean userClicked) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(true);
        store.setSkipToastEnabled(false);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(rootWindow);
        AccessibilityNodeInfo root = node("", new Rect(0, 0, 1080, 2400));
        AccessibilityNodeInfo button = node("关闭广告", new Rect(810, 120, 1030, 220));
        button.setClickable(true);
        shadowOf(root).setAccessibilityWindowInfo(window);
        shadowOf(button).setAccessibilityWindowInfo(window);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        if (buttonDelay == 0) shadowOf(root).addChild(button);

        SplashSkipper skipper = new SplashSkipper(service);
        try {
            if (stateWindow != null)
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, stateWindow);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(rootDelay));
            if (userClicked) sendWindowEvent(skipper, AccessibilityEvent.TYPE_VIEW_CLICKED, PKG, rootWindow);
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, rootWindow);
            if (buttonDelay > 0) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(buttonDelay));
                shadowOf(root).addChild(button);
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, rootWindow);
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            return shadowOf(button).getPerformedActions();
        } finally {
            skipper.close();
        }
    }

    @Test public void warmSplashAndServiceReconnectEachStartAFreshDiagnostic() {
        String pkg = "com.tencent.qqlive";
        String hotActivity = "com.tencent.qqlive.qadsplash.template.QAdHotSplashActivity";
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(pkg, hotActivity));
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(true);
        store.setSkipToastEnabled(false);
        Rect screen = new Rect(0, 0, 1080, 2400);
        Rect corner = new Rect(810, 120, 1030, 220);
        AccessibilityNodeInfo ad = node("", screen);
        ad.setPackageName(pkg);
        AccessibilityNodeInfo close = node("关闭广告", corner);
        close.setPackageName(pkg);
        close.setClickable(true);
        shadowOf(ad).addChild(close);
        AccessibilityNodeInfo content = node("普通内容", screen);
        content.setPackageName(pkg);
        shadowOf(close).setOnPerformActionListener((action, args) -> {
            shadowService.activeRoot = () -> content;
            return true;
        });

        SkipDiagnostics diagnostics = SkipDiagnostics.LOCAL;
        diagnostics.start(pkg, 1);
        SplashSkipper first = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> ad;
            sendWindowEvent(first, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertTrue(diagnostics.summary(true, true)
                    .startsWith("净屏开屏诊断\n" + SkipDiagnostics.Phase.CONFIRMED.label));

            AccessibilityEvent opened = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            opened.setPackageName(pkg);
            opened.setClassName(hotActivity);
            first.event(opened);
            opened.recycle();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            String warmReport = diagnostics.summary(true, true);
            assertTrue(warmReport.contains(SkipDiagnostics.Phase.NO_READABLE_TARGET.label));
            assertFalse(warmReport.contains("CONFIRMED"));

            first.close();
            SplashSkipper reconnected = new SplashSkipper(service);
            try {
                AccessibilityNodeInfo skip = node("跳过 5", corner);
                skip.setPackageName(pkg);
                skip.setClickable(true);
                shadowOf(content).addChild(skip);
                sendWindowEvent(reconnected, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
                String report = diagnostics.summary(true, true);
                assertTrue(report.contains(SkipDiagnostics.Phase.AD_EVIDENCE_MISSING.label));
                assertFalse(report.contains("NO_READABLE_TARGET"));
            } finally {
                reconnected.close();
            }
        } finally {
            first.close();
            diagnostics.clear();
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
        sendWindowEvent(skipper, type, pkg, -1);
    }

    private static void sendWindowEvent(SplashSkipper skipper, int type, String pkg, int windowId) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        shadowOf(event).setWindowId(windowId);
        skipper.event(event);
        event.recycle();
    }
}
