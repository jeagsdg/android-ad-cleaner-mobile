package app.clearscreen;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Prefer the native close hit area; never map an adjacent mute or resume control to "close". */
final class PauseAd {
    private PauseAd(){}
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root,String activity,Rect screen,List<AccessibilityNodeInfo> nodes){
        String pkg=String.valueOf(root.getPackageName());
        boolean sdk=pkg.equals("com.youku.phone")&&"com.youku.ui.activity.DetailActivity".equals(activity);
        boolean pad=pkg.equals("com.qiyi.video.pad")&&"org.iqiyi.video.activity.PlayerActivity".equals(activity);
        if(!sdk&&!pad||screen.width()<=screen.height())return null;
        boolean resume=false,details=false;AccessibilityNodeInfo hit=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!TimedAd.onPage(root,n,screen))continue;
            String t=SplashSkipper.label(n.getText()),d=SplashSkipper.label(n.getContentDescription());
            if(n.isPassword()||n.isEditable()&&n.isFocused()||SplashSkipper.unsafeWindowNode(n,false)||PlayerAd.blockedText(t)||PlayerAd.blockedText(d))return null;
            resume|=t.equals("继续播放");details|=t.equals("查看详情");
            AccessibilityNodeInfo p=n.getParent();if(p==null||!p.isClickable()||!p.isEnabled()||!TimedAd.onPage(root,p,screen)||!SplashSkipper.safeClickTarget(p))continue;
            if(sdk){
                if(!d.equals("关闭")||!id(n,pkg,"xadsdk_pause_ad_close")||!id(p,pkg,"xadsdk_pause_ad_close_layout")
                        ||p.getChildCount()!=1||!id(p.getParent(),pkg,"xadsdk_pause_ad_float_container"))continue;
                boolean badge=false;AccessibilityNodeInfo panel=p.getParent();
                for(int i=0;i<panel.getChildCount();i++){AccessibilityNodeInfo c=panel.getChild(i);
                    if(id(c,pkg,"xadsdk_pause_ad_txt_tips")&&TimedAd.onPage(root,c,screen)&&"广告".equals(SplashSkipper.label(c.getText())))badge=true;}
                if(!badge)continue;
            }else if(!t.equals("关闭广告，放大暂停画面")||!id(n,pkg,"unused_res_a")||p.getChildCount()!=2)continue;
            Rect b=PlayerAd.bounds(p);if(!screen.contains(b)||!b.contains(PlayerAd.bounds(n))||b.width()>screen.width()*.35||b.height()>screen.height()*.17)continue;
            if(hit!=null&&!hit.equals(p))return null;hit=p;
        }
        return sdk||resume&&details?hit:null;
    }
    private static boolean id(AccessibilityNodeInfo n,String pkg,String key){return n!=null&&(pkg+":id/"+key).equals(n.getViewIdResourceName());}
}
