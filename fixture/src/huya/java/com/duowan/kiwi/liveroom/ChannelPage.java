package com.duowan.kiwi.liveroom;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.duowan.kiwi.R;

/** Simulates the accessibility fields and hit regions of the observed offline live-ad snapshot. */
public final class ChannelPage extends Activity {
    private static final int COUNTDOWN_SECONDS = 13;
    private static final int REFERENCE_WIDTH = 2400;
    private static final int REFERENCE_HEIGHT = 1080;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout page;
    private FrameLayout ad;
    private TextView summary;
    private long shownAt;
    private long adClickAt = -1;
    private int coordinateClose;
    private int accessibilityClose;
    private int adCardClicks;
    private int vipClicks;
    private int playerCloseClicks;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        page = new FrameLayout(this);
        setContentView(page);
        page.post(() -> {
            buildPlayer();
            main.postDelayed(this::showAd, 600);
        });
    }

    private void buildPlayer() {
        TextView content = text("隔离直播内容");
        page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        summary = text("");
        place(page, summary, 24, 944, 760, 90);
        TextView vip = text("开通VIP免广告");
        vip.setId(R.id.vip_no_ads_landscape_entry);
        vip.setOnClickListener(view -> {
            vipClicks++;
            report();
        });
        place(page, vip, 1259, 122, 289, 72);

        View playerClose = described("关闭");
        playerClose.setId(R.id.live_player_close);
        playerClose.setOnClickListener(view -> {
            playerCloseClicks++;
            report();
        });
        place(page, playerClose, 2298, 34, 72, 56);
    }

    private void showAd() {
        String mode = getIntent().getStringExtra("mode");
        ad = group();
        ad.setBackgroundColor(0xffe2e7eb);
        ad.setOnClickListener(view -> {
            adCardClicks++;
            report();
        });
        FrameLayout card = group();
        place(ad, card, 0, 0, 660, 732);
        place(card, new ImageView(this), 3, 3, 60, 60);
        place(card, described("主播优选"), 75, 9, 388, 48);
        View countdown = buildControls(card);

        FrameLayout videoShell = group();
        place(card, videoShell, 3, 81, 654, 372);
        FrameLayout video = new FrameLayout(this);
        video.setId(R.id.base_video_view_player_container);
        video.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        place(videoShell, video, 0, 0, 654, 372);
        FrameLayout player = new FrameLayout(this);
        player.setId(R.id.video_play_container);
        player.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        place(video, player, 0, 0, 654, 372);

        if ("ordinary".equals(mode)) place(card, text("直播"), 606, 93, 36, 30);
        else if ("outside-mark".equals(mode)) placeMarker(page, 1420, 267);
        else placeMarker(card, 564, 93);
        place(page, ad, 1620, 174, 660, 732);
        shownAt = SystemClock.uptimeMillis();
        report();
        updateCountdown(countdown);
    }

    private View buildControls(FrameLayout card) {
        FrameLayout controls = group();
        place(card, controls, 469, 3, 188, 60);
        View countdown = described(COUNTDOWN_SECONDS + "s");
        place(controls, countdown, 18, 6, 53, 48);
        place(controls, group(), 83, 18, 3, 24);

        View close = new View(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    accessibilityClose++;
                    report();
                }
                return super.performAccessibilityAction(action, args);
            }
        };
        close.setContentDescription("关闭");
        close.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        close.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                coordinateClose++;
                adClickAt = SystemClock.uptimeMillis() - shownAt;
                page.removeView(ad);
                ad = null;
                report();
            }
            return true;
        });
        place(controls, close, 98, 6, 72, 48);
        return countdown;
    }

    private void placeMarker(FrameLayout parent, int x, int y) {
        FrameLayout shell = new FrameLayout(this);
        LinearLayout marker = new LinearLayout(this);
        marker.setId(R.id.lz_ad_container);
        marker.setPadding(scaledX(6), 0, 0, 0);
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ImageView logo = new ImageView(this);
        logo.setId(R.id.lz_ad_logo);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        marker.addView(logo, new LinearLayout.LayoutParams(scaledX(30), -1));
        TextView badge = text("广告");
        badge.setId(R.id.lz_ad_text);
        LinearLayout.LayoutParams badgePlacement = new LinearLayout.LayoutParams(scaledX(36), -1);
        badgePlacement.leftMargin = scaledX(6);
        marker.addView(badge, badgePlacement);
        place(shell, marker, 0, 0, 84, 30);
        place(parent, shell, x, y, 84, 30);
    }

    private void updateCountdown(View countdown) {
        if (ad == null) return;
        long remaining = shownAt + COUNTDOWN_SECONDS * 1_000 - SystemClock.uptimeMillis();
        if (remaining < 0) return;
        countdown.setContentDescription(((remaining + 999) / 1_000) + "s");
        main.postDelayed(() -> updateCountdown(countdown), 1_000);
    }

    private void report() {
        summary.setText("直播广告关闭结果=" + coordinateClose + "," + accessibilityClose + ","
                + adCardClicks + "," + vipClicks + "," + playerCloseClicks + "," + adClickAt);
    }

    private FrameLayout group() {
        FrameLayout group = new FrameLayout(this) {
            @Override public CharSequence getAccessibilityClassName() {
                return "android.view.ViewGroup";
            }
        };
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return group;
    }

    private View described(String description) {
        View view = new View(this);
        view.setContentDescription(description);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    private TextView text(String value) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setGravity(Gravity.CENTER);
        return text;
    }

    private void place(FrameLayout parent, View child, int x, int y, int width, int height) {
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(scaledX(width), scaledY(height));
        position.leftMargin = scaledX(x);
        position.topMargin = scaledY(y);
        parent.addView(child, position);
    }

    private int scaledX(int x) {
        return Math.round(x * page.getWidth() / (float) REFERENCE_WIDTH);
    }

    private int scaledY(int y) {
        return Math.round(y * page.getHeight() / (float) REFERENCE_HEIGHT);
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
