package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class CleanerService extends AccessibilityService {
    private static java.lang.ref.WeakReference<CleanerService> reference=new java.lang.ref.WeakReference<>(null);
    public static CleanerService get(){return reference.get();}
    public volatile boolean busy;
    public volatile String progress="准备就绪";
    private volatile boolean cancelled;private int resultTab=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout overlay;private TextView overlayText;
    @Override protected void onServiceConnected(){reference=new java.lang.ref.WeakReference<>(this);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){stop();}
    @Override public void onDestroy(){stop();worker.shutdownNow();reference.clear();hideOverlay();super.onDestroy();}
    public void stop(){cancelled=true;progress="正在停止…";}
    private void note(String s){progress=s;main.post(()->{if(overlayText!=null)overlayText.setText(s);});}
    /** One user-approved run: inspect each exact target, save original, change, and verify. */
    public synchronized void clean(List<Rule> rules){if(busy)return;resultTab=0;start(()->{
        Store store=new Store(this);Policy policy=store.policy();JSONArray results=new JSONArray();store.setLastRun("上次任务未完整结束，请核对记录");store.save("scan",results);
        UiDriver ui=new UiDriver(this,()->cancelled);int index=0;
        for(Rule rule:rules){if(cancelled)break;note("清理 "+(++index)+" / "+rules.size()+" · "+rule.title);
            try{if(rule.notification()&&(!policy.classify(rule.channelName,rule.channelId,rule.pkg,rule.channelApp).recommended()||policy.criticalNotification(rule.channelName)))throw new UiDriver.Halt("通知渠道用途不明确或包含重要服务，未操作");ui.open(rule);
                for(String label:rule.targets){if(cancelled)break;String record="";JSONObject item=scanItem(rule,label);
                    try{if(policy.protectedSetting(label))throw new UiDriver.Halt("服务类功能受到保护，未操作");
                        ui.top();UiDriver.Found f=ui.find(label);boolean desired=policy.desired(label);
                        if(f==null){put(item,"status","missing");put(item,"message","未识别目标开关，未操作");}
                        else if(f.checked==desired){put(item,"status","already");put(item,"message","已经是目标状态");}
                        else{put(item,"before",f.checked);record=store.prepare(rule,label,f.checked,desired);
                            if(!ui.set(label,f.checked,desired))throw new UiDriver.Halt("未确认改变结果，请核对原设置");
                            store.finish(record,"changed","已回读确认");put(item,"status","changed");put(item,"message","已回读确认");}
                    }catch(Exception e){if(e instanceof UiDriver.Halt&&((UiDriver.Halt)e).terminal)cancelled=true;if(!record.isEmpty()){store.finish(record,"unknown",e.getMessage());cancelled=true;}put(item,"status","failed");put(item,"message",e.getMessage());}
                    results.put(item);store.save("scan",results);
                }
            }catch(Exception e){if(e instanceof UiDriver.Halt&&((UiDriver.Halt)e).terminal)cancelled=true;JSONObject item=scanItem(rule,"");put(item,"status","skipped");put(item,"message",e.getMessage());results.put(item);store.save("scan",results);}
        }
        store.setLastRun(cancelled?"已停止，已完成的改动已保存":"本轮清理结束");note(store.lastRun());
    });}
    private JSONObject scanItem(Rule r,String label){JSONObject j=new JSONObject();put(j,"rule",r.json());put(j,"label",label);put(j,"key",r.id+":"+label);put(j,"build",Build.FINGERPRINT);return j;}
    public synchronized void restore(JSONObject entry){if(busy)return;resultTab=2;start(()->{
        Store store=new Store(this);String id=entry.getString("id");Rule r=new Rule(entry.getJSONObject("rule"));String label=entry.getString("label");UiDriver ui=new UiDriver(this,()->cancelled);note("恢复 · "+label);
        try{if(!Build.FINGERPRINT.equals(entry.optString("build")))throw new UiDriver.Halt("系统版本已变化，请按原路径手动核对");
            if(entry.optLong("packageVersion",-1)>=0&&entry.optLong("packageVersion")!=store.packageVersion(r.pkg))throw new UiDriver.Halt("目标应用版本已变化，请按原路径手动核对");
            ui.open(r);ui.top();UiDriver.Found f=ui.find(label);if(f==null)throw new UiDriver.Halt("找不到原开关，未操作");
            boolean before=entry.getBoolean("before"),after=entry.getBoolean("after");
            if(f.checked==before){store.finish(id,"restored","原始状态已确认");note("已经是原始状态");return;}
            if(f.checked!=after)throw new UiDriver.Halt("当前状态与记录不同，未覆盖");
            store.finish(id,"restoring","恢复前已核对当前状态");
            if(!ui.set(label,after,before))throw new UiDriver.Halt("恢复结果未能确认，请手动核对");
            store.finish(id,"restored","已恢复原值并回读确认");note("已恢复 · "+label);
        }catch(Exception e){store.finish(id,"restore_failed",e.getMessage());note("恢复未完成，请查看记录");}
    });}
    static void put(JSONObject j,String key,Object value){try{j.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
    private interface Work{void run()throws Exception;}
    private void start(Work work){busy=true;cancelled=false;showOverlay();worker.execute(()->{try{work.run();}catch(Exception e){note("未完成："+e.getMessage());}finally{busy=false;main.post(()->{hideOverlay();if(get()==this){Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab",resultTab);startActivity(i);}});}});}
    private void showOverlay(){main.post(()->{overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.HORIZONTAL);overlay.setPadding(18,8,10,8);overlay.setGravity(Gravity.CENTER_VERTICAL);overlay.setBackgroundColor(Color.rgb(20,55,46));overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setTextSize(13);overlayText.setText("准备操作…");overlay.addView(overlayText,new LinearLayout.LayoutParams(0,-2,1));Button stop=new Button(this);stop.setText("停止");stop.setOnClickListener(v->stop());overlay.addView(stop);WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,android.graphics.PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM;((WindowManager)getSystemService(WINDOW_SERVICE)).addView(overlay,p);});}
    private void hideOverlay(){if(overlay!=null){try{((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(Exception ignored){}overlay=null;overlayText=null;}}
}
