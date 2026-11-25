package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class RuleWorkbenchTest {
    static final String PKG="com.example.player";
    static AccessibilityNodeInfo node(String id,String text,boolean click){
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName(PKG);n.setText(text);n.setClassName("android.view.View");
        n.setViewIdResourceName(PKG+":id/"+id);n.setVisibleToUser(true);n.setEnabled(true);n.setClickable(click);n.setBoundsInScreen(new Rect(300,100,450,170));return n;
    }
    @Test public void sameTargetOnDeviceAndRedactedReplay() throws Exception {
        AccessibilityNodeInfo root=node("ad_panel",null,false),skip=node("skip","跳过",true),secret=node("content","私人消息",false);
        shadowOf(root).addChild(skip);shadowOf(root).addChild(secret);String exp="gkd:[vid='ad_panel'] > @View[vid='skip'][clickable=true]";
        assertTrue(new RuleSelector(exp).matches(skip));assertFalse(new RuleSelector(exp).matches(secret));
        RuleSample s=RuleSample.capture(PKG,"Page",root.getWindowId(),List.of(root,skip,secret));assertNotNull(s);
        assertTrue(s.replay(exp).contains("唯一命中"));assertTrue(s.replay("gkd:[text='错字']").startsWith("未命中"));
        assertEquals(s.replay(exp),RuleSample.read(s.export().toString()).replay(exp));assertFalse(s.export().toString().contains("私人消息"));assertThrows(IllegalArgumentException.class,()->new RuleSelector("gkd:[unknown=true]"));
        secret.setPassword(true);assertNull(RuleSample.capture(PKG,"Page",root.getWindowId(),List.of(root,skip,secret)));
    }
    @Test public void explicitSkipTextInsideSearchSuggestionIsNotAnAd(){
        AccessibilityNodeInfo root=node("page",null,false),list=node("recycler_view",null,false),row=node("item_view",null,true),text=node("keyword","跳过广告",false);
        root.setBoundsInScreen(new Rect(0,0,1080,2400));shadowOf(root).addChild(list);shadowOf(list).addChild(row);shadowOf(row).addChild(text);
        assertNull(SplashSkipper.match(root,SplashRules.EMPTY,"com.example.search.SuperSearchActivity",600));
    }
    @Test public void subscriptionNeverDropsUnknownConstraintsOrOverridesLocalRules() throws Exception {
        String doc="{\"name\":\"样本\",\"version\":3,\"apps\":[{\"id\":\""+PKG+"\",\"groups\":[{\"name\":\"开屏广告\",\"rules\":[{\"matches\":\"[vid='skip']\"},{\"matches\":\"[vid='close']\",\"excludeMatches\":\"[text='设置']\"}]}]}]}";
        CommunityRules.Preview p=CommunityRules.parse(doc);assertEquals(2,p.rules.size());assertEquals(0,p.omitted.size());
        JSONObject src=CommunityRules.stage(p,true);JSONObject all=new JSONObject().put("source",src);
        assertEquals(2,CommunityRules.active(all,p.rules).size());src.put("enabled",false);assertTrue(CommunityRules.active(all,Collections.emptyList()).isEmpty());
    }
}
