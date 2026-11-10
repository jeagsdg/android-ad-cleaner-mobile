package app.clearscreen;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A short, non-interactive confirmation in the accessibility service's display. */
final class SkipFeedback {
    private static final long MIN_INTERVAL_MS = 1800;
    private static final long VISIBLE_MS = 950;
    static final String ACCESSIBILITY_CLASS = "app.clearscreen.SkipFeedback";
    private final Context context;
    private final Handler main;
    private final WindowManager windows;
    private final Runnable hide = this::fadeOut;
    private LinearLayout badge;
    private long lastShown = -MIN_INTERVAL_MS;

    SkipFeedback(Context context, Handler main) {
        this.context = context;
        this.main = main;
        windows = context.getSystemService(WindowManager.class);
    }

    void show(String message) {
        long now = SystemClock.uptimeMillis();
        if (windows == null || now - lastShown < MIN_INTERVAL_MS) return;
        clear();
        LinearLayout view = new LinearLayout(context) {
            @Override public CharSequence getAccessibilityClassName() { return ACCESSIBILITY_CLASS; }
        };
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(9), dp(6), dp(14), dp(6));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xf51b312b);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), 0xff507665);
        view.setBackground(background);

        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.ic_app);
        mark.setClipToOutline(true);
        GradientDrawable markShape = new GradientDrawable();
        markShape.setColor(0xff176d59);
        markShape.setCornerRadius(dp(6));
        mark.setBackground(markShape);
        view.addView(mark, new LinearLayout.LayoutParams(dp(23), dp(23)));

        TextView label = new TextView(context);
        label.setText(message);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setMaxLines(1);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2);
        labelParams.setMarginStart(dp(7));
        view.addView(label, labelParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(60);
        params.setTitle("ClearScreenSkipFeedback");
        try {
            windows.addView(view, params);
        } catch (RuntimeException ignored) {
            return; // The display or accessibility connection may have changed.
        }
        badge = view;
        lastShown = now;
        main.postDelayed(hide, VISIBLE_MS);
    }

    void clear() {
        main.removeCallbacks(hide);
        LinearLayout view = badge;
        badge = null;
        if (view == null) return;
        view.animate().cancel();
        try { windows.removeViewImmediate(view); } catch (RuntimeException ignored) { }
    }

    private void fadeOut() {
        LinearLayout view = badge;
        if (view != null) view.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (badge == view) clear();
        }).start();
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
