package app.clearscreen;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;
import org.json.*;
import java.util.*;

/** Opt-in launch-ad assistance. No coordinates, generic close buttons, or permission clicks. */
final class SplashSkipper {
    static final long WINDOW_MS=12000;
    private final CleanerService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Store store;
    private final SplashRules rules;
    private String foreground="",launcher="";
    private String activity="";
    private long deadline;
    private boolean queued,attempted,closed;
    SplashSkipper(CleanerService service){
        this.service=service;store=new Store(service);rules=new SplashRules(service);
        try{android.content.pm.ResolveInfo info=service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);if(info!=null&&info.activityInfo!=null)launcher=info.activityInfo.packageName;}catch(Exception ignored){}
    }
    void close(){closed=true;main.removeCallbacksAndMessages(null);}
    void event(AccessibilityEvent event){
        if(closed||!store.splashEnabled()||service.busy){deadline=0;return;}
        String pkg=String.valueOf(event.getPackageName());
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED&&pkg.equals(foreground)){deadline=0;return;}
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED&&pkg.equals(foreground)){
            AccessibilityNodeInfo source=event.getSource();if(source!=null&&source.isEditable()){deadline=0;return;}
        }
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root==null||!pkg.equals(String.valueOf(root.getPackageName())))return;
            if(pkg.equals("com.android.systemui")||pkg.contains("permissioncontroller")||pkg.contains("packageinstaller")){deadline=0;return;}
            if(!pkg.equals(foreground)){
                foreground=pkg;activity="";attempted=false;
                deadline=excluded(pkg)||pkg.equals(launcher)?0:SystemClock.elapsedRealtime()+WINDOW_MS;
            }
            String windowClass=String.valueOf(event.getClassName());if(!windowClass.startsWith("android.")&&!windowClass.startsWith("androidx."))activity=windowClass;
        }
        schedule();
    }
    private boolean excluded(String pkg){return pkg.equals(service.getPackageName())||pkg.equals("com.android.settings")||pkg.startsWith("com.android.launcher")||store.splashExcluded().contains(pkg);}
    private void schedule(){if(!queued&&!attempted&&deadline>SystemClock.elapsedRealtime()){queued=true;main.postDelayed(this::scan,200);}}
    private void scan(){
        queued=false;
        if(closed||attempted||service.busy||!store.splashEnabled()||deadline<=SystemClock.elapsedRealtime()||excluded(foreground))return;
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);if(keyguard!=null&&keyguard.isKeyguardLocked()){deadline=0;return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null){schedule();return;}
        if(!foreground.equals(String.valueOf(root.getPackageName()))){deadline=0;return;}
        AccessibilityNodeInfo target=match(root,rules,activity);
        if(target==null){schedule();return;}
        attempted=true;deadline=0;String pkg=foreground;
        boolean clicked=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(!clicked){record(pkg,false);return;}
        main.postDelayed(()->verify(pkg,0),350);
    }
    private void verify(String pkg,int retry){
        if(closed)return;AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean confirmed=root!=null&&pkg.equals(String.valueOf(root.getPackageName()))&&match(root,rules,activity)==null;
        if(!confirmed&&retry==0){main.postDelayed(()->verify(pkg,1),500);return;}
        record(pkg,confirmed);
    }
    private void record(String pkg,boolean confirmed){
        try{store.recordSplash(pkg,confirmed);}catch(RuntimeException ignored){/* History failure never retries a screen click. */}
    }
    static boolean skipLabel(String value){
        String label=value.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
        return label.matches("(跳过|跳過|skip)(广告|廣告|ad)?([（(]?[0-9]{1,2}(s|秒)?[）)]?)?")
                ||label.matches("[0-9]{1,2}(s|秒)?(跳过|跳過|skip)");
    }
    static boolean explicitAd(String value){return value.matches("(?i).*(广告|廣告|\\bad\\b).*" )||value.toLowerCase(Locale.ROOT).replace(" ","").startsWith("skipad");}
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root){
        return match(root,SplashRules.EMPTY,"");
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root,SplashRules rules,String activity){
        Rect screen=new Rect();root.getBoundsInScreen(screen);if(screen.width()<100||screen.height()<100)return null;
        List<AccessibilityNodeInfo> nodes=UiDriver.walk(root);boolean ad=false;
        for(AccessibilityNodeInfo node:nodes){if(!node.isVisibleToUser())continue;String text=UiDriver.text(node);
            if(node.isEditable())return null;
            if(node.isClickable()&&text.matches("同意|同意并继续|同意并使用|允许|接受|确认支付|立即授权"))return null;
            if(text.matches(".*(观看广告领取|觀看廣告領取|观看视频领奖|看视频领奖|观看完整视频|獎勵將|奖励将).*") )return null;
            ad|=text.matches("(?i)广告|廣告|ad|advertisement");
        }
        Set<AccessibilityNodeInfo> hits=new HashSet<>();
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser()||!node.isEnabled()||node.isCheckable())continue;
            boolean known=rules.matches(String.valueOf(root.getPackageName()),activity,node);
            if(!known&&!skipLabel(UiDriver.text(node)))continue;
            boolean explicit=explicitAd(UiDriver.text(node));if(!known&&!explicit&&!ad)continue;
            AccessibilityNodeInfo click=node;
            for(int i=0;i<2&&!click.isClickable();i++){AccessibilityNodeInfo parent=click.getParent();if(parent==null)break;click=parent;}
            Rect bounds=new Rect();click.getBoundsInScreen(bounds);
            boolean horizontal=bounds.left>=screen.left+screen.width()*.5||(known&&bounds.right<=screen.left+screen.width()*.5);
            boolean corner=horizontal&&(bounds.bottom<=screen.top+screen.height()*.27||((explicit||known)&&bounds.top>=screen.top+screen.height()*.75));
            if(!click.isClickable()||!click.isEnabled()||!click.isVisibleToUser()||click.isCheckable()||!screen.contains(bounds)||!corner
                    ||bounds.width()>screen.width()*.48||bounds.height()>screen.height()*.16||bounds.width()<12||bounds.height()<12)continue;
            int actionable=0;boolean unsafe=false;
            for(AccessibilityNodeInfo child:UiDriver.walk(click)){
                if(child.isVisibleToUser()&&child.isClickable())actionable++;
                if(child.isEditable()||child.isCheckable())unsafe=true;
            }
            if(!unsafe&&actionable==1)hits.add(click);
        }
        return hits.size()==1?hits.iterator().next():null;
    }
}
