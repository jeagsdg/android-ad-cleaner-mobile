package app.clearscreen;

import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.Locale;

/** Local evidence that an exit belongs to the advertisement rather than adjacent app content. */
final class AdSurface {
    private AdSurface() {}

    // Only accept identifiers from the app/SDK, never user-facing content containing these words.
    private static String id(AccessibilityNodeInfo n) {
        String value=n.getViewIdResourceName();
        int i=value==null?-1:value.indexOf(":id/");
        return i<0?"":value.substring(i+4).toLowerCase(Locale.ROOT);
    }
    private static boolean sameSurface(AccessibilityNodeInfo a,AccessibilityNodeInfo b) {
        return b!=null&&b.isVisibleToUser()&&a.getWindowId()==b.getWindowId()
                &&TextUtils.equals(a.getPackageName(),b.getPackageName());
    }
    private static boolean list(AccessibilityNodeInfo n){
        String cls=String.valueOf(n.getClassName());
        return n.isScrollable()||n.getCollectionInfo()!=null||n.getCollectionItemInfo()!=null
                ||cls.endsWith("RecyclerView")||cls.endsWith("ListView");
    }
    private static boolean adLayer(AccessibilityNodeInfo n,Rect screen){
        String id=id(n);
        if(!(id.equals("ad_container")||id.equals("splash_ad_container")||id.equals("main_splash_ad")
                ||id.equals("splash_ad_layout")||id.equals("splash_ad_root")))return false;
        Rect b=new Rect();n.getBoundsInScreen(b);
        return b.intersect(screen)&&b.width()>=screen.width()*.70&&b.height()>=screen.height()*.55;
    }
    /** A countdown label inside an ad layer, or the SDK's exit immediately beside that layer. */
    static boolean launchControl(AccessibilityNodeInfo root,AccessibilityNodeInfo label,Rect screen){
        AccessibilityNodeInfo button=SplashSkipper.clickable(label);
        if(!sameSurface(root,button)||!sameSurface(root,label)||TimedAd.lockedControl(button))return false;
        boolean insideAd=false;
        AccessibilityNodeInfo n=label;
        for(int depth=0;depth<64&&n!=null;depth++,n=n.getParent()){
            if(!sameSurface(root,n)||list(n))return false;
            if(adLayer(n,screen))insideAd=true;
            if(n.equals(root))break;
        }
        if(!root.equals(n))return false;
        if(insideAd)return true;
        String bid=id(button);
        if(!bid.equals("skip_button")&&!bid.equals("splash_skip")&&!bid.endsWith("_splash_skip_area"))return false;
        AccessibilityNodeInfo p=button.getParent();
        if(!sameSurface(root,p))return false;
        for(int i=0;i<p.getChildCount();i++){
            AccessibilityNodeInfo sibling=p.getChild(i);
            if(sameSurface(root,sibling)&&adLayer(sibling,screen))return true;
        }
        return false;
    }
    static boolean labelBelongsToButton(AccessibilityNodeInfo label,AccessibilityNodeInfo button){
        if(label.equals(button))return true;
        if(!button.equals(label.getParent())||!sameSurface(button,label)||list(button))return false;
        Rect inner=new Rect(),outer=new Rect();label.getBoundsInScreen(inner);button.getBoundsInScreen(outer);
        return outer.contains(inner)&&inner.width()>=outer.width()*.32&&inner.height()>=outer.height()*.20;
    }

