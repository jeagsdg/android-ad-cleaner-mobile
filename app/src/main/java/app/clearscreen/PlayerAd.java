package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.regex.Pattern;

/** Native control signatures measured on public mini-program and player snapshots. */
final class PlayerAd {
    private PlayerAd(){}
    private static final Pattern MINI_TIMER=Pattern.compile("[0-9]{1,2}\\s*(?:秒|s)?\\s*跳[过過]");
    static boolean miniWebExit(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> nodes){
        if(nodes==null||node.getChildCount()!=0||!MINI_TIMER.matcher(SplashSkipper.label(node.getText())).matches()
                ||!"android.widget.TextView".contentEquals(String.valueOf(node.getClassName())))return false;
        AccessibilityNodeInfo p=node.getParent();
        if(p==null||p.getChildCount()!=2)return false;
        Rect btn=bounds(node),layer=bounds(p),screen=bounds(nodes.get(0));
        if(screen.isEmpty()||!screen.contains(btn)||btn.left<screen.centerX()
                ||btn.bottom>screen.top+screen.height()*.22||btn.width()>screen.width()*.32)return false;
        AccessibilityNodeInfo web=p.getParent();
        if(web==null||!String.valueOf(web.getClassName()).endsWith("WebView"))return false;
        boolean artwork=false,capsule=false;
        for(int i=0;i<2;i++){
            AccessibilityNodeInfo child=p.getChild(i);if(child==null||child.equals(node)||!child.isVisibleToUser())continue;
            Rect r=bounds(child);
            artwork=String.valueOf(child.getClassName()).endsWith("Image")&&r.equals(layer)
                &&r.width()>=screen.width()*.92&&r.height()>=screen.height()*.9;
        }
        if(!artwork)return false;
        for(AccessibilityNodeInfo n:nodes){
            if(!n.isVisibleToUser()||n.getWindowId()!=node.getWindowId()||!"com.tencent.mm".contentEquals(String.valueOf(n.getPackageName())))continue;
            if(n.isPassword()||n.isEditable()||SplashSkipper.rewardAd(SplashSkipper.label(n.getText()))||SplashSkipper.rewardAd(SplashSkipper.label(n.getContentDescription())))return false;
            if(n.isClickable()&&"关闭".contentEquals(String.valueOf(n.getContentDescription()))){
                Rect c=bounds(n);if(!c.isEmpty()&&c.left>screen.centerX()&&c.bottom<=btn.top&&btn.top-c.bottom<screen.height()*.07)capsule=true;
            }
        }
        return capsule;
    }
    /** The left capsule is a compact common ancestor; changes in anonymous wrapper depth
     * must not turn the unrelated close-mini-program button on the right into a target. */
    static boolean miniLeftExit(AccessibilityNodeInfo n,List<AccessibilityNodeInfo> nodes){
        if(nodes==null||n.isClickable()||n.getChildCount()!=0||!"跳过".equals(SplashSkipper.label(n.getText()))
                ||!"android.widget.TextView".equals(String.valueOf(n.getClassName())))return false;
        Rect screen=bounds(nodes.get(0)),button=bounds(n);
        if(screen.isEmpty()||button.isEmpty()||!screen.contains(button)||button.right>screen.left+screen.width()*.45
                ||button.bottom>screen.top+screen.height()*.20)return false;
        AccessibilityNodeInfo ancestor=n.getParent();
        for(int depth=0;depth<8&&ancestor!=null;depth++,ancestor=ancestor.getParent()){
            Rect group=bounds(ancestor);if(group.width()>screen.width()*.46||group.height()>screen.height()*.12)break;
            if(ancestor.isClickable()||!ancestor.isVisibleToUser()||!group.contains(button))return false;
            if(ancestor.getChildCount()<2)continue;
            for(AccessibilityNodeInfo badge:UiDriver.walkComplete(ancestor)){
                if(!badge.isVisibleToUser()||badge.isClickable()||badge.getChildCount()!=0
                        ||!"广告".equals(SplashSkipper.label(badge.getText()))
                        ||!"android.widget.TextView".equals(String.valueOf(badge.getClassName()))
                        ||!android.text.TextUtils.equals(n.getPackageName(),badge.getPackageName())||n.getWindowId()!=badge.getWindowId())continue;
                Rect b=bounds(badge);if(!b.isEmpty()&&group.contains(b)&&b.right<=button.left
                        &&button.left-b.right<=screen.width()*.16&&Math.abs(b.centerY()-button.centerY())<=button.height()/4)return true;
            }
        }
        return false;
    }
    static boolean blockedPlayer(List<AccessibilityNodeInfo> nodes){
        for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&"com.tencent.qqlive".contentEquals(String.valueOf(n.getPackageName()))){
            String t=SplashSkipper.label(n.getText()),d=SplashSkipper.label(n.getContentDescription());
            if(protectedAdText(t)||protectedAdText(d)||SplashSkipper.rewardAd(t)||SplashSkipper.rewardAd(d))return true;
        }
        return false;
    }
    static AccessibilityNodeInfo videoExit(AccessibilityNodeInfo root,String activity,Rect screen,List<AccessibilityNodeInfo> nodes){
        if(!"com.tencent.qqlive".equals(String.valueOf(root.getPackageName()))||screen.isEmpty())return null;
        boolean vipPage="com.tencent.qqlive.ona.activity.VideoDetailActivity".equals(activity);
        boolean overlay="com.tencent.qqlive.kmm.VideoDetailKmmActivityBk".equals(activity);
        if(!vipPage&&!overlay)return null;
        boolean play=false,details=false;AccessibilityNodeInfo exit=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!TimedAd.onPage(root,n,screen))continue;
            String t=SplashSkipper.label(n.getText()),d=SplashSkipper.label(n.getContentDescription());
            if(n.isPassword()||n.isEditable()||SplashSkipper.unsafeWindowNode(n,true)||protectedAdText(t)||protectedAdText(d))return null;
            play|=t.equals("继续播放");details|=t.equals("了解详情");
            if(!n.isEnabled()||!n.isClickable()||n.getChildCount()!=0||!String.valueOf(n.getClassName()).endsWith(".TextView")
                    ||!"com.tencent.qqlive:id/arg".equals(n.getViewIdResourceName()))continue;
            if(!(vipPage&&t.equals("跳过广告")&&hasVipNeighbor(n)||overlay&&t.equals("关闭广告")))continue;
            Rect b=bounds(n);if(!screen.contains(b)||b.left<screen.centerX()||b.bottom>screen.top+screen.height()*.30
                    ||b.width()>screen.width()*.42||b.height()>screen.height()*.15||b.width()<12||b.height()<12)continue;
            if(exit!=null&&!exit.equals(n))return null;exit=n;
        }
        return vipPage||play&&details?exit:null;
    }
    private static boolean protectedAdText(String t){return (t.contains("广告")&&(t.contains("领金币")||t.contains("领奖励")))||t.contains("看广告免费看")||t.contains("放弃权益")||t.contains("解锁奖励")||CustomSkipRule.sensitiveText(t);}
    private static boolean hasVipNeighbor(AccessibilityNodeInfo n){
        AccessibilityNodeInfo p=n.getParent();if(p==null||p.getChildCount()!=3)return false;
        for(int i=0;i<3;i++){AccessibilityNodeInfo c=p.getChild(i);
            if(c!=null&&c.isVisibleToUser()&&c.isClickable()&&"VIP可关闭该广告".equals(SplashSkipper.label(c.getText()))
                    &&"com.tencent.qqlive:id/arg".equals(c.getViewIdResourceName())&&bounds(c).left>=bounds(n).right)return true;}
        return false;
    }
    static Rect bounds(AccessibilityNodeInfo n){Rect b=new Rect();n.getBoundsInScreen(b);return b;}
}
