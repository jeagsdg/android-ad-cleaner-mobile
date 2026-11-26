package app.clearscreen;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowVpnService;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class AdFilterServiceTest {
    @org.robolectric.annotation.Implements(android.os.ParcelFileDescriptor.class)
    public static class TestTunnel {
        boolean closed;
        @org.robolectric.annotation.Implementation protected void close(){closed=true;}
    }

    @Config(shadows=TestTunnel.class)
    @Test public void liveBypassKeepsWorkingTunnelOnFailureAndCommitsOnlyTheLatestSelection()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        FilterStore store=new FilterStore(context);
        store.setBypassedApps(java.util.Collections.singleton("example.old"));
        ServiceController<AdFilterService> controller=Robolectric.buildService(AdFilterService.class).create();
        AdFilterService filter=controller.get();
        android.os.ParcelFileDescriptor old=org.robolectric.shadow.api.Shadow.newInstanceOf(android.os.ParcelFileDescriptor.class);
        android.os.ParcelFileDescriptor next=org.robolectric.shadow.api.Shadow.newInstanceOf(android.os.ParcelFileDescriptor.class);
        java.util.List<String> results=new java.util.ArrayList<>();
        try{
            AdFilterService.active=filter;
            ReflectionHelpers.setField(filter,"tunnel",old);
            ReflectionHelpers.setField(filter,"ready",true);
            AdFilterService.saveBypassedApps(context,java.util.Collections.singleton("example.pending"),results::add);
            filter.applyPendingBypass(apps->{throw new java.io.IOException("test setup failure");});
            shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.Collections.singleton("example.old"),store.bypassedApps());
            assertSame(old,ReflectionHelpers.getField(filter,"tunnel"));
            assertTrue(AdFilterService.running());
            assertTrue(results.get(0).contains("保留原名单"));
            AdFilterService.saveBypassedApps(context,store.bypassedApps(),results::add);
            filter.applyPendingBypass(apps->{throw new AssertionError("identical policy must not create a new VPN");});
            shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse(store.message().contains("未能应用"));
            AdFilterService.saveBypassedApps(context,java.util.Collections.singleton("example.superseded"),results::add);
            AdFilterService.saveBypassedApps(context,java.util.Collections.singleton("example.final"),results::add);
            filter.applyPendingBypass(apps->{assertEquals(java.util.Collections.singleton("example.final"),apps);return next;});
            shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.Collections.singleton("example.final"),store.bypassedApps());
            assertSame(next,ReflectionHelpers.getField(filter,"tunnel"));
            TestTunnel oldTunnel=org.robolectric.shadow.api.Shadow.extract(old);
            assertTrue(oldTunnel.closed);
            assertEquals(4,results.size());
            assertTrue(results.get(3).contains("已生效"));
        }finally{
            controller.destroy();
        }
        AdFilterService.saveBypassedApps(context,java.util.Collections.emptySet(),results::add);
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AdFilterService.running());
        assertTrue(store.bypassedApps().isEmpty());
        assertTrue(results.get(4).contains("下次开启"));
    }

    @Test public void revocationAfterVisibleStartStopsBeforeEnteringForeground() {
        new Store(RuntimeEnvironment.getApplication()).noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> activityController = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = activityController.get();
        Context base = activity.getBaseContext();
        ReflectionHelpers.setField(ContextWrapper.class, activity, "mBase", new ContextWrapper(base) {
            @Override public ComponentName startForegroundService(Intent intent) {
                throw new AssertionError("VPN authorization can change before the service is dispatched");
            }
        });
        ServiceController<AdFilterService> serviceController = null;
        boolean paused = false;
        try {
            ShadowVpnService.setPrepareResult(null);
            activity.prepareFilter();
            Intent start = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
            assertNotNull(start);
            assertEquals(new ComponentName(activity, AdFilterService.class), start.getComponent());
            assertEquals("start", start.getAction());
            activityController.pause();
            paused = true;

            ShadowVpnService.setPrepareResult(new Intent("app.clearscreen.test.VPN_CONSENT"));
            serviceController = Robolectric.buildService(AdFilterService.class).create();
            AdFilterService filter = serviceController.get();
            assertEquals(Service.START_NOT_STICKY, filter.onStartCommand(start, 0, 1));
            assertTrue(shadowOf(filter).isStoppedBySelf());
            assertNull(shadowOf(filter).getLastForegroundNotification());
            assertFalse(AdFilterService.running());
            assertFalse(new FilterStore(activity).enabled());
            assertTrue(new FilterStore(activity).message().contains("请先确认系统 VPN 授权"));
        } finally {
            if (serviceController != null) serviceController.destroy();
            ReflectionHelpers.setField(ContextWrapper.class, activity, "mBase", base);
            if (!paused) activityController.pause();
            activityController.stop().destroy();
        }
    }
}
