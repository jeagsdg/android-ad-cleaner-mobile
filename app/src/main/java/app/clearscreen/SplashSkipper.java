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
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Opt-in launch and in-app dismissal through verified accessibility controls. */
final class SplashSkipper {
    static final long WINDOW_MS=12000;
    private static final long WINDOW_SIGNAL_MS=1200;
    private static final long QUICK_SCAN_MS=65;
    private static final long USER_INPUT_PAUSE_MS=900;
    private static final long PREVIEW_INTERVAL_MS=250;
    private static final long POPUP_PROBE_INTERVAL_MS=240;
    private static final long POPUP_COOLDOWN_MS=750;
    private static final long MEDIA_COOLDOWN_MS=1500;
    private static final Pattern SPACE=Pattern.compile("\\s+");
    private static final Pattern CONSENT=Pattern.compile(
            "同意.*|允[许許].*|接受.*|确认支付|確認支付|立即授[权權]|allow(?: .*)?|i agree",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SKIP=Pattern.compile(
            "(?:跳过(?:此)?广告|跳過(?:此)?廣告|略过广告|略過廣告|skip(?:this)?ads?|skipadvertisement|広告をスキップ|광고건너뛰기)");
    private static final Pattern AD_CLOSE_LABEL=Pattern.compile(
            "关闭(?:此)?广告|關閉(?:此)?廣告|close (?:this )?ad|close advertisement|dismiss (?:this )?ad",
            Pattern.CASE_INSENSITIVE);
    private static final String SKIP_WORD="(?:(?:点击|點擊|点此|點此)?(?:跳过|跳過)|略过|略過|skip|スキップ|건너뛰기)";
    private static final String COUNTDOWN="(?:[0-9]{1,2}(?:s|秒|초)?)";
    private static final Pattern SKIP_LABEL=Pattern.compile(
            "(?:"+EXPLICIT_SKIP.pattern()+"|"+SKIP_WORD+")(?:\\("+COUNTDOWN+"\\)|[·:|]?"+COUNTDOWN+")?"
                    +"|"+COUNTDOWN+"[·:|]?"+SKIP_WORD);
    private static final Pattern AD_MARKER=Pattern.compile(
            "广告|廣告|推广|推廣|ad|advertisement|広告|광고|广告\\|已wi-?fi预加载|品牌广告|启动图广告");
    private static final Pattern ONBOARDING_PAGE=Pattern.compile("onboarding|tutorial|setupwizard|userguide|walkthrough",Pattern.CASE_INSENSITIVE);
    private static final Pattern REWARDED_AD=Pattern.compile(
            "观看广告领取|觀看廣告領取|看广告得奖励|看廣告得獎勵|观看视频领奖|看视频领奖|观看完整视频|獎勵將|奖励将"
                    +"|watch.*(?:earn|reward)|reward\\s+in|rewarded\\s+ad",Pattern.CASE_INSENSITIVE);
    private final CleanerService service;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable scheduledScan=this::scan;
    private final Store store;
    private final SplashRules rules;
    private final PopupAdRules popupAds;
    private final SkipFeedback feedback;
    private final SkipDiagnostics diagnostics = SkipDiagnostics.LOCAL;
    private final Object diagnosticOwner = new Object();
    private SkipDiagnostics.Attempt diagnosticAttempt;
    private final RulePreview preview=RulePreview.LOCAL;
    private String foreground="",launcher="";
    private String activity="";
    private long deadline, foregroundVisit, nextPopupClick, nextGenericProbe, popupDue, userInteraction;
    private long scanAt=Long.MAX_VALUE, nextSplashProbe, lastSplashProbe;
    private long activitySince;
    private long pauseUntil, nextPopupProbe, nextPreviewProbe, previewDue=Long.MAX_VALUE, lastHotSplash;
    private int gesturesInFlight, activityWindow=-1;
    private long ownClickUntil;
    private AccessibilityNodeInfo ownClickTarget;
    private boolean attempted,closed,popupPending,popupVerifying,splashWaiting,splashVerifying,mediaPending;
    private Runnable delayedSplash;
    private Runnable pendingWindow;
    private String pendingPackage="";
    private String pendingClass="";
    private int pendingWindowId=-1;
    private long pendingStateAt;
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
        feedback = new SkipFeedback(service, main);
        try{android.content.pm.ResolveInfo info=service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);if(info!=null&&info.activityInfo!=null)launcher=info.activityInfo.packageName;}catch(Exception ignored){}
    }
    void close(){
        closed=true;
        diagnostics.disconnected(diagnosticOwner);
        feedback.clear();
        main.removeCallbacksAndMessages(null);
    }
    void interruptFeedback(){feedback.clear();}
    boolean startRulePreview(List<CustomSkipRule> drafts){
        if(closed||service.busy||gesturesInFlight!=0||drafts.isEmpty())return false;
        if(foreground.equals(drafts.get(0).pkg)&&(splashVerifying||popupVerifying))return false;
        if(!preview.start(drafts))return false;
        stopCurrentInput();
        feedback.clear();
        nextPreviewProbe=0;
        previewDue=preview.observes(foreground)?0:Long.MAX_VALUE;
        schedule();
        return true;
    }
    void event(AccessibilityEvent event){
        if(closed||event.getPackageName()==null)return;
        String pkg=event.getPackageName().toString();
        Set<String> media=store.mediaAssistEnabled();
        if(service.busy||(!store.splashEnabled()&&!store.popupEnabled()&&media.isEmpty()
                &&!preview.hasSession()&&!diagnostics.hasSession())){
            if(!foreground.isEmpty()){
                feedback.clear();stopCurrentInput();foreground="";foregroundVisit++;
                previewDue=Long.MAX_VALUE;
                schedule();
            }else cancelWindowRetry();
            return;
        }
        if(pkg.equals(service.getPackageName())&&SkipFeedback.ACCESSIBILITY_CLASS.contentEquals(
                event.getClassName()==null?"":event.getClassName()))return;
        int type=event.getEventType();
        boolean previewing=preview.observes(pkg);
        boolean inActiveWindow=pkg.equals(foreground)&&event.getWindowId()==activeWindow;
        boolean inPendingWindow=pkg.equals(pendingPackage)&&event.getWindowId()==pendingWindowId;
        if(!previewing&&(inActiveWindow||inPendingWindow)
                &&(type==AccessibilityEvent.TYPE_VIEW_CLICKED||type==AccessibilityEvent.TYPE_VIEW_SCROLLED
                ||(type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED&&editableSource(event)))){
            if(type==AccessibilityEvent.TYPE_VIEW_CLICKED&&isOwnClick(event))return;
            if(deadline>0)note(SkipDiagnostics.Phase.USER_INPUT);
            stopCurrentInput();
            pauseUntil=SystemClock.elapsedRealtime()+USER_INPUT_PAUSE_MS;
            schedule();
            return;
        }
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            cancelWindowRetry();
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            String windowClass=String.valueOf(event.getClassName());
            long stateAt=SystemClock.elapsedRealtime();
            if(!matchesWindow(root,pkg,event.getWindowId())){
                // Some launch transitions report an intermediate window. The verified
                // active package can arm splash detection, while page rules wait for its class.
                if(belongsTo(root,pkg)&&!pkg.equals(foreground)){
                    acceptWindow(pkg,"",root.getWindowId(),stateAt);
                    if(pkg.equals(foreground))queueWindowContent(media);
                }
                pendingPackage=pkg;
                pendingClass=windowClass;
                pendingWindowId=event.getWindowId();
                pendingStateAt=stateAt;
                retryWindow(5);
                schedule();
                return;
            }
            acceptWindow(pkg,windowClass,root.getWindowId(),stateAt);
        }else if(type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                &&event.getWindowId()>=0&&(!pkg.equals(foreground)||activeWindow!=event.getWindowId())){
            // Confirm an actual window transition from the active root. A service rebind
            // without a preceding launch signal must not arm splash from ordinary content.
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(matchesWindow(root,pkg,event.getWindowId())){
                long now=SystemClock.elapsedRealtime();
                boolean pendingForApp=pkg.equals(pendingPackage);
                boolean sameStateWindow=pendingForApp&&pendingWindowId==event.getWindowId();
                // After the short polling loop, only a matching window can confirm the earlier signal.
                boolean launchSignal=pendingForApp&&(pendingWindow!=null
                        ||(sameStateWindow&&now-pendingStateAt<=WINDOW_SIGNAL_MS));
                String windowClass=launchSignal&&sameStateWindow?pendingClass:"";
                long launchAt=foreground.isEmpty()?-1:now;
                if(launchSignal)launchAt=pendingStateAt;
                if(launchSignal||!pkg.equals(foreground))cancelWindowRetry();
                acceptWindow(pkg,windowClass,root.getWindowId(),launchAt);
            }
        }
        if(pkg.equals(foreground)&&(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                ||type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                ||(previewing&&(type==AccessibilityEvent.TYPE_VIEW_SCROLLED||type==AccessibilityEvent.TYPE_VIEW_CLICKED)))){
            int changes=event.getContentChangeTypes();
            boolean relevant=type!=AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED||changes==0
                    ||(changes&(AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE|AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT
                    |AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION))!=0;
            if(scanSplash()){
                if(relevant){
                    long age=launchAge();
                    long minimumGap=age<800?QUICK_SCAN_MS:age<3000?115:220;
                    nextSplashProbe=Math.min(nextSplashProbe,lastSplashProbe+minimumGap);
                }
            }
            if(relevant)queueWindowContent(media);
        }
        schedule();
    }
    private static boolean editableSource(AccessibilityEvent event){
        AccessibilityNodeInfo source=event.getSource();
        return source!=null&&source.isEditable();
    }
    private boolean isOwnClick(AccessibilityEvent event){
        if(ownClickTarget==null)return false;
        if(SystemClock.elapsedRealtime()>ownClickUntil){ownClickTarget=null;return false;}
        if(!ownClickTarget.equals(event.getSource()))return false;
        ownClickTarget=null;
        return true;
    }
    private void rememberClick(AccessibilityNodeInfo target){
        ownClickTarget=target;
        ownClickUntil=SystemClock.elapsedRealtime()+550;
    }
    private void stopCurrentInput(){
        deadline=0;
        cancelWindowRetry();
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
        pendingStateAt=0;
    }
    private void retryWindow(int attempts){
        pendingWindow=()->{
            pendingWindow=null;
            if(closed||service.busy){cancelWindowRetry();return;}
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(matchesWindow(root,pendingPackage,pendingWindowId)){
                String pkg=pendingPackage,windowClass=pendingClass;
                long launchAt=pendingStateAt;
                cancelWindowRetry();
                acceptWindow(pkg,windowClass,root.getWindowId(),launchAt);
                if(pkg.equals(foreground))queueWindowContent(store.mediaAssistEnabled());
                schedule();
            }else if(attempts>1)retryWindow(attempts-1);
        };
        main.postDelayed(pendingWindow,75);
    }
    private void acceptWindow(String pkg,String windowClass,int windowId,long launchAt){
        if(transientSystem(pkg)){
            feedback.clear();
            stopCurrentInput();
            return;
        }
        long now=SystemClock.elapsedRealtime();
        boolean newVisit=!pkg.equals(foreground);
        boolean newWindow=activeWindow!=windowId;
        if(newVisit){
            feedback.clear();
            foreground=pkg;
            activity="";
            activityWindow=-1;
            activitySince=now;
            ownClickTarget=null;
            attempted=false;
            foregroundVisit++;
            stopCurrentInput();
            resetPopupProgress();
            nextSplashProbe=0;
            lastSplashProbe=0;
            nextPopupClick=0;
            nextGenericProbe=0;
            nextPopupProbe=0;
            pauseUntil=0;
            previewDue=Long.MAX_VALUE;
            splashVerifying=false;
            popupVerifying=false;
            popupRetries=0;
            if(launchAt>=0)armSplash(launchAt);
        }
        boolean newActivity=(newVisit||activityWindow!=windowId||!windowClass.equals(activity))
                &&rememberActivity(pkg,windowClass,windowId);
        if(newWindow){
            activeWindow=windowId;
            nextGenericProbe=0;
            nextPopupProbe=0;
        }
        // This verified, dedicated Tencent activity also hosts warm launches within the same app.
        if(!newVisit&&newActivity&&pkg.equals("com.tencent.qqlive")
                &&activity.equals("com.tencent.qqlive.qadsplash.template.QAdHotSplashActivity")
                &&now>=pauseUntil&&now-lastHotSplash>=1500&&!splashVerifying){
            lastHotSplash=now;
            attempted=false;
            nextSplashProbe=now;
            armSplash(now);
        }
    }
    private void armSplash(long now){
        cancelDelayedSplash();
        diagnosticAttempt=diagnostics.begin(foreground,diagnosticOwner);
        if(preview.observes(foreground)){
            deadline=0;
            note(SkipDiagnostics.Phase.PREVIEW_ACTIVE);
            return;
        }
        if(!store.splashEnabled())note(SkipDiagnostics.Phase.DISABLED);
        else if(CustomSkipRule.protectedPackage(foreground)||excluded(foreground)
                ||store.skipExcluded(CustomSkipRule.SPLASH).contains(foreground))note(SkipDiagnostics.Phase.EXCLUDED);
        else deadline=now+WINDOW_MS;
    }
    private static boolean transientSystem(String pkg){
        return pkg.equals("com.android.systemui")||pkg.contains("permissioncontroller")||pkg.contains("packageinstaller");
    }
    private void queueWindowContent(Set<String> media){
        if(preview.observes(foreground)){
            if(previewDue==Long.MAX_VALUE)previewDue=nextPreviewProbe;
            return;
        }
        if(foreground.isEmpty()||excluded(foreground)||CustomSkipRule.protectedPackage(foreground)
                ||PopupAdRules.protectedActivity(activity))return;
        if(store.popupEnabled()&&!popupPending){
            List<CustomSkipRule> available=popupRules();
            boolean catalog=catalogPopupAllowed();
            popupPending=!available.isEmpty()||catalog||genericPopupAllowed();
            popupDue=available.isEmpty()&&!catalog?nextGenericProbe:0;
            popupRetries=available.stream().mapToInt(rule->rule.searches).max().orElse(1)-1;
        }
        mediaPending=!media.isEmpty()&&(foreground.equals("com.tencent.mm")||foreground.equals("com.tencent.mobileqq"));
    }
    private boolean rememberActivity(String pkg,String windowClass,int windowId){
        if(!windowClass.contains(".")||windowClass.startsWith("android.")||windowClass.startsWith("androidx."))return false;
        try{
            if(windowClass.startsWith("."))windowClass=pkg+windowClass;
            service.getPackageManager().getActivityInfo(new ComponentName(pkg,windowClass),0);
            if(!windowClass.equals(activity))activitySince=SystemClock.elapsedRealtime();
            activity=windowClass;
            activityWindow=windowId;
            return true;
        }catch(android.content.pm.PackageManager.NameNotFoundException|SecurityException ignored){/* Dialog classes are not activities. */}
        return false;
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
            if(!attemptedPopups.contains(rule)&&(rule.sequenceIndex<0||rule.sequenceIndex==popupStep)
                    &&(!rule.legacy||rule.times==0||confirmedPopups.getOrDefault(rule,0)<rule.times))result.add(rule);
        }
        if(!customized)for(CustomSkipRule rule:PopupMatch.APP_PRESETS)
            if(rule.appliesTo(foreground,activity,CustomSkipRule.POPUP)
                    &&confirmedPopups.getOrDefault(rule,0)<rule.times)result.add(rule);
        return result;
    }
    private boolean genericPopupAllowed(){
        return genericPopupWindows.size()<5&&!genericPopupWindows.contains(activeWindow)&&defaultPopupAllowed();
    }
    private boolean catalogPopupAllowed(){
        return defaultPopupAllowed()&&popupAds.hasRules(foreground,activity,activityAge(),this::catalogRuleAvailable);
    }
    private boolean catalogRuleAvailable(PopupAdRules.Rule rule){
        return !catalogUnconfirmed.contains(rule)&&catalogAttempts.getOrDefault(rule,0)<rule.maxPerVisit();
    }
    private long activityAge(){return SystemClock.elapsedRealtime()-activitySince;}
    private boolean defaultPopupAllowed(){
        if(foreground.isEmpty()||CustomSkipRule.protectedPackage(foreground)||excluded(foreground)
                ||store.skipExcluded(CustomSkipRule.POPUP).contains(foreground))return false;
        for(CustomSkipRule rule:customRules())if(rule.appliesTo(foreground,activity,CustomSkipRule.POPUP))return false;
        return true;
    }
    private void resetPopupProgress(){
        attemptedPopups.clear();confirmedPopups.clear();genericPopupWindows.clear();popupStep=0;
        catalogAttempts.clear();
        catalogUnconfirmed.clear();
    }
    private boolean scanSplash(){
        return !attempted&&!splashWaiting&&automationAllowed()&&store.splashEnabled()&&deadline>SystemClock.elapsedRealtime()
                &&!store.skipExcluded(CustomSkipRule.SPLASH).contains(foreground);
    }
    private boolean automationAllowed(){return !preview.observes(foreground);}
    private boolean screenLocked(){
        KeyguardManager keyguard=service.getSystemService(KeyguardManager.class);
        return keyguard!=null&&keyguard.isKeyguardLocked();
    }
    private void note(SkipDiagnostics.Phase phase){diagnostics.note(foreground,diagnosticAttempt,phase);}
    private void schedule(){
        if(closed)return;
        long now=SystemClock.elapsedRealtime(), due=Long.MAX_VALUE;
        if(!service.busy&&preview.observes(foreground)){
            if(previewDue!=Long.MAX_VALUE)due=Math.max(now+QUICK_SCAN_MS,previewDue);
        }else if(!service.busy){
            if(scanSplash())due=Math.max(now+QUICK_SCAN_MS,nextSplashProbe);
            if(deadline>0&&diagnostics.observes(foreground,diagnosticAttempt))due=Math.min(due,Math.max(now,deadline));
            if(!splashWaiting&&!splashVerifying&&!popupVerifying){
                if(popupPending)due=Math.min(due,Math.max(now+QUICK_SCAN_MS,Math.max(pauseUntil,popupReadyAt())));
                if(mediaPending)due=Math.min(due,Math.max(now+150,Math.max(pauseUntil,nextMediaProbe)));
            }
        }
        if(due==Long.MAX_VALUE){
            if(scanAt!=Long.MAX_VALUE)main.removeCallbacks(scheduledScan);
            scanAt=Long.MAX_VALUE;
            return;
        }
        if(due>=scanAt)return;
        main.removeCallbacks(scheduledScan);
        scanAt=due;
        main.postDelayed(scheduledScan,due-now);
    }
    private void scan(){
        scanAt=Long.MAX_VALUE;
        if(closed||service.busy)return;
        long now=SystemClock.elapsedRealtime();
        if(deadline>0&&deadline<=now){note(SkipDiagnostics.Phase.SCAN_ENDED);deadline=0;}
        if(windowTransitionPending()){schedule();return;}
        if(preview.observes(foreground)){
            if(previewDue<=now){
                previewDue=Long.MAX_VALUE;
                nextPreviewProbe=now+PREVIEW_INTERVAL_MS;
                inspectPreview();
            }
            schedule();
            return;
        }
        if(excluded(foreground)||CustomSkipRule.protectedPackage(foreground))return;
        boolean checkPopup=popupPending&&store.popupEnabled()&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&now>=pauseUntil&&now>=popupReadyAt();
        if(checkPopup||!store.popupEnabled())popupPending=false;
        boolean checkMedia=mediaPending&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&now>=pauseUntil&&now>=nextMediaProbe;
        boolean checkSplash=scanSplash()&&now>=nextSplashProbe;
        if(!checkSplash&&!checkPopup&&!checkMedia){schedule();return;}
        if(checkSplash){
            long age=launchAge();
            lastSplashProbe=SystemClock.elapsedRealtime();
            nextSplashProbe=lastSplashProbe+(age<800?90:age<3000?175:330);
        }
        if(screenLocked()){
            if(checkSplash)note(SkipDiagnostics.Phase.GUARDED);
            stopCurrentInput();
            return;
        }
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null){
            if(checkSplash)note(SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
            if(checkPopup&&popupRetries>0){popupRetries--;popupPending=true;}
            if(checkMedia)mediaPending=false;
            schedule();return;
        }
        if(!belongsTo(root,foreground)){
            if(checkSplash)note(SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
            if(scanSplash()&&launchAge()<=1200){schedule();return;}
            deadline=0;
            return;
        }
        List<AccessibilityNodeInfo> nodes=checkSplash||checkPopup?UiDriver.walkComplete(root):Collections.emptyList();
        if(checkSplash){
            List<CustomSkipRule> custom=customRules();
            Consumer<SkipDiagnostics.Phase> trace=diagnostics.observes(foreground,diagnosticAttempt)?this::note:null;
            SplashHit hit=splashHit(root,rules,splashActivity(root),custom,launchAge(),trace,nodes,false);
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
        if(checkPopup){
            nextPopupProbe=now+POPUP_PROBE_INTERVAL_MS;
            if(scanPopup(root,nodes))return;
        }
        if(checkPopup&&popupRetries>0){popupRetries--;popupPending=true;}
        if(checkMedia){
            mediaPending=false;
            nextMediaProbe=now+300;
            AccessibilityNodeInfo mediaTarget=MediaAssist.find(root,activity,store.mediaAssistEnabled());
            if(mediaTarget!=null&&ready(foregroundVisit)&&matchesWindow(service.getRootInActiveWindow(),foreground,activeWindow)){
                nextMediaProbe=SystemClock.elapsedRealtime()+MEDIA_COOLDOWN_MS;
                if(mediaTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK))rememberClick(mediaTarget);
            }
        }
        schedule();
    }
    private long popupReadyAt(){return Math.max(nextPopupProbe,Math.max(popupDue,nextPopupClick));}
    private String splashActivity(AccessibilityNodeInfo root){return root.getWindowId()==activityWindow?activity:"";}
    private void inspectPreview(){
        List<CustomSkipRule> drafts=preview.rulesFor(foreground);
        if(drafts.isEmpty())return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean valid=matchesWindow(root,foreground,activeWindow);
        List<AccessibilityNodeInfo> nodes=valid?UiDriver.walkComplete(root):Collections.emptyList();
        Rect display=valid?screen(root):new Rect();
        boolean locked=screenLocked();
        for(int i=0;i<drafts.size();i++){
            CustomSkipRule rule=drafts.get(i);
            SkipDiagnostics.Phase result=!valid?SkipDiagnostics.Phase.WINDOW_UNAVAILABLE
                    :locked||PopupAdRules.protectedActivity(activity)?SkipDiagnostics.Phase.GUARDED
                    :previewRule(root,display,nodes,rule);
            preview.note(drafts,i,result);
        }
    }
    private SkipDiagnostics.Phase previewRule(AccessibilityNodeInfo root,Rect display,List<AccessibilityNodeInfo> nodes,CustomSkipRule rule){
        SkipDiagnostics.Phase[] result={SkipDiagnostics.Phase.NO_RULE_MATCH};
        Consumer<SkipDiagnostics.Phase> trace=phase->result[0]=phase;
        List<CustomSkipRule> selection=Collections.singletonList(rule);
        if(CustomSkipRule.SPLASH.equals(rule.mode)){
            SplashHit hit=splashHit(root,SplashRules.EMPTY,splashActivity(root),selection,0,trace,nodes,true);
            if(hit!=null&&hit.usesGesture()&&tapGesture(hit.target)==null)return SkipDiagnostics.Phase.TOUCH_UNAVAILABLE;
        }else{
            if(!rule.appliesTo(foreground,activity,CustomSkipRule.POPUP))return SkipDiagnostics.Phase.PAGE_MISMATCH;
            PopupMatch hit=PopupMatch.find(root,display,selection,nodes,trace);
            if(hit!=null){
                if(rule.backsOut()&&!canReturnFrom(root,foreground,activeWindow))return SkipDiagnostics.Phase.WINDOW_UNAVAILABLE;
                if(rule.gesture&&hit.target!=null&&tapGesture(hit.target)==null)return SkipDiagnostics.Phase.TOUCH_UNAVAILABLE;
            }
        }
        return result[0];
    }
    private void finishDelayedSplash(long visit,long interaction,SplashHit expected){
        splashWaiting=false;
        delayedSplash=null;
        if(!scanSplash()||!ready(visit)||interaction!=userInteraction||excluded(foreground)){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(!matchesWindow(root,foreground,expected.target.getWindowId())){note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();return;}
        List<CustomSkipRule> custom=customRules();
        SplashHit current=splashHit(root,rules,splashActivity(root),custom,launchAge());
        if(current!=null&&current.rule==expected.rule&&current.target.equals(expected.target)){
            clickSplash(current);
        }else{note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();}
    }
    private void cancelDelayedSplash(){
        if(delayedSplash!=null)main.removeCallbacks(delayedSplash);
        delayedSplash=null;
        splashWaiting=false;
    }
    private long launchAge(){return Math.max(0,WINDOW_MS-(deadline-SystemClock.elapsedRealtime()));}
    private void clickSplash(SplashHit hit){
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(!ready(foregroundVisit)||!matchesWindow(root,foreground,hit.target.getWindowId())){
            note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();return;
        }
        GestureDescription gesture=null;
        if(hit.usesGesture()){
            SplashHit current=scanSplash()&&pendingWindow==null&&activeWindow>=0
                    &&hit.target.getWindowId()==activeWindow?splashHit(root,rules,splashActivity(root),customRules(),launchAge()):null;
            if(current==null||!current.usesGesture()||current.rule!=hit.rule||!current.target.equals(hit.target)){
                note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();return;
            }
            hit=current;
            gesture=tapGesture(hit.target);
            if(gesture==null){note(SkipDiagnostics.Phase.TOUCH_UNAVAILABLE);schedule();return;}
        }
        attempted=true;
        deadline=0;
        String pkg=foreground;
        AccessibilityNodeInfo target=hit.target;
        long visit=foregroundVisit;
        long interaction=userInteraction;
        SkipDiagnostics.Attempt attempt=diagnosticAttempt;
        splashVerifying=true;
        Runnable verify=()->main.postDelayed(()->verifySplash(pkg,visit,interaction,attempt,target,false),350);
        if(gesture!=null){
            boolean sent=sendGesture(gesture,verify,()->finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.CANCELLED));
            if(sent){rememberClick(target);note(SkipDiagnostics.Phase.SENT);}
            else finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.REJECTED);
        }else if(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
            rememberClick(target);
            note(SkipDiagnostics.Phase.SENT);
            verify.run();
        }else{
            finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.REJECTED);
        }
    }
    private boolean scanPopup(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes){
        if(PopupAdRules.protectedActivity(activity)||nodes.isEmpty())return false;
        List<CustomSkipRule> available=popupRules();
        Rect display=screen(root);
        if(display.isEmpty())return false;
        PopupMatch hit=available.isEmpty()?null:PopupMatch.find(root,display,available,nodes,null);
        if(hit==null&&catalogPopupAllowed()){
            PopupAdRules.Match catalog=popupAds.find(root,activity,display,activityAge(),nodes,this::catalogRuleAvailable);
            if(catalog!=null){
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
                PopupMatch ad=PopupMatch.findGenericAd(root,display,nodes);
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
                PopupAdRules.Match current=popupAds.find(root,activity,screen(root),activityAge(),
                        UiDriver.walkComplete(root),this::catalogRuleAvailable);
                if(current!=null&&current.rule==expected.rule&&current.target.equals(expected.target)){
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
        return !closed&&!service.busy&&pendingWindow==null&&!excluded(foreground)
                &&gesturesInFlight==0&&automationAllowed()&&SystemClock.elapsedRealtime()>=pauseUntil
                &&!PopupAdRules.protectedActivity(activity)&&visit==foregroundVisit&&!screenLocked();
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
    private static boolean canReturnFrom(AccessibilityNodeInfo root,String pkg,int window){
        return window>=0&&matchesWindow(root,pkg,window)&&displayId(root)==Display.DEFAULT_DISPLAY;
    }
    private void clickPopup(PopupMatch hit,int window){
        long visit=foregroundVisit;
        if(!ready(visit)||!matchesWindow(service.getRootInActiveWindow(),foreground,window)){schedule();return;}
        attemptedPopups.add(hit.rule);
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long interaction=userInteraction;
        boolean sent;
        if(hit.rule.backsOut()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            sent=canReturnFrom(root,pkg,window)
                    &&service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }else if(hit.rule.gesture){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            GestureDescription gesture=ready(visit)&&matchesWindow(root,pkg,window)
                    &&hit.target.getWindowId()==window?tapGesture(hit.target):null;
            sent=gesture!=null&&sendGesture(gesture,()->main.postDelayed(()->verifyPopup(pkg,visit,interaction,hit,false),350),()->{
                if(visit==foregroundVisit&&pkg.equals(foreground))finishPopupVerification();
                recordPopup(pkg,false);
            });
            if(sent){rememberClick(hit.target);popupVerifying=true;return;}
        }else sent=hit.target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(!sent){recordPopup(pkg,false);schedule();return;}
        if(hit.target!=null)rememberClick(hit.target);
        popupVerifying=true;
        main.postDelayed(()->verifyPopup(pkg,visit,interaction,hit,false),350);
    }
    private void clickObservedPopup(AccessibilityNodeInfo target,PopupAdRules.Rule rule){
        if(!ready(foregroundVisit)||!matchesWindow(service.getRootInActiveWindow(),foreground,target.getWindowId())){schedule();return;}
        if(rule==null)genericPopupWindows.add(target.getWindowId());
        else {
            catalogAttempts.merge(rule, 1, Integer::sum);
            catalogUnconfirmed.add(rule);
        }
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long visit=foregroundVisit;
        long interaction=userInteraction;
        if(!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
            recordPopup(pkg,false);
            schedule();
            return;
        }
        rememberClick(target);
        popupVerifying=true;
        main.postDelayed(()->verifyObservedPopup(pkg,visit,interaction,target,rule,false),350);
    }
    private void verifyObservedPopup(String pkg,long visit,long interaction,AccessibilityNodeInfo target,PopupAdRules.Rule rule,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean uninterrupted=sameVisit&&interaction==userInteraction;
        boolean verifiedWindow=uninterrupted&&pendingWindow==null&&matchesWindow(root,pkg,activeWindow);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        boolean confirmed=verifiedWindow&&!visibleNode(nodes,target)&&(rule==null||!popupAds.visible(root,activity,rule,nodes));
        if(!confirmed&&uninterrupted&&!retried){
            main.postDelayed(()->verifyObservedPopup(pkg,visit,interaction,target,rule,true),500);
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
    private GestureDescription tapGesture(AccessibilityNodeInfo target){
        Rect bounds=new Rect();
        target.getBoundsInScreen(bounds);
        AccessibilityWindowInfo window=target.getWindow();
        if(window==null||window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!window.isActive()
                ||window.getId()!=target.getWindowId()||bounds.isEmpty()||!screen(target).contains(bounds))return null;
        int display=Build.VERSION.SDK_INT>=30?window.getDisplayId():Display.DEFAULT_DISPLAY;
        if(display==Display.INVALID_DISPLAY||!containsPoint(window,bounds.centerX(),bounds.centerY()))return null;
        List<AccessibilityWindowInfo> windows=Build.VERSION.SDK_INT>=30
                ?service.getWindowsOnAllDisplays().get(display):service.getWindows();
        if(windows==null||windows.isEmpty())return null;
        boolean listed=false;
        for(AccessibilityWindowInfo other:windows){
            if(other.getId()==window.getId()){listed=true;continue;}
            if(other.getLayer()>=window.getLayer()&&containsPoint(other,bounds.centerX(),bounds.centerY()))return null;
        }
        if(!listed)return null;
        Path path=new Path();
        path.moveTo(bounds.exactCenterX(),bounds.exactCenterY());
        GestureDescription.Builder gesture=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path,0,35));
        if(Build.VERSION.SDK_INT>=30)gesture.setDisplayId(display);
        return gesture.build();
    }
    private static boolean containsPoint(AccessibilityWindowInfo window,int x,int y){
        if(Build.VERSION.SDK_INT>=30){
            android.graphics.Region touchable=new android.graphics.Region();
            window.getRegionInScreen(touchable);
            return touchable.contains(x,y);
        }
        Rect bounds=new Rect();
        window.getBoundsInScreen(bounds);
        return bounds.contains(x,y);
    }
    private boolean sendGesture(GestureDescription gesture,Runnable completed,Runnable cancelled){
        if(gesturesInFlight!=0||!automationAllowed())return false;
        boolean sent=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback(){
            @Override public void onCompleted(GestureDescription delivered){gesturesInFlight--;if(!closed)completed.run();}
            @Override public void onCancelled(GestureDescription interrupted){gesturesInFlight--;if(!closed)cancelled.run();}
        },main);
        if(sent)gesturesInFlight++;
        return sent;
    }
    private void verifyPopup(String pkg,long visit,long interaction,PopupMatch hit,boolean retried){
        if(closed)return;
        CustomSkipRule rule=hit.rule;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean uninterrupted=sameVisit&&interaction==userInteraction;
        boolean verifiedWindow=uninterrupted&&pendingWindow==null&&matchesWindow(root,pkg,activeWindow);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        boolean confirmed=verifiedWindow&&!PopupMatch.promptVisible(nodes,rule)&&(hit.target==null||!visibleNode(nodes,hit.target));
        if(!confirmed&&uninterrupted&&!retried){
            main.postDelayed(()->verifyPopup(pkg,visit,interaction,hit,true),500);
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
        if(confirmed)showFeedback(pkg,"净屏 · 弹窗已关闭");
    }
    private void verifySplash(String pkg,long visit,long interaction,SkipDiagnostics.Attempt attempt,
                              AccessibilityNodeInfo target,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        if(sameVisit&&interaction!=userInteraction){finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.USER_INPUT);return;}
        boolean verifiedWindow=sameVisit&&pendingWindow==null&&matchesWindow(root,pkg,activeWindow);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        if(verifiedWindow&&!visibleNode(nodes,target)){finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.CONFIRMED);return;}
        if(sameVisit&&!retried){main.postDelayed(()->verifySplash(pkg,visit,interaction,attempt,target,true),500);return;}
        finishSplash(pkg,visit,attempt,verifiedWindow&&!nodes.isEmpty()?SkipDiagnostics.Phase.TARGET_STILL_VISIBLE
                :belongsTo(root,pkg)||root==null?SkipDiagnostics.Phase.UNCONFIRMED:SkipDiagnostics.Phase.LEFT_WINDOW);
    }
    private void finishSplash(String pkg,long visit,SkipDiagnostics.Attempt attempt,SkipDiagnostics.Phase phase){
        if(closed)return;
        if(visit==foregroundVisit&&pkg.equals(foreground)){splashVerifying=false;schedule();}
        diagnostics.note(pkg,attempt,phase);
        boolean confirmed=phase==SkipDiagnostics.Phase.CONFIRMED;
        try{store.recordSplash(pkg,confirmed);}catch(RuntimeException ignored){/* History failure never retries a screen click. */}
        if(confirmed)showFeedback(pkg,"净屏 · 开屏已跳过");
    }
    private static boolean visibleNode(List<AccessibilityNodeInfo> nodes,AccessibilityNodeInfo target){
        if(nodes.isEmpty())return true;
        Rect before=new Rect();target.getBoundsInScreen(before);
        Rect after=new Rect();
        String resource=target.getViewIdResourceName(), oldLabel=UiDriver.text(target);
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser())continue;
            if(target.equals(node))return true;
            if(node.getWindowId()!=target.getWindowId()||!Objects.equals(node.getClassName(),target.getClassName())
                    ||!Objects.equals(node.getPackageName(),target.getPackageName()))continue;
            node.getBoundsInScreen(after);
            if(!Rect.intersects(before,after))continue;
            if(resource!=null&&resource.equals(node.getViewIdResourceName()))return true;
            String newLabel=UiDriver.text(node);
            if(resource==null&&!oldLabel.isEmpty()&&(oldLabel.equals(newLabel)||(skipLabel(oldLabel)&&skipLabel(newLabel))))return true;
        }
        return false;
    }
    private void showFeedback(String pkg,String message){
        if(closed||service.busy||!store.skipToastEnabled()||!pkg.equals(foreground))return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(belongsTo(root,pkg)&&displayId(root)==Display.DEFAULT_DISPLAY)feedback.show(message);
    }
    static boolean skipLabel(String value){
        return !value.isEmpty()&&(AD_CLOSE_LABEL.matcher(value).matches()||SKIP_LABEL.matcher(normalizedLabel(value)).matches());
    }
    private static String normalizedLabel(String value){
        if(value.isEmpty()||value.length()>80)return "";
        return SPACE.matcher(Normalizer.normalize(value,Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
    }
    static boolean explicitAd(String value){
        if(AD_CLOSE_LABEL.matcher(value).matches())return true;
        String normalized=normalizedLabel(value);
        return SKIP_LABEL.matcher(normalized).matches()&&EXPLICIT_SKIP.matcher(normalized).lookingAt();
    }
    static boolean rewardAd(String value){
        return !value.isEmpty()&&REWARDED_AD.matcher(value).find();
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
        boolean usesGesture(){return tap||(rule!=null&&rule.gesture);}
    }
    private static SplashHit splashHit(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom,long elapsed){
        return splashHit(root,rules,activity,custom,elapsed,null,UiDriver.walkComplete(root),false);
    }
    private static SplashHit splashHit(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom,
                                       long elapsed,Consumer<SkipDiagnostics.Phase> trace,List<AccessibilityNodeInfo> nodes,
                                       boolean ruleOnly){
        if(root==null)return noHit(trace,SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
        Rect screen=new Rect();root.getBoundsInScreen(screen);
        if(screen.width()<100||screen.height()<100)return noHit(trace,SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
        String pkg=String.valueOf(root.getPackageName());
        if(CustomSkipRule.protectedPackage(pkg)||PopupAdRules.protectedActivity(activity))return noHit(trace,SkipDiagnostics.Phase.GUARDED);
        int window=root.getWindowId();
        if(nodes.isEmpty())return noHit(trace,SkipDiagnostics.Phase.TREE_UNAVAILABLE);
        List<CustomSkipRule> scoped=new ArrayList<>();
        for(CustomSkipRule rule:custom)if(rule.appliesTo(pkg,activity,CustomSkipRule.SPLASH))scoped.add(rule);
        if(ruleOnly&&scoped.isEmpty())return noHit(trace,SkipDiagnostics.Phase.PAGE_MISMATCH);
        boolean useDefaults=!ruleOnly&&scoped.stream().noneMatch(rule->rule.replaceDefaults);
        boolean onboarding=useDefaults&&activity!=null&&ONBOARDING_PAGE.matcher(activity).find();
        boolean ad=false;
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser())continue;
            if(unsafeWindowNode(node,true))return noHit(trace,SkipDiagnostics.Phase.GUARDED);
            if(useDefaults&&!ad&&belongsTo(node,pkg)&&node.getWindowId()==window){
                ad=adMarker(label(node.getText()))||adMarker(label(node.getContentDescription()));
            }
        }
        SplashRules.Prepared defaults=useDefaults?rules.prepare(pkg,activity,elapsed,nodes):null;
        boolean readable=false,missingAd=false,unsafe=false,ambiguous=false;
        SplashHit best=null;
        Rect bounds=new Rect();
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser()||node.getWindowId()!=window||!belongsTo(node,pkg))continue;
            String text=label(node.getText()),desc=label(node.getContentDescription());
            CustomSkipRule userRule=null;
            for(CustomSkipRule rule:scoped)if(rule.matchesButton(node)){userRule=rule;break;}
            SplashRules.Action action=userRule==null&&defaults!=null?defaults.action(node,text.isEmpty()?desc:text):null;
            boolean known=userRule!=null||action!=null;
            if(!known&&(!useDefaults||(!skipLabel(text)&&!skipLabel(desc))))continue;
            readable=true;
            boolean explicit=explicitAd(text)||explicitAd(desc);
            if(!known&&!explicit&&onboarding){unsafe=true;continue;}
            if(!known&&!explicit&&!ad){missingAd=true;continue;}
            if(!node.isEnabled()||node.isCheckable()||!safeActionLabel(text)||!safeActionLabel(desc)){unsafe=true;continue;}
            boolean tap=action==SplashRules.Action.TAP;
            if(tap&&!safeTouchLeaf(node)){unsafe=true;continue;}
            AccessibilityNodeInfo click=tap?node:userRule==null?clickable(node):ruleClickTarget(node,userRule);
            if(!belongsTo(click,pkg)||click.getWindowId()!=window){unsafe=true;continue;}
            click.getBoundsInScreen(bounds);
            boolean horizontal=bounds.left>=screen.left+screen.width()*.5||((explicit||known)&&bounds.right<=screen.left+screen.width()*.5);
            boolean bottom=bounds.top>=screen.top+screen.height()*.75;
            boolean corner=horizontal&&(bounds.bottom<=screen.top+screen.height()*.27||((explicit||known)&&bottom));
            boolean adFooter=explicit&&ad&&bottom;
            if(!screen.contains(bounds)||(!corner&&!adFooter)
                    ||bounds.width()>screen.width()*.48||bounds.height()>screen.height()*.16||bounds.width()<12||bounds.height()<12
                    ||(!tap&&userRule==null&&!safeClickTarget(click))){unsafe=true;continue;}
            int priority=userRule!=null?4:known?3:explicit?2:1;
            if(best==null||best.priority<priority){
                best=new SplashHit(click,userRule,priority,tap);
                ambiguous=false;
            }else if(best.priority==priority&&!best.target.equals(click)){
                ambiguous=true;
            }
        }
        if(ambiguous)return noHit(trace,SkipDiagnostics.Phase.AMBIGUOUS);
        if(best==null){
            if(unsafe)return noHit(trace,SkipDiagnostics.Phase.TARGET_UNSAFE);
            SkipDiagnostics.Phase reason=trace!=null&&useDefaults?rules.explainMiss(pkg,activity,elapsed,window,nodes):null;
            if(reason!=null)return noHit(trace,reason);
            return noHit(trace,missingAd?SkipDiagnostics.Phase.AD_EVIDENCE_MISSING
                    :readable?SkipDiagnostics.Phase.NO_RULE_MATCH:SkipDiagnostics.Phase.NO_READABLE_TARGET);
        }
        if(trace!=null)trace.accept(SkipDiagnostics.Phase.TARGET_FOUND);
        return best;
    }
    private static boolean adMarker(String value){
        return !value.isEmpty()&&AD_MARKER.matcher(normalizedLabel(value)).matches();
    }
    static boolean unsafeWindowNode(AccessibilityNodeInfo node,boolean anyInput){
        if(node.isEditable()&&(anyInput||node.isFocused()))return true;
        String text=label(node.getText()),desc=label(node.getContentDescription());
        return rewardAd(text)||rewardAd(desc)||(node.isClickable()&&(consentLabel(text)||consentLabel(desc)));
    }
    private static SplashHit noHit(Consumer<SkipDiagnostics.Phase> trace,SkipDiagnostics.Phase phase){
        if(trace!=null)trace.accept(phase);
        return null;
    }
    private static boolean consentLabel(String value){
        return !value.isEmpty()&&CONSENT.matcher(value).matches();
    }
    static AccessibilityNodeInfo ruleClickTarget(AccessibilityNodeInfo node,CustomSkipRule rule){
        AccessibilityNodeInfo target=clickable(node);
        if(target==null){
            if(!rule.gesture||!node.isVisibleToUser()||!node.isEnabled()||!safeTouchLeaf(node))return null;
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
        if(node.isEditable()||node.isCheckable()||node.getChildCount()!=0)return false;
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
