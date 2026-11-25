package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import org.json.*;
import org.junit.Test;import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;import org.robolectric.RuntimeEnvironment;import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RuleUpgradeTest {
    private final String pkg="example.reader";
    private final Rect screen=new Rect(0,0,1080,2400);
    private AccessibilityNodeInfo n(AccessibilityNodeInfo p,String cls,String id,String text,boolean click,Rect b){
        AccessibilityNodeInfo n=PlayerAdTest.node(p,pkg,cls,text,click,b);if(id!=null)n.setViewIdResourceName(pkg+":id/"+id);return n;
    }
    @Test public void gkdWindowConditionsSurviveStorageAndActuallySuppressActions() throws Exception{
        String source="{// 注释\n name:'示例', version:2, apps:[{id:'example.reader',groups:[{name:'开屏广告',activityIds:['.Home'],excludeActivityIds:['.Home.Settings'],matchDelay:300,matchTime:700,rules:[{matches:[\"[vid='mark']\",\"[vid='exit']\"],anyMatches:[\"[text='广告']\",\"[desc='广告']\"],excludeMatches:[\"[vid='editor']\"]},],},],},],}";
        CommunityRules.Preview p=CommunityRules.parse(source);assertEquals(1,p.rules.size());
        JSONObject stored=CommunityRules.stage(p,true);List<CustomSkipRule> rules=CommunityRules.active(new JSONObject().put("site",stored),Collections.emptyList());
        CustomSkipRule rule=rules.get(0);assertNotNull(rule.scope);
        AccessibilityNodeInfo root=n(null,"android.widget.FrameLayout",null,"",false,screen);
        AccessibilityNodeInfo mark=n(root,"android.widget.TextView","mark","广告",false,new Rect(10,110,90,170));
        AccessibilityNodeInfo exit=n(root,"android.widget.TextView","exit","略过",true,new Rect(840,100,1020,180));
        RuleScope.Window window=new RuleScope.Window(root,UiDriver.walkComplete(root));
        assertFalse(rule.scope.allows(pkg+".Home",299,window));assertTrue(rule.scope.allows(pkg+".HomeActivity",300,window));
        assertFalse(rule.scope.allows(pkg+".Home.SettingsDialog",320,window));assertFalse(rule.scope.allows(pkg+".Home",1000,window));
        assertTrue(rule.matchesButton(exit));mark.setVisibleToUser(false);
        assertFalse(rule.scope.allows(pkg+".Home",500,new RuleScope.Window(root,UiDriver.walkComplete(root))));
        mark.setVisibleToUser(true);n(root,"android.view.View","editor","",false,new Rect(0,500,200,600));
        assertFalse(rule.scope.allows(pkg+".Home",500,new RuleScope.Window(root,UiDriver.walkComplete(root))));
    }
    @Test public void compareIncludesARealCounterexampleAndUnknownTextNeverMatches(){
        AccessibilityNodeInfo r=n(null,"android.widget.FrameLayout",null,"",false,screen);
        n(r,"android.widget.TextView","exit","跳过广告",true,new Rect(850,100,1020,190));
        RuleSample good=RuleSample.capture(pkg,"Home",r.getWindowId(),UiDriver.walkComplete(r));
        AccessibilityNodeInfo safe=n(null,"android.widget.FrameLayout",null,"",false,screen);n(safe,"android.widget.TextView","normal","私人文本",true,new Rect(850,100,1020,190));
        RuleSample bad=RuleSample.capture(pkg,"Home",safe.getWindowId(),UiDriver.walkComplete(safe));
        assertTrue(good.compare(bad,"gkd:[vid='exit'][index=0][depth=1][clickable!=false]").contains("本次对照通过"));
        assertTrue(good.compare(bad,"gkd:[clickable=true]").contains("对照未通过"));
    }
    @Test public void individualSwitchAndPreviousVersionAreIndependentOfTheSourceSwitch() throws Exception{
        CommunityRules.Preview a=new CommunityRules.Preview();a.name="订阅";a.version=1;
        a.rules.add(new CustomSkipRule(pkg,"","splash","","关闭广告",""));JSONObject old=CommunityRules.stage(a,true);
        old.put("disabled",new JSONArray().put(CommunityRules.key(a.rules.get(0))));a.version=2;a.rules.add(new CustomSkipRule(pkg,"","splash","","跳过广告",""));
        JSONObject current=CommunityRules.update(old,CommunityRules.stage(a,true));assertEquals(1,current.getJSONObject("previous").getInt("version"));
        assertEquals(1,CommunityRules.active(new JSONObject().put("x",current),Collections.emptyList()).size());
    }
    @Test public void countingAnAppRequiresOptInAndClearingDetailsForgetsItsStatistics(){
        Store s=new Store(RuntimeEnvironment.getApplication());s.setKeepSkipDetails(false);s.recordSplash(pkg,true);assertEquals(0,s.confirmedFor(pkg,"splash"));
        s.setKeepSkipDetails(true);s.recordPopup(pkg,false);s.recordPopup(pkg,true);assertEquals(1,s.confirmedFor(pkg,"popup"));assertTrue(s.lastAttemptFor(pkg)>0);
        s.setKeepSkipDetails(false);assertEquals(0,s.confirmedFor(pkg,"popup"));assertEquals(0,s.lastAttemptFor(pkg));
    }    @Test public void importGkdSnapshotDropsPageContentsPreservesChildIndexAndSupportsLocalLibrary()throws Exception{
        String gkd="{appId:'example.reader',activityId:'example.reader.Main',nodes:["
            +"{id:10,pid:-1,attr:{left:0,top:0,right:1080,bottom:2400,name:'FrameLayout',childCount:2}},"
            +"{id:12,pid:10,index:1,attr:{left:850,top:90,right:1050,bottom:180,id:'example.reader:id/skip',name:'TextView',text:'跳过广告',clickable:true}},"
            +"{id:11,pid:10,index:0,attr:{left:0,top:500,right:300,bottom:600,name:'TextView',text:'不可留存的个人正文'}}]}";
        java.io.ByteArrayOutputStream zipBytes=new java.io.ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(zipBytes)){
            zip.putNextEntry(new java.util.zip.ZipEntry("snapshot.json"));zip.write(gkd.getBytes("UTF-8"));zip.closeEntry();
        }
        RuleSample sample=SnapshotImport.read(new java.io.ByteArrayInputStream(zipBytes.toByteArray()));
        assertEquals(1,sample.evaluate("gkd:[index=1][vid='skip']").count);
        assertFalse(sample.export().toString().contains("不可留存的个人正文"));
        java.io.File f=SampleLibrary.save(RuntimeEnvironment.getApplication(),sample);
        try{assertEquals(1,SampleLibrary.read(f).evaluate("gkd:[text='跳过广告']").count);}finally{assertTrue(f.delete());}
        assertThrows(JSONException.class,()->SnapshotImport.json(gkd.replace("name:'FrameLayout'","editable:true,name:'FrameLayout'")));
    }

}
