package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.regex.Pattern;

/** Recognise the ad's own controller, not the surrounding live player. */
final class LiveRoomAd {
    private LiveRoomAd(){}
    static final String HUYA="com.duowan.kiwi", DOUYU="air.tv.douyu.android";
    static final class Hit {
        final AccessibilityNodeInfo target; final boolean touch;
        Hit(AccessibilityNodeInfo n,boolean t){target=n;touch=t;}
    }
    private static final Pattern SECONDS=Pattern.compile("[0-9]{1,2}\\s*(?:[sS]|秒)");
    private static String label(AccessibilityNodeInfo n){String t=SplashSkipper.label(n.getText());return t.isEmpty()?SplashSkipper.label(n.getContentDescription()):t;}
    private static boolean id(AccessibilityNodeInfo n,String pkg,String suffix){return n!=null&&(pkg+":id/"+suffix).equals(n.getViewIdResourceName());}
    private static boolean childType(AccessibilityNodeInfo n,String cls){return n!=null&&String.valueOf(n.getClassName()).endsWith("."+cls);}
    private static boolean on(AccessibilityNodeInfo root,AccessibilityNodeInfo n,Rect screen){return n!=null&&TimedAd.onPage(root,n,screen);}
    private static AccessibilityNodeInfo up(AccessibilityNodeInfo n,int depth){for(int i=0;i<depth&&n!=null;i++)n=n.getParent();return n;}
    private static boolean subtree(AccessibilityNodeInfo panel,AccessibilityNodeInfo n){
        for(int i=0;n!=null&&i<16;i++,n=n.getParent())if(n.equals(panel))return true;
        return false;
    }
    static boolean launchExit(String pkg,String act,AccessibilityNodeInfo root,AccessibilityNodeInfo n,Rect rect){
        if(!DOUYU.equals(pkg)||!("com.douyu.module.home.pages.main.MainActivity".equals(act)||"com.douyu.module.ad.launch.HotStartSplashActivity".equals(act)))return false;
        if(!on(root,n,rect)||!n.isClickable()||!n.isEnabled()||!childType(n,"TextView")||n.getChildCount()!=0
                ||!SplashSkipper.skipLabel(label(n))||TimedAd.locked(n))return false;
        AccessibilityNodeInfo layer=n.getParent();if(!on(root,layer,rect)||!PlayerAd.bounds(layer).equals(rect))return false;
        boolean art=false,jump=false;
        for(AccessibilityNodeInfo c:UiDriver.walkComplete(layer)){
            if(!on(root,c,rect))continue;
            if(childType(c,"ImageView")&&!c.isClickable()&&PlayerAd.bounds(c).equals(rect))art=true;
            if("跳转详情页面".equals(label(c)))jump=true;
        }
        return art&&jump;
    }
    static Hit find(AccessibilityNodeInfo root,String host,Rect screen,List<AccessibilityNodeInfo> nodes){
        String pkg=String.valueOf(root.getPackageName());
        boolean huya=HUYA.equals(pkg)&&"com.duowan.kiwi.liveroom.ChannelPage".equals(host);
        boolean douyu=DOUYU.equals(pkg)&&("tv.douyu.view.activity.PlayerActivity".equals(host)||
                "com.douyu.module.player.p.rambobase.RamboPlayerActivity".equals(host));
        if(!huya&&!douyu)return null;
        Hit best=null;boolean ambiguous=false;
        for(AccessibilityNodeInfo n:nodes){
            if(!on(root,n,screen))continue;
            if(n.isPassword()||n.isFocused()&&n.isEditable()||SplashSkipper.unsafeWindowNode(n,false))return null;
            if(!n.isEnabled()||n.getChildCount()!=0||TimedAd.locked(n))continue;
            Hit hit=huya?huya(root,n,nodes,screen):douyu(root,n,nodes,screen);
            if(hit!=null){
                // Disjoint ads may coexist: dismiss the upper one, then let the next scan
                // revalidate the lower card. Never choose between overlapping controls.
                if(best!=null&&!best.target.equals(hit.target)){
                    Rect a=PlayerAd.bounds(best.target),b=PlayerAd.bounds(hit.target);
                    if(Rect.intersects(a,b)){ambiguous=true;continue;}
                    if(b.top>a.top||(b.top==a.top&&b.left>=a.left))continue;
                }
                best=hit;
            }
        }
        return ambiguous?null:best;
    }
    static boolean passiveChatNotice(AccessibilityNodeInfo n,AccessibilityNodeInfo exit){
        if(!id(exit,HUYA,"ad_close")||!id(n,HUYA,"animation_message")||!childType(n,"TextView")
                ||n.isClickable()||n.isLongClickable()||n.isEditable()||n.isPassword()||n.isFocusable()||n.isCheckable()
                ||n.getChildCount()!=0||!label(n).startsWith("系统消息：虎牙依法对直播内容进行")
                ||!id(n.getParent(),HUYA,"topic_container")||!id(up(n,4),HUYA,"channel_chat_browser")
                ||n.getWindowId()!=exit.getWindowId()||Rect.intersects(PlayerAd.bounds(n),PlayerAd.bounds(exit)))return false;
        AccessibilityNodeInfo panel=exit.getParent();
        return panel!=null&&panel.getChildCount()==2&&id(panel.getChild(0),HUYA,"fl_ad_container")&&exit.equals(panel.getChild(1))
                &&id(panel.getParent(),HUYA,"fl_ad_float_container");
    }
    private static Hit huya(AccessibilityNodeInfo root,AccessibilityNodeInfo n,List<AccessibilityNodeInfo> ns,Rect screen){
        if(!"关闭".equals(label(n)))return null;
        AccessibilityNodeInfo row=n.getParent();
        if(!on(root,row,screen)||row.getChildCount()!=3||!n.equals(row.getChild(2)))return null;
        AccessibilityNodeInfo time=row.getChild(0);if(!on(root,time,screen)||!SECONDS.matcher(label(time)).matches())return null;
        Rect r=PlayerAd.bounds(n),rt=PlayerAd.bounds(time),bar=PlayerAd.bounds(row);
        if(!screen.contains(bar)||!bar.contains(r)||!bar.contains(rt)||rt.right>r.left||Math.abs(r.centerY()-rt.centerY())>r.height()/3
                ||r.width()<12||r.height()<12||bar.width()>screen.width()*.35||bar.height()>screen.height()*.12)return null;
        // The portrait SDK exposes a clickable timer pill; the landscape SDK only exposes
        // its text leaf and a much larger clickable ad. Never promote that card to the target.
        boolean portrait=id(n,HUYA,"view_iv_close")&&id(up(n,2),HUYA,"view_ad_timer");
        AccessibilityNodeInfo panel=portrait?up(n,3):up(n,2);
        if(!on(root,panel,screen)||PlayerAd.bounds(panel).width()>screen.width()*.70)return null;
        boolean badge=false,media=false;
        for(AccessibilityNodeInfo c:ns){
            if(!on(root,c,screen)||!subtree(panel,c))continue;
            if("广告".equals(label(c))&&(id(c,HUYA,"lz_ad_text")||id(c,HUYA,"view_ad_video_logo")))badge=true;
            if("主播优选".equals(label(c))||id(c,HUYA,"base_video_view_player_container"))media=true;
        }
        if(!badge||!portrait&&!media)return null;
        AccessibilityNodeInfo button=portrait?up(n,2):n;
        if(!button.isEnabled())return null;
        return button.isClickable()&&SplashSkipper.safeClickTarget(button)?new Hit(button,false):
                !portrait&&!n.isClickable()?new Hit(n,true):null;
    }
    private static Hit douyu(AccessibilityNodeInfo root,AccessibilityNodeInfo n,List<AccessibilityNodeInfo> ns,Rect screen){
        if(!childType(n,"ImageView")||!n.isClickable()||!label(n).isEmpty())return null;
        AccessibilityNodeInfo panel=n.getParent();if(!on(root,panel,screen))return null;
        Rect b=PlayerAd.bounds(n),p=PlayerAd.bounds(panel);
        if(!screen.contains(p)||!p.contains(b)||b.width()<12||b.height()<12||b.width()>screen.width()*.12||b.height()>screen.height()*.08)return null;
        boolean floating=id(panel,DOUYU,"xhdamucard_widget_placeHolder")&&id(n,DOUYU,"bx5");
        boolean banner=(id(n,DOUYU,"el2")||id(n,DOUYU,"e6i"))&&p.width()>.70*screen.width()&&p.height()<.18*screen.height();
        if(!floating&&!banner){
            if(id(n,DOUYU,"a59")&&panel.getChildCount()==2&&n.equals(panel.getChild(1))&&id(panel.getChild(0),DOUYU,"a5b")
                    &&id(up(n,2),DOUYU,"ofa")&&id(up(n,5),DOUYU,"a_func_container")&&p.left>screen.centerX()&&p.top>screen.centerY()
                    &&Math.abs(p.right-b.right)<4&&Math.abs(p.top-b.top)<4){
                for(AccessibilityNodeInfo c:ns)if(on(root,c,screen)&&id(c,DOUYU,"a5f")&&childType(c,"ViewPager")
                        &&subtree(panel.getChild(0),c))return new Hit(n,false);
            }
            return taggedCard(root,n,panel,ns,screen,b);
        }
        if(n.equals(panel.getChild(panel.getChildCount()-1))&&b.right>=p.right-b.width()/2){
            boolean cta=false,rec=false;
            for(AccessibilityNodeInfo c:ns){
                if(!on(root,c,screen)||!subtree(panel,c))continue;
                String text=label(c);
                rec|="推荐".equals(text);
                Rect action=PlayerAd.bounds(c);
                cta|=p.contains(action)&&!Rect.intersects(action,b)&&action.left<b.left
                        &&((id(c,DOUYU,"bwy")&&(floating?"免费下载".equals(text):"去看看".equals(text)))
                        ||(banner&&id(n,DOUYU,"e6i")&&id(c,DOUYU,"bu2")&&"立即购买".equals(text)
                        &&action.right==b.left));
            }
            if(cta&&(!floating||rec)&&SplashSkipper.safeClickTarget(n))return new Hit(n,false);
        }
        return null;
    }
    /** The player overlays its close glyph on the corner of a separately marked ad.
     * IDs vary between versions; require a measured sibling relationship and proximity. */
    private static Hit taggedCard(AccessibilityNodeInfo root,AccessibilityNodeInfo glyph,AccessibilityNodeInfo panel,
                                  List<AccessibilityNodeInfo> nodes,Rect screen,Rect b){
        if(glyph.getViewIdResourceName()==null||!glyph.getViewIdResourceName().startsWith(DOUYU+":id/")
                ||Math.abs(b.width()-b.height())>b.width()/3||b.right<screen.left+screen.width()*.80
                ||b.top<screen.centerY()||b.width()>screen.width()*.065)return null;
        for(AccessibilityNodeInfo badge:nodes){
            if(!on(root,badge,screen)||!"广告".equals(label(badge))||badge.isClickable()||!childType(badge,"TextView"))continue;
            AccessibilityNodeInfo ad=badge.getParent(),holder=up(badge,2);
            if(!on(root,ad,screen)||!ad.isClickable()||!childType(ad,"FrameLayout")||holder==null||!panel.equals(holder.getParent()))continue;
            Rect card=PlayerAd.bounds(ad);if(!screen.contains(card)||card.width()>screen.width()*.36||card.height()>screen.height()*.26
                    ||!card.contains(PlayerAd.bounds(badge))||Math.abs(b.centerX()-card.right)>b.width()*1.4
                    ||Math.abs(b.centerY()-card.top)>b.height()*2)continue;
            boolean iconBefore=false;
            for(int i=0;i<panel.getChildCount();i++){
                AccessibilityNodeInfo c=panel.getChild(i);
                if(glyph.equals(c))iconBefore=true;
                if(holder.equals(c)&&iconBefore)return new Hit(glyph,false);
            }
        }
        return null;
    }

}
