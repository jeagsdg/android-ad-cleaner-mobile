package app.clearscreen;

import android.graphics.Rect;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MissedAdCaptureTest {
    private static final String APP = "com.example.video", OTHER = "com.example.privateapp";
    private static final Rect SCREEN = new Rect(0, 0, 360, 800);

    @Test public void oneVisitIsRestrictedToItsAppAndModeAndExpires() {
        AtomicLong clock = new AtomicLong(5_000);
        MissedAdCapture capture = new MissedAdCapture(clock::get);
        MissedAdCapture.Session session = capture.start(APP, 123, MissedAdCapture.Mode.SPLASH, false);
        capture.verifiedWindow(OTHER);
        assertFalse(session.entered);
        assertNull(capture.session(OTHER));
        capture.verifiedWindow(APP);
        capture.sample(APP, APP + ".AdActivity", 11, SCREEN, List.of());
        capture.note(APP, MissedAdCapture.Mode.POPUP, SkipDiagnostics.Phase.CONFIRMED);
        capture.note(OTHER, MissedAdCapture.Mode.SPLASH, SkipDiagnostics.Phase.CONFIRMED);
        capture.note(APP, MissedAdCapture.Mode.SPLASH, SkipDiagnostics.Phase.AMBIGUOUS);
        capture.verifiedWindow(OTHER);
        capture.verifiedWindow(APP);
        capture.note(APP, MissedAdCapture.Mode.SPLASH, SkipDiagnostics.Phase.CONFIRMED);
        assertTrue(session.complete);
        String report = session.report("2.0", 28);
        assertTrue(report.contains("多个候选按钮"));
        assertTrue(report.contains("应用版本号：123"));
        assertTrue(report.contains(session.reference));
        assertFalse(report.contains("AdActivity"));
        assertFalse(report.contains(OTHER));
        clock.addAndGet(MissedAdCapture.RETAIN_MS);
        assertNull(capture.session(APP));
    }

    @Test public void consentedSampleRetainsBoundedStructuralEvidenceButNeverRawLabels() {
        MissedAdCapture capture = new MissedAdCapture(() -> 5_000);
        MissedAdCapture.Session session = capture.start(APP, 42, MissedAdCapture.Mode.POPUP, true);
        capture.verifiedWindow(APP);
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        for (int i = 0; i < 10; i++) nodes.add(node(APP, "ad_skip_" + i, "关闭广告", "private media title"));
        nodes.add(node(OTHER, "skip_privateapp", "关闭广告", "other app private text"));
        capture.sample(APP, APP + ".AdActivity", nodes.get(0).getWindowId(), SCREEN, nodes);
        capture.stop(APP, true);
        String report = session.report("2.0", 28);
        assertTrue(report.contains(APP + ".AdActivity"));
        assertTrue(report.contains(APP + ":id/ad_skip_0"));
        assertTrue(report.contains("android.widget.ImageButton"));
        assertEquals(6, report.split("• ", -1).length - 1);
        assertFalse(report.contains("关闭广告"));
        assertFalse(report.contains("private media title"));
        assertFalse(report.contains(OTHER));
    }

    @Test public void passwordPageOverridesCandidateAndCaptureClosesAtTimeLimit() {
        AtomicLong clock = new AtomicLong(5_000);
        MissedAdCapture capture = new MissedAdCapture(clock::get);
        MissedAdCapture.Session session = capture.start(APP, 45, MissedAdCapture.Mode.SPLASH, true);
        capture.verifiedWindow(APP);
        AccessibilityNodeInfo ad = node(APP, "ad_skip", "跳过", "");
        AccessibilityNodeInfo password = node(APP, "secret", "sensitive account info", "");
        password.setPassword(true);
        capture.sample(APP, APP + ".SecretPage", ad.getWindowId(), SCREEN, List.of(ad, password));
        clock.addAndGet(MissedAdCapture.CAPTURE_MS);
        assertEquals(Long.MAX_VALUE, capture.nextProbe(APP));
        assertTrue(session.complete);
        String report = session.report("2.0", 28);
        assertTrue(report.contains("受保护"));
        assertFalse(report.contains("SecretPage"));
        assertFalse(report.contains("ad_skip"));
        assertFalse(report.contains("sensitive account info"));
    }

    @Test public void appDetailExplainsRecentResultAndOnlyExportsAfterPreview() {
        PackageInfo installed = new PackageInfo();
        installed.packageName = APP;
        installed.versionCode = 333;
        installed.versionName = "3.3.3";
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(installed);
        Bundle state = new Bundle();
        state.putInt("tab", MainActivity.APP_AD_DETAIL);
        state.putString("appAdSelected", APP);
        ActivityController<MainActivity> activity = Robolectric.buildActivity(MainActivity.class).setup(state);
        try {
            MainActivity screen = activity.get();
            screen.store.setSplashEnabled(true);
            screen.store.setPopupEnabled(true);
            screen.store.setKeepSkipDetails(true);
            screen.store.recordSplash(APP, false);
            screen.store.recordPopup(OTHER, true);
            screen.render();
            View decor = screen.getWindow().getDecorView();
            find(decor, "刚才没跳过").performClick();
            assertEquals(MainActivity.APP_AD_MISS, screen.tab);
            assertNotNull(find(decor, "最近 5 分钟有一次处理记录，未确认广告已关闭。"));
            assertNull(find(decor, "最近 5 分钟有一次已确认关闭的记录"));
            find(decor, "生成基本反馈").performClick();
            AlertDialog choice = ShadowAlertDialog.getLatestAlertDialog();
            choice.getListView().performItemClick(null, 0, 0);
            shadowOf(Looper.getMainLooper()).idle();
            assertNull(shadowOf(screen).getNextStartedActivityForResult());
            ClipboardManager clipboard = screen.getSystemService(ClipboardManager.class);
            assertFalse(clipboard.hasPrimaryClip());
            find(decor, "预览、复制或保存报告").performClick();
            AlertDialog preview = ShadowAlertDialog.getLatestAlertDialog();
            android.widget.EditText editor=preview.findViewById(R.id.feedback_report_text);
            String contents=editor.getText().toString();
            assertTrue(contents.contains("编号：JP-"));
            assertTrue(contents.contains("应用版本：3.3.3"));
            assertTrue(contents.contains("应用版本号：333"));
            assertFalse(contents.contains(OTHER));
            assertTrue(contents.contains("查看报告时的规则"));
            editor.setText("仅共享我核对过的部分");
            assertFalse(clipboard.hasPrimaryClip());
            preview.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, shadowOf(screen).getNextStartedActivityForResult().intent.getAction());
            screen.onBackPressed();
            assertEquals(MainActivity.APP_AD_DETAIL, screen.tab);
            assertNotNull(find(decor, "允许跳过开屏广告"));
        } finally {
            MissedAdCapture.LOCAL.clear(APP);
            activity.pause().stop().destroy();
        }
    }

    private static View find(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View match = find(((ViewGroup) root).getChildAt(i), text);
            if (match != null) return match;
        }
        return null;
    }

    private static AccessibilityNodeInfo node(String pkg, String id, String text, String description) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(pkg);
        node.setClassName("android.widget.ImageButton");
        node.setViewIdResourceName(pkg + ":id/" + id);
        node.setText(text);
        node.setContentDescription(description);
        node.setVisibleToUser(true);
        node.setClickable(true);
        node.setBoundsInScreen(new Rect(235, 20, 325, 80));
        return node;
    }
}
