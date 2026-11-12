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
