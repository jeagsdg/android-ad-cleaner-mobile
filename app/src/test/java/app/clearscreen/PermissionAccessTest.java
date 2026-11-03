package app.clearscreen;

import android.content.*;
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
        for (String brand : Arrays.asList("oppo", "oneplus", "realme", "xiaomi", "redmi", "poco", "honor", "vivo", "samsung", "google", "other")) {
            org.robolectric.shadows.ShadowBuild.setManufacturer(brand);
            org.robolectric.shadows.ShadowBuild.setBrand(brand);
            String help = PermissionAccess.restrictedHelp();
            assertTrue(help.contains("如果系统明确提示"));
            assertTrue(help.contains("查看是否提供"));
            if (brand.equals("oppo")) {assertTrue(help.contains("权限管理 → 右上角"));assertTrue(help.contains("解除所有授权限制"));}
            if (brand.equals("xiaomi")) assertTrue(help.contains("高级设置"));
        }
    }
    @Test public void diagnosticsSeparateDisabledFromRestrictedAndKeepLedger() throws Exception {
        Store store = new Store(context());
        Rule rule = store.allRules().get(0);
        String id = store.prepare(rule, rule.targets.get(0), true, false);
        JSONObject export = new JSONObject(store.export());
        JSONObject permissions = export.getJSONObject("permissions");
        assertEquals("OFF", permissions.getString("accessibilityState"));
        assertEquals("not_determined", permissions.getString("restrictionStatus"));
        assertTrue(permissions.has("installingPackage"));
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
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        service.onServiceConnected();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        View ready = ScreenTest.find(activity.getWindow().getDecorView(), "返回开始清理");
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
        assertNotNull(ScreenTest.find(next.getWindow().getDecorView(), "前往系统授权"));
        recreated.pause().stop().destroy();
    }
}
