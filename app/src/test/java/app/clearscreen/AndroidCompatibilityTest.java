package app.clearscreen;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.os.Build;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.xmlpull.v1.XmlPullParser;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28, 33, 34})
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
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("skip-keep-alive", "跳过保活",
                NotificationManager.IMPORTANCE_NONE));
        assertFalse(SkipKeepAliveService.notificationsAllowed(app));
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
}
