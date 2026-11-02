package app.clearscreen.testfixture;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
/** Public menu leading to a deliberately unexported setting activity. */
public class RouteActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,64,32,32);TextView title=new TextView(this);title.setText("备用入口测试");page.addView(title);Button next=new Button(this);next.setText("广告设置");next.setOnClickListener(v->startActivity(new Intent(this,PrivateSettingsActivity.class)));page.addView(next);setContentView(page);}
}
