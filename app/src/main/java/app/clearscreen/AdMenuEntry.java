package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Recognises an ad badge whose dedicated sibling icon opens an exit menu.
 * A conventional “more” button is deliberately not an advertisement identifier. */
final class AdMenuEntry {
    private AdMenuEntry(){}
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen){
        return find(root,nodes,screen,false);
    }
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen,boolean allowDislike){
        if(root==null||nodes.isEmpty())return null;
        AccessibilityNodeInfo only=null;
        Rect dormantReply=null;
        for(AccessibilityNodeInfo icon:nodes){
            if(!TimedAd.onPage(root,icon,screen))continue;
            if(icon.isEditable()){
                Rect r=PlayerAd.bounds(icon);
                if(!allowDislike||!"com.sina.weibo".contentEquals(String.valueOf(root.getPackageName()))
                        ||dormantReply!=null||icon.isPassword()||icon.isFocused()||icon.isAccessibilityFocused()||icon.isFocusable()
                        ||!screen.contains(r)||r.top<screen.top+screen.height()*.88||r.height()>screen.height()*.09
                        ||!SplashSkipper.label(icon.getText()).startsWith("回复@"))return null;
                dormantReply=r;continue;
            }
            if(icon.isPassword()||SplashSkipper.unsafeWindowNode(icon,true)
                    ||CustomSkipRule.sensitiveText(SplashSkipper.label(icon.getText()))
                    ||CustomSkipRule.sensitiveText(SplashSkipper.label(icon.getContentDescription())))return null;
            boolean weibo=allowDislike&&"com.sina.weibo".contentEquals(String.valueOf(root.getPackageName()))&&"iv_close_icon".equals(localId(icon));
            if((!weibo&&!"icon_close".equals(localId(icon))) || icon.getChildCount()!=0 || icon.isClickable()
                    ||!String.valueOf(icon.getClassName()).endsWith("ImageView"))continue;
            AccessibilityNodeInfo button=icon.getParent();
            if(button==null||!button.isClickable()||!button.isEnabled()||button.getChildCount()!=2
                    ||!TimedAd.onPage(root,button,screen)||!SplashSkipper.safeClickTarget(button))continue;
            AccessibilityNodeInfo badge=button.getChild(0),last=button.getChild(1);
            if(weibo&&!"ll_close".equals(localId(button)))continue;
            if(!icon.equals(last)||badge==null||badge.isClickable()||!TimedAd.onPage(root,badge,screen)||!(weibo?"tv_tips":"tv_ad_logo").equals(localId(badge))
                    ||!"广告".equals(SplashSkipper.label(badge.getText())))continue;
            Rect hit=PlayerAd.bounds(button),left=PlayerAd.bounds(badge),right=PlayerAd.bounds(icon);
            if(!screen.contains(hit)||hit.width()<16||hit.height()<12||hit.width()>screen.width()*.32||hit.height()>screen.height()*.12
                    ||left.isEmpty()||right.isEmpty()||!hit.contains(left)||!hit.contains(right)||left.centerX()>=right.centerX())continue;
            if(only!=null&&!only.equals(button))return null;
            only=button;
        }
        if(only!=null&&dormantReply!=null&&PlayerAd.bounds(only).bottom>=dormantReply.top)return null;
        return only;
    }
    static AdSurface.Exit dislike(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen,String host){
        if(!"com.sina.weibo".contentEquals(String.valueOf(root.getPackageName()))||
                !("com.sina.weibo.feed.DetailWeiboActivity".equals(host)||"com.sina.weibo.feed.SubCommentActiity".equals(host)
                ||"com.sina.weibo.feed.detailrefactor.DetailPageActivity".equals(host)))return null;
        AccessibilityNodeInfo target=null,why=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!TimedAd.onPage(root,n,screen))continue;
            if(n.isEditable()||n.isPassword()||SplashSkipper.unsafeWindowNode(n,true))return null;
            String label=SplashSkipper.label(n.getText());
            if(label.equals("为何会看到此广告"))why=n;
            if(!label.equals("对此内容不感兴趣")&&!label.equals("不感兴趣"))continue;
            if(!n.isEnabled()||!n.isClickable()||!SplashSkipper.safeClickTarget(n))return null;
            if(target!=null)return null;target=n;
        }
        if(target==null||why==null)return null;
        for(AccessibilityNodeInfo p=target.getParent();p!=null;p=p.getParent()){
            Rect r=PlayerAd.bounds(p);
            if(r.isEmpty()||r.height()>screen.height()*.54)break;
            if(r.contains(PlayerAd.bounds(why)) && UiDriver.walkComplete(p).contains(why))
                return new AdSurface.Exit(target,SplashSkipper.label(target.getText()),"为何会看到此广告");
        }
        return null;
    }
    private static String localId(AccessibilityNodeInfo n){
        String id=n.getViewIdResourceName(),prefix=String.valueOf(n.getPackageName())+":id/";
        return id!=null&&id.startsWith(prefix)?id.substring(prefix.length()):"";
    }
}
