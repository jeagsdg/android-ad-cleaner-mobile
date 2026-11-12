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
    @Test public void genericSplashRecognizesTencentMapsCountdownBeforeSkip(){
        // Snapshot 13400128 has an ad badge and a native skip separate from the ad and its CTA.
        String pkg="com.tencent.map";
        AccessibilityNodeInfo page=node("",false,new Rect(0,0,1080,2400));
        AccessibilityNodeInfo panel=node("",false,new Rect(0,0,1080,2356));
        AccessibilityNodeInfo image=node("",true,new Rect(0,0,1080,2356));
        AccessibilityNodeInfo marker=node("广告",false,new Rect(708,99,807,149));
        AccessibilityNodeInfo close=node("1 跳过",true,new Rect(843,85,1030,162));
        AccessibilityNodeInfo promotion=node("立即查看",true,new Rect(330,1844,750,1993));
        for(AccessibilityNodeInfo part:new AccessibilityNodeInfo[]{page,panel,image,marker,close,promotion}){
            part.setPackageName(pkg);
        }
        image.setClassName("android.widget.ImageView");
        image.setViewIdResourceName(pkg+":id/czz");
        marker.setClassName("android.widget.TextView");
        marker.setViewIdResourceName(pkg+":id/czu");
        close.setClassName("android.widget.Button");
        close.setViewIdResourceName(pkg+":id/czw");
        promotion.setClassName("android.widget.TextView");
        promotion.setViewIdResourceName(pkg+":id/czy");
        shadowOf(page).addChild(panel);
        for(AccessibilityNodeInfo child:new AccessibilityNodeInfo[]{image,marker,close,promotion}){
            shadowOf(panel).addChild(child);
        }

        assertEquals(close,SplashSkipper.match(page));
        marker.setVisibleToUser(false);
        assertNull(SplashSkipper.match(page));
        marker.setVisibleToUser(true);
        close.setEnabled(false);
        assertNull(SplashSkipper.match(page));
    }
    @Test public void genericSplashRecognizesVerifiedAdBadgesWithoutAppSpecificRules(){
        AccessibilityNodeInfo marker=node("",false,new Rect(10,1800,80,1850));
        shadowOf(root).addChild(marker);
        for(String text:new String[]{"广告 | 已Wi-Fi预加载","广告 | 已 WiFi 预加载","广告 ｜ 已 Ｗｉ－Ｆｉ 预加载","品牌广告"}){
            marker.setText(text);
            assertEquals(text,skip,SplashSkipper.match(root));
        }
        marker.setText("");
        marker.setContentDescription("启动图广告");
        assertEquals(skip,SplashSkipper.match(root));

        marker.setContentDescription("");
        for(String text:new String[]{"广告设置","免广告","已Wi-Fi预加载","品牌广告设置","启动图广告示例"}){
            marker.setText(text);
            assertNull(text,SplashSkipper.match(root));
        }
    }
    @Test public void tapToSkipPhraseStillRequiresAdContext(){
        skip.setText("点击跳过");
        assertNull(SplashSkipper.match(root));
        ad();
        assertEquals(skip,SplashSkipper.match(root));
    }
    @Test public void explicitAdSkipSupportsTraditionalEnglishAndCountdown(){for(String text:new String[]{"跳过广告","跳過廣告 (3秒)","Skip Ad 5s","5秒跳过"}){skip.setText(text);assertTrue(SplashSkipper.skipLabel(text));}skip.setText("Skip Ad 5s");assertEquals(skip,SplashSkipper.match(root));}
    @Test public void genericSplashAcceptsOnlyExplicitAdCloseLabels(){
        for(String text:new String[]{"关闭广告","关闭此广告","關閉廣告","關閉此廣告",
                "Close ad","Close this ad","Close advertisement","Dismiss ad","Dismiss this ad"}){
            skip.setText(text);
            assertEquals(text,skip,SplashSkipper.match(root));
        }
        skip.setText("×");
        skip.setContentDescription("Close this ad");
        assertEquals(skip,SplashSkipper.match(root));

        ad();
        skip.setContentDescription("");
        for(String text:new String[]{"关闭","Close","关闭广告设置","Close this ad settings"}){
            skip.setText(text);
            assertNull(text,SplashSkipper.match(root));
        }
    }
    @Test public void explicitAdCloseStillRequiresSafePlacementAndUniqueTarget(){
        skip.setText("关闭广告");
        skip.setBoundsInScreen(new Rect(400,900,600,1000));
        assertNull(SplashSkipper.match(root));
        skip.setBoundsInScreen(new Rect(810,120,1030,220));
        AccessibilityNodeInfo other=node("Close this ad",true,new Rect(40,120,250,220));
        shadowOf(root).addChild(other);
        assertNull(SplashSkipper.match(root));
        other.setText("同意协议");
        assertNull(SplashSkipper.match(root));
        other.setText("观看广告领取奖励");
        assertNull(SplashSkipper.match(root));
        other.setText("");
        other.setEditable(true);
        assertNull(SplashSkipper.match(root));
    }
    @Test public void closeAndCentralSkipNeverMatch(){ad();skip.setText("关闭");assertNull(SplashSkipper.match(root));skip.setText("跳过");skip.setBoundsInScreen(new Rect(400,900,600,1000));assertNull(SplashSkipper.match(root));}
    @Test public void editingOrConsentSuppressesAnyClick(){ad();AccessibilityNodeInfo input=node("",false,new Rect(20,500,1000,700));input.setEditable(true);shadowOf(root).addChild(input);assertNull(SplashSkipper.match(root));input.setEditable(false);AccessibilityNodeInfo consent=node("同意",true,new Rect(20,800,300,900));shadowOf(root).addChild(consent);assertNull(SplashSkipper.match(root));}
    @Test public void duplicatesAndRewardAdsAreNotClicked(){ad();AccessibilityNodeInfo duplicate=node("跳过",true,new Rect(700,250,1000,350));shadowOf(root).addChild(duplicate);assertNull(SplashSkipper.match(root));duplicate.setText("观看广告领取奖励");duplicate.setClickable(false);assertNull(SplashSkipper.match(root));}
    @Test public void unnamedOrDisabledControlsAreNotGuessed(){ad();skip.setEnabled(false);assertNull(SplashSkipper.match(root));skip.setEnabled(true);skip.setCheckable(true);assertNull(SplashSkipper.match(root));}
    @Test public void knownSdkImageControlWorksWithoutAdTextButKeepsInputGuard(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("");skip.setViewIdResourceName("test.app:id/tt_splash_skip_btn");assertEquals(skip,SplashSkipper.match(root,rules,""));AccessibilityNodeInfo input=node("",false,new Rect(20,500,1000,700));input.setEditable(true);shadowOf(root).addChild(input);assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void sdkResourceNameIsExactAndStillNeedsUniqueSmallCorner(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("");skip.setViewIdResourceName("test.app:id/not_tt_splash_skip_btn");assertNull(SplashSkipper.match(root,rules,""));skip.setViewIdResourceName("test.app:id/tt_splash_skip_btn");skip.setBoundsInScreen(new Rect(0,0,1080,2400));assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void packageScopedRulePreservesTextAndClassConstraints(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());skip.setText("跳过 3");skip.setClassName("android.widget.TextView");skip.setViewIdResourceName("tv.danmaku.bili:id/count_down");assertNull(SplashSkipper.match(root,rules,""));root.setPackageName("tv.danmaku.bili");skip.setPackageName("tv.danmaku.bili");assertEquals(skip,SplashSkipper.match(root,rules,""));skip.setText("下载进度");assertNull(SplashSkipper.match(root,rules,""));}
    @Test public void activityRestrictionCannotBeDroppedWhenPortingRules(){SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());root.setPackageName("com.hihonor.phoneservice");skip.setPackageName("com.hihonor.phoneservice");skip.setClassName("android.widget.TextView");skip.setViewIdResourceName("com.hihonor.phoneservice:id/start_skip_count_down");assertNull(SplashSkipper.match(root,rules,""));assertNull(SplashSkipper.match(root,rules,"other.Activity"));assertEquals(skip,SplashSkipper.match(root,rules,"com.hihonor.phoneservice.OpenScreenAdActivity"));}
    @Test public void explicitInternationalLabelWorksAtLeftAndReadsDescriptionSeparately(){
        skip.setText("×");
        skip.setBoundsInScreen(new Rect(45,95,300,220));
        for(String text:new String[]{"SKIP ADS （５s）","広告をスキップ","광고 건너뛰기","略過廣告｜５秒"}){
            skip.setContentDescription(text);
            assertEquals(text,skip,SplashSkipper.match(root));
        }
        skip.setContentDescription("Skip this tutorial");
        assertNull(SplashSkipper.match(root));
    }
    @Test public void exactSdkTargetTakesPrecedenceOverWeakerGenericCandidates(){
        SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());
        ad();
        AccessibilityNodeInfo sdk=node("",true,new Rect(30,100,200,220));
        sdk.setViewIdResourceName("test.app:id/tt_splash_skip_btn");
        shadowOf(root).addChild(sdk);
        assertEquals(sdk,SplashSkipper.match(root,rules,""));
        sdk.setPackageName("foreign.app");
        assertEquals(skip,SplashSkipper.match(root,rules,""));
    }
    @Test public void sdkIdOrSkipDescriptionCannotOverrideAConflictingAction(){
        SplashRules rules=new SplashRules(org.robolectric.RuntimeEnvironment.getApplication());
        skip.setText("立即购买");
        skip.setContentDescription("跳过广告");
        skip.setViewIdResourceName("test.app:id/tt_splash_skip_btn");
        assertNull(SplashSkipper.match(root,rules,""));
        assertNull(SplashSkipper.match(root));
        skip.setText("跳过广告");
        skip.setContentDescription("确认领取");
        assertNull(SplashSkipper.match(root,rules,""));
    }
    @Test public void skipTextInsideAPurchaseControlCannotClickItsParent(){
        skip.setText("立即购买");
        AccessibilityNodeInfo caption=node("跳过广告",false,new Rect(850,140,990,200));
        shadowOf(skip).addChild(caption);
        assertNull(SplashSkipper.match(root));
        skip.setText("");
        assertEquals(skip,SplashSkipper.match(root));
    }
    @Test public void launchMatchingRequiresACompleteTreeToRuleOutSensitiveContent(){
        skip.setText("跳过广告");
        assertEquals(skip,SplashSkipper.match(root));
        AccessibilityNodeInfo filler=node("",false,new Rect());
        for(int i=0;i<1798;i++)shadowOf(root).addChild(filler);
        shadowOf(root).addChild(node("同意",true,new Rect(20,800,300,900)));
        assertNull(SplashSkipper.match(root));
    }
    @Test public void protectedSystemPackagesAreNeverSplashTargets(){
        root.setPackageName("com.android.systemui");
        skip.setPackageName("com.android.systemui");
        skip.setText("跳过广告");
        assertNull(SplashSkipper.match(root));
    }
    @Test public void foreignAdMarkerCannotEnableGenericLaunchSkip(){
        AccessibilityNodeInfo marker=node("广告",false,new Rect(10,1800,80,1850));
        marker.setPackageName("other.app");
        shadowOf(root).addChild(marker);
        assertNull(SplashSkipper.match(root));
        marker.setPackageName("test.app");
        assertEquals(skip,SplashSkipper.match(root));
    }
}
