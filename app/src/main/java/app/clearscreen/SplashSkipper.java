package app.clearscreen;

import android.app.KeyguardManager;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.*;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import android.view.accessibility.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Opt-in launch and in-app dismissal through verified accessibility controls. */
final class SplashSkipper {
    static final long WINDOW_MS=12000;
    private static final long QUICK_SCAN_MS=65;
    private static final long POPUP_COOLDOWN_MS=750;
    private static final long MEDIA_COOLDOWN_MS=1500;
    private static final Pattern SPACE=Pattern.compile("\\s+");
    private static final Pattern CONSENT=Pattern.compile(
            "同意.*|允[许許].*|接受.*|确认支付|確認支付|立即授[权權]|allow(?: .*)?|i agree",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SKIP=Pattern.compile(
            "(?:跳过(?:此)?广告|跳過(?:此)?廣告|略过广告|略過廣告|skip(?:this)?ads?|skipadvertisement|広告をスキップ|광고건너뛰기)");
    private static final String SKIP_WORD="(?:(?:点击|點擊|点此|點此)?(?:跳过|跳過)|略过|略過|skip|スキップ|건너뛰기)";
    private static final String COUNTDOWN="(?:[0-9]{1,2}(?:s|秒|초)?)";
    private static final Pattern SKIP_LABEL=Pattern.compile(
            "(?:"+EXPLICIT_SKIP.pattern()+"|"+SKIP_WORD+")(?:\\("+COUNTDOWN+"\\)|[·:|]?"+COUNTDOWN+")?"
                    +"|"+COUNTDOWN+"[·:|]?"+SKIP_WORD);
    private static final Pattern AD_MARKER=Pattern.compile("广告|廣告|推广|推廣|ad|advertisement|広告|광고");
    private static final Pattern REWARDED_AD=Pattern.compile(
            "观看广告领取|觀看廣告領取|看广告得奖励|看廣告得獎勵|观看视频领奖|看视频领奖|观看完整视频|獎勵將|奖励将"
                    +"|watch.*(?:earn|reward)|reward\\s+in|rewarded\\s+ad",Pattern.CASE_INSENSITIVE);
    private final CleanerService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable scheduledScan=this::scan;
    private final Store store;
    private final SplashRules rules;
    private final PopupAdRules popupAds;
    private String foreground="",launcher="";
    private String activity="";
    private long deadline, foregroundVisit, nextPopupClick, nextGenericProbe, popupDue, userInteraction;
    private long scanAt=Long.MAX_VALUE, nextSplashProbe, lastSplashProbe;
    private long activitySince;
    private boolean attempted,closed,popupPending,popupVerifying,splashWaiting,splashVerifying,mediaPending;
    private Runnable delayedSplash;
    private Runnable pendingWindow;
    private String pendingPackage="";
    private String pendingClass="";
    private int pendingWindowId=-1;
    private int popupRetries;
    private int popupStep;
    private int activeWindow=-1;
    private long nextMediaProbe;
    private final Set<CustomSkipRule> attemptedPopups=new HashSet<>();
    private final Map<CustomSkipRule,Integer> confirmedPopups=new HashMap<>();
    private final Set<Integer> genericPopupWindows=new HashSet<>();
    private final Map<PopupAdRules.Rule,Integer> catalogAttempts=new HashMap<>();
    private final Set<PopupAdRules.Rule> catalogUnconfirmed=new HashSet<>();
    private List<CustomSkipRule> popupRuleSnapshot=Collections.emptyList();
    SplashSkipper(CleanerService service){
        this.service=service;store=new Store(service);rules=new SplashRules(service);popupAds=new PopupAdRules(service);
        try{android.content.pm.ResolveInfo info=service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);if(info!=null&&info.activityInfo!=null)launcher=info.activityInfo.packageName;}catch(Exception ignored){}
    }
    void close(){closed=true;main.removeCallbacksAndMessages(null);}
    void event(AccessibilityEvent event){
        Set<String> media=store.mediaAssistEnabled();
        if(closed||(!store.splashEnabled()&&!store.popupEnabled()&&media.isEmpty())||service.busy){
            cancelWindowRetry();stopCurrentInput();foreground="";return;
        }
        if(event.getPackageName()==null)return;
        String pkg=event.getPackageName().toString();
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_CLICKED&&pkg.equals(foreground)){
            stopCurrentInput();return;
        }
        if(event.getEventType()==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED&&pkg.equals(foreground)){
            AccessibilityNodeInfo source=event.getSource();if(source!=null&&source.isEditable()){stopCurrentInput();return;}
        }
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            cancelWindowRetry();
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            String windowClass=String.valueOf(event.getClassName());
            if(!matchesWindow(root,pkg,event.getWindowId())){
                // Some launch transitions report an intermediate window. The verified
                // active package can arm splash detection, while page rules wait for its class.
                if(belongsTo(root,pkg)&&!pkg.equals(foreground)){
                    acceptWindow(pkg,"",root.getWindowId(),true);
                    if(pkg.equals(foreground))queueWindowContent(media);
                }
                retryWindow(pkg,windowClass,event.getWindowId(),5);
                schedule();
                return;
            }
            acceptWindow(pkg,windowClass,root.getWindowId(),true);
        }else if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                &&event.getWindowId()>=0&&(!pkg.equals(foreground)||activeWindow!=event.getWindowId())){
            // Confirm an actual window transition from the active root. A service rebind
            // without a preceding launch signal must not arm splash from ordinary content.
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(matchesWindow(root,pkg,event.getWindowId())){
                boolean pendingForApp=pkg.equals(pendingPackage);
                boolean sameStateWindow=pendingForApp&&pendingWindowId==event.getWindowId();
                String windowClass=sameStateWindow?pendingClass:"";
                boolean launchEvent=pendingForApp||!foreground.isEmpty();
                if(pendingForApp||!pkg.equals(foreground))cancelWindowRetry();
                acceptWindow(pkg,windowClass,root.getWindowId(),launchEvent);
            }
        }
        if(pkg.equals(foreground)&&(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)){
            if(scanSplash()){
                int changes=event.getContentChangeTypes();
                if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        ||(changes&(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE|AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                        |AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION))!=0){
                    nextSplashProbe=Math.min(nextSplashProbe,lastSplashProbe+QUICK_SCAN_MS);
                }
            }
            queueWindowContent(media);
        }
        schedule();
    }
    private void stopCurrentInput(){
        deadline=0;
        cancelDelayedSplash();
        popupPending=false;
        mediaPending=false;
        userInteraction++;
    }
    private static boolean belongsTo(AccessibilityNodeInfo root,String pkg){
        return root!=null&&pkg.contentEquals(root.getPackageName()==null?"":root.getPackageName());
    }
    private static boolean matchesWindow(AccessibilityNodeInfo root,String pkg,int windowId){
        return belongsTo(root,pkg)&&(windowId<0||root.getWindowId()==windowId);
    }
    private void cancelWindowRetry(){
        if(pendingWindow!=null)main.removeCallbacks(pendingWindow);
        pendingWindow=null;
        pendingPackage="";
        pendingClass="";
        pendingWindowId=-1;
    }
    private void retryWindow(String pkg,String windowClass,int windowId,int attempts){
        pendingPackage=pkg;
        pendingClass=windowClass;
        pendingWindowId=windowId;
        pendingWindow=()->{
            cancelWindowRetry();
            if(closed||service.busy)return;
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(matchesWindow(root,pkg,windowId)){
                acceptWindow(pkg,windowClass,root.getWindowId(),true);
                if(pkg.equals(foreground))queueWindowContent(store.mediaAssistEnabled());
                schedule();
            }else if(attempts>1)retryWindow(pkg,windowClass,windowId,attempts-1);
        };
        main.postDelayed(pendingWindow,75);
    }
    private void acceptWindow(String pkg,String windowClass,int windowId,boolean launchEvent){
        if(transientSystem(pkg)){
            stopCurrentInput();
            return;
        }
        if(!pkg.equals(foreground)){
            foreground=pkg;
            activity="";
            activitySince=SystemClock.elapsedRealtime();
            attempted=false;
            foregroundVisit++;
            stopCurrentInput();
            resetPopupProgress();
            nextSplashProbe=0;
            lastSplashProbe=0;
            nextPopupClick=0;
            nextGenericProbe=0;
            splashVerifying=false;
            popupVerifying=false;
            popupRetries=0;
            if(launchEvent&&store.splashEnabled()&&!CustomSkipRule.protectedPackage(pkg)
                    &&!excluded(pkg)&&!store.skipExcluded(CustomSkipRule.SPLASH).contains(pkg)){
                deadline=SystemClock.elapsedRealtime()+WINDOW_MS;
            }
        }
        if(!windowClass.equals(activity)&&windowClass.contains(".")&&!windowClass.startsWith("android.")
                &&!windowClass.startsWith("androidx."))rememberActivity(pkg,windowClass);
        if(activeWindow!=windowId){
            activeWindow=windowId;
            nextGenericProbe=0;
        }
    }
    private static boolean transientSystem(String pkg){
        return pkg.equals("com.android.systemui")||pkg.contains("permissioncontroller")||pkg.contains("packageinstaller");
    }
    private void queueWindowContent(Set<String> media){
        if(store.popupEnabled()){
            List<CustomSkipRule> available=popupRules();
            boolean catalog=catalogPopupAllowed();
            popupPending=!available.isEmpty()||catalog||genericPopupAllowed();
            popupDue=available.isEmpty()&&!catalog?nextGenericProbe:0;
            popupRetries=available.stream().mapToInt(rule->rule.searches).max().orElse(1)-1;
        }
        mediaPending=!media.isEmpty()&&(foreground.equals("com.tencent.mm")||foreground.equals("com.tencent.mobileqq"));
    }
    private void rememberActivity(String pkg,String windowClass){
        try{
            if(windowClass.startsWith("."))windowClass=pkg+windowClass;
            service.getPackageManager().getActivityInfo(new ComponentName(pkg,windowClass),0);
            if(!windowClass.equals(activity))activitySince=SystemClock.elapsedRealtime();
            activity=windowClass;
        }catch(android.content.pm.PackageManager.NameNotFoundException|SecurityException ignored){/* Dialog classes are not activities. */}
    }
    private boolean excluded(String pkg){return pkg.equals(service.getPackageName())||pkg.equals(launcher)||pkg.equals("com.android.settings")||pkg.startsWith("com.android.launcher")||store.splashExcluded().contains(pkg);}
    private List<CustomSkipRule> customRules(){
        try{return store.customSkipRules();}catch(IllegalArgumentException e){return Collections.emptyList();}
    }
    private List<CustomSkipRule> popupRules(){
        if(CustomSkipRule.protectedPackage(foreground)||excluded(foreground)
                ||store.skipExcluded(CustomSkipRule.POPUP).contains(foreground))return Collections.emptyList();
        List<CustomSkipRule> result=new ArrayList<>();
        List<CustomSkipRule> custom=customRules();
        if(custom!=popupRuleSnapshot){popupRuleSnapshot=custom;resetPopupProgress();}
        boolean customized=false;
        for(CustomSkipRule rule:custom)if(rule.appliesTo(foreground,activity,CustomSkipRule.POPUP)){
            customized=true;
            if((rule.sequenceIndex<0||rule.sequenceIndex==popupStep)
                    &&(!rule.legacy||rule.times==0||confirmedPopups.getOrDefault(rule,0)<rule.times))result.add(rule);
        }
        if(!customized)for(CustomSkipRule rule:PopupMatch.APP_PRESETS)
            if(rule.appliesTo(foreground,activity,CustomSkipRule.POPUP)
                    &&confirmedPopups.getOrDefault(rule,0)<rule.times)result.add(rule);
        return result;
    }
    private boolean genericPopupAllowed(){
        return genericPopupWindows.size()<5&&defaultPopupAllowed();
    }
    private boolean catalogPopupAllowed(){
        return popupAds.hasRules(foreground,activity,activityAge())&&defaultPopupAllowed();
    }
    private long activityAge(){return SystemClock.elapsedRealtime()-activitySince;}
    private boolean defaultPopupAllowed(){
        if(foreground.isEmpty()||CustomSkipRule.protectedPackage(foreground)||excluded(foreground)
                ||store.skipExcluded(CustomSkipRule.POPUP).contains(foreground))return false;
        for(CustomSkipRule rule:customRules())if(rule.pkg.equals(foreground)&&rule.mode.equals(CustomSkipRule.POPUP))return false;
        return true;
    }
    private void resetPopupProgress(){
        attemptedPopups.clear();confirmedPopups.clear();genericPopupWindows.clear();popupStep=0;
        catalogAttempts.clear();
        catalogUnconfirmed.clear();
    }
    private boolean scanSplash(){
        return !attempted&&!splashWaiting&&store.splashEnabled()&&deadline>SystemClock.elapsedRealtime()
                &&!store.skipExcluded(CustomSkipRule.SPLASH).contains(foreground);
    }
    private void schedule(){
        if(closed)return;
        long now=SystemClock.elapsedRealtime(), due=Long.MAX_VALUE;
        if(scanSplash())due=Math.max(now+QUICK_SCAN_MS,nextSplashProbe);
        if(!splashWaiting&&!splashVerifying){
            if(popupPending&&!popupVerifying){
                due=Math.min(due,Math.max(now+QUICK_SCAN_MS,Math.max(popupDue,nextPopupClick)));
            }
            if(mediaPending&&!popupVerifying)due=Math.min(due,Math.max(now+150,nextMediaProbe));
        }
        if(due>=scanAt)return;
        main.removeCallbacks(scheduledScan);
        scanAt=due;
        main.postDelayed(scheduledScan,due-now);
    }
    private void scan(){
        scanAt=Long.MAX_VALUE;
        if(windowTransitionPending()){schedule();return;}
        boolean checkPopup=popupPending&&store.popupEnabled()&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&SystemClock.elapsedRealtime()>=Math.max(popupDue,nextPopupClick);
        if(checkPopup||!store.popupEnabled())popupPending=false;
        boolean checkMedia=mediaPending&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&SystemClock.elapsedRealtime()>=nextMediaProbe;
        if(closed||service.busy||excluded(foreground))return;
        boolean checkSplash=scanSplash()&&SystemClock.elapsedRealtime()>=nextSplashProbe;
        if(!checkSplash&&!checkPopup&&!checkMedia){schedule();return;}
        if(checkSplash){
            long age=launchAge();
            lastSplashProbe=SystemClock.elapsedRealtime();
            nextSplashProbe=lastSplashProbe+(age<800?90:age<3000?175:330);
        }
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);if(keyguard!=null&&keyguard.isKeyguardLocked()){deadline=0;return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null){
            if(checkPopup&&popupRetries>0){popupRetries--;popupPending=true;}
            if(checkMedia)mediaPending=false;
            schedule();return;
        }
        if(!belongsTo(root,foreground)){
            if(scanSplash()&&launchAge()<=1200){schedule();return;}
            deadline=0;
            return;
        }
        if(checkSplash){
            List<CustomSkipRule> custom=customRules();
            String page=activity;
            SplashHit hit=splashHit(root,rules,page,custom,launchAge());
            if(hit!=null){
                popupPending|=checkPopup;
                if(hit.rule!=null&&hit.rule.delayMs>0){
                    splashWaiting=true;
                    long visit=foregroundVisit;
                    long interaction=userInteraction;
                    delayedSplash=()->finishDelayedSplash(visit,interaction,hit);
                    main.postDelayed(delayedSplash,hit.rule.delayMs);
                }else clickSplash(hit);
                return;
            }
        }
        // Window content can change before the corresponding event arrives. Only
        // splash detection may use an intermediate window of the same package.
        if(root.getWindowId()!=activeWindow){
            if(checkMedia)mediaPending=false;
            schedule();
            return;
        }
        if(checkPopup&&scanPopup(root))return;
        if(checkPopup&&popupRetries>0){popupRetries--;popupPending=true;}
        if(checkMedia){
            mediaPending=false;
            AccessibilityNodeInfo mediaTarget=MediaAssist.find(root,activity,store.mediaAssistEnabled());
            if(mediaTarget!=null){
                nextMediaProbe=SystemClock.elapsedRealtime()+MEDIA_COOLDOWN_MS;
                mediaTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
        schedule();
    }
    private void finishDelayedSplash(long visit,long interaction,SplashHit expected){
        splashWaiting=false;
        delayedSplash=null;
        if(!scanSplash()||!ready(visit)||interaction!=userInteraction||excluded(foreground)){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null||!foreground.equals(String.valueOf(root.getPackageName()))){schedule();return;}
        List<CustomSkipRule> custom=customRules();
        SplashHit current=splashHit(root,rules,activity,custom,launchAge());
        if(current!=null&&current.rule==expected.rule&&current.target.equals(expected.target)){
            clickSplash(current);
        }else schedule();
    }
    private void cancelDelayedSplash(){
        if(delayedSplash!=null)main.removeCallbacks(delayedSplash);
        delayedSplash=null;
        splashWaiting=false;
    }
    private long launchAge(){return Math.max(0,WINDOW_MS-(deadline-SystemClock.elapsedRealtime()));}
    private void clickSplash(SplashHit hit){
        GestureDescription gesture=null;
        if(hit.tap){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            SplashHit current=scanSplash()&&pendingWindow==null&&activeWindow>=0
                    &&hit.target.getWindowId()==activeWindow&&matchesWindow(root,foreground,activeWindow)
                    &&ready(foregroundVisit)?splashHit(root,rules,activity,customRules(),launchAge()):null;
            if(current==null||!current.tap||!current.target.equals(hit.target)){schedule();return;}
            hit=current;
            gesture=tapGesture(hit.target);
            if(gesture==null){schedule();return;}
        }
        attempted=true;
        deadline=0;
        String pkg=foreground;
        AccessibilityNodeInfo target=hit.target;
        long visit=foregroundVisit;
        splashVerifying=true;
        Runnable verify=()->main.postDelayed(()->verifySplash(pkg,visit,target,false),350);
        if(gesture!=null){
            boolean sent=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
                @Override public void onCompleted(GestureDescription delivered){if(!closed)verify.run();}
                @Override public void onCancelled(GestureDescription cancelled){finishSplash(pkg,visit,false);}
            },main);
            if(!sent)finishSplash(pkg,visit,false);
        }else if(click(hit.rule,target)){
            verify.run();
        }else{
            finishSplash(pkg,visit,false);
        }
    }
    private boolean scanPopup(AccessibilityNodeInfo root){
        List<CustomSkipRule> available=popupRules();
        Rect display=screen(root);
        if(display.isEmpty())return false;
        PopupMatch hit=available.isEmpty()?null:PopupMatch.find(root,display,available);
        if(hit==null&&catalogPopupAllowed()){
            PopupAdRules.Match catalog=popupAds.find(root,activity,display,activityAge());
            if(catalog!=null&&!catalogUnconfirmed.contains(catalog.rule)
                    &&catalogAttempts.getOrDefault(catalog.rule,0)<catalog.rule.maxPerVisit()){
                popupVerifying=true;
                long visit=foregroundVisit, interaction=userInteraction;
                int window=root.getWindowId();
                main.postDelayed(()->finishCatalogPopup(visit,interaction,window,catalog),130);
                return true;
            }
        }
        if(hit==null&&genericPopupAllowed()&&!genericPopupWindows.contains(root.getWindowId())){
            long now=SystemClock.elapsedRealtime();
            if(now<nextGenericProbe){
                popupPending=true;
                popupDue=nextGenericProbe;
            }else{
                nextGenericProbe=now+650;
                PopupMatch ad=PopupMatch.findGenericAd(root,display);
                if(ad!=null){
                    popupVerifying=true;
                    long visit=foregroundVisit, interaction=userInteraction;
                    int window=root.getWindowId();
                    main.postDelayed(()->finishGenericPopup(visit,interaction,window,ad),180);
                    return true;
                }
            }
        }
        if(hit==null||attemptedPopups.contains(hit.rule))return false;
        if(hit.rule.times==0){
            popupStep++;
            popupPending=true;
            schedule();
            return true;
        }
        int window=root.getWindowId();
        if(hit.rule.delayMs>0){
            popupVerifying=true;
            long visit=foregroundVisit;
            long interaction=userInteraction;
            main.postDelayed(()->finishDelayedPopup(visit,interaction,window,hit),hit.rule.delayMs);
        }else clickPopup(hit,window);
        return true;
    }
    private void finishCatalogPopup(long visit,long interaction,int window,PopupAdRules.Match expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(store.popupEnabled()&&ready(visit)&&interaction==userInteraction&&catalogPopupAllowed()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(belongsTo(root,foreground)&&root.getWindowId()==window){
                PopupAdRules.Match current=popupAds.find(root,activity,screen(root),activityAge());
                if(current!=null&&current.rule==expected.rule&&current.target.equals(expected.target)){
                    catalogAttempts.merge(current.rule,1,Integer::sum);
                    catalogUnconfirmed.add(current.rule);
                    clickObservedPopup(current.target,current.rule);
                    return;
                }
            }
        }
        schedule();
    }
    private void finishGenericPopup(long visit,long interaction,int window,PopupMatch expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(store.popupEnabled()&&ready(visit)&&interaction==userInteraction&&genericPopupAllowed()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(belongsTo(root,foreground)&&root.getWindowId()==window){
                PopupMatch current=PopupMatch.findGenericAd(root,screen(root));
                if(current!=null&&expected.target.equals(current.target)&&expected.rule.context.equals(current.rule.context)){
                    genericPopupWindows.add(window);
                    clickObservedPopup(current.target,null);
                    return;
                }
            }
        }
        schedule();
    }
    private void finishDelayedPopup(long visit,long interaction,int window,PopupMatch expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(!store.popupEnabled()||!ready(visit)||interaction!=userInteraction){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(!matchesWindow(root,foreground,window)){schedule();return;}
        PopupMatch current=PopupMatch.find(root,screen(root),popupRules());
        if(current!=null&&current.rule==expected.rule&&Objects.equals(current.target,expected.target))clickPopup(current,window);
        else schedule();
    }
    private boolean ready(long visit){
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        return !closed&&!service.busy&&!windowTransitionPending()
                &&visit==foregroundVisit&&(keyguard==null||!keyguard.isKeyguardLocked());
    }
    private boolean windowTransitionPending(){return pendingWindow!=null&&!pendingPackage.equals(foreground);}
    private Rect screen(AccessibilityNodeInfo node){
        DisplayManager manager=service.getSystemService(DisplayManager.class);
        int id=displayId(node);
        // Node-based matching can still work on OEMs which omit window metadata.
        Display display=manager==null?null:manager.getDisplay(id==Display.INVALID_DISPLAY?Display.DEFAULT_DISPLAY:id);
        if(display==null)return new Rect();
        android.graphics.Point size=new android.graphics.Point();
        display.getRealSize(size);
        return new Rect(0,0,size.x,size.y);
    }
    private static int displayId(AccessibilityNodeInfo node){
        if(Build.VERSION.SDK_INT<30)return Display.DEFAULT_DISPLAY;
        AccessibilityWindowInfo window=node.getWindow();
        return window==null?Display.INVALID_DISPLAY:window.getDisplayId();
    }
    private void clickPopup(PopupMatch hit,int window){
        attemptedPopups.add(hit.rule);
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long visit=foregroundVisit;
        boolean sent;
        if(hit.rule.backsOut()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            sent=window>=0&&matchesWindow(root,pkg,window)&&displayId(root)==Display.DEFAULT_DISPLAY
                    &&service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }else sent=click(hit.rule,hit.target);
        if(!sent){recordPopup(pkg,false);schedule();return;}
        popupVerifying=true;
        main.postDelayed(()->verifyPopup(pkg,visit,hit.rule,false),350);
    }
    private void clickObservedPopup(AccessibilityNodeInfo target,PopupAdRules.Rule rule){
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long visit=foregroundVisit;
        if(!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
            recordPopup(pkg,false);
            schedule();
            return;
        }
        popupVerifying=true;
        main.postDelayed(()->verifyObservedPopup(pkg,visit,target,rule,false),350);
    }
    private void verifyObservedPopup(String pkg,long visit,AccessibilityNodeInfo target,PopupAdRules.Rule rule,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean confirmed=sameVisit&&belongsTo(root,pkg)&&!visibleNode(root,target)
                &&(rule==null||!popupAds.visible(root,activity,rule));
        if(!confirmed&&sameVisit&&!retried){
            main.postDelayed(()->verifyObservedPopup(pkg,visit,target,rule,true),500);
            return;
        }
        if(sameVisit){
            if(confirmed&&rule!=null)catalogUnconfirmed.remove(rule);
            finishPopupVerification();
        }
        recordPopup(pkg,confirmed);
    }
    private void finishPopupVerification(){
        popupVerifying=false;
        if(store.popupEnabled()){
            popupPending=true;
            popupDue=0;
            schedule();
        }
    }
    private boolean click(CustomSkipRule rule,AccessibilityNodeInfo target){
        return rule!=null&&rule.gesture?tap(target):target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    private boolean tap(AccessibilityNodeInfo target){
        GestureDescription gesture=tapGesture(target);
        return gesture!=null&&service.dispatchGesture(gesture,null,null);
    }
    private GestureDescription tapGesture(AccessibilityNodeInfo target){
        Rect bounds=new Rect();
        target.getBoundsInScreen(bounds);
        int display=displayId(target);
        if(display==Display.INVALID_DISPLAY||!screen(target).contains(bounds))return null;
        if(Build.VERSION.SDK_INT>=30){
            AccessibilityWindowInfo window=target.getWindow();
            android.graphics.Region touchable=new android.graphics.Region();
            if(window==null)return null;
            window.getRegionInScreen(touchable);
            if(!touchable.contains(bounds.centerX(),bounds.centerY()))return null;
        }
        Path path=new Path();
        path.moveTo(bounds.exactCenterX(),bounds.exactCenterY());
        GestureDescription.Builder gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,45));
        if(Build.VERSION.SDK_INT>=30)gesture.setDisplayId(display);
        return gesture.build();
    }
    private void verifyPopup(String pkg,long visit,CustomSkipRule rule,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean confirmed=sameVisit&&root!=null&&pkg.equals(String.valueOf(root.getPackageName()))
                &&!PopupMatch.promptVisible(root,rule);
        if(!confirmed&&sameVisit&&!retried){
            main.postDelayed(()->verifyPopup(pkg,visit,rule,true),500);
            return;
        }
        if(sameVisit){
            if(confirmed&&(customRules().contains(rule)||PopupMatch.APP_PRESETS.contains(rule))){
                attemptedPopups.remove(rule);
                int count=confirmedPopups.merge(rule,1,Integer::sum);
                if(rule.sequenceIndex==popupStep&&count>=rule.times)popupStep++;
            }
            finishPopupVerification();
        }
        recordPopup(pkg,confirmed);
    }
    private void recordPopup(String pkg,boolean confirmed){
        try{store.recordPopup(pkg,confirmed);}catch(RuntimeException ignored){/* History failure never retries a screen click. */}
        if(confirmed&&store.skipToastEnabled())showToast("净屏已关闭弹窗");
    }
    private void verifySplash(String pkg,long visit,AccessibilityNodeInfo target,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean confirmed=sameVisit&&belongsTo(root,pkg)&&!visibleNode(root,target);
        if(!confirmed&&sameVisit&&!retried){main.postDelayed(()->verifySplash(pkg,visit,target,true),500);return;}
        finishSplash(pkg,visit,confirmed);
    }
    private void finishSplash(String pkg,long visit,boolean confirmed){
        if(closed)return;
        if(visit==foregroundVisit&&pkg.equals(foreground)){splashVerifying=false;schedule();}
        record(pkg,confirmed);
    }
    private static boolean visibleNode(AccessibilityNodeInfo root,AccessibilityNodeInfo target){
        List<AccessibilityNodeInfo> nodes=UiDriver.walkComplete(root);
        return nodes.isEmpty()||nodes.stream().anyMatch(node->node.isVisibleToUser()&&target.equals(node));
    }
    private void record(String pkg,boolean confirmed){
        try{store.recordSplash(pkg,confirmed);}catch(RuntimeException ignored){/* History failure never retries a screen click. */}
        if(confirmed&&store.skipToastEnabled())showToast("净屏已跳过开屏广告");
    }
    private void showToast(String text){android.widget.Toast.makeText(service,text,android.widget.Toast.LENGTH_SHORT).show();}
    static boolean skipLabel(String value){
        return SKIP_LABEL.matcher(normalizedLabel(value)).matches();
    }
    private static String normalizedLabel(String value){
        if(value.length()>80)return "";
        return SPACE.matcher(Normalizer.normalize(value,Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
    }
    static boolean explicitAd(String value){
        return skipLabel(value)&&EXPLICIT_SKIP.matcher(normalizedLabel(value)).lookingAt();
    }
    static boolean rewardAd(String value){
        return REWARDED_AD.matcher(value).find();
    }
    private static String label(CharSequence value){
        return value==null?"":value.toString().trim();
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root){
        return match(root,SplashRules.EMPTY,"");
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root,SplashRules rules,String activity){
        return match(root,rules,activity,Collections.emptyList());
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom){
        SplashHit hit=splashHit(root,rules,activity,custom,0);
        return hit==null?null:hit.target;
    }
    private static final class SplashHit {
        final AccessibilityNodeInfo target;
        final CustomSkipRule rule;
        final int priority;
        final boolean tap;
        SplashHit(AccessibilityNodeInfo target,CustomSkipRule rule,int priority,boolean tap){
            this.target=target;this.rule=rule;this.priority=priority;this.tap=tap;
        }
    }
    private static SplashHit splashHit(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom,long elapsed){
        if(root==null)return null;
        Rect screen=new Rect();root.getBoundsInScreen(screen);if(screen.width()<100||screen.height()<100)return null;
        String pkg=String.valueOf(root.getPackageName());
        if(CustomSkipRule.protectedPackage(pkg))return null;
        int window=root.getWindowId();
        List<AccessibilityNodeInfo> nodes=UiDriver.walkComplete(root);boolean ad=false;
        List<CustomSkipRule> scoped=new ArrayList<>();
        for(CustomSkipRule rule:custom)if(rule.appliesTo(pkg,activity,CustomSkipRule.SPLASH))scoped.add(rule);
        boolean useDefaults=scoped.stream().noneMatch(rule->rule.replaceDefaults);
        for(AccessibilityNodeInfo node:nodes){if(!node.isVisibleToUser())continue;
            String text=label(node.getText()),desc=label(node.getContentDescription());
            if(node.isEditable())return null;
            if(node.isClickable()&&(consentLabel(text)||consentLabel(desc)))return null;
            if(rewardAd(text)||rewardAd(desc))return null;
            if(belongsTo(node,pkg)&&node.getWindowId()==window){
                ad|=AD_MARKER.matcher(normalizedLabel(text)).matches()||AD_MARKER.matcher(normalizedLabel(desc)).matches();
            }
        }
        Map<AccessibilityNodeInfo,SplashHit> hits=new HashMap<>();
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser()||!node.isEnabled()||node.isCheckable()
                    ||node.getWindowId()!=window||!belongsTo(node,pkg))continue;
            CustomSkipRule userRule=null;
            for(CustomSkipRule rule:scoped)if(rule.matchesButton(node)){userRule=rule;break;}
            SplashRules.Action action=userRule==null&&useDefaults?rules.action(pkg,activity,node,elapsed):null;
            boolean known=userRule!=null||action!=null;
            String text=label(node.getText()),desc=label(node.getContentDescription());
            if(!known&&(!useDefaults||(!skipLabel(text)&&!skipLabel(desc))))continue;
            if(!safeActionLabel(text)||!safeActionLabel(desc))continue;
            boolean explicit=explicitAd(text)||explicitAd(desc);
            if(!known&&!explicit&&!ad)continue;
            boolean tap=action==SplashRules.Action.TAP;
            if(tap&&!safeTouchLeaf(node))continue;
            AccessibilityNodeInfo click=tap?node:userRule==null?clickable(node):ruleClickTarget(node,userRule);
            if(!belongsTo(click,pkg)||click.getWindowId()!=window)continue;
            Rect bounds=new Rect();click.getBoundsInScreen(bounds);
            boolean horizontal=bounds.left>=screen.left+screen.width()*.5||((explicit||known)&&bounds.right<=screen.left+screen.width()*.5);
            boolean bottom=bounds.top>=screen.top+screen.height()*.75;
            boolean corner=horizontal&&(bounds.bottom<=screen.top+screen.height()*.27||((explicit||known)&&bottom));
            boolean adFooter=explicit&&ad&&bottom;
            if(!screen.contains(bounds)||(!corner&&!adFooter)
                    ||bounds.width()>screen.width()*.48||bounds.height()>screen.height()*.16||bounds.width()<12||bounds.height()<12)continue;
            if(tap||userRule!=null||safeClickTarget(click)){
                int priority=userRule!=null?4:known?3:explicit?2:1;
                SplashHit previous=hits.get(click);
                if(previous==null||previous.priority<priority)hits.put(click,new SplashHit(click,userRule,priority,tap));
            }
        }
        SplashHit best=null;
        boolean ambiguous=false;
        for(SplashHit hit:hits.values()){
            if(best==null||best.priority<hit.priority){best=hit;ambiguous=false;}
            else if(best.priority==hit.priority)ambiguous=true;
        }
        return ambiguous?null:best;
    }
    private static boolean consentLabel(String value){
        return CONSENT.matcher(value).matches();
    }
    static AccessibilityNodeInfo ruleClickTarget(AccessibilityNodeInfo node,CustomSkipRule rule){
        AccessibilityNodeInfo target=clickable(node);
        if(target==null){
            if(!rule.gesture||!node.isVisibleToUser()||!node.isEnabled()||node.isEditable()
                    ||node.isCheckable()||node.getChildCount()!=0)return null;
            target=node;
        }else if(!safeClickTarget(target))return null;
        if(!rule.pkg.equals(String.valueOf(target.getPackageName()))||!safeActionLabel(target))return null;
        return target;
    }
    static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node){
        for(int i=0;i<2&&!node.isClickable();i++){
            AccessibilityNodeInfo parent=node.getParent();
            if(parent==null)return null;
            node=parent;
        }
        return node.isClickable()&&node.isEnabled()&&node.isVisibleToUser()&&!node.isCheckable()?node:null;
    }
    static boolean safeClickTarget(AccessibilityNodeInfo target){
        int actionable=0;
        for(AccessibilityNodeInfo child:UiDriver.walkComplete(target)){
            if(child.isEditable()||child.isCheckable())return false;
            if(!child.isVisibleToUser())continue;
            if(!safeActionLabel(child))return false;
            if(child.isClickable()&&++actionable>1)return false;
        }
        return actionable==1;
    }
    private static boolean safeTouchLeaf(AccessibilityNodeInfo node){
        if(node.getChildCount()!=0)return false;
        AccessibilityNodeInfo parent=node.getParent();
        for(int depth=0;parent!=null&&depth<40;depth++,parent=parent.getParent()){
            if(parent.isEditable()||parent.isCheckable()||!safeActionLabel(parent))return false;
        }
        return parent==null;
    }
    static boolean safeActionLabel(AccessibilityNodeInfo node){
        return safeActionLabel(label(node.getText()))&&safeActionLabel(label(node.getContentDescription()));
    }
    private static boolean safeActionLabel(String value){
        return !CustomSkipRule.unsafeButton(value)&&!CustomSkipRule.sensitiveText(value);
    }
}
