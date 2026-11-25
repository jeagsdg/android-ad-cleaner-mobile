package com.douyu.module.player.p.rambobase;

import air.tv.douyu.android.R;
import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Offline fixture for the two independently dismissible ads in sample 17977729. */
public final class RamboPlayerActivity extends Activity {
    private FrameLayout page;
    private TextView summary;
    private final StringBuilder order = new StringBuilder();
    private int bannerActions;
    private int downloadActions;
    private int ctaActions;
    private int cardActions;
    private int outerActions;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        page = new FrameLayout(this);
        setContentView(page);
        page.post(() -> {
            summary = text("");
            place(page, summary, 24, 210, 1_140, 90);
            TextView player = text("正常直播内容");
            place(page, player, 24, 500, 810, 120);
            TextView outer = text("更多直播");
            outer.setOnClickListener(view -> {
                outerActions++;
                report();
            });
            place(page, outer, 1_150, 300, 220, 78);
            page.postDelayed(this::showAds, 600);
        });
    }

    private void showAds() {
        FrameLayout banner = card();
        place(banner, text("横幅广告"), 390, 42, 643, 70);
        place(banner, action("去看看"), 1_061, 68, 248, 90);
        ImageView closeBanner = new ImageView(this);
        closeBanner.setId(R.id.el2);
        closeBanner.setOnClickListener(view -> {
            bannerActions++;
            order.append('1');
            page.removeView(banner);
            report();
        });
        place(banner, closeBanner, 1_309, 47, 131, 131);
        place(page, banner, 0, 1_350, 1_440, 225);

        FrameLayout download = card();
        download.setId(R.id.xhdamucard_widget_placeHolder);
        FrameLayout downloadBody = new FrameLayout(this);
        place(downloadBody, text("推荐"), 11, 11, 106, 67);
        place(downloadBody, text("下载广告"), 6, 310, 300, 70);
        place(downloadBody, action("免费下载"), 19, 580, 375, 90);
        place(download, downloadBody, 0, 28, 413, 675);
        ImageView closeDownload = new ImageView(this);
        closeDownload.setId(R.id.bx5);
        closeDownload.setOnClickListener(view -> {
            downloadActions++;
            order.append('2');
            page.removeView(download);
            report();
        });
        place(download, closeDownload, 385, 0, 56, 56);
        place(page, download, 988, 2_287, 441, 703);
        report();
    }

    private FrameLayout card() {
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundColor(0xffe3e6ea);
        card.setOnClickListener(view -> {
            cardActions++;
            report();
        });
        return card;
    }

    private FrameLayout action(String label) {
        FrameLayout action = new FrameLayout(this);
        TextView caption = text(label);
        caption.setId(R.id.bwy);
        action.addView(caption, new FrameLayout.LayoutParams(-1, -1));
        action.setOnClickListener(view -> {
            ctaActions++;
            report();
        });
        return action;
    }

    private void report() {
        summary.setText("斗鱼关闭结果=" + bannerActions + "," + downloadActions + "," + ctaActions
                + "," + cardActions + "," + outerActions + "," + order);
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
        return Math.round(x * page.getWidth() / 1440f);
    }

    private int scaledY(int y) {
        return Math.round(y * page.getHeight() / 3200f);
    }
}
