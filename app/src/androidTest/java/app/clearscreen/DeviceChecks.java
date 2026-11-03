package app.clearscreen;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import org.json.*;
import java.util.*;

/** Real Android execution against a separate disposable fixture, never OEM evidence. */
public class DeviceChecks extends Instrumentation {
    int assertions=0;
    String suite="clean";
    void require(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)suite=args.getString("suite","clean");start();}
    void await(CleanerService s)throws Exception{long deadline=System.currentTimeMillis()+90000;while(s.busy&&System.currentTimeMillis()<deadline)Thread.sleep(100);require(!s.busy,"task timed out");Thread.sleep(800);}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(suite.equals("network")){NetworkChecks.run(this);result.putString("stream","PASS: "+assertions+" network assertions on Android "+Build.VERSION.RELEASE+"\n");finish(RESULT_OK,result);return;}
        android.app.UiAutomation automation=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        // Instrumentation restarts the target process: reconnect its service only in this disposable emulator.
        shell(automation,"settings put secure enabled_accessibility_services null");
        shell(automation,"settings put secure enabled_accessibility_services app.clearscreen/.CleanerService");
        shell(automation,"settings put secure accessibility_enabled 1");
        shell(automation,"cmd notification disallow_listener app.clearscreen/.ChannelObserver");
        shell(automation,"cmd notification allow_listener app.clearscreen/.ChannelObserver");
        shell(automation,"pm grant app.clearscreen.testfixture android.permission.POST_NOTIFICATIONS");
        long connectDeadline=System.currentTimeMillis()+15000;while(CleanerService.get()==null&&System.currentTimeMillis()<connectDeadline)Thread.sleep(200);
        CleanerService service=CleanerService.get();require(service!=null,"accessibility service connected");
        Store store=new Store(getTargetContext());store.save("scan",new JSONArray());store.save("journal",new JSONArray());store.save("channels",new JSONArray());
        Rule rule=new Rule(new JSONObject("{\"id\":\"test-only\",\"title\":\"隔离测试页面\",\"package\":\"app.clearscreen.testfixture\",\"activity\":\".FixtureActivity\",\"targets\":[\"个性化推荐\",\"限制广告跟踪\",\"应用更新通知\"]}"));
        UiDriver ui=new UiDriver(service,()->false);ui.open(rule);
        require(ui.find("个性化推荐").checked,"initial recommendation on");require(!ui.find("限制广告跟踪").checked,"initial protection off");
        service.clean(Collections.singletonList(rule));await(service);
        JSONArray entries=store.read("journal");require(entries.length()==2,"only two ad-related changes recorded");
        require(entries.getJSONObject(0).getString("status").equals("changed"),"verified journal entry");
        ui.open(rule);require(!ui.find("个性化推荐").checked,"recommendation disabled after fresh launch");require(ui.find("限制广告跟踪").checked,"inverse protection enabled");require(ui.find("应用更新通知").checked,"service notification untouched");
        service.clean(Collections.singletonList(rule));await(service);require(store.read("journal").length()==2,"idempotent second run");
        service.restore(entries.getJSONObject(0));await(service);ui.open(rule);require(ui.find("个性化推荐").checked,"restore original on");
        service.restore(entries.getJSONObject(1));await(service);ui.open(rule);require(!ui.find("限制广告跟踪").checked,"restore original off");require(ui.find("应用更新通知").checked,"service survives restore");
        JSONObject confirmationJson=rule.json();confirmationJson.remove("routes");confirmationJson.put("activity",".ConfirmActivity");confirmationJson.put("targets",new JSONArray().put("个性化推荐"));Rule confirmation=new Rule(confirmationJson);
        service.clean(Collections.singletonList(confirmation));await(service);JSONArray confirmed=store.read("journal");require(confirmed.length()==3,"confirmation change recorded");require(confirmed.getJSONObject(2).getString("status").equals("changed"),"known disable dialog confirmed and read back");
        ui.open(confirmation);require(!ui.find("个性化推荐").checked,"confirmed setting persisted");service.restore(confirmed.getJSONObject(2));await(service);ui.open(confirmation);require(ui.find("个性化推荐").checked,"confirmed setting restored");
        ui.open(rule);require(ui.tap(Collections.singletonList("发送测试通知")),"post fixture notifications through visible button");
        long channelDeadline=System.currentTimeMillis()+10000;while(store.read("channels").length()==0&&System.currentTimeMillis()<channelDeadline)Thread.sleep(200);
        JSONArray channels=store.read("channels");require(channels.length()==1,"marketing detected, weather warning excluded");Rule channel=new Rule(channels.getJSONObject(0));require(channel.channelName.equals("活动优惠"),"correct marketing channel");
        service.clean(Collections.singletonList(channel));await(service);JSONArray withChannel=store.read("journal");require(withChannel.length()==4,"channel change recorded");require(withChannel.getJSONObject(3).getString("status").equals("changed"),"channel change verified");
        ui.open(channel);require(!ui.find("允许通知").checked,"AOSP channel disabled");service.restore(withChannel.getJSONObject(3));await(service);ui.open(channel);require(ui.find("允许通知").checked,"AOSP channel restored");
        require(!store.export().contains("测试通知正文不得进入净屏记录"),"notification body not persisted in export");
        Rule alternate=new Rule(new JSONObject("{\"id\":\"route-fixture\",\"title\":\"备用入口与同义名称\",\"package\":\"app.clearscreen.testfixture\",\"targets\":[\"个性化广告推荐\",\"限制广告跟踪\"],\"aliases\":{\"个性化广告推荐\":[\"个性化推荐\"]},\"routes\":[{\"activity\":\".PrivateSettingsActivity\"},{\"activity\":\".FixtureActivity\",\"anchors\":[\"不存在的页面标题\"]},{\"activity\":\".RouteActivity\",\"path\":[[\"广告设置\"]]}]}"));
        ui.open(alternate);require(ui.attempts().length()==3,"private direct entry and wrong page fallback to menu");require(ui.find("个性化广告推荐").checked,"route selection never toggles setting");
        service.clean(Collections.singletonList(alternate));await(service);JSONArray routed=store.read("journal");require(routed.length()==6,"alias and inverse changes recorded through fallback");
        JSONObject pinned=routed.getJSONObject(4);Rule pinnedRule=new Rule(pinned.getJSONObject("rule"));require(pinnedRule.routes.size()==1&&pinnedRule.routes.get(0).activity.equals(".RouteActivity"),"restoration pins successful menu entry");require(pinned.getString("label").equals("个性化推荐"),"actual alias label retained");
        require(store.read("scan").getJSONObject(0).getJSONArray("attempts").length()==3,"diagnostic retains bounded route attempts");
        service.restoreAll(Arrays.asList(routed.getJSONObject(5),pinned));await(service);ui.open(alternate);require(ui.find("个性化广告推荐").checked,"batch restore original recommendation");require(!ui.find("限制广告跟踪").checked,"batch restore inverse original");require(ui.find("应用更新通知").checked,"batch restore preserves service notification");
        service.clean(Arrays.asList(alternate,rule));service.stop();await(service);require(store.read("journal").length()==6,"immediate stop performs no writes");
        JSONArray stopped=store.read("scan");require(stopped.length()==5,"stopped run retains all untouched targets");boolean queued=false;for(int i=0;i<stopped.length();i++){JSONObject item=stopped.getJSONObject(i);if(item.getJSONObject("rule").optString("id").equals("test-only")&&item.optString("status").equals("skipped"))queued=true;}require(queued,"unvisited rule remains visible for retry");
        boolean halted=false;try{new UiDriver(service,()->true).current(rule);}catch(UiDriver.Halt expected){halted=true;}require(halted,"cancellation prevents access");
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
        halted=false;try{ui.current(rule);}catch(UiDriver.Halt expected){halted=true;}require(halted,"foreign foreground stops");
        Rule delayed=new Rule(new JSONObject("{\"id\":\"delayed-fixture\",\"title\":\"延迟加载\",\"package\":\"app.clearscreen.testfixture\",\"activity\":\".DelayedActivity\",\"targets\":[\"个性化推荐\"]}"));
        long began=SystemClock.elapsedRealtime();service.clean(Collections.singletonList(delayed));await(service);require(SystemClock.elapsedRealtime()-began>=2300,"waited for delayed controls");require(store.read("scan").getJSONObject(0).optString("status").equals("changed"),"delayed setting cleaned in one run");
        splashChecks(store,service);
        result.putString("stream","PASS: "+assertions+" real-device assertions; Android "+Build.VERSION.RELEASE+"; isolated fixture only\n");finish(RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL after "+assertions+" assertions: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
    static final int RESULT_OK=-1;
    void splashChecks(Store store,CleanerService service)throws Exception{
        store.setSplashEnabled(true);store.setSplashExcluded(Collections.emptySet());long count=store.splashCount();
        for(String mode:new String[]{"plain","consent","editable","ad","sdk"}){
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(900);
            getTargetContext().startActivity(new Intent().setClassName("app.clearscreen.testfixture","app.clearscreen.testfixture.SplashActivity").putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            Thread.sleep(2400);StringBuilder texts=new StringBuilder();for(android.view.accessibility.AccessibilityNodeInfo n:UiDriver.walk(service.getRootInActiveWindow()))texts.append(UiDriver.text(n));
            require((mode.equals("ad")||mode.equals("sdk"))==texts.toString().contains("已进入测试内容"),"splash mode "+mode+" guarded correctly");
        }
        require(store.splashCount()==count+2,"only confirmed ad skips counted");
        store.setSplashExcluded(Collections.singleton("app.clearscreen.testfixture"));getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(900);
        getTargetContext().startActivity(new Intent().setClassName("app.clearscreen.testfixture","app.clearscreen.testfixture.SplashActivity").putExtra("mode","ad").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(1800);
        require(store.splashCount()==count+2,"excluded application not clicked");store.setSplashEnabled(false);store.setSplashExcluded(Collections.emptySet());
    }
    void shell(android.app.UiAutomation a,String command)throws Exception{try(ParcelFileDescriptor fd=a.executeShellCommand(command);java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
}
