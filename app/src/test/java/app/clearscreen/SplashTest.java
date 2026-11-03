package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class SplashTest {
    AccessibilityNodeInfo root,skip;
    AccessibilityNodeInfo node(String label,boolean clickable,Rect rect){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setText(label);n.setPackageName("test.app");n.setEnabled(true);n.setVisibleToUser(true);n.setClickable(clickable);n.setBoundsInScreen(rect);return n;}
    @Before public void setup(){root=node("",false,new Rect(0,0,1080,2400));skip=node("跳过 5",true,new Rect(810,120,1030,220));shadowOf(root).addChild(skip);}
    void ad(){shadowOf(root).addChild(node("广告",false,new Rect(10,1800,80,1850)));}
    @Test public void genericSkipRequiresAdMarker(){assertNull(SplashSkipper.match(root));ad();assertEquals(skip,SplashSkipper.match(root));}
    @Test public void explicitAdSkipSupportsTraditionalEnglishAndCountdown(){for(String text:new String[]{"跳过广告","跳過廣告 (3秒)","Skip Ad 5s","5秒跳过"}){skip.setText(text);assertTrue(SplashSkipper.skipLabel(text));}skip.setText("Skip Ad 5s");assertEquals(skip,SplashSkipper.match(root));}
    @Test public void closeAndCentralSkipNeverMatch(){ad();skip.setText("关闭");assertNull(SplashSkipper.match(root));skip.setText("跳过");skip.setBoundsInScreen(new Rect(400,900,600,1000));assertNull(SplashSkipper.match(root));}
    @Test public void editingOrConsentSuppressesAnyClick(){ad();AccessibilityNodeInfo input=node("",false,new Rect(20,500,1000,700));input.setEditable(true);shadowOf(root).addChild(input);assertNull(SplashSkipper.match(root));input.setEditable(false);AccessibilityNodeInfo consent=node("同意",true,new Rect(20,800,300,900));shadowOf(root).addChild(consent);assertNull(SplashSkipper.match(root));}
    @Test public void duplicatesAndRewardAdsAreNotClicked(){ad();AccessibilityNodeInfo duplicate=node("跳过",true,new Rect(700,250,1000,350));shadowOf(root).addChild(duplicate);assertNull(SplashSkipper.match(root));duplicate.setText("观看广告领取奖励");duplicate.setClickable(false);assertNull(SplashSkipper.match(root));}
    @Test public void unnamedOrDisabledControlsAreNotGuessed(){ad();skip.setEnabled(false);assertNull(SplashSkipper.match(root));skip.setEnabled(true);skip.setCheckable(true);assertNull(SplashSkipper.match(root));}
    @Test public void knownSdkImageControlWorksWithoutAdTextButKeepsInputGuard(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("");skip.setViewIdResourceName("test.app:id/tt_splash_skip_btn");assertEquals(skip,SplashSkipper.match(root,rules,""));AccessibilityNodeInfo input=node("",false,new Rect(20,500,1000,700));input.setEditable(true);shadowOf(root).addChild(input);assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void sdkResourceNameIsExactAndStillNeedsUniqueSmallCorner(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("");skip.setViewIdResourceName("test.app:id/not_tt_splash_skip_btn");assertNull(SplashSkipper.match(root,rules,""));skip.setViewIdResourceName("test.app:id/tt_splash_skip_btn");skip.setBoundsInScreen(new Rect(0,0,1080,2400));assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void packageScopedRulePreservesTextAndClassConstraints(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("跳过 3");skip.setClassName("android.widget.TextView");skip.setViewIdResourceName("tv.danmaku.bili:id/count_down");assertNull(SplashSkipper.match(root,rules,""));root.setPackageName("tv.danmaku.bili");assertEquals(skip,SplashSkipper.match(root,rules,""));skip.setText("下载进度");assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void activityRestrictionCannotBeDroppedWhenPortingRules(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());root.setPackageName("com.hihonor.phoneservice");skip.setClassName("android.widget.TextView");skip.setViewIdResourceName("com.hihonor.phoneservice:id/start_skip_count_down");assertNull(SplashSkipper.match(root,rules,""));assertNull(SplashSkipper.match(root,rules,"other.Activity"));assertEquals(skip,SplashSkipper.match(root,rules,"com.hihonor.phoneservice.OpenScreenAdActivity"));}
}
