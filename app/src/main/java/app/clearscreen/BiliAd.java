package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Ad surfaces verified on ordinary, non-HD Bilibili Android builds. */
final class BiliAd {
    static final String PKG="tv.danmaku.bili", HOME=PKG+".MainActivityV2";
    private BiliAd(){}
    static boolean owns(AccessibilityNodeInfo n){return n!=null&&PKG.contentEquals(String.valueOf(n.getPackageName()));}
    static String id(AccessibilityNodeInfo n){String s=n==null?null:n.getViewIdResourceName();if(s==null)return "";
        if(s.startsWith(PKG+":id/"))return s.substring(PKG.length()+4);
        return s.startsWith("tv.danmaku.bili.adbiz:id/")?s.substring(s.indexOf(":id/")+4):"";}
    static String text(AccessibilityNodeInfo n){return n==null?"":SplashSkipper.label(n.getText());}
    static String desc(AccessibilityNodeInfo n){return n==null?"":SplashSkipper.label(n.getContentDescription());}
    static boolean is(AccessibilityNodeInfo n,String cls){return n!=null&&String.valueOf(n.getClassName()).endsWith("."+cls);}
    private static boolean on(AccessibilityNodeInfo root,AccessibilityNodeInfo n,Rect screen){return owns(n)&&TimedAd.onPage(root,n,screen);}
    static boolean detail(String a){return "com.bilibili.ship.theseus.detail.UnitedBizDetailsActivity".equals(a)
            ||"com.bilibili.ship.theseus.all.UnitedBizDetailsActivity".equals(a)||"com.bilibili.video.videodetail.VideoDetailsActivity".equals(a);}
    private static boolean childId(AccessibilityNodeInfo p,String name){
        for(int i=0;p!=null&&i<p.getChildCount();i++){AccessibilityNodeInfo c=p.getChild(i);if(c!=null&&c.isVisibleToUser()&&id(c).equals(name))return true;}return false;
    }
    static boolean splash(AccessibilityNodeInfo root,String a,AccessibilityNodeInfo n,Rect screen){
        if(!HOME.equals(a)||!on(root,n,screen)||!n.isClickable()||!n.isEnabled()||n.getChildCount()!=0||!is(n,"TextView")
                ||!SplashSkipper.skipLabel(text(n))||TimedAd.locked(n))return false;
        Rect b=PlayerAd.bounds(n);if(!screen.contains(b)||b.top<screen.top+screen.height()*.70||b.left<screen.centerX()||b.width()>screen.width()*.4||b.height()>screen.height()*.12)return false;
        AccessibilityNodeInfo p=n.getParent();if(!on(root,p,screen)||!childId(p,"logo"))return false;
        if(id(n).equals("skip"))return id(p).equals("root")&&childId(p,"background_image");
        if(!id(n).equals("count_down")||!id(p).equals("logo_layout"))return false;
        for(int i=0;i<4&&p!=null;i++,p=p.getParent())if(id(p).equals("splash_layout"))return true;
        return false;
    }
    static boolean splashAboveHome(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen){
        for(AccessibilityNodeInfo n:nodes){
            if(splash(root,HOME,n,screen))return true;
            if(!on(root,n,screen))continue;
            String name=id(n);if(!name.equals("splash_layout")&&!name.equals("root"))continue;
            Rect b=PlayerAd.bounds(n);if(b.width()>=screen.width()*.9&&b.height()>=screen.height()*.8&&
                    (name.equals("splash_layout")||childId(n,"background_image")&&childId(n,"logo")))return true;
        }
        return false;
    }
    static boolean badPage(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen){
        for(AccessibilityNodeInfo n:nodes)if(on(root,n,screen)&&(n.isPassword()||n.isEditable()&&n.isFocused()
                ||SplashSkipper.unsafeWindowNode(n,false)))return true;
        return false;
    }
    static AccessibilityNodeInfo direct(AccessibilityNodeInfo root,String host,List<AccessibilityNodeInfo> nodes,Rect screen){
        if(!owns(root)||!detail(host)||badPage(root,nodes,screen))return null;AccessibilityNodeInfo hit=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!on(root,n,screen)||!n.isEnabled()||!n.isClickable()||n.getChildCount()!=0)continue;
            AccessibilityNodeInfo p=n.getParent();if(!on(root,p,screen))continue;Rect b=PlayerAd.bounds(n);
            if(!screen.contains(b)||b.width()<12||b.height()<12||b.width()>screen.width()*.4||b.height()>screen.height()*.18)continue;
            boolean banner="close".equals(id(n))&&is(n,"ImageView")&&"关闭".equals(desc(n))&&id(p).equals("ad_tint_frame")
                    &&id(p.getParent()).equals("comment_ad_container")&&childId(p,"content");
            boolean cancel=false;
            if(is(n,"TextView")&&text(n).equals("取消")&&p.getChildCount()==3&&n.equals(p.getChild(2))){
                AccessibilityNodeInfo t=p.getChild(0),hint=p.getChild(1);
                if(on(root,t,screen)&&on(root,hint,screen)&&!t.isClickable()&&!hint.isClickable()&&text(t).matches("[0-9]{1,2}秒")
                        &&text(hint).equals("后将展示广告")){
                    Rect h=PlayerAd.bounds(hint);
                    cancel=h.right<=b.left+3&&b.left-h.right<=b.width()/2&&Math.abs(h.centerY()-b.centerY())<b.height()/3;
                }
            }
            if(banner||cancel){if(hit!=null&&!hit.equals(n))return null;hit=n;}
        }
        return hit;
    }
    static AccessibilityNodeInfo entry(AccessibilityNodeInfo root,String host,List<AccessibilityNodeInfo> nodes,Rect screen){
        if(!owns(root)||(!HOME.equals(host)&&!detail(host))||badPage(root,nodes,screen)||(HOME.equals(host)&&splashAboveHome(root,nodes,screen)))return null;AccessibilityNodeInfo hit=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!on(root,n,screen)||!(HOME.equals(host)?id(n).equals("more"):id(n).equals("more_layout"))||!n.isClickable()||!n.isEnabled())continue;
            AccessibilityNodeInfo p=n.getParent();Rect b=PlayerAd.bounds(n);
            if(b.isEmpty()||!screen.contains(b)||b.width()>screen.width()*.23||b.height()>screen.height()*.12)continue;
            for(int level=0;p!=null&&level<5;level++,p=p.getParent()){
                if(!on(root,p,screen)||p.isScrollable()||p.getCollectionInfo()!=null)break;
                if((id(p).equals("ad_tint_frame")||id(p).equals("root_container"))&&desc(p).matches("(?s)^(广告|推广)[,，].+")
                        &&PlayerAd.bounds(p).contains(b)){
                    if(hit!=null&&!hit.equals(n))return null;hit=n;break;
                }
            }
        }
        return hit;
    }
    static AdSurface.Exit feedback(AccessibilityNodeInfo root,String host,List<AccessibilityNodeInfo> ns,Rect screen){
        if(!owns(root)||(!HOME.equals(host)&&!detail(host))||badPage(root,ns,screen))return null;
        AccessibilityNodeInfo hit=null,why=null;boolean title=false;String label="";
        for(AccessibilityNodeInfo n:ns){
            if(!on(root,n,screen))continue;String t=text(n),id=id(n);
            if(!id.matches("title|menu_text|reason[12]"))continue;
            if(t.matches("我为什么会看到此(广告|推广)"))why=n;
            if(t.equals("屏蔽广告")||t.equals("屏蔽推广"))title=true;
            if(!t.matches("视频不感兴趣|不感兴趣|不想看该内容|不喜欢此商品"))continue;
            AccessibilityNodeInfo b=SplashSkipper.clickable(n);if(!on(root,b,screen)||!b.isEnabled()||!SplashSkipper.safeClickTarget(b)||!AdSurface.labelBelongsToButton(n,b))continue;
            if(hit!=null&&!hit.equals(b))return null;hit=b;label=t;
        }
        if(!title||why==null||hit==null)return null;
        for(AccessibilityNodeInfo p=hit.getParent();p!=null;p=p.getParent()){
            Rect r=PlayerAd.bounds(p);if(r.height()>screen.height()*.72)break;
            if(r.contains(PlayerAd.bounds(why))&&UiDriver.walkComplete(p).contains(why))return new AdSurface.Exit(hit,label,"广告反馈");
        }
        return null;
    }
}
