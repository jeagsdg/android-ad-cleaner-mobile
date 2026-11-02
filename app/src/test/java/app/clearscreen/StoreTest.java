package app.clearscreen;

import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class StoreTest {
    @Test public void writesOriginalStateBeforeActionAndSurvivesNewStore()throws Exception{Store s=new Store(RuntimeEnvironment.getApplication());Rule r=s.allRules().get(0);String id=s.prepare(r,r.targets.get(0),true,false);Store reopened=new Store(RuntimeEnvironment.getApplication());JSONObject entry=reopened.read("journal").getJSONObject(0);assertTrue(entry.getBoolean("before"));assertFalse(entry.getBoolean("after"));assertEquals("pending",entry.getString("status"));s.finish(id,"changed","verified");assertEquals("changed",reopened.read("journal").getJSONObject(0).getString("status"));s.finish(id,"restored","verified");assertEquals("restored",reopened.read("journal").getJSONObject(0).getString("status"));assertTrue(reopened.read("journal").getJSONObject(0).getBoolean("before"));}
    @Test public void interruptionDoesNotBecomeSuccess(){Store s=new Store(RuntimeEnvironment.getApplication());Rule r=s.allRules().get(0);String id=s.prepare(r,r.targets.get(0),true,false);s.finish(id,"unknown","stopped");assertEquals("unknown",new Store(RuntimeEnvironment.getApplication()).read("journal").optJSONObject(0).optString("status"));}
    @Test public void exportsNoNotificationBodies(){Store s=new Store(RuntimeEnvironment.getApplication());s.save("channels",new JSONArray().put("not part of export"));assertFalse(s.export().contains("not part of export"));}
}
