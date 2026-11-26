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
        String g="{name:'本机测试',id:771,version:2,apps:[],globalGroups:[{key:1,name:'开屏广告',matchRoot:true,matchAnyApp:false,apps:[{id:'example.host'},{id:'example.paused',enable:false}],actionMaximum:1,resetMatch:'app',actionCd:450,matchTime:9000,rules:[{matches:\"[vid='skip']\"}]}]}";
        CommunityRules.Preview p=CommunityRules.parse(g);assertEquals(1,p.rules.size());
        CustomSkipRule rule=CustomSkipRule.subscriptionRules(CommunityRules.stage(p,true).getString("rules")).get(0);
        assertEquals("example.host",rule.pkg);assertTrue(rule.scope.resetByApp);assertEquals(450,rule.scope.cooldown);assertEquals(1,rule.scope.maximum);
        assertTrue(CommunityRules.parse(g.replace("matchAnyApp:false","matchAnyApp:true")).rules.isEmpty());
        assertTrue(CommunityRules.parse(g.replace("resetMatch:'app'","resetMatch:'match'")).rules.isEmpty());
    }
    @Test public void importPreservesDefaultEventScopeGlobalCategoryAndDependency() throws Exception {
        String input="{categories:[{key:1,name:'开屏广告',enable:true}],globalGroups:[{name:'开屏广告',matchAnyApp:false,apps:[{id:'example.host'}],rules:[{anyMatches:\"[vid='skip']\"}]}]}";
        CommunityRules.Preview missing=CommunityRules.parse(input);
        assertEquals(1,missing.rules.size());
        assertTrue(missing.rules.get(0).scope.eventScoped);
        String rooted=input.replace("matchAnyApp:false","matchRoot:true,matchAnyApp:false");
        assertEquals(1,CommunityRules.parse(rooted).rules.size());
        assertTrue(CommunityRules.parse(rooted.replace("matchRoot:true","enable:false,matchRoot:true")).rules.isEmpty());
        assertTrue(CommunityRules.parse(rooted.replace("anyMatches:","preKeys:[1],anyMatches:")).omitted.toString().contains("preKeys"));
    }

    @Test public void literalRegexOnlyMatchesItsEntireLabel() {
        RuleSelector exact=new RuleSelector("gkd:[text~='^跳过广告$']");
        assertTrue(exact.matches(RuleWorkbenchTest.node("exit","跳过广告",true)));
        assertFalse(exact.matches(RuleWorkbenchTest.node("other","跳过广告教程",true)));
        assertThrows(IllegalArgumentException.class,()->new RuleSelector("gkd:[text~='.*广告']"));
    }

    @Test public void defaultEventQueryDoesNotSelectAnIdenticalSiblingOutsideTheSource() throws Exception {
        String body="{apps:[{id:'com.example.player',groups:[{name:'开屏广告',rules:[{matches:\"[vid='exit']\"}]}]}]}";
        CustomSkipRule rule=CommunityRules.parse(body).rules.get(0);
        android.view.accessibility.AccessibilityNodeInfo root=RuleWorkbenchTest.node("root","",false);
        android.view.accessibility.AccessibilityNodeInfo part=RuleWorkbenchTest.node("ad","",false);
        android.view.accessibility.AccessibilityNodeInfo inside=RuleWorkbenchTest.node("exit","关闭",true);
        android.view.accessibility.AccessibilityNodeInfo outside=RuleWorkbenchTest.node("exit","关闭",true);
        org.robolectric.Shadows.shadowOf(root).addChild(part);
        org.robolectric.Shadows.shadowOf(part).addChild(inside);
        org.robolectric.Shadows.shadowOf(root).addChild(outside);
        RuleScope.setEventSource(android.view.accessibility.AccessibilityNodeInfo.obtain(part));
        try {assertTrue(rule.matchesButton(inside));assertFalse(rule.matchesButton(outside));}
        finally {RuleScope.clearEvent();}
        assertTrue(rule.matchesButton(outside));
    }

    @Test public void subscriptionUpgradeAndRollbackKeepOldPerRuleChoices() throws Exception {
        String source="{apps:[{id:'example.host',groups:[{name:'开屏广告',rules:[{matches:\"[vid='skip']\"}]}]}]}";
        CommunityRules.Preview preview=CommunityRules.parse(source);
        JSONObject current=CommunityRules.stage(preview,true);
        CustomSkipRule rule=preview.rules.get(0);
        String newKey=CommunityRules.key(rule);
        rule.scope=null; // Schema emitted by the earlier converter.
        JSONObject old=CommunityRules.stage(preview,true);
        old.put("disabled",new JSONArray().put(CommunityRules.key(rule)));
        JSONObject updated=CommunityRules.update(old,current);
        assertTrue(CommunityRules.disabled(updated).contains(newKey));
        updated.put("disabled",new JSONArray()); // User explicitly re-enabled the updated row.
        assertTrue(CommunityRules.disabled(CommunityRules.restore(updated)).isEmpty());
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