    static final class Exit {
        final AccessibilityNodeInfo button;
        final String action,context;
        Exit(AccessibilityNodeInfo button,String action,String context){this.button=button;this.action=action;this.context=context;}
    }
    static Exit menuExit(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect screen){
        Exit hit=null;
        for(AccessibilityNodeInfo n:nodes){
            if(!TimedAd.onPage(root,n,screen))continue;
            if(n.isPassword()||n.isEditable()||CustomSkipRule.sensitiveText(SplashSkipper.label(n.getText()))
                    ||CustomSkipRule.sensitiveText(SplashSkipper.label(n.getContentDescription())))return null;
            if(!n.isClickable()||!n.isEnabled()||TimedAd.lockedControl(n)||!SplashSkipper.safeActionLabel(n))continue;
            String action=SplashSkipper.label(n.getText());
            if(!SplashSkipper.explicitAd(action))action=SplashSkipper.label(n.getContentDescription());
            if(!SplashSkipper.explicitAd(action)||action.length()>48||!SplashSkipper.safeClickTarget(n))continue;
            Rect b=new Rect();n.getBoundsInScreen(b);
            if(!screen.contains(b)||b.height()<12||b.width()<12||b.height()>screen.height()*.15)continue;
            String context=reportBeside(root,n,b,screen);
            if(context==null)continue;
            if(hit!=null&&!hit.button.equals(n))return null;
            hit=new Exit(n,action,context);
        }
        return hit;
    }
    static boolean reportText(String text){
        return text.equals("举报广告")||text.equals("舉報廣告")||text.equalsIgnoreCase("Report ad")
                ||text.equals("举报")||text.equals("舉報");
    }
    private static String reportBeside(AccessibilityNodeInfo root,AccessibilityNodeInfo close,Rect button,Rect screen){
        AccessibilityNodeInfo p=close.getParent();
        // Android can omit non-interactive layout containers: a sheet's action rows then
        // appear as direct siblings in the full-screen content view. Recover only the
        // geometry of a bottom action sheet, not arbitrary labels anywhere in that window.
        if(sameSurface(root,p)&&flattenedMenu(root,p,close,button,screen))return "举报广告";
        for(int depth=0;depth<32&&sameSurface(root,p);depth++,p=p.getParent()){
            Rect bounds=new Rect();p.getBoundsInScreen(bounds);
            if(bounds.height()>screen.height()*.47)break;
            if(!bounds.contains(button)||!screen.contains(bounds))continue;
            boolean contextualId=id(close).equals("close_ad");
            for(AccessibilityNodeInfo n:UiDriver.walkComplete(p)){
                if(!sameSurface(root,n)||n.equals(close))continue;
                String text=SplashSkipper.label(n.getText());
                if(text.isEmpty())text=SplashSkipper.label(n.getContentDescription());
                boolean report=text.equals("举报广告")||text.equals("舉報廣告")||text.equalsIgnoreCase("Report ad")
                        ||(contextualId&&(text.equals("举报")||text.equals("舉報"))&&id(n).contains("ad"));
                if(!report)continue;
                Rect b=new Rect();n.getBoundsInScreen(b);
                if(!b.isEmpty()&&bounds.contains(b)&&!Rect.intersects(button,b))return text;
            }
            if(p.equals(root))break;
        }
        return null;
    }
    private static boolean flattenedMenu(AccessibilityNodeInfo root,AccessibilityNodeInfo parent,
                                         AccessibilityNodeInfo close,Rect button,Rect screen){
        if(list(parent)||button.width()<screen.width()*.75||button.top<screen.top+screen.height()*.55
                ||button.height()>screen.height()*.12)return false;
        boolean report=false,cancel=false;
        int tolerance=Math.max(4,button.height()/8);
        for(int i=0;i<parent.getChildCount();i++){
            AccessibilityNodeInfo row=parent.getChild(i);
            if(row==null||row.equals(close)||!sameSurface(root,row)||!row.isEnabled()||!row.isClickable())continue;
            Rect b=new Rect();row.getBoundsInScreen(b);
            if(!screen.contains(b)||Rect.intersects(b,button)||Math.abs(b.left-button.left)>tolerance
                    ||Math.abs(b.right-button.right)>tolerance||Math.abs(b.height()-button.height())>tolerance)continue;
            String label=SplashSkipper.label(row.getText());
            if(label.isEmpty())label=SplashSkipper.label(row.getContentDescription());
            int steps=Math.round((float)(b.top-button.top)/button.height());
            if(Math.abs(steps)>2||Math.abs((b.top-button.top)-steps*button.height())>tolerance)continue;
            if(label.equals("举报广告")||label.equals("舉報廣告")||label.equalsIgnoreCase("report ad"))report=true;
            if(label.equals("取消")||label.equalsIgnoreCase("cancel"))cancel=true;
        }
        return report&&cancel;
    }

}
