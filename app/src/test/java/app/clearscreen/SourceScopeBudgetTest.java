package app.clearscreen;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SourceScopeBudgetTest {
    @Test public void commonCountdownAndSpecificGlobalScopeAreNotStripped() throws Exception {
        String g="{name:'本机测试',id:771,version:2,apps:[],globalGroups:[{key:1,name:'开屏广告',matchAnyApp:false,apps:[{id:'example.host'},{id:'example.paused',enable:false}],actionMaximum:1,resetMatch:'app',actionCd:450,matchTime:9000,rules:[{matches:\"[vid='skip']\"}]}]}";
        CommunityRules.Preview p=CommunityRules.parse(g);assertEquals(1,p.rules.size());
        CustomSkipRule rule=CustomSkipRule.subscriptionRules(CommunityRules.stage(p,true).getString("rules")).get(0);
        assertEquals("example.host",rule.pkg);assertTrue(rule.scope.resetByApp);assertEquals(450,rule.scope.cooldown);assertEquals(1,rule.scope.maximum);
        assertTrue(CommunityRules.parse(g.replace("matchAnyApp:false","matchAnyApp:true")).rules.isEmpty());
        assertTrue(CommunityRules.parse(g.replace("resetMatch:'app'","resetMatch:'match'")).rules.isEmpty());
    }
    @Test public void timedPriorityFallsBackToDeclaredOrderAfterWindowEnds()throws Exception{
        CustomSkipRule a=new CustomSkipRule("example.app","","splash","","跳过","");
        CustomSkipRule b=new CustomSkipRule("example.app","","splash","","关闭广告","");
        a.fromSubscription=b.fromSubscription=true;
        a.scope=new RuleScope(new JSONObject().put("order",4).put("priorityTime",500));
        b.scope=new RuleScope(new JSONObject().put("order",-2));
        assertTrue(RuleScope.compare(a,b,499)<0);assertTrue(RuleScope.compare(a,b,500)>0);
    }

}
