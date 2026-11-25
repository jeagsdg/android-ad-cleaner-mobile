package app.clearscreen;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import java.util.List;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class PlayerAdTest {
    static AccessibilityNodeInfo node(AccessibilityNodeInfo p,String pkg,String cls,String text,boolean click,Rect box){
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName(pkg);n.setClassName(cls);n.setText(text);n.setVisibleToUser(true);n.setEnabled(true);n.setClickable(click);n.setBoundsInScreen(box);
        if(p!=null)shadowOf(p).addChild(n);return n;
    }
    @Test public void playerRejectsMembershipCountdownAndEarnedContent(){
        String pkg="com.tencent.qqlive",act=pkg+".ona.activity.VideoDetailActivity";Rect screen=new Rect(0,0,2400,1080);
        AccessibilityNodeInfo root=node(null,pkg,"android.widget.FrameLayout","",false,screen),p=node(root,pkg,"android.widget.LinearLayout","",false,screen);
        AccessibilityNodeInfo exit=node(p,pkg,"android.widget.TextView","跳过广告",true,new Rect(1846,23,2024,92));
        node(p,pkg,"android.widget.TextView","|",false,new Rect(2025,23,2045,92));
        AccessibilityNodeInfo vip=node(p,pkg,"android.widget.TextView","VIP可关闭该广告",true,new Rect(2048,23,2367,92));
        exit.setViewIdResourceName(pkg+":id/arg");vip.setViewIdResourceName(pkg+":id/arg");
        assertEquals(exit,PopupMatch.findUsingApp(root,act,screen,UiDriver.walkComplete(root)).target);
        exit.setText("24秒后可跳过广告");assertNull(PopupMatch.findUsingApp(root,act,screen,UiDriver.walkComplete(root)));
        exit.setText("跳过广告");node(root,pkg,"android.widget.TextView","点击并浏览15秒广告领金币",true,new Rect(100,300,550,420));assertNull(PopupMatch.findUsingApp(root,act,screen,UiDriver.walkComplete(root)));
    }
    @Test public void wechatWebSkipIsNotTheMiniProgramCloseButton(){
        String pkg="com.tencent.mm";Rect screen=new Rect(0,0,1440,3088);
        AccessibilityNodeInfo root=node(null,pkg,"android.widget.FrameLayout","",false,screen);
        AccessibilityNodeInfo web=node(root,pkg,"android.webkit.WebView","",false,screen),p=node(web,pkg,"android.view.View","",false,screen);
        AccessibilityNodeInfo skip=node(p,pkg,"android.widget.TextView","3跳过",false,new Rect(1053,300,1406,420));
        node(p,pkg,"android.widget.Image","",false,screen);
        AccessibilityNodeInfo capsule=node(root,pkg,"android.widget.ImageButton","",true,new Rect(1228,126,1408,246));capsule.setContentDescription("关闭");
        List<AccessibilityNodeInfo> ns=UiDriver.walkComplete(root);
        assertTrue(PlayerAd.miniWebExit(skip,ns));assertFalse(PlayerAd.miniWebExit(capsule,ns));
        capsule.setBoundsInScreen(new Rect(1150,320,1350,410));assertFalse(PlayerAd.miniWebExit(skip,UiDriver.walkComplete(root)));
        capsule.setBoundsInScreen(new Rect(1228,126,1408,246));skip.setText("3秒后可跳过");assertFalse(PlayerAd.miniWebExit(skip,ns));
    }
}
