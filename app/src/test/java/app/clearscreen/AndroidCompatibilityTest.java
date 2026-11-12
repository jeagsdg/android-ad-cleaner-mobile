package app.clearscreen;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowVpnService;
import org.xmlpull.v1.XmlPullParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 25, 26, 28, 33, 34})
public class AndroidCompatibilityTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test public void cleanerLoadsTheAccessibilityConfigurationForItsApi() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        PackageManager manager = app.getPackageManager();
        ServiceInfo service = manager.getServiceInfo(new ComponentName(app, CleanerService.class),
                PackageManager.GET_META_DATA);
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission);
        assertTrue(service.exported);

        try (XmlResourceParser xml = service.loadXmlMetaData(manager, "android.accessibilityservice")) {
            assertNotNull(xml);
            int event = xml.getEventType();
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = xml.next();
            }
            assertEquals(XmlPullParser.START_TAG, event);
            assertEquals("accessibility-service", xml.getName());
            assertTrue(xml.getAttributeBooleanValue(ANDROID_NS, "canRetrieveWindowContent", false));
            assertTrue(xml.getAttributeBooleanValue(ANDROID_NS, "canPerformGestures", false));
            int flags = xml.getAttributeIntValue(ANDROID_NS, "accessibilityFlags", 0);
            int required = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            assertEquals(required, flags & required);
            assertEquals(Build.VERSION.SDK_INT >= 31,
                    xml.getAttributeValue(ANDROID_NS, "isAccessibilityTool") != null);
            assertFalse(xml.getAttributeBooleanValue(ANDROID_NS, "isAccessibilityTool", false));
        }
    }

    @Test public void keepAliveServiceIsPrivateAndUsesTheRequiredForegroundType() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ServiceInfo service = app.getPackageManager().getServiceInfo(
                new ComponentName(app, SkipKeepAliveService.class), 0);
        assertFalse(service.exported);
        if (Build.VERSION.SDK_INT >= 34) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, service.getForegroundServiceType());
        }
    }

    @Test public void keepAliveRespectsPlatformNotificationGateAndBlockedChannel() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals(Build.VERSION.SDK_INT < 33, SkipKeepAliveService.notificationsAllowed(app));

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertTrue(SkipKeepAliveService.notificationsAllowed(app));
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("skip-keep-alive", "跳过保活",
                NotificationManager.IMPORTANCE_NONE));
        assertFalse(SkipKeepAliveService.notificationsAllowed(app));
    }

    @Test @Config(sdk = 26)
    public void legacyKeepAliveStopsWhenNotificationsAreDisabledWithoutReturningToTheApp() {
        Application app = RuntimeEnvironment.getApplication();
        Store store = new Store(app);
        store.setSplashEnabled(true);
        store.setSkipKeepAliveEnabled(true);
        CleanerService cleaner = Robolectric.buildService(CleanerService.class).create().get();
        SkipKeepAliveService keepAlive = Robolectric.buildService(SkipKeepAliveService.class).create().get();
        try {
            cleaner.onServiceConnected();
            Intent start = shadowOf(app).getNextStartedService();
            assertNotNull(start);
            assertEquals(new ComponentName(app, SkipKeepAliveService.class), start.getComponent());
            keepAlive.onStartCommand(start, 0, 1);
            assertTrue(SkipKeepAliveService.running());
            assertNotNull(shadowOf(keepAlive).getLastForegroundNotification());

            NotificationManager manager = keepAlive.getSystemService(NotificationManager.class);
            shadowOf(manager).setNotificationsEnabled(false);
            assertTrue(SkipKeepAliveService.running());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));

            assertFalse(SkipKeepAliveService.running());
            assertTrue(shadowOf(keepAlive).isForegroundStopped());
            assertTrue(shadowOf(keepAlive).isStoppedBySelf());
            assertTrue(store.skipKeepAliveEnabled());
        } finally {
            keepAlive.onDestroy();
            cleaner.onDestroy();
        }
    }

    @Test @Config(sdk = {24, 25})
    public void nougatUsesTheLegacyForegroundNotificationAndSettingsEntry() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Notification status = ForegroundServices.lowPriorityNotification(app, "test-status", "测试").build();
        assertEquals(Notification.PRIORITY_LOW, status.priority);
        assertFalse(app.getPackageManager().getServiceInfo(new ComponentName(app, ChannelObserver.class),
                PackageManager.MATCH_DISABLED_COMPONENTS).enabled);
        List<Intent> destinations = new ArrayList<>();
        assertEquals(PermissionAccess.Destination.ACCESSIBILITY, PermissionAccess.openAccessibility(app, destinations::add));
        assertEquals(1, destinations.size());
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, destinations.get(0).getAction());

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "使用说明");
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "通知渠道检查"));
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "清除通知渠道缓存"));
            activity.openNotificationSettings();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    shadowOf(activity).getNextStartedActivity().getAction());
            ShadowVpnService.setPrepareResult(null);
            activity.prepareFilter();
            Intent filter = shadowOf(app).getNextStartedService();
            if (activity.networkFilterAvailable()) {
                assertNotNull(filter);
                assertEquals(new ComponentName(app, AdFilterService.class), filter.getComponent());
                assertEquals("start", filter.getAction());
            } else assertNull(filter);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void firstLaunchOffersPermissionSetupWithoutStartingServices() {
        Application app = RuntimeEnvironment.getApplication();
        assertEquals(Build.VERSION.SDK_INT >= 28 ? Boolean.FALSE : null,
                PermissionAccess.backgroundRestricted(app));
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "系统广告设置");
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "打开手机设置"));
            assertNull(CleanerService.get());
            assertFalse(SkipKeepAliveService.running());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = {24, 26, 28})
    public void legacyVpnRecoveryIsAvailableOnlyInTheFullEditionOnOlderAndroid() {
        Application app = RuntimeEnvironment.getApplication();
        new Store(app).noteUpdateCheck(System.currentTimeMillis());
        Intent network = new Intent(app, MainActivity.class).putExtra("tab", 6);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, network).setup();
        try {
            MainActivity activity = controller.get();
            String label = "打开系统 VPN 设置";
            boolean legacyFull = activity.networkFilterAvailable() && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1;
            assertEquals(legacyFull, ScreenTest.find(activity.getWindow().getDecorView(), label) != null);
            if (legacyFull) {
                ScreenTest.clickFeature(activity, label);
                assertEquals(Settings.ACTION_VPN_SETTINGS, shadowOf(activity).getNextStartedActivity().getAction());
            }
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = 26)
    public void legacyVpnSettingsFallsBackToSystemSettingsWhenDirectEntryIsUnavailable() {
        List<Intent> destinations = new ArrayList<>();
        assertTrue(PermissionAccess.openVpnSettings(intent -> {
            destinations.add(intent);
            if (Settings.ACTION_VPN_SETTINGS.equals(intent.getAction())) throw new ActivityNotFoundException();
        }));
        assertEquals(2, destinations.size());
        assertEquals(Settings.ACTION_VPN_SETTINGS, destinations.get(0).getAction());
        assertEquals(Settings.ACTION_SETTINGS, destinations.get(1).getAction());
    }
}
