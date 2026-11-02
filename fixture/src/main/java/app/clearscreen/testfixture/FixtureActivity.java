package app.clearscreen.testfixture;
import android.app.*;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.widget.*;

/** Isolated test app. Never included in the distributed cleaner APK. */
public class FixtureActivity extends Activity {
    LinearLayout page;
    @Override public void onCreate(Bundle state){super.onCreate(state);
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,64,32,32);setContentView(page);
        TextView title=new TextView(this);title.setText("模拟广告设置 · 仅用于测试");title.setTextSize(23);page.addView(title);
        addSwitch("个性化推荐",true);addSwitch("限制广告跟踪",false);addSwitch("应用更新通知",true);
        Button reset=new Button(this);reset.setText("重置测试开关");reset.setOnClickListener(v->{getSharedPreferences("settings",0).edit().clear().commit();recreate();});page.addView(reset);
        Button post=new Button(this);post.setText("发送测试通知");post.setOnClickListener(v->notifyChannels());page.addView(post);
    }
    boolean confirms(){return false;}
    void addSwitch(String title,boolean initial){SharedPreferences prefs=getSharedPreferences("settings",0);Switch s=new Switch(this);s.setText(title);s.setTextSize(18);s.setPadding(0,24,0,24);s.setChecked(prefs.getBoolean(title,initial));boolean[] internal={false};s.setOnCheckedChangeListener((v,b)->{
        if(internal[0])return;
        if(confirms()&&!b&&title.equals("个性化推荐")){internal[0]=true;s.setChecked(true);internal[0]=false;
            new AlertDialog.Builder(this).setTitle("确定关闭“个性化推荐”吗？").setPositiveButton("确定",(d,w)->{internal[0]=true;s.setChecked(false);internal[0]=false;prefs.edit().putBoolean(title,false).commit();}).setNegativeButton("取消",null).show();
        }else prefs.edit().putBoolean(title,b).commit();
    });page.addView(s);}
    void notifyChannels(){NotificationManager n=getSystemService(NotificationManager.class);String[] names={"活动优惠","天气预警"};for(int i=0;i<2;i++){String id="fixture-"+i;n.createNotificationChannel(new NotificationChannel(id,names[i],NotificationManager.IMPORTANCE_DEFAULT));n.notify(i,new Notification.Builder(this,id).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(names[i]).setContentText("测试通知正文不得进入净屏记录").build());}}
}
