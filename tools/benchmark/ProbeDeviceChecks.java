package app.clearscreen;

import android.app.UiAutomation;
import android.os.PowerManager;
import android.os.SystemClock;

/** Compiled only into the explicitly prepared benchmark instrumentation APK. */
final class ProbeDeviceChecks {
    static void run(DeviceChecks test, UiAutomation automation, CleanerService service, boolean screenOff)
            throws Exception {
        Store prefs = new Store(test.getTargetContext());
        prefs.setSplashEnabled(true);
        prefs.setPopupEnabled(true);
        test.require(service == CleanerService.get() && prefs.splashEnabled() && prefs.popupEnabled(),
                "idle measurement starts with a live service and both features enabled");
        if (!screenOff) return;

        PowerManager power = test.getTargetContext().getSystemService(PowerManager.class);
        long before = prefs.splashCount() + prefs.popupCount();
        try {
            test.require(power.isInteractive(), "display begins awake");
            test.shell(automation, "input keyevent 223");
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (power.isInteractive() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
            test.require(!power.isInteractive(), "system confirms screen off");
            test.shell(automation,
                    "am start -n app.clearscreen.testfixture/.SplashActivity --es mode bench-splash-delayed");
            SystemClock.sleep(1000);
            int reads = SplashSkipper.rootReadProbe.get();
            SystemClock.sleep(3000);
            test.require(!power.isInteractive() && CleanerService.get() == service,
                    "fixture left display asleep and service connected");
            test.require(SplashSkipper.rootReadProbe.get() == reads, "no active-root scan while asleep");
            test.require(prefs.splashCount() + prefs.popupCount() == before, "no new dismissal while asleep");
        } finally {
            prefs.setSplashEnabled(false);
            prefs.setPopupEnabled(false);
            test.shell(automation, "input keyevent 224");
            test.shell(automation, "input keyevent 82");
        }
    }
}
