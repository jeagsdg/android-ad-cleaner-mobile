package app.clearscreen.testfixture;

import android.app.Activity;
import android.os.*;
import android.view.Gravity;
import android.widget.*;

/** Ad-like UI fixture, no ads or external network. Modes: ad, plain, consent, editable. */
public class SplashActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);FrameLayout page=new FrameLayout(this);page.setBackgroundColor(0xffe0eee6);setContentView(page);String mode=getIntent().getStringExtra("mode");if(mode==null)mode="ad";
        TextView center=new TextView(this);center.setText("隔离开屏测试\n仅用于验证控件识别");center.setTextSize(24);center.setGravity(Gravity.CENTER);page.addView(center,new FrameLayout.LayoutParams(-1,-1));
        if(!mode.equals("plain")&&!mode.equals("sdk")){TextView ad=new TextView(this);ad.setText("广告");FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.LEFT);p.bottomMargin=100;p.leftMargin=30;page.addView(ad,p);}
        Button skip=new Button(this);skip.setText("跳过 5");FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(110*getResources().getDisplayMetrics().densityDpi/160,-2,Gravity.TOP|Gravity.RIGHT);p.topMargin=65;p.rightMargin=15;page.addView(skip,p);
        if(mode.equals("sdk")){skip.setText("");skip.setId(app.clearscreen.testfixture.R.id.tt_splash_skip_btn);}
        skip.setOnClickListener(v->{page.removeAllViews();TextView done=new TextView(this);done.setText("已进入测试内容");done.setTextSize(24);done.setGravity(Gravity.CENTER);page.addView(done,new FrameLayout.LayoutParams(-1,-1));});
        if(mode.equals("editable")){EditText edit=new EditText(this);edit.setHint("输入保护测试");FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);page.addView(edit,ep);}
        if(mode.equals("consent")){Button allow=new Button(this);allow.setText("同意");page.addView(allow,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));}
    }
}
