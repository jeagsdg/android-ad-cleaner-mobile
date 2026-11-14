package app.clearscreen;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Looper;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowBuild;

import java.time.Duration;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class AccessibilityRecoveryTest {
    private static final String OTHER_SERVICE = "other.package/.Service";
    private Application app;
    private CleanerService cleaner;
    private SkipKeepAliveService keepAlive;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setBrand("Redmi");
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS,
                AccessibilityRecovery.PERMISSION);
        new Store(app).setSplashEnabled(true);
        setEnabledServices(OTHER_SERVICE + ":" + PermissionAccess.component(app).flattenToString());
        cleaner = Robolectric.buildService(CleanerService.class).create().get();
        cleaner.onServiceConnected();
    }

    @After public void teardown() {
        SkipKeepAliveService.stopRecovery();
        if (keepAlive != null) keepAlive.onDestroy();
        cleaner.onDestroy();
    }

    @Test public void restoresOnlyOwnServiceAfterUnbindAndRecoversAgainAfterReconnect() {
        startRecovery();
        removeOwnService();
        assertTrue(SkipKeepAliveService.recoveryRunning());
        poll();
        assertRestoredWithoutChangingOtherService();
        cleaner.onServiceConnected();
        removeOwnService();
        poll();
        assertRestoredWithoutChangingOtherService();
        cleaner.onServiceConnected();
        poll();
        assertTrue(SkipKeepAliveService.recoveryRunning());
    }

    @Test public void enabledButDisconnectedIsNotToggled() {
        startRecovery();
        String before = enabledServices();
        cleaner.onUnbind(new Intent());
        poll();
        poll();
        assertEquals(before, enabledServices());
        assertTrue(SkipKeepAliveService.recoveryRunning());
    }

    @Test public void stopsAfterUnconfirmedRestoreWithoutRetrying() {
        startRecovery();
        removeOwnService();
        poll();
        assertRestoredWithoutChangingOtherService();
        setEnabledServices(OTHER_SERVICE);
        poll();
        assertEquals(OTHER_SERVICE, enabledServices());
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertTrue(SkipKeepAliveService.recoveryFailed());
    }

    @Test public void notificationStopDisarmsBeforeNextPoll() throws Exception {
        startRecovery();
        Notification notification = shadowOf(keepAlive).getLastForegroundNotification();
        assertNotNull(notification.deleteIntent);
        assertEquals("停止保活", notification.actions[0].title.toString());
        keepAlive.onStartCommand(shadowOf(notification.actions[0].actionIntent).getSavedIntent(), 0, 2);
        removeOwnService();
        poll();
        assertFalse(new Store(app).skipKeepAliveEnabled());
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertEquals(OTHER_SERVICE, enabledServices());
    }

    @Test public void notificationBlockDisarmsBeforeNextPoll() {
        startRecovery();
        removeOwnService();
        shadowOf(app.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        poll();
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertEquals(OTHER_SERVICE, enabledServices());
    }

    @Test public void requiresPositiveOptInOnSupportedBrandWithGrantAndInitialConnection() {
        assertFalse(SkipKeepAliveService.recoveryRunning());
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setBrand("Google");
        assertFalse(SkipKeepAliveService.startRecovery(app));
        ShadowBuild.setManufacturer("POCO");
        shadowOf(app).denyPermissions(AccessibilityRecovery.PERMISSION);
        assertFalse(SkipKeepAliveService.startRecovery(app));
        shadowOf(app).grantPermissions(AccessibilityRecovery.PERMISSION);
        removeOwnService();
        assertFalse(SkipKeepAliveService.startRecovery(app));
        assertEquals(OTHER_SERVICE, enabledServices());
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test public void permissionRevocationDisarmsBeforeNextPoll() {
        startRecovery();
        removeOwnService();
        shadowOf(app).denyPermissions(AccessibilityRecovery.PERMISSION);
        poll();
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertEquals(OTHER_SERVICE, enabledServices());
    }

    @Test public void visibleXiaomiActionRequiresConfirmationAndOffersStop() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "无障碍掉线？自动恢复");
            ScreenTest.clickFeature(activity, "开启自动恢复");
            assertNull(shadowOf(app).getNextStartedService());
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            acceptForegroundStart();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            ScreenTest.clickFeature(activity, "停止自动恢复");
            removeOwnService();
            poll();
            assertFalse(SkipKeepAliveService.recoveryRunning());
            assertEquals(OTHER_SERVICE, enabledServices());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void setupChecksOrdinaryPrerequisitesBeforeShowingComputerInstructions() {
        new Store(app).setSplashEnabled(false);
        shadowOf(app).denyPermissions(AccessibilityRecovery.PERMISSION);
        removeOwnService();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "无障碍掉线？自动恢复");
            ScreenTest.clickFeature(activity, "开启开屏跳过");
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    shadowOf(activity).getNextStartedActivity().getAction());
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "查看电脑授权步骤"));
            setEnabledServices(OTHER_SERVICE + ":" + PermissionAccess.component(app).flattenToShortString());
            cleaner.onServiceConnected();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertEquals(MainActivity.ACCESSIBILITY_RECOVERY, activity.tab);
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "开启自动恢复"));
            if (Build.VERSION.SDK_INT >= 30) {
                ScreenTest.clickFeature(activity, "用手机授权");
                ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
                shadowOf(Looper.getMainLooper()).idle();
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                assertEquals("pm grant app.clearscreen " + AccessibilityRecovery.PERMISSION,
                        clipboard.getPrimaryClip().getItemAt(0).getText().toString());
            } else assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "用手机授权"));
            ScreenTest.clickFeature(activity, Build.VERSION.SDK_INT >= 30 ? "用电脑授权" : "查看电脑授权步骤");
            assertEquals("复制授权命令", ShadowAlertDialog.getLatestAlertDialog()
                    .getButton(android.app.AlertDialog.BUTTON_POSITIVE).getText().toString());
            android.app.AlertDialog help = ShadowAlertDialog.getLatestAlertDialog();
            shadowOf(app).grantPermissions(AccessibilityRecovery.PERMISSION);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertFalse(help.isShowing());
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "开启自动恢复"));
            assertNull(shadowOf(app).getNextStartedService());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void grantingNotificationsDoesNotStartRecovery() {
        NotificationManager manager = app.getSystemService(NotificationManager.class);
        shadowOf(manager).setNotificationsEnabled(false);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "无障碍掉线？自动恢复");
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "允许保活通知"));
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "开启自动恢复"));
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "查看电脑授权步骤"));
            shadowOf(manager).setNotificationsEnabled(true);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "开启自动恢复"));
            assertNull(shadowOf(app).getNextStartedService());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void otherBrandsDoNotShowSetup() {
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setBrand("Google");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "无障碍掉线？自动恢复"));
            activity.showPage(MainActivity.ACCESSIBILITY_RECOVERY);
            assertEquals(0, activity.tab);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private void startRecovery() {
        assertTrue(SkipKeepAliveService.startRecovery(app));
        acceptForegroundStart();
    }

    private void acceptForegroundStart() {
        Intent start = shadowOf(app).getNextStartedService();
        assertNotNull(start);
        assertEquals(new ComponentName(app, SkipKeepAliveService.class), start.getComponent());
        keepAlive = Robolectric.buildService(SkipKeepAliveService.class).create().get();
        keepAlive.onStartCommand(start, 0, 1);
        assertTrue(SkipKeepAliveService.recoveryRunning());
    }

    private void removeOwnService() {
        setEnabledServices(OTHER_SERVICE);
        cleaner.onUnbind(new Intent());
    }

    private void assertRestoredWithoutChangingOtherService() {
        assertEquals(OTHER_SERVICE + ":" + PermissionAccess.component(app).flattenToShortString(),
                enabledServices());
        assertEquals(0, Settings.Secure.getInt(app.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }

    private String enabledServices() {
        return Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    }

    private void setEnabledServices(String value) {
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
    }

    private void poll() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30));
    }
}
