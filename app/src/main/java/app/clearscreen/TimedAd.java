package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A countdown is evidence to keep observing an advertisement, never a command to click. */
final class TimedAd {
    private TimedAd() { }
    private static final String NUM="([0-9]{1,2})";
    private static final String UNIT="(?:秒(?:钟|鐘)?|s(?:ec(?:ond)?s?)?)";
    private static final String EXIT="(?:跳过|跳過|关闭|關閉|继续(?:上滑)?观看(?:短剧)?|繼續(?:上滑)?觀看(?:短劇)?)";
    private static final Pattern LOCKED=Pattern.compile("(?:(?:广告|廣告)[·:|,]?)?"+NUM+UNIT+"[后後](?:才可|可|可以)?"+EXIT+"(?:此?(?:广告|廣告))?|(?:skip|close)(?:this)?(?:ads?)?in"+NUM+UNIT);
    private static final Pattern TIMER=Pattern.compile("(?:(?:广告|廣告|倒计时|倒計時|剩余|剩餘|还剩|還剩)[·:|,]?)?"+NUM+UNIT+"(?:[·:|,]?(?:跳过|跳過|关闭|關閉|skip|close))?|(?:跳过|跳過|关闭|關閉|skip|close)(?:广告|廣告|ad)?[·:|,(]?"+NUM+UNIT+"\\)?");
    private static String normalized(CharSequence v){
        return v==null||v.length()>80?"":Normalizer.normalize(v,Normalizer.Form.NFKC)
                .replace(" ","").replace("\u00a0","").toLowerCase(Locale.ROOT).trim();
    }
    static boolean locked(CharSequence v){return v!=null && LOCKED.matcher(normalized(v)).matches();}
    static boolean locked(AccessibilityNodeInfo n){return n!=null&&(locked(n.getText())||locked(n.getContentDescription()));}
    static boolean lockedControl(AccessibilityNodeInfo n){
        if(locked(n))return true;
        if(n==null||!n.isClickable()||n.getChildCount()==0||n.getChildCount()>6)return false;
        // A close button can contain an icon AND a locked caption. Check the small
        // button subtree, not its ad panel: an independent true exit must remain usable.
        Rect button=new Rect();n.getBoundsInScreen(button);
        for(int i=0;i<n.getChildCount();i++){
            AccessibilityNodeInfo child=n.getChild(i);
            if(child==null||child.isClickable()||!child.isVisibleToUser()||!inside(n,child,button))continue;
            if(locked(child))return true;
            if(child.getChildCount()==1){
                AccessibilityNodeInfo caption=child.getChild(0);
                if(caption!=null&&!caption.isClickable()&&caption.isVisibleToUser()&&inside(n,caption,button)&&locked(caption))return true;
            }
        }
        return false;
    }
    private static boolean inside(AccessibilityNodeInfo button,AccessibilityNodeInfo part,Rect bounds){
        if(button.getWindowId()!=part.getWindowId()||!android.text.TextUtils.equals(button.getPackageName(),part.getPackageName()))return false;
        Rect r=new Rect();part.getBoundsInScreen(r);return !r.isEmpty()&&bounds.contains(r);
    }
    static int seconds(CharSequence v){
        String s=normalized(v);if(s.isEmpty())return -1;
        Matcher m=LOCKED.matcher(s);
        if(!m.matches()){m=TIMER.matcher(s);if(!m.matches())return -1;}
        for(int i=1;i<=m.groupCount();i++)if(m.group(i)!=null)return Integer.parseInt(m.group(i));
        return -1;
    }
    static boolean tagged(AccessibilityNodeInfo n){
        return SplashSkipper.adMarker(SplashSkipper.label(n.getText()))||SplashSkipper.adMarker(SplashSkipper.label(n.getContentDescription()));
    }
    static boolean onPage(AccessibilityNodeInfo root,AccessibilityNodeInfo n,Rect viewport){
        if(!n.isVisibleToUser()||n.getWindowId()!=root.getWindowId()
                ||!android.text.TextUtils.equals(root.getPackageName(),n.getPackageName()))return false;
        Rect r=new Rect();n.getBoundsInScreen(r);return Rect.intersects(viewport,r);
    }
    static final class Evidence {
        final AccessibilityNodeInfo root,badge; final int seconds;
        Evidence(AccessibilityNodeInfo r,AccessibilityNodeInfo b,int s){root=r;badge=b;seconds=s;}
        boolean samePage(Evidence other){return other!=null&&root.equals(other.root)&&badge.equals(other.badge);}
    }
    static Evidence inspect(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect viewport){
        AccessibilityNodeInfo badge=null;int seconds=-1;
        for(AccessibilityNodeInfo n:nodes){
            if(!onPage(root,n,viewport))continue;
            if(n.isPassword()||n.isEditable()||CustomSkipRule.sensitiveText(SplashSkipper.label(n.getText()))||CustomSkipRule.sensitiveText(SplashSkipper.label(n.getContentDescription()))||SplashSkipper.unsafeWindowNode(n,true))return null;
            if(badge==null&&!n.isClickable()&&tagged(n))badge=n;
            seconds=Math.max(seconds,Math.max(seconds(n.getText()),seconds(n.getContentDescription())));
        }
        return badge==null?null:new Evidence(root,badge,seconds);
    }
    /** Only a direct, enabled exit in the corner of a full-page ad, never a host list item. */
    static boolean fullPageExit(AccessibilityNodeInfo root,AccessibilityNodeInfo target,Rect display,Evidence evidence){
        if(evidence==null || !root.equals(evidence.root)||!target.isClickable()||!target.isEnabled()||locked(target)
                ||target.isCheckable()||!onPage(root,target,display)||!SplashSkipper.safeClickTarget(target))return false;
        Rect page=new Rect(),button=new Rect();root.getBoundsInScreen(page);target.getBoundsInScreen(button);
        if(!page.intersect(display)||page.width()<display.width()*.8||page.height()<display.height()*.75
                ||!page.contains(button)||button.width()<12||button.height()<12
                ||button.width()>page.width()*.43||button.height()>page.height()*.17)return false;
        boolean corner=(button.right<=page.left+page.width()*.45||button.left>=page.left+page.width()*.55)
                &&(button.bottom<=page.top+page.height()*.29||button.top>=page.top+page.height()*.71);
        if(!corner)return false;
        return standaloneBranch(target,root)&&standaloneBranch(evidence.badge,root);
    }
    private static boolean standaloneBranch(AccessibilityNodeInfo n,AccessibilityNodeInfo root){
        for(int depth=0;n!=null&&depth<64;n=n.getParent(),depth++){
            String cls=String.valueOf(n.getClassName());
            if(n.isScrollable()||n.getCollectionInfo()!=null||n.getCollectionItemInfo()!=null
                    ||cls.endsWith("RecyclerView")||cls.endsWith("ListView"))return false;
            if(n.equals(root))return true;
        }
        return false;
    }
    /** Scoped polling deadline. A static 15s label must not start a fresh 15s timer each scan. */
    static final class Watch {
        private Evidence page;private long until;
        void clear(){page=null;until=0;}
        boolean matches(Evidence e,long now){return e!=null && e.samePage(page) && now<=until;}
        boolean sameWindow(int id){return page!=null&&page.root.getWindowId()==id;}
        long observe(Evidence e,long now){
            if(e==null||!e.samePage(page)){clear();if(e!=null&&e.seconds>=0){page=e;until=now+(e.seconds+3L)*1000;}}
            return matches(e,now)?until:0;
        }
    }
}
