package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class TimedAdTest {
    static final Rect VIEW=new Rect(0,0,1080,2400), EXIT=new Rect(820,90,1024,190);
    static AccessibilityNodeInfo add(AccessibilityNodeInfo parent,String text,boolean click,Rect bounds){
        AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setText(text);n.setClassName("android.view.View");
        n.setPackageName("example.video");n.setVisibleToUser(true);n.setEnabled(true);n.setClickable(click);n.setBoundsInScreen(bounds);
        if(parent!=null)shadowOf(parent).addChild(n);return n;
    }
    @Test public void countdownDoesNotDisableAnIndependentExitOrBecomeAnExitItself(){
        AccessibilityNodeInfo root=add(null,"",false,VIEW),timer=add(root,"15秒后可跳过",true,EXIT);
        add(root,"广告",false,new Rect(50,80,180,130));AccessibilityNodeInfo close=add(root,"关闭广告",true,new Rect(840,260,1040,340));
        PopupMatch m=PopupMatch.findGenericAd(root,VIEW);assertNotNull(m);assertEquals(close,m.target);
        close.setEnabled(false);assertNull(PopupMatch.findGenericAd(root,VIEW));assertNull(SplashSkipper.match(root));
        timer.setText("跳过广告");assertEquals(timer,PopupMatch.findGenericAd(root,VIEW).target);
    }
    @Test public void plainSkipRequiresATimerAndARealAdMarkerOutsideCollections(){
        AccessibilityNodeInfo root=add(null,"",false,VIEW),row=add(root,"",false,VIEW);
        add(root,"广告",false,new Rect(40,60,120,100));AccessibilityNodeInfo skip=add(row,"跳过",true,EXIT);
        AccessibilityNodeInfo clock=add(root,"00:15",false,EXIT);assertNull(PopupMatch.findGenericAd(root,VIEW));
        clock.setText("剩余15秒");assertEquals(skip,PopupMatch.findGenericAd(root,VIEW).target);
        row.setClassName("androidx.recyclerview.widget.RecyclerView");assertNull(PopupMatch.findGenericAd(root,VIEW));
        row.setClassName("android.view.View");clock.setText("浏览15秒得奖励");assertNull(PopupMatch.findGenericAd(root,VIEW));
    }
    @Test public void watchIsBoundToTheAdvertNotJustTheApplicationOrTheChangingNumber(){
        AccessibilityNodeInfo root=add(null,"",false,VIEW);AccessibilityNodeInfo badge=add(root,"广告",false,EXIT);
        AccessibilityNodeInfo n=add(root,"１５ 秒後可跳過",false,EXIT);List<AccessibilityNodeInfo> nodes=List.of(root,badge,n);
        TimedAd.Watch w=new TimedAd.Watch();
        TimedAd.Evidence e=TimedAd.inspect(root,nodes,VIEW);assertEquals(15,e.seconds);
        assertEquals(18100,w.observe(e,100));assertEquals(18100,w.observe(e,1000));assertEquals(0,w.observe(e,19000));
        n.setText("关闭广告");assertEquals(-1,TimedAd.inspect(root,nodes,VIEW).seconds);w.clear();assertFalse(w.matches(e,101));
        assertFalse(TimedAd.locked("15s | 跳过"));assertEquals(15,TimedAd.seconds("Skip in 15s"));
        assertEquals(-1,TimedAd.seconds("00:15"));assertEquals(-1,TimedAd.seconds("看15秒领金币"));
    }
    @Test public void countdownInsideCompositeButtonBlocksThatButtonOnly(){
        AccessibilityNodeInfo root=add(null,"",false,VIEW);
        AccessibilityNodeInfo button=add(root,"跳过广告",true,EXIT),img=add(button,"",false,EXIT);
        AccessibilityNodeInfo wrapper=add(button,"",false,EXIT);
        AccessibilityNodeInfo timer=add(wrapper,"15秒后可跳过",false,EXIT);
        assertTrue(TimedAd.lockedControl(button));
        timer.setVisibleToUser(false);assertFalse(TimedAd.lockedControl(button));
        timer.setVisibleToUser(true);timer.setBoundsInScreen(new Rect(0,2000,300,2090));assertFalse(TimedAd.lockedControl(button));
        assertFalse(TimedAd.lockedControl(img));
    }

}
