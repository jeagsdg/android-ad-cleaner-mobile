package app.clearscreen;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class LiveRoomAdTest {
    static final String PKG=LiveRoomAd.DOUYU, ACT="com.douyu.module.player.p.rambobase.RamboPlayerActivity";
    static final Rect SCREEN=new Rect(0,0,1440,3200);
    static AccessibilityNodeInfo n(AccessibilityNodeInfo p,String cls,String id,String t,boolean click,Rect box){
        AccessibilityNodeInfo n=PlayerAdTest.node(p,PKG,"android.widget."+cls,t,click,box);
        if(id!=null)n.setViewIdResourceName(PKG+":id/"+id);return n;
    }
    @Test public void choosesTwoSeparateAdExitsInVisualOrderAndNeverTheirCtas(){
        AccessibilityNodeInfo root=n(null,"FrameLayout",null,"",false,SCREEN);
        AccessibilityNodeInfo floatCard=n(root,"FrameLayout","xhdamucard_widget_placeHolder","",true,new Rect(988,2287,1429,2990));
        n(floatCard,"TextView","biz","推荐",false,new Rect(995,2330,1115,2390));
        AccessibilityNodeInfo download=n(floatCard,"TextView","bwy","免费下载",true,new Rect(1007,2881,1370,2971));
        AccessibilityNodeInfo floater=n(floatCard,"ImageView","bx5","",true,new Rect(1373,2287,1429,2343));
        AccessibilityNodeInfo ad=n(root,"FrameLayout","panel","",true,new Rect(0,1350,1440,1575));
        n(ad,"TextView","bwy","去看看",true,new Rect(1061,1418,1309,1508));
        AccessibilityNodeInfo banner=n(ad,"ImageView","el2","",true,new Rect(1309,1397,1440,1528));
        assertEquals(banner,LiveRoomAd.find(root,ACT,SCREEN,UiDriver.walkComplete(root)).target);
        banner.setVisibleToUser(false);assertEquals(floater,LiveRoomAd.find(root,ACT,SCREEN,UiDriver.walkComplete(root)).target);
        download.setText("送礼");assertNull(LiveRoomAd.find(root,ACT,SCREEN,UiDriver.walkComplete(root)));
        banner.setVisibleToUser(true);assertNull(LiveRoomAd.find(root,"com.douyu.module.findgame.tailcateb.page.TailCateBActivity",SCREEN,UiDriver.walkComplete(root)));
    }
}
