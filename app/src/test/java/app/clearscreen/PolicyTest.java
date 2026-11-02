package app.clearscreen;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.nio.file.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PolicyTest {
    @Test public void parityWithPinnedPythonPolicy()throws Exception{
        Store s=new Store(RuntimeEnvironment.getApplication());Policy p=s.policy();
        JSONArray cases=new JSONArray(new String(Files.readAllBytes(Paths.get(System.getProperty("clearscreen.artifacts"),"policy-cases.json")),java.nio.charset.StandardCharsets.UTF_8));
        for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i);Policy.Decision d=p.classify(c.getString("name"),c.getString("id"),c.getString("package"),c.getString("app"));String why=c.toString();assertEquals(why,c.getBoolean("candidate"),d.candidate);assertEquals(why,c.getBoolean("excluded"),d.excluded);assertEquals(why,c.getBoolean("uncertain"),d.uncertain);assertEquals(why,c.getBoolean("protected"),d.protectedValue);}
    }
    @Test public void inverseProtectionSwitchesAreEnabled(){Policy p=new Store(RuntimeEnvironment.getApplication()).policy();assertTrue(p.desired("限制广告跟踪"));assertTrue(p.desired("关闭个性化推荐"));assertFalse(p.desired("个性化推荐"));assertTrue(p.protectedTitle("机 位 推 荐"));}
    @Test public void importantNotificationNamesStayProtectedEvenWithAdSdkIds(){Policy p=new Store(RuntimeEnvironment.getApplication()).policy();assertTrue(p.criticalNotification("天气预警"));assertTrue(p.criticalNotification("支付与优惠"));assertTrue(p.protectedSetting("应用更新通知"));assertFalse(p.criticalNotification("活动优惠"));}
    @Test public void rulesAreBoundedAndSourceTracked(){Store s=new Store(RuntimeEnvironment.getApplication());assertEquals(75,s.allRules().size());java.util.Set<String> ids=new java.util.HashSet<>();for(Rule r:s.allRules()){assertTrue(ids.add(r.id));assertFalse(r.pkg.isEmpty());assertFalse(r.targets.isEmpty());assertTrue(r.routes.size()<=6);for(Route route:r.routes)assertTrue(route.path.size()<=8);assertTrue(r.source.startsWith("recipes/"));for(String t:r.targets)assertFalse(s.policy().protectedSetting(t));}}
}
