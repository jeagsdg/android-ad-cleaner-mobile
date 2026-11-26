package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class AdSurfaceTest {
    private static final Rect SCREEN=TimedAdTest.VIEW;
    private static AccessibilityNodeInfo node(AccessibilityNodeInfo parent,String id,String text,boolean click,Rect bounds){
        AccessibilityNodeInfo n=TimedAdTest.add(parent,text,click,bounds);
        if(id!=null)n.setViewIdResourceName("example.video:id/"+id);
        return n;
    }
    @Test public void textInsideAnAdLayerNeedsNoSecondTextBadge(){
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo layer=node(root,"ad_container","",false,SCREEN);
        AccessibilityNodeInfo button=node(layer,"sdk_skip_area","",true,TimedAdTest.EXIT);
        node(button,null,"4s | Skip",false,TimedAdTest.EXIT);
        assertEquals(button,SplashSkipper.match(root));
        layer.setViewIdResourceName("example.video:id/list_container");assertNull(SplashSkipper.match(root));
        layer.setViewIdResourceName("example.video:id/ad_container");layer.setScrollable(true);assertNull(SplashSkipper.match(root));
    }
    @Test public void adjacentSdkSkipIsNotAnOrdinaryAppControl(){
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo layer=node(root,"ad_container","",false,SCREEN);
        AccessibilityNodeInfo button=node(root,"skip_button","",true,TimedAdTest.EXIT);
        AccessibilityNodeInfo label=node(button,null,"跳过 1",false,TimedAdTest.EXIT);
        assertEquals(button,SplashSkipper.match(root));label.setText("15秒后可跳过");assertNull(SplashSkipper.match(root));
        label.setText("跳过");layer.setVisibleToUser(false);assertNull(SplashSkipper.match(root));
    }
    @Test public void fullPageCanPointToTheParentOfTheVisibleTextNotTheWholeOverlay(){
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);node(root,null,"广告",false,new Rect(50,80,160,140));
        node(root,null,"15秒后可跳过",false,new Rect(70,145,270,205));
        AccessibilityNodeInfo button=node(root,null,"",true,TimedAdTest.EXIT);
        node(button,null,"关闭广告",false,new Rect(850,110,997,171));
        assertEquals(button,PopupMatch.findGenericAd(root,SCREEN).target);
        button.setBoundsInScreen(SCREEN);assertNull(PopupMatch.findGenericAd(root,SCREEN));
    }
    @Test public void reportMenuIsOnlyContextNeverTheTarget(){
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo sheet=node(root,null,"",false,new Rect(0,1800,1080,2400));
        AccessibilityNodeInfo exit=node(sheet,null,"关闭此广告",true,new Rect(0,2080,1080,2220));
        AccessibilityNodeInfo report=node(sheet,null,"举报广告",true,new Rect(100,1870,330,1990));
        assertEquals(exit,PopupMatch.findGenericAd(root,SCREEN).target);
        report.setText("举报");assertNull(PopupMatch.findGenericAd(root,SCREEN));
        exit.setViewIdResourceName("example.video:id/close_ad");report.setViewIdResourceName("example.video:id/report_ad");
        assertEquals(exit,PopupMatch.findGenericAd(root,SCREEN).target);
        report.setBoundsInScreen(new Rect(100,150,250,260));assertNull(PopupMatch.findGenericAd(root,SCREEN));
        report.setBoundsInScreen(new Rect(100,1870,330,1990));report.setText("举报广告");
        node(sheet,null,"关闭此广告",true,new Rect(0,2240,1080,2370));
        assertNull(PopupMatch.findGenericAd(root,SCREEN));
    }
    @Test public void skipCaptionCanOnlyAddressItsCoincidentSdkImageButton(){
        Rect bounds=new Rect(920,90,1040,210);
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo layer=node(root,null,"",false,SCREEN);
        layer.setViewIdResourceName("example.video:id/ksad_splash_root_container");
        AccessibilityNodeInfo row=node(layer,null,"",false,new Rect(740,75,1060,290));
        AccessibilityNodeInfo button=node(row,null,"",true,bounds);
        button.setClassName("android.widget.ImageView");
        AccessibilityNodeInfo overlay=node(row,null,"",false,bounds);
        AccessibilityNodeInfo caption=node(overlay,null,"跳过",false,new Rect(934,105,1020,175));
        caption.setClassName("android.widget.TextView");
        assertEquals(button,AdSurface.overlaidExit(root,caption,SCREEN));
        button.setText("取消下载");assertNull(AdSurface.overlaidExit(root,caption,SCREEN));button.setText("");
        overlay.setBoundsInScreen(new Rect(710,10,820,70));assertNull(AdSurface.overlaidExit(root,caption,SCREEN));
        overlay.setBoundsInScreen(bounds);layer.setViewIdResourceName("example.video:id/files");
        assertNull(AdSurface.overlaidExit(root,caption,SCREEN));
    }

    @Test public void passiveLegalCopyInAnotherAdCannotExemptAFileOrPaymentDialog() {
        String pkg="com.xunlei.downloadprovider",prefix=pkg+":id/";
        Rect upper=new Rect(10,20,800,600),lower=new Rect(10,1200,800,1810);
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo ad=node(root,null,"",false,upper);ad.setViewIdResourceName(prefix+"ad_container");
        AccessibilityNodeInfo poster=node(ad,null,"",false,upper);
        String legal="应用名称：- | 应用版本：- | 开发者：- | 权限详情 > | 隐私协议 > | 功能介绍 >";
        AccessibilityNodeInfo copy=node(poster,null,legal,false,new Rect(20,105,760,150));
        copy.setViewIdResourceName(prefix+"stop_ad_app_infos");copy.setClassName("android.widget.TextView");
        AccessibilityNodeInfo banner=node(root,null,"",false,lower);banner.setViewIdResourceName(prefix+"play_detail_ad_container");
        AccessibilityNodeInfo row=node(banner,null,"",false,lower);
        AccessibilityNodeInfo close=node(row,null,"",true,new Rect(690,1210,785,1290));close.setViewIdResourceName(prefix+"close_iv");
        for(AccessibilityNodeInfo n:java.util.Arrays.asList(root,ad,poster,copy,banner,row,close))n.setPackageName(pkg);
        assertEquals(pkg,copy.getPackageName());
        assertEquals(pkg,close.getPackageName());
        assertEquals(prefix+"ad_container",copy.getParent().getParent().getViewIdResourceName());
        assertEquals(prefix+"play_detail_ad_container",close.getParent().getParent().getViewIdResourceName());
        assertTrue(PopupAdRules.separateAdDisclosure(copy,legal,close));
        assertFalse(PopupAdRules.separateAdDisclosure(copy,legal.replace("应用名称：-","应用名称：确认支付"),close));
        copy.setClickable(true);assertFalse(PopupAdRules.separateAdDisclosure(copy,legal,close));copy.setClickable(false);
        close.setViewIdResourceName(prefix+"cancel_download");assertFalse(PopupAdRules.separateAdDisclosure(copy,legal,close));
    }

    @Test public void flattenedSheetRequiresAdjacentReportAndCancelRows(){
        AccessibilityNodeInfo root=node(null,null,"",false,SCREEN);
        AccessibilityNodeInfo exit=node(root,null,"关闭此广告",true,new Rect(0,2000,1080,2120));
        node(root,null,"举报广告",true,new Rect(0,2120,1080,2240));
        AccessibilityNodeInfo cancel=node(root,null,"取消",true,new Rect(0,2240,1080,2360));
        assertEquals(exit,PopupMatch.findGenericAd(root,SCREEN).target);
        cancel.setBoundsInScreen(new Rect(10,200,240,290));assertNull(PopupMatch.findGenericAd(root,SCREEN));
    }

}
