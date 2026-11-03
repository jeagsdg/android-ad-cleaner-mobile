package app.clearscreen.testfixture;

import android.os.*;
import android.widget.*;

public class DelayedActivity extends FixtureActivity {
    @Override public void onCreate(Bundle state){super.onCreate(state);page.removeAllViews();ProgressBar progress=new ProgressBar(this);page.addView(progress);TextView title=new TextView(this);title.setText("正在加载…");page.addView(title);
        new Handler(Looper.getMainLooper()).postDelayed(()->{if(isFinishing()||isDestroyed())return;page.removeAllViews();TextView loaded=new TextView(this);loaded.setText("延迟加载完成");page.addView(loaded);addSwitch("个性化推荐",true);addSwitch("应用更新通知",true);},2300);
    }
}
