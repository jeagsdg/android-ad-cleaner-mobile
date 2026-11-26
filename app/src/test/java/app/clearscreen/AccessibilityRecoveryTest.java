package app.clearscreen;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowBuild;

import java.time.Duration;

import static app.clearscreen.AccessibilityReliability.Kind.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class AccessibilityRecoveryTest {
    private static final String OTHER_SERVICE = "other.package/.Service";
    private static final int BOOT_COUNT = 12;
    private Application app;
    private CleanerService cleaner;
    private SkipKeepAliveService keepAlive;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        ShadowBuild.setManufacturer("Xiaomi");
        ShadowBuild.setBrand("Redmi");
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        new Store(app).setSplashEnabled(true);
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT_COUNT);
        setEnabledServices(selectedServices());
        cleaner = Robolectric.buildService(CleanerService.class).create().get();
        cleaner.onServiceConnected();
    }

    @After public void teardown() {
        SkipKeepAliveService.stopRecovery(app);
        if (keepAlive != null) keepAlive.onDestroy();
        cleaner.onDestroy();
    }

    @Test public void watchesStillSelectedServiceUntilActualReconnectAcrossRepeatedChecks() throws Exception {
        startRecovery();
        cleaner.onUnbind(new Intent());
        long disconnectedAt = AccessibilityReliability.lastDisconnectionMillis(app);
        assertTrue(disconnectedAt > 0);
        assertEquals(DISCONNECTED, AccessibilityReliability.recentEvent(app).kind);

        settle();
        assertEquals(SkipKeepAliveService.RecoveryConnection.WAITING_FOR_SYSTEM,
                SkipKeepAliveService.recoveryConnection());
        assertEquals(RECOVERY_WAITING, AccessibilityReliability.recentRecovery(app).kind);
        for (int check = 0; check < 6; check++) {
            poll();
            assertEquals(selectedServices(), enabledServices());
            assertTrue(SkipKeepAliveService.recoveryRunning());
        }

        cleaner.onServiceConnected();
        assertEquals(SkipKeepAliveService.RecoveryConnection.RESTORED,
                SkipKeepAliveService.recoveryConnection());
        assertEquals(RECOVERED, AccessibilityReliability.recentEvent(app).kind);
        assertEquals(disconnectedAt, AccessibilityReliability.lastDisconnectionMillis(app));
        JSONObject report = PermissionAccess.diagnostics(app);
        assertEquals(disconnectedAt, report.getLong("lastAccessibilityDisconnectionTimeMillis"));
        assertEquals("RECOVERED", report.getJSONObject("lastAccessibilityRecoveryEvent").getString("kind"));

        cleaner.onUnbind(new Intent());
        settle();
        assertEquals(SkipKeepAliveService.RecoveryConnection.WAITING_FOR_SYSTEM,
                SkipKeepAliveService.recoveryConnection());
        cleaner.onServiceConnected();
        assertEquals(RECOVERED, AccessibilityReliability.recentRecovery(app).kind);
        assertEquals(selectedServices(), enabledServices());
    }

    @Test public void switchingOffAccessibilityEndsWatchingAndRemainsOffAcrossRestarts() {
        startRecovery();
        removeOwnService();
        settle();
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertEquals(RECOVERY_STOPPED, AccessibilityReliability.recentRecovery(app).kind);
        Notification firstNotice = selectionNotice();
        assertNotNull(firstNotice);
        assertEquals("净屏的无障碍开关已关闭", firstNotice.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertTrue((firstNotice.flags & Notification.FLAG_AUTO_CANCEL) != 0);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(firstNotice.contentIntent).getSavedIntent().getAction());
        assertEquals(Service.START_NOT_STICKY, restartService());
        SkipKeepAliveService.onForeground(app);
        poll();
        assertSame(firstNotice, selectionNotice());
        assertEquals(OTHER_SERVICE, enabledServices());
        assertFalse(AccessibilityRecovery.watching(app));
        assertNull(shadowOf(app).getNextStartedService());

        // A manual re-enable can connect normally without opting back into watching.
        setEnabledServices(selectedServices());
        cleaner.onServiceConnected();
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertEquals(RECOVERY_STOPPED, AccessibilityReliability.recentRecovery(app).kind);
        assertNull(selectionNotice());
        assertNull(shadowOf(app).getNextStartedService());
    }

    @Test public void unboundStatusIsAccurateBeforeFirstCheckAndBriefGapsAreMeasured() throws Exception {
        cleaner.onUnbind(new Intent());
        startRecovery();
        assertEquals("WAITING_FOR_SYSTEM", PermissionAccess.diagnostics(app).getString("accessibilityRecovery"));
        assertEquals("无障碍已开启，等待系统连接", shadowOf(keepAlive).getLastForegroundNotification()
                .extras.getCharSequence(Notification.EXTRA_TEXT));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450));
        cleaner.onServiceConnected();
        assertEquals(RECOVERED, AccessibilityReliability.recentRecovery(app).kind);
        cleaner.onUnbind(new Intent());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
        cleaner.onServiceConnected();
        JSONObject report = PermissionAccess.diagnostics(app);
        assertEquals(2L, report.getLong("observedAccessibilityDisconnections"));
        assertEquals(250L, report.getLong("lastObservedRecoveryWaitMillis"));
        assertEquals("RESTORED", report.getString("accessibilityRecovery"));
    }

    @Test public void waitingChecksBackOffThenObserveAtMostOncePerQuarterHourUntilNewEvidence() {
        startRecovery();
        settle();
        cleaner.onUnbind(new Intent());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        long[] delays = {5000, 10000, 20000, 40000, 80000, 160000};
        for (int i = 0; i < delays.length; i++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(delays[i] - 1));
            assertEquals(i, SkipKeepAliveService.timedRecoveryChecks());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
            assertEquals(i + 1, SkipKeepAliveService.timedRecoveryChecks());
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30));
        assertEquals(8, SkipKeepAliveService.timedRecoveryChecks());
        assertTrue(SkipKeepAliveService.recoveryRunning());
        app.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6500));
        assertEquals(9, SkipKeepAliveService.timedRecoveryChecks());
        cleaner.onServiceConnected();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5));
        assertEquals(9, SkipKeepAliveService.timedRecoveryChecks());
        assertEquals(SkipKeepAliveService.RecoveryConnection.RESTORED, SkipKeepAliveService.recoveryConnection());
    }

    @Test public void anIdleOrInterruptedConnectedServiceDoesNotEnterRecovery() {
        startRecovery();
        cleaner.onInterrupt();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(30));
        assertEquals(PermissionAccess.State.READY, PermissionAccess.state(app));
        assertEquals(0L, AccessibilityReliability.observedDisconnects(app));
        assertEquals(0, SkipKeepAliveService.timedRecoveryChecks());
        assertEquals(SkipKeepAliveService.RecoveryConnection.CONNECTED, SkipKeepAliveService.recoveryConnection());
    }

    @Test public void stoppingWatchEndsOrdinaryKeepAliveIfOnlyEnabledByWatch() {
        startRecovery();
        SkipKeepAliveService.stopRecovery(app);
        assertFalse(new Store(app).skipKeepAliveEnabled());
        assertFalse(SkipKeepAliveService.running());
        assertEquals(SkipKeepAliveService.RecoveryConnection.INACTIVE, SkipKeepAliveService.recoveryConnection());
    }

    @Test public void stoppingWatchRestoresPriorOrdinaryKeepAlivePreference() {
        new Store(app).setSkipKeepAliveEnabled(true);
        startRecovery();
        SkipKeepAliveService.stopRecovery(app);
        assertTrue(new Store(app).skipKeepAliveEnabled());
        assertTrue(SkipKeepAliveService.running());
        assertFalse(SkipKeepAliveService.recoveryRunning());
    }

    @Test public void foregroundResumesPreviouslyEnabledWatchAfterSameProcessServiceLoss() {
        startRecovery();
        cleaner.onUnbind(new Intent());
        destroyKeepAlive();
        SkipKeepAliveService.sync(app);
        assertNull(shadowOf(app).getNextStartedService());

        SkipKeepAliveService.onForeground(app);
        assertEquals(Service.START_STICKY, acceptForegroundStart());
        assertEquals(RECOVERY_RESUMED, AccessibilityReliability.recentRecovery(app).kind);
        assertEquals("FOREGROUND_SAME_PROCESS", AccessibilityReliability.lastWatchResumeTrigger(app));
        settle();
        assertEquals(SkipKeepAliveService.RecoveryConnection.WAITING_FOR_SYSTEM,
                SkipKeepAliveService.recoveryConnection());
        assertEquals(selectedServices(), enabledServices());
    }

    @Test @Config(sdk = 34) public void foregroundResumesAfterVerifiedSameBootResourceExit() {
        startRecovery();
        int owner = simulateProcessLoss();
        addExit(owner, ApplicationExitInfo.REASON_LOW_MEMORY);

        SkipKeepAliveService.onForeground(app);
        assertEquals(Service.START_STICKY, acceptForegroundStart());
        assertEquals(RECOVERY_RESUMED, AccessibilityReliability.recentRecovery(app).kind);
        assertEquals("FOREGROUND_RESOURCE_EXIT", AccessibilityReliability.lastWatchResumeTrigger(app));
        assertEquals(selectedServices(), enabledServices());
    }

    @Test @Config(sdk = 34) public void userRequestedStopWinsOverOlderRecoverableExit() {
        startRecovery();
        int owner = simulateProcessLoss();
        addExit(owner, ApplicationExitInfo.REASON_LOW_MEMORY);
        addExit(owner + 1, ApplicationExitInfo.REASON_USER_REQUESTED);

        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        assertFalse(AccessibilityRecovery.watching(app));
        assertFalse(new Store(app).skipKeepAliveEnabled());
        assertEquals(RECOVERY_STOPPED, AccessibilityReliability.recentRecovery(app).kind);
        assertNull(selectionNotice());
    }

    @Test @Config(sdk = 34) public void unverifiedProcessExitDoesNotResumeFromOpeningApp() {
        startRecovery();
        addExit(simulateProcessLoss(), ApplicationExitInfo.REASON_SIGNALED);
        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        assertEquals(selectedServices(), enabledServices());
    }

    @Test public void newBootDoesNotResumeEvenWithSavedOptIn() {
        startRecovery();
        destroyKeepAlive();
        Settings.Global.putInt(app.getContentResolver(), Settings.Global.BOOT_COUNT, BOOT_COUNT + 1);
        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        assertFalse(AccessibilityRecovery.watching(app));
        assertFalse(new Store(app).skipKeepAliveEnabled());
    }

    @Test public void legacySessionResumesOnlyFromAndroidStickyRestartIfSwitchIsStillOn() {
        legacySession();
        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        assertEquals(Service.START_STICKY, restartService());
        assertEquals(RECOVERY_RESUMED, AccessibilityReliability.recentRecovery(app).kind);
        assertEquals("SYSTEM_RESTART", AccessibilityReliability.lastWatchResumeTrigger(app));
        assertEquals("WATCHING", recoveryPrefs().getString("state", ""));
        assertFalse(recoveryPrefs().contains("last-attempt"));
        assertEquals(selectedServices(), enabledServices());
    }

    @Test public void legacyRestartWithRemovedSelectionLeavesSwitchOffAndDisarms() {
        legacySession();
        removeOwnService();
        assertEquals(Service.START_NOT_STICKY, restartService());
        poll();
        assertFalse(AccessibilityRecovery.watching(app));
        assertEquals(OTHER_SERVICE, enabledServices());
        assertNull(selectionNotice());
    }

    @Test @Config(sdk = {24, 26}) public void olderSystemReportsUnavailableRestrictionAndLeavesUnknownExitAlone() throws Exception {
        startRecovery();
        simulateProcessLoss();
        assertEquals(AccessibilityRecovery.Resume.UNVERIFIED, AccessibilityRecovery.resumeDisposition(app));
        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        JSONObject report = PermissionAccess.diagnostics(app);
        assertEquals("UNVERIFIED", report.getString("savedWatchResumeDisposition"));
        assertTrue(report.isNull("backgroundRestricted"));
        assertTrue(report.isNull("lastWatchResumeTrigger"));
        assertTrue(report.isNull("lastObservedRecoveryWaitMillis"));
    }

    @Test public void stoppingWhileExplicitStartIsQueuedCancelsThatRequest() {
        assertTrue(SkipKeepAliveService.startRecovery(app));
        SkipKeepAliveService.stopRecovery(app);
        assertEquals(Service.START_NOT_STICKY, acceptForegroundStart());
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertFalse(new Store(app).skipKeepAliveEnabled());
    }

    @Test public void notificationStopDisarmsOnForegroundReturnAndSystemRestart() {
        startRecovery();
        Notification notification = shadowOf(keepAlive).getLastForegroundNotification();
        assertNotNull(notification.deleteIntent);
        assertEquals("停止保活", notification.actions[0].title.toString());
        keepAlive.onStartCommand(shadowOf(notification.actions[0].actionIntent).getSavedIntent(), 0, 2);
        SkipKeepAliveService.onForeground(app);
        assertNoForegroundWatch();
        assertFalse(new Store(app).skipKeepAliveEnabled());
        assertEquals(Service.START_NOT_STICKY, restartService());
        assertEquals(selectedServices(), enabledServices());
        assertNull(selectionNotice());
    }

    @Test public void blockingNotificationsStopsWatching() {
        startRecovery();
        shadowOf(app.getSystemService(NotificationManager.class)).setNotificationsEnabled(false);
        removeOwnService();
        poll();
        assertFalse(SkipKeepAliveService.recoveryRunning());
        assertFalse(AccessibilityRecovery.watching(app));
        assertEquals(OTHER_SERVICE, enabledServices());
        assertNull(selectionNotice());
    }

    @Test public void requiresNotificationsAndOwnSelectionButMayStartWhileUnbound() {
        ShadowBuild.setManufacturer("Google");
        ShadowBuild.setBrand("Google");
        assertFalse(SkipKeepAliveService.startRecovery(app));
        ShadowBuild.setManufacturer("POCO");
        removeOwnService();
        assertFalse(SkipKeepAliveService.startRecovery(app));
        setEnabledServices(selectedServices());

        NotificationManager manager = app.getSystemService(NotificationManager.class);
        shadowOf(manager).setNotificationsEnabled(false);
        assertFalse(SkipKeepAliveService.startRecovery(app));
        shadowOf(manager).setNotificationsEnabled(true);
        assertNoForegroundWatch();

        startRecovery();
        settle();
        assertEquals(SkipKeepAliveService.RecoveryConnection.WAITING_FOR_SYSTEM,
                SkipKeepAliveService.recoveryConnection());
        assertEquals(selectedServices(), enabledServices());
    }

    @Test public void visibleActionRequiresConfirmationAndOffersStop() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            ScreenTest.clickFeature(activity, "无障碍连接守护");
            ScreenTest.clickFeature(activity, "开启连接守护");
            assertNoForegroundWatch();
            ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Service.START_STICKY, acceptForegroundStart());
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            ScreenTest.clickFeature(activity, "停止连接守护");
            assertFalse(SkipKeepAliveService.recoveryRunning());
            assertEquals(selectedServices(), enabledServices());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    private void startRecovery() {
        assertTrue(SkipKeepAliveService.startRecovery(app));
        assertEquals(Service.START_STICKY, acceptForegroundStart());
        assertTrue(SkipKeepAliveService.recoveryRunning());
    }

    private int acceptForegroundStart() {
        Intent start = shadowOf(app).getNextStartedService();
        assertNotNull(start);
        assertEquals(new ComponentName(app, SkipKeepAliveService.class), start.getComponent());
        keepAlive = Robolectric.buildService(SkipKeepAliveService.class).create().get();
        return keepAlive.onStartCommand(start, 0, 1);
    }

    private int restartService() {
        destroyKeepAlive();
        keepAlive = Robolectric.buildService(SkipKeepAliveService.class).create().get();
        return keepAlive.onStartCommand(null, 0, 1);
    }

    private void destroyKeepAlive() {
        if (keepAlive == null) return;
        keepAlive.onDestroy();
        keepAlive = null;
    }

    private int simulateProcessLoss() {
        destroyKeepAlive();
        int owner = Process.myPid() + 100;
        recoveryPrefs().edit().putInt("owner-pid", owner)
                .putLong("owner-since", System.currentTimeMillis() - 1000).apply();
        return owner;
    }

    private void addExit(int pid, int reason) {
        shadowOf(app.getSystemService(ActivityManager.class)).addApplicationExitInfo(
                ApplicationExitInfoBuilder.newBuilder().setProcessName(app.getPackageName())
                        .setPid(pid).setReason(reason).setTimestamp(System.currentTimeMillis()).build());
    }

    private void legacySession() {
        new Store(app).setSkipKeepAliveEnabled(true);
        recoveryPrefs().edit().putString("state", "RESTORE_ATTEMPTED").putLong("last-attempt", 1).apply();
    }

    private SharedPreferences recoveryPrefs() {
        return app.getSharedPreferences("accessibility-recovery-session", Context.MODE_PRIVATE);
    }

    private void assertNoForegroundWatch() {
        assertNull(shadowOf(app).getNextStartedService());
        assertFalse(SkipKeepAliveService.recoveryRunning());
    }

    private Notification selectionNotice() {
        return shadowOf(app.getSystemService(NotificationManager.class))
                .getNotification(SkipKeepAliveService.ACCESSIBILITY_OFF_NOTICE_ID);
    }

    private void removeOwnService() {
        setEnabledServices(OTHER_SERVICE);
        cleaner.onUnbind(new Intent());
    }

    private String selectedServices() {
        return OTHER_SERVICE + ":" + PermissionAccess.component(app).flattenToShortString();
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

    private void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
    }
}
