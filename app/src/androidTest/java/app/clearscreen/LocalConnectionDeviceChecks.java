package app.clearscreen;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import java.util.ArrayList;

final class LocalConnectionDeviceChecks {
    static void run(DeviceChecks test, CleanerService service) {
        Intent setup = new Intent(test.getTargetContext(), MainActivity.class)
                .putExtra("tab", 4).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        MainActivity screen = (MainActivity) test.startActivitySync(setup);
        test.waitForIdleSync();
        try {
            test.runOnMainSync(() -> {
                View entry = text(screen.getWindow().getDecorView(), "测试本机点击");
                test.require(entry != null && entry.performClick(), "open check from connected permission screen");
                if (Build.VERSION.SDK_INT >= 29) checkManualPath(test);
            });
            boolean passed = false;
            long until = SystemClock.uptimeMillis() + 6000;
            while (SystemClock.uptimeMillis() < until && !passed) {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                passed = root != null && screen.getPackageName().equals(String.valueOf(root.getPackageName()))
                        && !root.findAccessibilityNodeInfosByText("测试通过。无障碍服务已连接").isEmpty();
                if (!passed) SystemClock.sleep(80);
            }
            test.require(passed, "real service clicks the dedicated control and the result is visible");
        } finally {
            test.runOnMainSync(screen::finish);
        }
    }

    @android.annotation.TargetApi(29)
    private static void checkManualPath(DeviceChecks test) {
        for (View decor : WindowInspector.getGlobalWindowViews()) {
            View button = decor.findViewById(R.id.local_check_exit);
            if (button == null) continue;
            // Stay on the UI thread until both user paths finish, before the service's delayed attempt.
            test.require(button.performClick(), "ordinary button activation reaches its listener");
            test.require(text(decor, "手动点击不会计入测试") != null, "manual activation remains unconfirmed");
            test.require(!button.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, new Bundle()),
                    "untagged accessibility activation cannot complete the test");
            test.require(text(decor, "测试通过。") == null, "no success before service action");
            return;
        }
        throw new AssertionError("Own test dialog was not attached");
    }

    private static View text(View decor, String value) {
        ArrayList<View> matches = new ArrayList<>();
        decor.findViewsWithText(matches, value, View.FIND_VIEWS_WITH_TEXT);
        return matches.isEmpty() ? null : matches.get(0);
    }
}
