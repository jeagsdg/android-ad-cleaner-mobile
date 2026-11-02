package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UiDriverTest {
    public static class TestService extends AccessibilityService {AccessibilityNodeInfo root;public AccessibilityNodeInfo getRootInActiveWindow(){return root;}public void onAccessibilityEvent(AccessibilityEvent e){}public void onInterrupt(){} }
    TestService service;Rule rule;UiDriver driver;
    @Before public void setup()throws Exception{service=Robolectric.buildService(TestService.class).create().get();rule=new Rule(new JSONObject("{\"id\":\"fixture\",\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"]}"));service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));driver=new UiDriver(service,()->false);driver.current(rule);}
    AccessibilityNodeInfo node(String label,String cls,Rect b){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName("com.android.settings");n.setText(label);n.setClassName(cls);n.setVisibleToUser(true);n.setEnabled(true);n.setBoundsInScreen(b);return n;}
    AccessibilityNodeInfo row(String label,int y,boolean checked){AccessibilityNodeInfo row=node("","android.widget.LinearLayout",new Rect(0,y,800,y+100));AccessibilityNodeInfo title=node(label,"android.widget.TextView",new Rect(20,y+20,500,y+80));AccessibilityNodeInfo toggle=node("","android.widget.Switch",new Rect(600,y+20,740,y+80));toggle.setCheckable(true);toggle.setClickable(true);toggle.setChecked(checked);shadowOf(row).addChild(title);shadowOf(row).addChild(toggle);shadowOf(service.root).addChild(row);return toggle;}
    @Test public void selectsOnlyUniqueNamedSwitch()throws Exception{row("自动更新",100,true);AccessibilityNodeInfo target=row("个性化推荐",220,true);UiDriver.Found f=driver.find("个性化推荐");assertNotNull(f);assertEquals(target,f.node);assertTrue(f.checked);assertNull(driver.find("不存在"));}
    @Test public void duplicateLabelsStopInsteadOfGuessing()throws Exception{row("个性化推荐",100,true);row("个性化推荐",220,true);try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("不唯一"));}}
    @Test public void disabledSwitchCannotBeChanged()throws Exception{row("个性化推荐",100,true).setEnabled(false);try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("不可操作"));}}
    @Test public void otherForegroundAppStops()throws Exception{service.root.setPackageName("com.example.chat");try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("前台"));}}
    @Test public void cancellationPreventsAction()throws Exception{driver=new UiDriver(service,()->true);try{driver.current(rule);fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("停止"));}}
    @Test public void clickIsVerifiedAndRepeatedRunDoesNotToggleBack()throws Exception{AccessibilityNodeInfo target=row("个性化推荐",100,true);shadowOf(target).setOnPerformActionListener((action,args)->{target.setChecked(!target.isChecked());return true;});assertTrue(driver.set("个性化推荐",true,false));assertFalse(target.isChecked());int count=shadowOf(target).getPerformedActions().size();assertTrue(driver.set("个性化推荐",true,false));assertFalse(target.isChecked());assertEquals(count,shadowOf(target).getPerformedActions().size());}
    @Test public void ignoredClickCannotReportSuccess()throws Exception{AccessibilityNodeInfo target=row("个性化推荐",100,true);shadowOf(target).setOnPerformActionListener((action,args)->true);assertFalse(driver.set("个性化推荐",true,false));assertTrue(target.isChecked());}
    @Test public void restorationWritesSavedOriginalValue()throws Exception{AccessibilityNodeInfo target=row("个性化推荐",100,false);shadowOf(target).setOnPerformActionListener((action,args)->{target.setChecked(true);return true;});assertTrue(driver.set("个性化推荐",false,true));assertTrue(target.isChecked());}
    @Test public void settingsIconDoesNotMatchUnrelatedSettingSwitch()throws Exception{AccessibilityNodeInfo icon=node("","android.widget.ImageView",new Rect(680,30,750,100));icon.setViewIdResourceName("com.android.settings:id/settings_icon");icon.setClickable(true);shadowOf(service.root).addChild(icon);AccessibilityNodeInfo unrelated=row("设置壁纸",240,true);unrelated.setViewIdResourceName("com.android.settings:id/securitySettingSwitchLayout");shadowOf(icon).setOnPerformActionListener((action,args)->true);assertTrue(driver.tap(java.util.Collections.singletonList("设置")));assertEquals(1,shadowOf(icon).getPerformedActions().size());assertEquals(0,shadowOf(unrelated).getPerformedActions().size());}
    @Test public void appNotificationPageIsNotChannelPage()throws Exception{rule.channelId="promo";rule.channelName="营销通知";rule.channelApp="测试应用";shadowOf(service.root).addChild(node("测试应用","android.widget.TextView",new Rect(0,0,500,80)));row("营销通知",200,true);try{driver.current(rule);fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("渠道名"));}}
    @Test public void onlyExplicitMatchingDisableDialogCanBeConfirmed()throws Exception{
        AccessibilityNodeInfo title=node("确定关闭“个性化推荐”吗？","android.widget.TextView",new Rect(20,200,700,300));shadowOf(service.root).addChild(title);
        AccessibilityNodeInfo ok=node("确定","android.widget.Button",new Rect(400,400,700,500));ok.setClickable(true);shadowOf(service.root).addChild(ok);shadowOf(ok).setOnPerformActionListener((a,b)->true);
        assertFalse(driver.confirmDisable("其他开关"));assertEquals(0,shadowOf(ok).getPerformedActions().size());assertTrue(driver.confirmDisable("个性化推荐"));
        title.setText("同意新的隐私协议");assertFalse(driver.confirmDisable("个性化推荐"));assertEquals(1,shadowOf(ok).getPerformedActions().size());
    }
    @Test public void aospChannelEntityHeaderAndEnglishToggleAreRecognized()throws Exception{
        rule.channelId="promo";rule.channelName="活动优惠";rule.channelApp="测试应用";
        AccessibilityNodeInfo header=node("","android.widget.LinearLayout",new Rect(0,500,800,750));
        AccessibilityNodeInfo title=node("活动优惠","android.widget.TextView",new Rect(20,510,780,590));title.setViewIdResourceName("com.android.settings:id/entity_header_title");
        AccessibilityNodeInfo app=node("测试应用","android.widget.TextView",new Rect(20,600,780,680));app.setViewIdResourceName("com.android.settings:id/entity_header_summary");
        shadowOf(header).addChild(title);shadowOf(header).addChild(app);shadowOf(service.root).addChild(header);row("Show notifications",800,true);
        driver.current(rule);assertNotNull(driver.find("允许通知"));
        app.setText("其他应用");try{driver.current(rule);fail();}catch(UiDriver.Halt expected){assertTrue(expected.getMessage().contains("渠道名"));}
    }
}
