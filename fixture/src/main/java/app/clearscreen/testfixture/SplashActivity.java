package app.clearscreen.testfixture;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.*;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.*;

/** Ad-like UI fixture with optional delayed dialogs, no ads or external network. */
public class SplashActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);FrameLayout page=new FrameLayout(this);page.setBackgroundColor(0xffe0eee6);setContentView(page);String mode=getIntent().getStringExtra("mode");if(mode==null)mode="ad";
        if (mode.startsWith("bench-")) {
            showBenchmark(page, mode);
            return;
        }
        if (mode.startsWith("popup")) {
            showPopupFixture(page, mode);
            return;
        }
        TextView center=new TextView(this);center.setText("隔离开屏测试\n仅用于验证控件识别");center.setTextSize(24);center.setGravity(Gravity.CENTER);page.addView(center,new FrameLayout.LayoutParams(-1,-1));
        if(!mode.equals("plain")&&!mode.equals("sdk")&&!mode.equals("multilingual")){TextView ad=new TextView(this);ad.setText("广告");FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);p.bottomMargin=100;p.leftMargin=30;page.addView(ad,p);}
        Button skip=new Button(this);skip.setText("跳过 5");FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(110*getResources().getDisplayMetrics().densityDpi/160,-2,Gravity.TOP|Gravity.RIGHT);p.topMargin=65;p.rightMargin=15;page.addView(skip,p);
        if(mode.equals("sdk")){skip.setText("");skip.setId(app.clearscreen.testfixture.R.id.tt_splash_skip_btn);}
        if(mode.equals("multilingual")){
            skip.setText("SKIP ADS （５s）");
            p.gravity=Gravity.TOP|Gravity.LEFT;
            p.leftMargin=15;
            skip.setLayoutParams(p);
        }
        skip.setOnClickListener(v->{page.removeAllViews();TextView done=new TextView(this);done.setText("已进入测试内容");done.setTextSize(24);done.setGravity(Gravity.CENTER);page.addView(done,new FrameLayout.LayoutParams(-1,-1));});
        if(mode.equals("editable")){EditText edit=new EditText(this);edit.setHint("输入保护测试");FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);page.addView(edit,ep);}
        if(mode.equals("consent")){Button allow=new Button(this);allow.setText("同意");page.addView(allow,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));}
    }

    private void showBenchmark(FrameLayout page, String mode) {
        TextView content = new TextView(this);
        content.setGravity(Gravity.CENTER);
        content.setText("等待模拟广告");
        page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        if (mode.equals("bench-popup")) {
            page.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                long shown = SystemClock.elapsedRealtime();
                new AlertDialog.Builder(this).setTitle("广告")
                        .setNegativeButton("关闭广告", (d, which) -> benchmarkResult(content, shown))
                        .show();
            }, 500);
            return;
        }
        Runnable show = () -> {
            if (isFinishing() || isDestroyed()) return;
            Button skip = new Button(this);
            skip.setText("跳过广告");
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.RIGHT);
            position.topMargin = 60;
            position.rightMargin = 18;
            long shown = SystemClock.elapsedRealtime();
            skip.setOnClickListener(view -> {
                page.removeView(skip);
                benchmarkResult(content, shown);
            });
            page.addView(skip, position);
        };
        if (mode.equals("bench-splash-delayed")) page.postDelayed(show, 550);
        else show.run();
    }

    private void benchmarkResult(TextView content, long shown) {
        content.setText("关闭耗时毫秒=" + (SystemClock.elapsedRealtime() - shown));
    }

    private void showPopupFixture(FrameLayout page, String mode) {
        TextView content = new TextView(this);
        content.setGravity(Gravity.CENTER);
        content.setTextSize(24);
        content.setText("隔离弹窗测试");
        page.addView(content, new FrameLayout.LayoutParams(-1, -1));
        boolean legacy = mode.startsWith("popup-legacy");
        boolean generic = mode.startsWith("popup-generic");
        boolean hasInitialState = mode.equals("popup-legacy");
        String prompt = legacy ? (hasInitialState ? "正在检查版本" : "发现新版本 2.3")
                : generic ? "广告" : (mode.equals("popup-mismatch") ? "有新内容可用" : "有新版本可用");
        long delay = mode.equals("popup-late") ? 13_000 : 1_000;
        page.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle(prompt)
                    .setNegativeButton(generic ? (mode.equals("popup-generic-ordinary") ? "关闭" : "关闭广告") : "以后再说", (d, which) -> {
                        Button button = ((AlertDialog) d).getButton(which);
                        if (!legacy) content.setText("已关闭测试弹窗");
                        else if (Boolean.TRUE.equals(button.getTag())) content.setText("已用手势关闭测试弹窗");
                        else content.setText("已通过控件关闭测试弹窗");
                    });
            if (mode.equals("popup-sensitive")) builder.setMessage("请阅读隐私协议");
            if (mode.equals("popup-generic-reward")) builder.setMessage("观看广告领取奖励");
            if (mode.equals("popup-generic-sensitive")) builder.setMessage("请阅读隐私协议");
            AlertDialog dialog = builder.show();
            if (legacy) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnTouchListener((button, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) button.setTag(Boolean.TRUE);
                return false;
            });
            if (hasInitialState) page.postDelayed(() -> {
                if (dialog.isShowing()) dialog.setTitle("发现新版本 2.3");
            }, 1_800);
        }, delay);
    }
}
