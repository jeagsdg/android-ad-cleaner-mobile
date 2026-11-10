package app.clearscreen;

import android.os.Build;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RouteTest {
    @Test public void legacyLedgerStillParsesAndPinsOriginalEntry()throws Exception{
        Rule r=new Rule(new JSONObject("{\"id\":\"old\",\"package\":\"com.example.old\",\"activity\":\".Settings\",\"path\":[[\"隐私\"]],\"targets\":[\"个性化广告\"]}"));
        assertEquals(1,r.routes.size());assertEquals("隐私",r.path.get(0).get(0));
        Rule roundTrip=new Rule(r.json());assertEquals(".Settings",roundTrip.routes.get(0).activity);assertEquals(r.path,roundTrip.path);
    }
    @Test public void pinnedRouteRetainsExplicitTransitionAndOnlyOneEntry()throws Exception{
        Rule r=new Rule(new JSONObject("{\"id\":\"cross\",\"package\":\"com.android.settings\",\"targets\":[\"个性化广告\"],\"routes\":[{\"path\":[{\"labels\":[\"广告与隐私\"],\"package\":\"com.example.id\"}]},{\"package\":\"com.example.id\",\"activity\":\".Settings\"}]}"));
        Rule pinned=new Rule(r.pin(r.routes.get(0)).json());assertEquals("com.example.id",pinned.pkg);assertEquals(1,pinned.routes.size());
        assertEquals("com.android.settings",pinned.routes.get(0).pkg);assertEquals("com.example.id",pinned.routes.get(0).destinations.get(0));
    }
    @Test public void arbitraryIntentUriIsRejected()throws Exception{
        try{new Rule(new JSONObject("{\"package\":\"com.example.app\",\"uri\":\"https://example.com\"}"));fail();}catch(IllegalArgumentException expected){}
        Rule allowed=new Rule(new JSONObject("{\"package\":\"com.heytap.quicksearchbox\",\"uri\":\"gs://search/setting\"}"));assertEquals("gs://search/setting",allowed.routes.get(0).uri);
    }
    @Test public void targetAliasesSurviveLedgerSerialization()throws Exception{
        Rule r=new Rule(new JSONObject("{\"package\":\"com.example\",\"targets\":[\"猜你想搜\"],\"aliases\":{\"猜你想搜\":[\"显示猜你想搜模块\"]}}"));
        assertEquals(2,new Rule(r.json()).labels("猜你想搜").size());assertEquals(1,r.labels("其他开关").size());
    }
    @Test public void modernHonorRouteShowsReadableMenuAndRetainsOldPackageGate(){
        Store store=new Store(RuntimeEnvironment.getApplication());
        Rule manager=store.allRules().stream().filter(rule->rule.id.equals("h-manager")).findFirst().orElseThrow();
        assertEquals("com.hihonor.devicemanager",manager.routes.get(0).foreground);
        assertEquals("com.hihonor.systemmanager",manager.routes.get(0).finalPackage());
        assertTrue(manager.pathDescription().contains("右上角设置"));
        assertFalse(manager.pathDescription().contains("@id:"));
        assertEquals("com.hihonor.devicemanager",new Rule(manager.json()).routes.get(2).skipWhenPackagePresent);
    }
    @Test public void k70ProHasExactMarketEntryAndKeepsMenuFallbacks(){
        Store store=new Store(RuntimeEnvironment.getApplication());
        Rule market=store.allRules().stream().filter(rule->rule.id.equals("mi-store")).findFirst().orElseThrow();
        Route direct=market.routes.get(0);
        assertEquals(".ui.MarketPreferenceActivity",direct.activity);
        assertEquals("com.xiaomi.market",direct.finalPackage());
        assertEquals(java.util.List.of(java.util.List.of("隐私设置"),java.util.List.of("个性化服务")),direct.path);
        assertTrue(market.routes.stream().anyMatch(route->route.activity.isEmpty()&&route.path.get(0).contains("我的")));
    }
    @Test public void interruptingAccessibilityFeedbackDoesNotStopTheCleanup(){
        CleanerService service=Robolectric.buildService(CleanerService.class).create().get();
        service.busy=true;
        String progress=service.progress;
        service.onInterrupt();
        assertEquals(progress,service.progress);
        service.stop();
        assertEquals("正在停止…",service.progress);
    }
    @Test public void fivePriorityModelsAreIdentifiedWithoutInventedOsVersion()throws Exception{
        Store s=new Store(RuntimeEnvironment.getApplication());String[][] devices={{"oppo","PKC110","OPPO Find X8 Pro"},{"HONOR","BKQ-AN00","荣耀 Magic8"},{"HONOR","PPG-AN00","荣耀 GT Pro"},{"Xiaomi","M2007J1SC","小米 10 至尊纪念版"},{"vivo","V2338A","iQOO Neo9"}};
        for(String[] device:devices){ReflectionHelpers.setStaticField(Build.class,"MANUFACTURER",device[0]);ReflectionHelpers.setStaticField(Build.class,"BRAND",device[0]);ReflectionHelpers.setStaticField(Build.class,"MODEL",device[1]);assertEquals(device[2],s.deviceName());assertFalse(s.deviceInfo().getBoolean("oemVerified"));assertTrue(s.deviceDescription().contains("Android "+Build.VERSION.RELEASE));}
    }
    @Test public void retrySelectsFailedGroupsAndNotSuccessfulOnes()throws Exception{
        Store s=new Store(RuntimeEnvironment.getApplication());JSONArray channels=new JSONArray();
        for(String id:new String[]{"retry-promo","done-promo"})channels.put(new JSONObject().put("id",id).put("package",RuntimeEnvironment.getApplication().getPackageName()).put("channelId",id).put("channelName","活动优惠").put("channelApp","测试应用"));
        s.save("channels",channels);JSONArray scan=new JSONArray();scan.put(new JSONObject().put("rule",channels.getJSONObject(0)).put("status","skipped"));scan.put(new JSONObject().put("rule",channels.getJSONObject(1)).put("status","changed"));s.save("scan",scan);
        assertEquals(1,s.retryRules().size());assertEquals("retry-promo",s.retryRules().get(0).id);
    }
    @Test public void stoppingMidGroupRetainsUntouchedTargetsForRetry()throws Exception{
        Rule first=new Rule(new JSONObject("{\"id\":\"one\",\"package\":\"com.example\",\"targets\":[\"已处理\",\"未处理\"]}"));Rule second=new Rule(new JSONObject("{\"id\":\"two\",\"package\":\"com.example\",\"targets\":[\"下一组\"]}"));
        JSONArray results=new JSONArray().put(new JSONObject().put("rule",first.json()).put("label","已处理").put("status","changed"));
        CleanerService.recordUnrun(java.util.Arrays.asList(first,second),results);assertEquals(3,results.length());assertEquals("changed",results.getJSONObject(0).getString("status"));assertEquals("未处理",results.getJSONObject(1).getString("label"));assertEquals("queued",results.getJSONObject(1).getString("status"));assertEquals("queued",results.getJSONObject(2).getString("status"));
        CleanerService.recordUnrun(java.util.Arrays.asList(first,second),results);assertEquals(3,results.length());
    }
}
