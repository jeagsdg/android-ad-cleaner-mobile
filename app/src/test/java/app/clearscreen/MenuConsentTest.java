package app.clearscreen;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;import org.robolectric.RuntimeEnvironment;import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class MenuConsentTest {
    @Test public void hidingWithNegativeFeedbackIsOptionalAndTheCardMustBeAnAd(){
        Store s=new Store(RuntimeEnvironment.getApplication());assertFalse(s.allowAdDislike());
        String app="com.sina.weibo";
        Rect screen=new Rect(0,0,1080,2400),b=new Rect(900,1000,1070,1080);
        AccessibilityNodeInfo root=PlayerAdTest.node(null,app,"android.widget.FrameLayout","",false,screen);
        AccessibilityNodeInfo p=PlayerAdTest.node(root,app,"android.widget.LinearLayout","",true,b);p.setViewIdResourceName(app+":id/ll_close");
        AccessibilityNodeInfo badge=PlayerAdTest.node(p,app,"android.widget.TextView","广告",false,new Rect(905,1005,984,1070));badge.setViewIdResourceName(app+":id/tv_tips");
        AccessibilityNodeInfo icon=PlayerAdTest.node(p,app,"android.widget.ImageView","",false,new Rect(994,1005,1063,1070));icon.setViewIdResourceName(app+":id/iv_close_icon");
        assertNull(AdMenuEntry.find(root,UiDriver.walkComplete(root),screen,s.allowAdDislike()));
        s.setAllowAdDislike(true);assertEquals(p,AdMenuEntry.find(root,UiDriver.walkComplete(root),screen,s.allowAdDislike()));
        badge.setText("更多");assertNull(AdMenuEntry.find(root,UiDriver.walkComplete(root),screen,true));
    }
    @Test public void dismissOnlyTheNamedFeedbackRow(){
        String app="com.sina.weibo",host=app+".feed.DetailWeiboActivity";
        Rect sheet=new Rect(150,450,860,930),screen=new Rect(0,0,1080,2400);
        AccessibilityNodeInfo root=PlayerAdTest.node(null,app,"android.widget.FrameLayout","",false,sheet);
        AccessibilityNodeInfo target=PlayerAdTest.node(root,app,"android.widget.TextView","不感兴趣",true,new Rect(180,475,820,540));
        PlayerAdTest.node(root,app,"android.widget.TextView","举报",true,new Rect(180,580,820,650));
        AccessibilityNodeInfo why=PlayerAdTest.node(root,app,"android.widget.TextView","为何会看到此广告",true,new Rect(180,740,820,880));
        assertEquals(target,AdMenuEntry.dislike(root,UiDriver.walkComplete(root),screen,host).button);
        assertNull(AdMenuEntry.dislike(root,UiDriver.walkComplete(root),screen,app+".Settings"));
        why.setText("更多");assertNull(AdMenuEntry.dislike(root,UiDriver.walkComplete(root),screen,host));
    }
}
