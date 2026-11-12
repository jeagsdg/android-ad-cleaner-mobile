package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.SystemClock;
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
import org.robolectric.util.ReflectionHelpers;
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

    @Test public void genericPopupRetriesOneMissingRootWithoutAnotherContentEvent() {
        assertInitialPopupRootRetry(PopupRootChange.RECOVERED);
        assertInitialPopupRootRetry(PopupRootChange.STILL_MISSING);
    }

    @Test public void partiallyReadPopupWaitsForOneCompleteTreeInTheSameWindow() {
        assertPartialPopupTreeRetry(false);
        assertPartialPopupTreeRetry(true);
    }

    private static void assertPartialPopupTreeRetry(boolean recovers) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(false);
        store.setPopupEnabled(true);
        store.setSkipToastEnabled(false);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityWindowInfo window = windowOnDisplay(42, Display.DEFAULT_DISPLAY);
        AccessibilityNodeInfo partial = windowNode("", screen, window);
        AccessibilityNodeInfo full = windowNode("", screen, window);
        AccessibilityNodeInfo partialButton = addGenericPopup(partial, display, window);
        AccessibilityNodeInfo fullButton = addGenericPopup(full, display, window);
        shadowOf(partialButton).setOnPerformActionListener((action, args) -> false);
        shadowOf(fullButton).setOnPerformActionListener((action, args) -> false);
        List<AccessibilityNodeInfo> children = ReflectionHelpers.getField(shadowOf(partial), "children");
        children.add(null);
        assertNull(partial.getChild(partial.getChildCount() - 1));

        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> partial;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, window.getId());
            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> reads.getAndIncrement() == 0 || !recovers ? partial : full;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertTrue(shadowOf(partialButton).getPerformedActions().isEmpty());
            if (recovers) {
                assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(fullButton).getPerformedActions());
            } else {
                assertEquals(2, reads.get());
                assertTrue(shadowOf(fullButton).getPerformedActions().isEmpty());
            }
        } finally {
            skipper.close();
        }
    }

    @Test public void genericPopupRootRetryDoesNotSurviveInputOrAWindowChange() {
        assertInitialPopupRootRetry(PopupRootChange.USER_CLICK);
        assertInitialPopupRootRetry(PopupRootChange.NEW_WINDOW);
        assertInitialPopupRootRetry(PopupRootChange.NEW_PACKAGE);
        assertInitialPopupRootRetry(PopupRootChange.MISMATCH);
    }

    @Test @Config(sdk = 34)
    public void scrollingCancelsTheQueuedPopupRootRetry() {
        assertInitialPopupRootRetry(PopupRootChange.USER_SCROLL);
    }

    private enum PopupRootChange { RECOVERED, STILL_MISSING, USER_CLICK, USER_SCROLL, NEW_WINDOW, NEW_PACKAGE, MISMATCH }

    private static void assertInitialPopupRootRetry(PopupRootChange change) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.setSplashEnabled(false);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityWindowInfo window = windowOnDisplay(42, Display.DEFAULT_DISPLAY);
        AccessibilityWindowInfo nextWindow = windowOnDisplay(43, Display.DEFAULT_DISPLAY);
        AccessibilityNodeInfo root = windowNode("", screen, window);
        AccessibilityNodeInfo nextRoot = windowNode("", screen, nextWindow);
        AccessibilityNodeInfo button = addGenericPopup(root, display, window);
        AccessibilityNodeInfo nextButton = addGenericPopup(nextRoot, display, nextWindow);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        shadowOf(nextButton).setOnPerformActionListener((action, args) -> false);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, window.getId());
            AtomicInteger probes = new AtomicInteger();
            shadowService.activeRoot = () -> {
                probes.incrementAndGet();
                return change == PopupRootChange.MISMATCH ? nextRoot : null;
            };
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(65));
            assertEquals(1, probes.get());
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            if (change == PopupRootChange.USER_CLICK) {
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_VIEW_CLICKED, PKG, window.getId());
            }
            if (change == PopupRootChange.USER_SCROLL) {
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_VIEW_SCROLLED, PKG, window.getId());
            }
            if (change == PopupRootChange.NEW_WINDOW || change == PopupRootChange.NEW_PACKAGE) {
                String pkg = change == PopupRootChange.NEW_PACKAGE ? "another.app" : PKG;
                for (AccessibilityNodeInfo node : UiDriver.walkComplete(nextRoot)) node.setPackageName(pkg);
                shadowService.activeRoot = () -> nextRoot;
                AccessibilityEvent changed = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
                changed.setPackageName(pkg);
                shadowOf(changed).setWindowId(nextWindow.getId());
                changed.setContentChangeTypes(AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION);
                skipper.event(changed);
                changed.recycle();
            }
            shadowService.activeRoot = () -> {
                probes.incrementAndGet();
                switch (change) {
                    case STILL_MISSING: return null;
                    case NEW_WINDOW:
                    case NEW_PACKAGE: return nextRoot;
                    default: return root;
                }
            };
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            if (change == PopupRootChange.RECOVERED) {
                assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, window.getId());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
                assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            } else {
                assertEquals(change.name(), change == PopupRootChange.STILL_MISSING ? 2 : 1, probes.get());
                assertTrue(change.name(), shadowOf(button).getPerformedActions().isEmpty());
                assertTrue(change.name(), shadowOf(nextButton).getPerformedActions().isEmpty());
            }
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
        AccessibilityWindowInfo window = windowOnDisplay(42, Display.DEFAULT_DISPLAY);
        AccessibilityNodeInfo root = windowNode("", new Rect(0, 0, display.x, display.y), window);
        root.setPackageName(pkg);
        AccessibilityNodeInfo banner = windowNode("", closeBounds, window);
        banner.setPackageName(pkg);
        banner.setClassName("android.widget.ImageView");
        banner.setViewIdResourceName(pkg + ":id/second_entrance_img");
        AccessibilityNodeInfo button = windowNode("", closeBounds, window);
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
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, window.getId(), activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(65));

            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> reads.getAndIncrement() == 0 ? root : null;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(130));
            assertEquals(2, reads.get());
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());

            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
        } finally {
            skipper.close();
        }
    }

    @Test public void timeLimitedCatalogPopupRestartsOnlyForANewVerifiedActivityWindow() {
        String pkg = "com.jingdong.pdj";
        String activity = "pdj.main.MainActivity";
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(pkg, activity));
        ActiveRootService shadowService = Shadow.extract(service);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        new Store(service).setPopupEnabled(true);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            for (int windowId = 41; windowId <= 43; windowId++) {
                AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
                shadowOf(window).setId(windowId);
                AccessibilityNodeInfo root = windowNode("", new Rect(0, 0, display.x, display.y), window);
                root.setPackageName(pkg);
                AccessibilityNodeInfo close = windowNode("", new Rect(display.x * 3 / 4, display.y / 4,
                        display.x * 9 / 10, display.y / 4 + 36), window);
                close.setPackageName(pkg);
                close.setContentDescription("关闭");
                close.setViewIdResourceName(pkg + ":id/ivClose");
                close.setClickable(true);
                shadowOf(close).setOnPerformActionListener((action, args) -> false);
                shadowOf(root).addChild(close);
                shadowService.activeRoot = () -> root;

                String windowClass = windowId == 42 ? "android.app.Dialog" : activity;
                if (windowId == 41) {
                    close.setVisibleToUser(false);
                    sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, windowId, windowClass);
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
                    close.setVisibleToUser(true);
                }
                sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, windowId, windowClass);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
                assertEquals("window " + windowId,
                        windowId == 43 ? List.of(AccessibilityNodeInfo.ACTION_CLICK) : List.of(),
                        shadowOf(close).getPerformedActions());
            }
        } finally {
            skipper.close();
        }
    }

    @Test public void splashRevalidatesNativeClickWhenTheSameNodeChangesPurpose() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setSplashEnabled(true);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityNodeInfo root = node("", new Rect(0, 0, display.x, display.y));
        AccessibilityNodeInfo button = node("跳过 5", new Rect(display.x * 3 / 4, display.y / 20,
                display.x * 19 / 20, display.y / 10));
        button.setClickable(true);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        shadowOf(root).addChild(button);
        shadowOf(root).addChild(node("广告", new Rect(0, display.y * 3 / 4, display.x / 12, display.y * 4 / 5)));
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> {
                if (reads.incrementAndGet() == 2) button.setText("查看详情");
                return root;
            };
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            assertTrue(reads.get() >= 2);
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            button.setText("跳过 5");
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
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
        assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), clicksAfterDelayedWindow(42, 42, 550, 0, null));
    }

    @Test public void previewReadsAControlDrawnWithoutAnotherAccessibilityEvent() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        String activity = PKG + ".MainActivity";
        shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(PKG, activity));
        ActiveRootService shadowService = Shadow.extract(service);
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityWindowInfo window = windowOnDisplay(42, Display.DEFAULT_DISPLAY);
        AccessibilityNodeInfo root = windowNode("", new Rect(0, 0, display.x, display.y), window);
        AccessibilityNodeInfo button = windowNode("跳过 5", new Rect(display.x * 3 / 4, display.y / 20,
                display.x * 19 / 20, display.y / 10), window);
        button.setClickable(true);
        CustomSkipRule rule = new CustomSkipRule(PKG, activity, CustomSkipRule.SPLASH, "", "跳过 5", "");
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            assertTrue(skipper.startRulePreview(List.of(rule)));
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, window.getId(), activity);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120));
            assertTrue(RulePreview.LOCAL.summary(true).contains(SkipDiagnostics.Phase.NO_READABLE_TARGET.label));

            shadowOf(root).addChild(button);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
            assertTrue(RulePreview.LOCAL.summary(true).contains("规则匹配通过"));
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());
        } finally {
            RulePreview.LOCAL.clear();
            skipper.close();
        }
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

    @Test public void popupWithoutAnEventSourceRequiresUniqueClickEvidence() {
        assertSourceLessPopupClick("关闭广告", false, true);
        assertSourceLessPopupClick("查看详情", false, false);
        assertSourceLessPopupClick("关闭广告", true, false);
    }

    private static void assertSourceLessPopupClick(String eventText, boolean ambiguous, boolean confirmed) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.setSkipToastEnabled(false);
        long before = store.popupCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(42);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityNodeInfo root = windowNode("", screen, window);
        AccessibilityNodeInfo button = addGenericPopup(root, display, window);
        button.setClassName("android.widget.Button");
        shadowOf(button).setOnPerformActionListener((action, args) -> true);
        if (ambiguous) {
            AccessibilityNodeInfo duplicate = windowNode("关闭广告",
                    new Rect(15, 15, display.x / 4, display.y / 10), window);
            duplicate.setClassName("android.widget.Button");
            duplicate.setClickable(true);
            shadowOf(root).addChild(duplicate);
        }
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, 42);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());

            AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED);
            event.setPackageName(PKG);
            event.setClassName("android.widget.Button");
            event.setEventTime(SystemClock.uptimeMillis());
            event.getText().add(eventText);
            shadowOf(event).setSourceNode(null);
            shadowOf(event).setWindowId(42);
            assertNull(event.getSource());
            skipper.event(event);
            event.recycle();
            AccessibilityNodeInfo content = windowNode("普通内容", screen, window);
            shadowService.activeRoot = () -> content;
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(before + (confirmed ? 1 : 0), store.popupCount());
        } finally {
            skipper.close();
        }
    }

    @Test public void partialReadbackCannotCountTheAdAsGone() throws Exception {
        assertDismissalAfterClick(true, true, true);
        assertDismissalAfterClick(false, true, true);
    }

    private static void assertDismissalAfterClick(boolean splash, boolean ownClick) throws Exception {
        assertDismissalAfterClick(splash, ownClick, false);
    }

    private static void assertDismissalAfterClick(boolean splash, boolean ownClick, boolean partialReadback) throws Exception {
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
        if (partialReadback) {
            shadowOf(content).addChild(node("普通标题", screen));
            List<AccessibilityNodeInfo> children = ReflectionHelpers.getField(shadowOf(content), "children");
            children.add(null);
        }
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
            boolean confirmed = ownClick && !partialReadback;
            assertEquals(confirmed ? 1 : 0, splash ? store.splashCount() : store.popupCount());
            assertEquals(confirmed, store.read(splash ? "splash-history" : "popup-history")
                    .getJSONObject(0).getBoolean("confirmed"));
        } finally {
            skipper.close();
        }
    }

    @Test public void delayedContentNeedsAFreshSignalForTheSameWindow() {
        assertTrue(clicksAfterDelayedWindow(null, 42, 550, 0, null).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 43, 550, 0, null).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 42, 1_500, 0, null).isEmpty());
        assertTrue(clicksAfterDelayedWindow(42, 42, 550, 0, AccessibilityEvent.TYPE_VIEW_CLICKED).isEmpty());
    }

    @Test public void delayedWindowDoesNotRestartLaunchDeadline() {
        assertTrue(clicksAfterDelayedWindow(42, 42, 550, SplashSkipper.WINDOW_MS - 450, null).isEmpty());
    }

    @Test @Config(sdk = 34)
    public void scrollInThePendingLaunchWindowCancelsTheQueuedDismissal() {
        assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), clicksAfterDelayedWindow(42, 42, 550, 0, null));
        assertTrue(clicksAfterDelayedWindow(42, 42, 550, 0, AccessibilityEvent.TYPE_VIEW_SCROLLED).isEmpty());
    }

    private static List<Integer> clicksAfterDelayedWindow(Integer stateWindow, int rootWindow,
                                                          long rootDelay, long buttonDelay, Integer inputType) {
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
            if (inputType != null) sendWindowEvent(skipper, inputType, PKG, rootWindow);
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

    @Test public void wechatMiniProgramEntryRearmsOnlyForTheExactCatalogRule() {
        String pkg = "com.tencent.mm";
        String home = pkg + ".ui.LauncherUI";
        String miniProgram = pkg + ".plugin.appbrand.ui.AppBrandPluginUI";
        String standardMiniProgram = pkg + ".plugin.appbrand.ui.AppBrandUI00";
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        for (String activity : List.of(home, miniProgram, standardMiniProgram, miniProgram + "Other")) {
            shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(pkg, activity));
        }
        ActiveRootService shadowService = Shadow.extract(service);
        new Store(service).setSplashEnabled(true);
        SkipDiagnostics diagnostics = SkipDiagnostics.LOCAL;
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            Rect screen = new Rect(0, 0, 1080, 2400);
            AccessibilityWindowInfo homeWindow = AccessibilityWindowInfo.obtain();
            shadowOf(homeWindow).setId(41);
            AccessibilityNodeInfo homeRoot = windowNode("", screen, homeWindow);
            homeRoot.setPackageName(pkg);
            assertEquals(41, homeRoot.getWindowId());
            shadowService.activeRoot = () -> homeRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 41, home);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SplashSkipper.WINDOW_MS + 400));

            AccessibilityWindowInfo miniProgramWindow = AccessibilityWindowInfo.obtain();
            shadowOf(miniProgramWindow).setId(42);
            AccessibilityNodeInfo miniProgramRoot = windowNode("", screen, miniProgramWindow);
            miniProgramRoot.setPackageName(pkg);
            assertEquals(42, miniProgramRoot.getWindowId());
            AccessibilityNodeInfo genericClose = windowNode("关闭广告", new Rect(820, 100, 1010, 190), miniProgramWindow);
            genericClose.setPackageName(pkg);
            genericClose.setClickable(true);
            shadowOf(miniProgramRoot).addChild(genericClose);

            diagnostics.start(pkg, 1);
            shadowService.activeRoot = () -> miniProgramRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 42, miniProgram + "Other");
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertFalse(diagnostics.summary(true, true).contains("\n观察开始 "));
            assertTrue(shadowOf(genericClose).getPerformedActions().isEmpty());

            shadowService.activeRoot = () -> homeRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 41, home);
            shadowService.activeRoot = () -> miniProgramRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 42, miniProgram);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            String report = diagnostics.summary(true, true);
            assertTrue(report, report.contains("\n观察开始 "));
            assertTrue(shadowOf(genericClose).getPerformedActions().isEmpty());

            diagnostics.start(pkg, 1);
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 42, miniProgram);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
            assertFalse(diagnostics.summary(true, true).contains("\n观察开始 "));

            shadowService.activeRoot = () -> homeRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 41, home);
            diagnostics.start(pkg, 1);
            shadowService.activeRoot = () -> miniProgramRoot;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, pkg, 42, standardMiniProgram);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1000));
            assertTrue(diagnostics.summary(true, true).contains("\n观察开始 "));
            assertTrue(shadowOf(genericClose).getPerformedActions().isEmpty());
        } finally {
            skipper.close();
            diagnostics.clear();
        }
    }

    @Test public void confirmedGenericPopupsCanContinueInTheSameWindowUpToTheVisitLimit() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.setSkipToastEnabled(false);
        long before = store.popupCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(42);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityNodeInfo content = windowNode("普通内容", screen, window);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            for (int index = 0; index < 6; index++) {
                AccessibilityNodeInfo root = windowNode("", screen, window);
                AccessibilityNodeInfo button = addGenericPopup(root, display, window);
                shadowOf(button).setOnPerformActionListener((action, args) -> {
                    shadowService.activeRoot = () -> content;
                    return true;
                });
                shadowService.activeRoot = () -> root;
                sendWindowEvent(skipper, index == 0 ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        : AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, window.getId());
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));

                assertEquals("popup " + index, index < 5 ? List.of(AccessibilityNodeInfo.ACTION_CLICK) : List.of(),
                        shadowOf(button).getPerformedActions());
                assertEquals(before + Math.min(index + 1, 5), store.popupCount());
            }
        } finally {
            skipper.close();
        }
    }

    @Test public void unconfirmedGenericPopupKeepsItsWindowLocked() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        long before = store.popupCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(42);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityNodeInfo stuck = windowNode("", screen, window);
        AccessibilityNodeInfo button = addGenericPopup(stuck, display, window);
        shadowOf(button).setOnPerformActionListener((action, args) -> true);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> stuck;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            assertEquals(before, store.popupCount());

            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());

            AccessibilityNodeInfo replacement = windowNode("", screen, window);
            AccessibilityNodeInfo replacementButton = addGenericPopup(replacement, display, window);
            shadowService.activeRoot = () -> replacement;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertTrue(shadowOf(replacementButton).getPerformedActions().isEmpty());
            assertEquals(before, store.popupCount());
        } finally {
            skipper.close();
        }
    }

    @Test public void silentDialogDismissalConfirmsOnlyTheKnownHostWindow() {
        for (Dismissal kind : List.of(Dismissal.GENERIC_POPUP, Dismissal.CUSTOM_POPUP)) {
            assertSilentDialogReadback(kind, 41, Display.DEFAULT_DISPLAY, false, true);
            assertSilentDialogReadback(kind, 43, Display.DEFAULT_DISPLAY, false, false);
            assertSilentDialogReadback(kind, 41, Display.DEFAULT_DISPLAY, true, false);
        }
    }

    @Test @Config(sdk = 34) public void silentDialogReturnStillNeedsTheOriginalDisplay() {
        assertSilentDialogReadback(Dismissal.GENERIC_POPUP, 41, Display.DEFAULT_DISPLAY, false, true);
        assertSilentDialogReadback(Dismissal.GENERIC_POPUP, 41, 2, false, false);
    }

    private static void assertSilentDialogReadback(Dismissal kind, int destination, int destinationDisplay,
                                                   boolean userClicked, boolean confirmed) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        String activity = PKG + ".MainActivity";
        shadowOf(service.getPackageManager()).addActivityIfNotPresent(new ComponentName(PKG, activity));
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.setSkipToastEnabled(false);
        store.saveCustomSkipRules(kind == Dismissal.CUSTOM_POPUP
                ? List.of(new CustomSkipRule(PKG, "", CustomSkipRule.POPUP, "", "关闭广告", "广告"))
                : List.of());
        long before = store.popupCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityWindowInfo host = windowOnDisplay(41, Display.DEFAULT_DISPLAY);
        AccessibilityWindowInfo dialog = windowOnDisplay(42, Display.DEFAULT_DISPLAY);
        AccessibilityWindowInfo returned = windowOnDisplay(destination, destinationDisplay);
        AccessibilityNodeInfo home = windowNode("普通内容", screen, host);
        AccessibilityNodeInfo popup = windowNode("", screen, dialog);
        AccessibilityNodeInfo content = windowNode("普通内容", screen, returned);
        AccessibilityNodeInfo button = addGenericPopup(popup, display, dialog);
        shadowOf(button).setOnPerformActionListener((action, args) -> {
            shadowService.activeRoot = () -> content;
            return true;
        });
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> home;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, host.getId(), activity);
            shadowService.activeRoot = () -> popup;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, dialog.getId(), "android.app.Dialog");
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            if (userClicked) sendWindowEvent(skipper, AccessibilityEvent.TYPE_VIEW_CLICKED, PKG, destination);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(kind.name(), before + (confirmed ? 1 : 0), store.popupCount());
        } finally {
            skipper.close();
        }
    }

    @Test public void customPopupRevalidatesNativeClickWhenTheSameNodeChangesPurpose() {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        store.setPopupEnabled(true);
        store.saveCustomSkipRules(List.of(new CustomSkipRule(PKG, "", CustomSkipRule.POPUP, "", "关闭广告", "广告")));
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(42);
        AccessibilityNodeInfo root = windowNode("", new Rect(0, 0, display.x, display.y), window);
        AccessibilityNodeInfo button = addGenericPopup(root, display, window);
        shadowOf(button).setOnPerformActionListener((action, args) -> false);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> root;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, window.getId());
            AtomicInteger reads = new AtomicInteger();
            shadowService.activeRoot = () -> {
                if (reads.incrementAndGet() == 2) button.setText("查看详情");
                return root;
            };
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
            assertTrue(reads.get() >= 2);
            assertTrue(shadowOf(button).getPerformedActions().isEmpty());

            button.setText("关闭广告");
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, PKG, window.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
        } finally {
            skipper.close();
        }
    }

    @Test @Config(sdk = 34) public void sameDisplayWindowChangeStillConfirmsDismissal() {
        for (Dismissal kind : Dismissal.values()) {
            assertWindowReadback(kind, Display.DEFAULT_DISPLAY, 43, Display.DEFAULT_DISPLAY, true);
        }
    }

    @Test @Config(sdk = 34) public void anotherDisplayCannotConfirmDismissal() {
        for (Dismissal kind : Dismissal.values()) {
            assertWindowReadback(kind, Display.DEFAULT_DISPLAY, 43, 2, false);
        }
    }

    @Test @Config(sdk = 34) public void unknownDisplayRequiresTheOriginalWindow() {
        assertWindowReadback(Dismissal.SPLASH, Display.INVALID_DISPLAY, 42, Display.INVALID_DISPLAY, true);
        assertWindowReadback(Dismissal.SPLASH, Display.INVALID_DISPLAY, 43, Display.DEFAULT_DISPLAY, false);
    }

    private enum Dismissal { SPLASH, GENERIC_POPUP, CUSTOM_POPUP }

    private static void assertWindowReadback(Dismissal kind, int sourceDisplay, int destinationWindow,
                                             int destinationDisplay, boolean confirmed) {
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        ActiveRootService shadowService = Shadow.extract(service);
        Store store = new Store(service);
        boolean splash = kind == Dismissal.SPLASH;
        store.setSplashEnabled(splash);
        store.setPopupEnabled(!splash);
        store.setSkipToastEnabled(false);
        store.setKeepSkipDetails(true);
        store.saveCustomSkipRules(kind == Dismissal.CUSTOM_POPUP
                ? List.of(new CustomSkipRule(PKG, "", CustomSkipRule.POPUP, "", "关闭广告", "广告"))
                : List.of());
        long before = splash ? store.splashCount() : store.popupCount();
        Point display = new Point();
        service.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(display);
        Rect screen = new Rect(0, 0, display.x, display.y);
        AccessibilityWindowInfo origin = windowOnDisplay(42, sourceDisplay);
        AccessibilityWindowInfo replacement = windowOnDisplay(destinationWindow, destinationDisplay);
        AccessibilityNodeInfo ad = windowNode("", screen, origin);
        AccessibilityNodeInfo content = windowNode("普通内容", screen, replacement);
        AccessibilityNodeInfo button;
        if (splash) {
            button = windowNode("关闭广告", new Rect(display.x * 3 / 4, display.y / 20,
                    display.x * 19 / 20, display.y / 10), origin);
            button.setClickable(true);
            shadowOf(ad).addChild(button);
        } else {
            button = addGenericPopup(ad, display, origin);
        }
        shadowOf(button).setOnPerformActionListener((action, args) -> true);
        SplashSkipper skipper = new SplashSkipper(service);
        try {
            shadowService.activeRoot = () -> ad;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, origin.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
            assertEquals(List.of(AccessibilityNodeInfo.ACTION_CLICK), shadowOf(button).getPerformedActions());
            assertEquals(before, splash ? store.splashCount() : store.popupCount());

            shadowService.activeRoot = () -> content;
            sendWindowEvent(skipper, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, PKG, replacement.getId());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
            assertEquals(kind.name(), before + (confirmed ? 1 : 0), splash ? store.splashCount() : store.popupCount());
            org.json.JSONObject latest = store.read(splash ? "splash-history" : "popup-history").optJSONObject(0);
            assertNotNull(latest);
            assertEquals(confirmed, latest.optBoolean("confirmed"));
        } finally {
            skipper.close();
        }
    }

    private static AccessibilityWindowInfo windowOnDisplay(int id, int display) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        shadowOf(window).setId(id);
        if (android.os.Build.VERSION.SDK_INT >= 30) org.robolectric.util.ReflectionHelpers.setField(window, "mDisplayId", display);
        return window;
    }

    private static AccessibilityNodeInfo addGenericPopup(
            AccessibilityNodeInfo root, Point display, AccessibilityWindowInfo window) {
        AccessibilityNodeInfo panel = windowNode("", new Rect(display.x / 5, display.y / 3,
                display.x * 4 / 5, display.y * 2 / 3), window);
        AccessibilityNodeInfo button = windowNode("关闭广告", new Rect(display.x / 3, display.y / 2,
                display.x * 2 / 3, display.y * 3 / 5), window);
        button.setClickable(true);
        shadowOf(root).addChild(panel);
        shadowOf(panel).addChild(button);
        shadowOf(panel).addChild(windowNode("广告", new Rect(display.x / 4, display.y * 2 / 5,
                display.x * 3 / 4, display.y / 2), window));
        return button;
    }

    private static AccessibilityNodeInfo windowNode(String text, Rect bounds, AccessibilityWindowInfo window) {
        AccessibilityNodeInfo node = node(text, bounds);
        shadowOf(node).setAccessibilityWindowInfo(window);
        return node;
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
        sendWindowEvent(skipper, type, pkg, windowId, null);
    }

    private static void sendWindowEvent(SplashSkipper skipper, int type, String pkg, int windowId, String windowClass) {
        AccessibilityEvent event = AccessibilityEvent.obtain(type);
        event.setPackageName(pkg);
        event.setClassName(windowClass);
        shadowOf(event).setWindowId(windowId);
        skipper.event(event);
        event.recycle();
    }
}
