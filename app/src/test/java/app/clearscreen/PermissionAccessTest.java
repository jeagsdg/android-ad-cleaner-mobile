package app.clearscreen;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import java.time.Duration;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowBuild;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.shadows.ShadowVpnService;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34})
public class PermissionAccessTest {
    Context context() { return RuntimeEnvironment.getApplication(); }
    @Test public void matchesExactComponentAndSupportsShortNames() {
        ComponentName own = PermissionAccess.component(context());
        assertFalse(PermissionAccess.containsService(null, own));
        assertFalse(PermissionAccess.containsService("other.package/.CleanerService", own));
        assertFalse(PermissionAccess.containsService(own.flattenToString() + "Extra", own));
        assertTrue(PermissionAccess.containsService("other.package/.Service:" + own.flattenToShortString(), own));
        assertTrue(PermissionAccess.containsService(own.flattenToString(), own));
    }
    @Test public void enabledOtherServicesDoNotGrantThisAppAndOwnUnboundIsNotReady() {
        Settings.Secure.putInt(context().getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Settings.Secure.putString(context().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other.package/.Service");
        assertEquals(PermissionAccess.State.OFF, PermissionAccess.state(context()));
        Settings.Secure.putString(context().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                PermissionAccess.component(context()).flattenToShortString());
        assertEquals(PermissionAccess.State.DISCONNECTED, PermissionAccess.state(context()));
    }
    @Test public void deniedPrivateDetailsFallsBackToPublicListWithoutGranting() {
        List<Intent> attempted = new ArrayList<>();
        PermissionAccess.Destination result = PermissionAccess.openAccessibility(context(), intent -> {
            attempted.add(intent);
            if (attempted.size() == 1) throw new SecurityException("system-only permission");
        });
        assertEquals(PermissionAccess.Destination.ACCESSIBILITY, result);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, attempted.get(1).getAction());
        assertEquals(PermissionAccess.component(context()), attempted.get(0).getParcelableExtra(Intent.EXTRA_COMPONENT_NAME));
        assertEquals(PermissionAccess.State.OFF, PermissionAccess.state(context()));
    }
    @Test public void missingOemSettingsFallbackAndTotalFailureAreHandled() {
        List<Intent> attempted = new ArrayList<>();
        assertEquals(PermissionAccess.Destination.SETTINGS, PermissionAccess.openAccessibility(context(), intent -> {
            attempted.add(intent);
            if (attempted.size() < 3) throw new ActivityNotFoundException();
        }));
        assertEquals(Settings.ACTION_SETTINGS, attempted.get(2).getAction());
        assertEquals(PermissionAccess.Destination.UNAVAILABLE, PermissionAccess.openAccessibility(context(), intent -> {
            throw new ActivityNotFoundException();
        }));
        assertFalse(PermissionAccess.openAppInfo(context(), intent -> { throw new SecurityException(); }));
    }
    @Test public void appInfoTargetsOnlyOwnPackage() {
        List<Intent> attempted = new ArrayList<>();
        assertTrue(PermissionAccess.openAppInfo(context(), attempted::add));
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempted.get(0).getAction());
        assertEquals("package:" + context().getPackageName(), attempted.get(0).getDataString());
    }
    @Test public void notificationAccessMissingOrDeniedCanOfferManualHelp() {
        List<Intent> attempted = new ArrayList<>();
        assertTrue(PermissionAccess.openNotificationAccess(attempted::add));
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, attempted.get(0).getAction());
        assertFalse(PermissionAccess.openNotificationAccess(intent -> { throw new ActivityNotFoundException(); }));
        assertFalse(PermissionAccess.openNotificationAccess(intent -> { throw new SecurityException("restricted by device policy"); }));
        assertEquals(PermissionAccess.State.OFF, PermissionAccess.state(context()));
    }
    @Test public void helpCoversAllBrandsAndDoesNotAssumeMenuExists() {
        List<String> colorOsBrands = Arrays.asList("oppo", "oplus", "oneplus", "realme");
        for (String brand : Arrays.asList("oppo", "oplus", "oneplus", "realme", "xiaomi", "redmi", "poco", "honor", "vivo", "samsung", "google", "other")) {
            ShadowBuild.setManufacturer(brand);
            ShadowBuild.setBrand(brand);
            String help = PermissionAccess.restrictedHelp();
            assertTrue(help.contains("如果系统明确提示"));
            assertTrue(help.contains("查看是否提供"));
            if (colorOsBrands.contains(brand)) {
                assertTrue(help.contains("权限管理 → 右上角"));
                assertTrue(help.contains("解除所有授权限制"));
            }
            assertEquals(Build.VERSION.SDK_INT >= 33 && colorOsBrands.contains(brand), PermissionAccess.showColorOsNotificationHelp());
            if (brand.equals("xiaomi")) assertTrue(help.contains("高级设置"));
        }
    }
    @Test public void diagnosticsSeparateDisabledFromRestrictedAndKeepLedger() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Store store = new Store(context());
        Rule rule = store.allRules().get(0);
        String id = store.prepare(rule, rule.targets.get(0), true, false);
        JSONObject export = new JSONObject(store.export());
        JSONObject permissions = export.getJSONObject("permissions");
        assertEquals("OFF", permissions.getString("accessibilityState"));
        assertEquals("not_determined", permissions.getString("restrictionStatus"));
        assertTrue(permissions.has("installingPackage"));
        if (Build.VERSION.SDK_INT >= 33) assertFalse(permissions.getBoolean("notificationRuntimePermission"));
        else assertTrue(permissions.isNull("notificationRuntimePermission"));
        assertEquals(id, export.getJSONArray("journal").getJSONObject(0).getString("id"));
    }
    @Test public void returningFromSettingsRefreshesStateWithoutStartingCleaning() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        activity.accessibility();
        controller.pause();
        Settings.Secure.putString(context().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                PermissionAccess.component(context()).flattenToString());
        controller.resume();
        assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "开关已开启，服务未连接"));
        assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "打开手机设置"));
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        service.onServiceConnected();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        View ready = ScreenTest.find(activity.getWindow().getDecorView(), "完成");
        assertNotNull(ready);
        ready.performClick();
        assertEquals(0, activity.tab);
        assertFalse(service.busy);
        assertEquals(0, new Store(context()).read("journal").length());
        service.onUnbind(new Intent());
        assertEquals(PermissionAccess.State.DISCONNECTED, PermissionAccess.state(context()));
        controller.pause().stop().destroy();
        service.onDestroy();
    }
    @Test public void authorizationFromPermissionHelpReturnsToOriginWhenServiceConnects() {
        ShadowBuild.setManufacturer("OPPO");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        CleanerService service = null;
        try {
            MainActivity activity = controller.get();
            activity.store.enableAdFeatures(false, true);
            ScreenTest.clickFeature(activity, "应用内弹窗");
            ScreenTest.clickFeature(activity, "继续授权");
            ScreenTest.clickFeature(activity, "开启遇到问题");
            ScreenTest.clickFeature(activity, "打开无障碍设置");

            Intent settings = shadowOf(activity).getNextStartedActivity();
            assertNotNull(settings);
            assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, settings.getAction());
            controller.pause();
            Settings.Secure.putString(context().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    PermissionAccess.component(context()).flattenToString());
            controller.resume();
            assertEquals(PermissionAccess.State.DISCONNECTED, PermissionAccess.state(activity));
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "没有解除入口"));

            service = Robolectric.buildService(CleanerService.class).create().get();
            service.onServiceConnected();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));

            assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
            assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "应用内弹窗"));
            assertFalse(service.busy);
            assertEquals(0, activity.store.read("journal").length());
        } finally {
            controller.pause().stop().destroy();
            if (service != null) service.onDestroy();
        }
    }
    @Test public void permissionHelpSurvivesRecreationAndBackReturnsToSetup() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        activity.accessibility();
        ScreenTest.find(activity.getWindow().getDecorView(), "开启遇到问题").performClick();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        ActivityController<MainActivity> recreated = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        MainActivity next = recreated.get();
        assertNotNull(ScreenTest.find(next.getWindow().getDecorView(), "没有解除入口"));
        next.onBackPressed();
        assertNotNull(ScreenTest.find(next.getWindow().getDecorView(), "打开手机设置"));
        recreated.pause().stop().destroy();
    }
    @Test @Config(sdk = 34) public void colorOsNotificationGrantReturnsToAppInfoWithoutEnablingAccessibility() throws Exception {
        ShadowBuild.setManufacturer("OPPO");
        ShadowBuild.setBrand("OPPO");
        String notificationPermission = Manifest.permission.POST_NOTIFICATIONS;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(notificationPermission);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            activity.accessibility();
            ScreenTest.find(activity.getWindow().getDecorView(), "开启遇到问题").performClick();
            View requestButton = ScreenTest.find(activity.getWindow().getDecorView(), "申请过滤状态通知");
            if (!activity.networkFilterAvailable()) {
                assertNull(requestButton);
                assertNull(ScreenTest.find(activity.getWindow().getDecorView(), "使用网络过滤"));
                return;
            }
            assertNotNull(requestButton);
            assertTrue(requestButton.performClick());
            ShadowActivity shadow = shadowOf(activity);
            ShadowActivity.PermissionsRequest request = shadow.getLastRequestedPermission();
            assertEquals(MainActivity.COLOROS_FILTER_NOTIFICATIONS, request.requestCode);
            assertArrayEquals(new String[]{notificationPermission}, request.requestedPermissions);

            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(notificationPermission);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue(ScreenTest.find(activity.getWindow().getDecorView(), "重新检查权限管理").performClick());
            Intent appInfo = shadow.getNextStartedActivity();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.getAction());
            assertEquals("package:" + activity.getPackageName(), appInfo.getDataString());
            assertEquals(PermissionAccess.State.OFF, PermissionAccess.state(activity));
            assertTrue(PermissionAccess.diagnostics(activity).getBoolean("notificationRuntimePermission"));
            assertNull(AdFilterService.active);
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test @Config(sdk = 34) public void colorOsNotificationDenialCanStillOpenAppInfo() {
        ShadowBuild.setManufacturer("OPPO");
        String notificationPermission = Manifest.permission.POST_NOTIFICATIONS;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(notificationPermission);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            activity.accessibility();
            ScreenTest.find(activity.getWindow().getDecorView(), "开启遇到问题").performClick();
            View requestButton = ScreenTest.find(activity.getWindow().getDecorView(), "申请过滤状态通知");
            if (!activity.networkFilterAvailable()) {
                assertNull(requestButton);
                assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "打开净屏应用详情"));
                return;
            }
            assertNotNull(requestButton);
            requestButton.performClick();
            activity.onRequestPermissionsResult(MainActivity.COLOROS_FILTER_NOTIFICATIONS,
                    new String[]{notificationPermission}, new int[]{PackageManager.PERMISSION_DENIED});

            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.isShowing());
            assertEquals("检查权限管理", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent appInfo = shadowOf(activity).getNextStartedActivity();
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appInfo.getAction());
            assertEquals("package:" + activity.getPackageName(), appInfo.getDataString());
            assertEquals(PermissionAccess.State.OFF, PermissionAccess.state(activity));
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test @Config(sdk = 34) public void networkNotificationManagementKeepsItsOwnContext() {
        ShadowBuild.setManufacturer("OPPO");
        String notificationPermission = Manifest.permission.POST_NOTIFICATIONS;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(notificationPermission);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            activity.showPage(6);
            View manageButton = ScreenTest.find(activity.getWindow().getDecorView(), "管理过滤通知");
            if (!activity.networkFilterAvailable()) {
                assertNull(manageButton);
                assertNotNull(ScreenTest.find(activity.getWindow().getDecorView(), "纯离线版"));
                return;
            }
            assertNotNull(manageButton);
            assertTrue(manageButton.performClick());
            ShadowActivity shadow = shadowOf(activity);
            ShadowActivity.PermissionsRequest request = shadow.getLastRequestedPermission();
            assertEquals(MainActivity.FILTER_NOTIFICATIONS, request.requestCode);

            shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(notificationPermission);
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertEquals("过滤状态通知已允许", ShadowToast.getTextOfLatestToast());
            assertTrue(ScreenTest.find(activity.getWindow().getDecorView(), "管理过滤通知").performClick());
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadow.getNextStartedActivity().getAction());
            assertNull(AdFilterService.active);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = 34) public void deniedNetworkNotificationManagementUsesSystemSettingsOnLaterTaps() {
        new Store(context()).noteUpdateCheck(System.currentTimeMillis());
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            activity.showPage(6);
            if (!activity.networkFilterAvailable()) return;
            ShadowActivity shadow = shadowOf(activity);
            ScreenTest.clickFeature(activity, "管理过滤通知");
            ShadowActivity.PermissionsRequest first = shadow.getLastRequestedPermission();
            assertNotNull(first);
            assertEquals(MainActivity.FILTER_NOTIFICATIONS, first.requestCode);
            activity.onRequestPermissionsResult(first.requestCode, new String[0], new int[0]);
            assertNull(ShadowAlertDialog.getLatestAlertDialog());
            assertNull(ShadowToast.getTextOfLatestToast());

            ScreenTest.clickFeature(activity, "管理过滤通知");
            ShadowActivity.PermissionsRequest denied = shadow.getLastRequestedPermission();
            assertNotSame(first, denied);
            assertArrayEquals(new String[]{Manifest.permission.POST_NOTIFICATIONS}, denied.requestedPermissions);
            activity.onRequestPermissionsResult(denied.requestCode, denied.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_DENIED});
            AlertDialog recovery = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(recovery);
            assertEquals("打开系统通知设置", recovery.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            recovery.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            Intent settings = shadow.getNextStartedActivity();
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settings.getAction());
            assertEquals(activity.getPackageName(), settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));

            ScreenTest.clickFeature(activity, "管理过滤通知");
            assertSame(denied, shadow.getLastRequestedPermission());
            assertSame(recovery, ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, shadow.getNextStartedActivity().getAction());
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = 34) public void interruptedNotificationRequestDoesNotStartNetworkFiltering() {
        String notificationPermission = Manifest.permission.POST_NOTIFICATIONS;
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(notificationPermission);
        ShadowVpnService.setPrepareResult(null);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            activity.showPage(6);
            View start = ScreenTest.find(activity.getWindow().getDecorView(), "开启网络过滤");
            if (!activity.networkFilterAvailable()) {
                assertNull(start);
                return;
            }
            assertNotNull(start);
            assertTrue(start.performClick());
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            ShadowActivity.PermissionsRequest request = shadowOf(activity).getLastRequestedPermission();
            assertNotNull(request);
            assertEquals(MainActivity.START_FILTER_NOTIFICATIONS, request.requestCode);
            assertArrayEquals(new String[]{notificationPermission}, request.requestedPermissions);

            activity.onRequestPermissionsResult(request.requestCode, new String[0], new int[0]);
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());

            assertTrue(start.performClick());
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    new int[]{PackageManager.PERMISSION_DENIED});
            Intent filter = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(filter);
            assertEquals(new ComponentName(activity, AdFilterService.class), filter.getComponent());
            assertEquals("start", filter.getAction());
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(sdk = {26, 34}) public void vpnConsentWaitsForResumeAndChecksAuthorizationAgain() {
        new Store(context()).noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            if (!activity.networkFilterAvailable()) return;
            Intent consent = new Intent("app.clearscreen.test.VPN_CONSENT");
            ShadowVpnService.setPrepareResult(consent);
            activity.prepareFilter();
            ShadowActivity.IntentForResult first = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(first);
            assertEquals(consent.getAction(), first.intent.getAction());

            controller.pause();
            ShadowVpnService.setPrepareResult(null);
            activity.onActivityResult(first.requestCode, Activity.RESULT_OK, null);
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            ShadowVpnService.setPrepareResult(consent);
            controller.resume().postResume();
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            assertEquals("VPN 授权已失效，请重新开启网络过滤", ShadowToast.getTextOfLatestToast());

            activity.prepareFilter();
            ShadowActivity.IntentForResult second = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(second);
            controller.pause();
            ShadowVpnService.setPrepareResult(null);
            activity.onActivityResult(second.requestCode, Activity.RESULT_OK, null);
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
            controller.resume().postResume();
            Intent start = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(start);
            assertEquals(new ComponentName(activity, AdFilterService.class), start.getComponent());
            assertEquals("start", start.getAction());
            controller.pause().resume().postResume();
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        } finally {
            controller.pause().stop().destroy();
        }
    }
}
