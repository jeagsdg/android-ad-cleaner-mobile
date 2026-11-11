package app.clearscreen;

import android.content.Context;
import android.content.pm.PackageInfo;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowBuild;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class StoreTest {
    @Test public void writesOriginalStateBeforeActionAndSurvivesNewStore()throws Exception{Store s=new Store(RuntimeEnvironment.getApplication());Rule r=s.allRules().get(0);String id=s.prepare(r,r.targets.get(0),true,false);Store reopened=new Store(RuntimeEnvironment.getApplication());JSONObject entry=reopened.read("journal").getJSONObject(0);assertTrue(entry.getBoolean("before"));assertFalse(entry.getBoolean("after"));assertEquals("pending",entry.getString("status"));s.finish(id,"changed","verified");assertEquals("changed",reopened.read("journal").getJSONObject(0).getString("status"));s.finish(id,"restored","verified");assertEquals("restored",reopened.read("journal").getJSONObject(0).getString("status"));assertTrue(reopened.read("journal").getJSONObject(0).getBoolean("before"));}
    @Test public void interruptionDoesNotBecomeSuccess(){Store s=new Store(RuntimeEnvironment.getApplication());Rule r=s.allRules().get(0);String id=s.prepare(r,r.targets.get(0),true,false);s.finish(id,"unknown","stopped");assertEquals("unknown",new Store(RuntimeEnvironment.getApplication()).read("journal").optJSONObject(0).optString("status"));}
    @Test public void exportsNoNotificationBodies(){Store s=new Store(RuntimeEnvironment.getApplication());s.save("channels",new JSONArray().put("not part of export"));assertFalse(s.export().contains("not part of export"));}
    @Test @Config(sdk={24,25}) public void oldAndroidKeepsChannelDataWithoutPlanningOrRestoringIt() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        ShadowBuild.setManufacturer("Xiaomi");
        PackageInfo installed = new PackageInfo();
        installed.packageName = "com.android.settings";
        shadowOf(app.getPackageManager()).installPackage(installed);
        Store store = new Store(app);
        JSONObject channel = new JSONObject().put("id", "cached-promo").put("package", app.getPackageName())
                .put("channelId", "promo").put("channelName", "活动优惠").put("channelApp", "测试应用");
        store.save("channels", new JSONArray().put(channel));

        assertFalse(store.plan().stream().anyMatch(Rule::notification));
        Rule setting = store.plan().stream().filter(rule -> rule.id.equals("mi-system")).findFirst().get();
        String settingRecord = store.prepare(setting, setting.targets.get(0), true, false);
        store.prepare(new Rule(channel), "允许通知", true, false);
        store.save("scan", new JSONArray()
                .put(new JSONObject().put("rule", channel).put("status", "queued"))
                .put(new JSONObject().put("rule", setting.json()).put("status", "queued")));
        assertEquals(1, store.resumeRules().size());
        assertEquals(setting.id, store.resumeRules().get(0).id);
        assertEquals(1, store.restorable().size());
        assertEquals(settingRecord, store.restorable().get(0).getString("id"));
        assertEquals(1, store.read("channels").length());
        assertEquals(2, store.read("scan").length());
        assertEquals(2, store.read("journal").length());
    }
    @Test public void cachedChannelWithoutAnIdIsNotPlannedAsAnAppSetting() throws Exception {
        Store store = new Store(RuntimeEnvironment.getApplication());
        JSONObject channel = new JSONObject().put("id", "missing-id-promo")
                .put("package", RuntimeEnvironment.getApplication().getPackageName())
                .put("channelName", "活动优惠").put("channelApp", "测试应用")
                .put("targets", new JSONArray().put("允许通知"));
        store.save("channels", new JSONArray().put(channel));
        assertFalse(store.plan().stream().anyMatch(rule -> rule.id.equals("missing-id-promo")));
    }
    @Test public void sameLabelFromDifferentInstalledVariantsRemainsRestorable()throws Exception{
        Store store=new Store(RuntimeEnvironment.getApplication());
        Rule rule=new Rule(new JSONObject("{\"id\":\"test\",\"package\":\"com.example.one\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"package\":\"com.example.one\"},{\"package\":\"com.example.two\"}]}"));
        String first=store.prepare(rule.pin(rule.routes.get(0)),"个性化推荐",true,false);
        String second=store.prepare(rule.pin(rule.routes.get(1)),"个性化推荐",true,false);
        assertEquals(2,store.restorable().size());
        assertEquals(second,store.restorable().get(0).getString("id"));
        store.finish(second,"restored","verified");
        assertEquals(1,store.restorable().size());
        assertEquals(first,store.restorable().get(0).getString("id"));
    }
}
