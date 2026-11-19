package app.clearscreen.testfixture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Local fixture for full-screen ads with explicit close actions and a separate countdown. */
public final class CountdownAdActivity extends Activity {
    private static final long LATE_DELAY_MS = 14_000;
    private static final long COUNTDOWN_MS = 15_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout page;
    private FrameLayout ad;
    private TextView content;
    private TextView countdown;
    private boolean late;
    private boolean locked;
    private long foregroundAt;
    private long shownAt;
    private long actionableAt;
    private int lockedActions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        page = new FrameLayout(this);
        content = new TextView(this);
        content.setText("隔离测试内容");
        content.setGravity(Gravity.CENTER);
        page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        setContentView(page);

        late = getIntent().getBooleanExtra("late", false);
        locked = getIntent().getBooleanExtra("locked", false);
        if (!late) {
            foregroundAt = SystemClock.uptimeMillis();
            showAd();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && late && foregroundAt == 0) {
            foregroundAt = SystemClock.uptimeMillis();
            handler.postDelayed(this::showAd, LATE_DELAY_MS);
        }
    }

    private void showAd() {
        ad = new FrameLayout(this);
        ad.setBackgroundColor(0xffe0eee6);
        ad.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.setVisibility(View.INVISIBLE);
        page.addView(ad, new FrameLayout.LayoutParams(-1, -1));

        TextView badge = new TextView(this);
        badge.setText("广告");
        ad.addView(badge, position(Gravity.TOP | Gravity.LEFT, 20));

        countdown = new TextView(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) lockedActions++;
                return super.performAccessibilityAction(action, arguments);
            }
        };
        ad.addView(countdown, position(Gravity.BOTTOM | Gravity.RIGHT, 42));
        shownAt = SystemClock.uptimeMillis();
        if (!locked) addClose("关闭广告");
        updateCountdown();
    }

    private void updateCountdown() {
        long remaining = shownAt + COUNTDOWN_MS - SystemClock.uptimeMillis();
        if (remaining <= 0) {
            ad.removeView(countdown);
            countdown = null;
            if (locked) addClose("跳过广告");
            return;
        }
        long seconds = (remaining + 999) / 1_000;
        countdown.setText(seconds + "秒后可跳过");
        handler.postAtTime(this::updateCountdown, shownAt + COUNTDOWN_MS - (seconds - 1) * 1_000);
    }

    private void addClose(String label) {
        Button close = new Button(this);
        close.setText(label);
        close.setOnClickListener(view -> {
            long dismissalAt = SystemClock.uptimeMillis();
            handler.removeCallbacksAndMessages(null);
            page.removeView(ad);
            ad = null;
            countdown = null;
            content.setText("全屏广告关闭结果=" + (shownAt - foregroundAt) + ","
                    + (actionableAt - shownAt) + "," + (dismissalAt - shownAt) + "," + lockedActions);
            content.setVisibility(View.VISIBLE);
        });
        actionableAt = SystemClock.uptimeMillis();
        ad.addView(close, position(Gravity.TOP | Gravity.RIGHT, 20));
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (countdown != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            int[] origin = new int[2];
            countdown.getLocationOnScreen(origin);
            float x = event.getRawX() - origin[0];
            float y = event.getRawY() - origin[1];
            if (x >= 0 && x < countdown.getWidth() && y >= 0 && y < countdown.getHeight()) lockedActions++;
        }
        return super.dispatchTouchEvent(event);
    }

    private FrameLayout.LayoutParams position(int gravity, int marginDp) {
        int margin = Math.round(marginDp * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, gravity);
        position.setMargins(margin, margin, margin, margin);
        return position;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
