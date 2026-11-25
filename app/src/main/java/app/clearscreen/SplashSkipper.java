package app.clearscreen;

import android.app.KeyguardManager;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.*;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.text.TextUtils;
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
    private static final String WECHAT_PACKAGE="com.tencent.mm";
    private static final String WECHAT_PLUGIN_SPLASH=WECHAT_PACKAGE+".plugin.appbrand.ui.AppBrandPluginUI";
    private static final String WECHAT_MINI_APP_SPLASH=WECHAT_PACKAGE+".plugin.appbrand.ui.AppBrandUI00";
    private static final String OOKCAM_PACKAGE="com.okcam.ookcam";
    private static final String OOKCAM_AD_SPLASH=OOKCAM_PACKAGE+".SplashActivity";
    private static final String QIYI_LITE_PACKAGE="com.qiyi.video.lite";
    private static final String QIYI_LITE_HOME=QIYI_LITE_PACKAGE+".homepage.HomeActivity";
    private static final String QIYI_LITE_RESOURCE=QIYI_LITE_PACKAGE+":id/unused_res_a";
    private static final long QUICK_SCAN_MS=65;
    private static final long USER_INPUT_PAUSE_MS=900;
    private static final long OWN_CLICK_ECHO_MS=550;
    private static final long PREVIEW_INTERVAL_MS=250;
    private static final int PREVIEW_RETRY_LIMIT=6;
    private static final long POPUP_PROBE_INTERVAL_MS=240;
    private static final long POPUP_COOLDOWN_MS=750;
    private static final long MEDIA_COOLDOWN_MS=1500;
    private static final Pattern SPACE=Pattern.compile("\\s+");
    private static final Pattern CONSENT=Pattern.compile(
            "同意.*|允[许許].*|接受.*|确认支付|確認支付|立即授[权權]|allow(?: .*)?|i agree",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SKIP=Pattern.compile(
            "(?:跳过(?:此)?广告|跳過(?:此)?廣告|略过广告|略過廣告|skip(?:this)?ads?|skipadvertisement|広告をスキップ|광고건너뛰기)");
    private static final String SKIP_WORD="(?:(?:点击|點擊|点此|點此)?(?:跳过|跳過)|略过|略過|skip|スキップ|건너뛰기)";
    private static final String COUNTDOWN="(?:[0-9]{1,2}(?:s|秒|초)?)";
    private static final String TIMER="(?:[·:|,]?"+COUNTDOWN+"|\\("+COUNTDOWN+"\\))";
    private static final String AD_ACTION="(?:"+EXPLICIT_SKIP.pattern()+"|(?:广告|廣告)[·:|,]?(?:跳过|跳過)|关闭(?:此)?广告|關閉(?:此)?廣告|(?:close|dismiss)(?:this)?ad(?:vertisement)?)";
    private static Pattern timedLabel(String action){
        return Pattern.compile("(?:"+action+")(?:"+TIMER+")?|(?:"+COUNTDOWN+"[·:|,]?)(?:"+action+")");
    }
    private static final Pattern SKIP_LABEL=timedLabel(SKIP_WORD);
    private static final Pattern EXPLICIT_LABEL=timedLabel(AD_ACTION);
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
    private final RulePacks rulePacks;
    private SplashRules rules;
    private PopupAdRules popupAds;
    private final SkipFeedback feedback;
    private final SkipDiagnostics diagnostics = SkipDiagnostics.LOCAL;
    private final MissedAdCapture missedCapture = MissedAdCapture.LOCAL;
    private final Object diagnosticOwner = new Object();
    private SkipDiagnostics.Attempt diagnosticAttempt;
    private final RulePreview preview=RulePreview.LOCAL;
    private String foreground="",launcher="";
    private String activity="";
    private long deadline, foregroundVisit, nextPopupClick, nextGenericProbe, popupDue, userInteraction;
    private long scanAt=Long.MAX_VALUE, nextSplashProbe, lastSplashProbe;
    private long activitySince;
    private long lastPageDownVisit=-1, launchStarted;
    private final TimedAd.Watch launchAd=new TimedAd.Watch(), popupAd=new TimedAd.Watch();
    private long pauseUntil, nextPopupProbe, nextPreviewProbe, previewDue=Long.MAX_VALUE, lastHotSplash;
    private boolean exactActivitySplash;
    private int gesturesInFlight, activityWindow=-1;
    private final ArrayDeque<ClickEcho> ownClicks=new ArrayDeque<>();
    private static final class ClickEcho {
        final AccessibilityNodeInfo target;final long started,until;final String label;final boolean menuAction;
        ClickEcho(AccessibilityNodeInfo target,long started,String label,boolean menuAction){
            this.target=target;this.started=started;this.label=label;this.menuAction=menuAction;until=SystemClock.elapsedRealtime()+OWN_CLICK_ECHO_MS;
        }
        boolean matches(AccessibilityEvent e,AccessibilityNodeInfo source){
            if(source!=null)return target.equals(source);
            long age=e.getEventTime()-started;
            if(age<0||age>OWN_CLICK_ECHO_MS||e.getWindowId()!=target.getWindowId()
                    ||!TextUtils.equals(e.getPackageName(),target.getPackageName())||!TextUtils.equals(e.getClassName(),target.getClassName()))return false;
            // Android explicitly attributes this event to accessibility ACTION_CLICK. On
            // transient menu rows it can drop the source and deliver empty or merged text.
            // Match only a still-outstanding menu action of the same class, app and window;
            // regular physical clicks (action=0) continue to require unique text evidence.
            if(menuAction&&e.getAction()==AccessibilityNodeInfo.ACTION_CLICK)return true;
            if(label.isEmpty())return false;
            return label.equals(SplashSkipper.label(e.getContentDescription()))
                    ||(e.getText().size()==1&&label.equals(SplashSkipper.label(e.getText().get(0))));
        }
    }
    private boolean attempted,closed,popupPending,popupVerifying,splashWaiting,splashVerifying,mediaPending;
    private boolean popupRootRetryPending;
    private Runnable delayedSplash;
    private Runnable pendingWindow;
    private String pendingPackage="";
    private String pendingClass="";
    private int pendingWindowId=-1;
    private long pendingStateAt;
    private int popupRetries;
    private int previewRetries;
    private int popupStep;
    private int activeWindow=-1;
    private long nextMediaProbe;
    private final Set<CustomSkipRule> attemptedPopups=new HashSet<>();
    private final Map<CustomSkipRule,Integer> confirmedPopups=new HashMap<>();
    private final Set<Integer> genericUnconfirmedWindows=new HashSet<>();
    private final Map<PopupAdRules.Rule,Integer> catalogAttempts=new HashMap<>();
    private final Set<PopupAdRules.Rule> catalogUnconfirmed=new HashSet<>();
    private List<CustomSkipRule> popupRuleSnapshot=Collections.emptyList();
    SplashSkipper(CleanerService service){
        this.service=service;store=new Store(service);rulePacks=RulePacks.get(service);useCurrentCatalog();
        feedback = new SkipFeedback(service, main);
        try{android.content.pm.ResolveInfo info=service.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);if(info!=null&&info.activityInfo!=null)launcher=info.activityInfo.packageName;}catch(Exception ignored){}
    }
    private void useCurrentCatalog(){
        RulePacks.State state=rulePacks.state();
        rules=state.splashRules;
        popupAds=state.popupRules;
    }
    void close(){
        closed=true;
        diagnostics.disconnected(diagnosticOwner);
        missedCapture.stop(foreground,true);
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
        previewRetries=PREVIEW_RETRY_LIMIT;
        previewDue=preview.observes(foreground)?0:Long.MAX_VALUE;
        schedule();
        return true;
    }
    void event(AccessibilityEvent event){
        if(closed||event.getPackageName()==null)return;
        String pkg=event.getPackageName().toString();
        Set<String> media=store.mediaAssistEnabled();
        if(service.busy||(!store.splashEnabled()&&!store.popupEnabled()&&media.isEmpty()
                &&!preview.hasSession()&&!diagnostics.hasSession()&&!missedCapture.awaiting(pkg)
                &&!missedCapture.awaiting(foreground))){
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
        if(!previewing&&(type==AccessibilityEvent.TYPE_VIEW_CLICKED||type==AccessibilityEvent.TYPE_VIEW_SCROLLED
                ||type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
                &&(inActiveWindow||inPendingWindow||popupHostInput(pkg,event.getWindowId()))
                &&(type!=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED||editableSource(event))){
            if(type==AccessibilityEvent.TYPE_VIEW_CLICKED&&isOwnClick(event))return;
            if(menuVisit==foregroundVisit&&popupVerifying)android.util.Log.i("ClearScreenMenu","Menu interrupted by event type="+type);
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
            if(relevant){
                if(inActiveWindow&&popupPending&&popupAd.sameWindow(activeWindow))popupDue=0;
                if(inActiveWindow||type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)missedCapture.requestProbe(pkg);
                queueWindowContent(media);
            }
        }
        schedule();
    }
    private static boolean editableSource(AccessibilityEvent event){
        AccessibilityNodeInfo source=event.getSource();
        return source!=null&&source.isEditable();
    }
    private boolean popupHostInput(String pkg,int windowId){
        return popupVerifying&&windowId>=0&&windowId==activityWindow&&pkg.equals(foreground)
                &&matchesWindow(service.getRootInActiveWindow(),pkg,windowId);
    }
    private boolean isOwnClick(AccessibilityEvent event){
        long now=SystemClock.elapsedRealtime();
        AccessibilityNodeInfo source=event.getSource();
        for(Iterator<ClickEcho> it=ownClicks.iterator();it.hasNext();){
            ClickEcho echo=it.next();
            if(now>echo.until){it.remove();continue;}
            if(echo.matches(event,source)){it.remove();return true;}
        }
        if(menuVisit==foregroundVisit&&popupVerifying)android.util.Log.i("ClearScreenMenu","Unattributed click: source="+(source!=null)
                +", action="+event.getAction()+", labels="+event.getText().size()+", pendingActions="+ownClicks.size());
        return false;
    }
    private void rememberClick(AccessibilityNodeInfo target){rememberClick(target,SystemClock.uptimeMillis(),"");}
    private void rememberClick(AccessibilityNodeInfo target,long started,String label){
        long now=SystemClock.elapsedRealtime();ownClicks.removeIf(e->e.until<now);
        ownClicks.addLast(new ClickEcho(target,started,label,false));
    }
    private void stopCurrentInput(){
        launchAd.clear();
        popupAd.clear();
        deadline=0;
        cancelWindowRetry();
        cancelDelayedSplash();
        popupPending=false;
        popupRootRetryPending=false;
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
    private AccessibilityNodeInfo lastMenuTrigger;
    private long menuVisit=-1;
    private final RecentOutcome recentOutcome=new RecentOutcome();
    String lastOutcome(String pkg,boolean splash){return recentOutcome.read(pkg,splash);}
    private String recentApp="";
    private long recentAt;
    String recentApp(){return SystemClock.elapsedRealtime()-recentAt<=300_000?recentApp:"";}
    private void acceptWindow(String pkg,String windowClass,int windowId,long launchAt){
        if(transientSystem(pkg)){
            feedback.clear();
            stopCurrentInput();
            return;
        }
        missedCapture.verifiedWindow(pkg);
        long now=SystemClock.elapsedRealtime();
        if(!pkg.isEmpty()&&!pkg.equals(service.getPackageName())&&!pkg.equals(launcher)
                &&!CustomSkipRule.protectedPackage(pkg)&&!pkg.startsWith("com.android.launcher")){
            recentOutcome.visit(pkg,now);
            recentApp=pkg;recentAt=now;
        }else if(pkg.equals(service.getPackageName())&&foreground.equals(recentApp)&&!recentApp.isEmpty()){
            recentAt=now;
        }
        boolean newVisit=!pkg.equals(foreground);
        boolean newWindow=activeWindow!=windowId;
        if(newVisit){
            useCurrentCatalog();
            feedback.clear();
            foreground=pkg;
            activity="";
            activityWindow=-1;
            activitySince=now;
            ownClicks.clear();
            attempted=false;
            foregroundVisit++;
            stopCurrentInput();
            resetPopupProgress();
            genericUnconfirmedWindows.clear();
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
            if(launchAt>=0)armSplash(launchAt,false);
        }
        String previousActivity=activity;
        boolean newActivity=rememberActivity(pkg,windowClass,windowId,launchAt>=0?launchAt:now);
        if(newWindow){
            popupAd.clear();
            launchAd.clear();
            if(deadline>0)deadline=Math.min(deadline,launchStarted+WINDOW_MS);
            if(popupRootRetryPending){
                popupPending=false;
                popupRootRetryPending=false;
            }
            activeWindow=windowId;
            nextGenericProbe=0;
            nextPopupProbe=0;
        }
        if(!newVisit&&newActivity&&newWindow&&launchAt>=0&&!splashVerifying
                &&reviewedInAppSplash(pkg,activity)&&!activity.equals(previousActivity)){
            // Some apps open a separate ad window without leaving the foreground package.
            attempted=false;
            nextSplashProbe=Math.max(now,pauseUntil);
            armSplash(launchAt,true);
        }else if(!newVisit&&newActivity&&pkg.equals("com.tencent.qqlive")
                &&activity.equals("com.tencent.qqlive.qadsplash.template.QAdHotSplashActivity")
                &&now>=pauseUntil&&now-lastHotSplash>=1500&&!splashVerifying){
            lastHotSplash=now;
            attempted=false;
            nextSplashProbe=now;
            armSplash(now,false);
        }
    }
    private static boolean reviewedInAppSplash(String pkg,String activity){
        return (WECHAT_PACKAGE.equals(pkg)&&(WECHAT_PLUGIN_SPLASH.equals(activity)||WECHAT_MINI_APP_SPLASH.equals(activity)))
                ||(OOKCAM_PACKAGE.equals(pkg)&&OOKCAM_AD_SPLASH.equals(activity))
                ||("com.coolapk.market".equals(pkg)&&"com.coolapk.market.view.splash.SplashAdActivity".equals(activity));
    }
    private void armSplash(long now,boolean exactActivityOnly){
        launchStarted=now;
        launchAd.clear();
        cancelDelayedSplash();
        exactActivitySplash=exactActivityOnly;
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
            if(previewDue==Long.MAX_VALUE){
                previewDue=nextPreviewProbe;
                previewRetries=PREVIEW_RETRY_LIMIT;
            }
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
    private boolean rememberActivity(String pkg,String windowClass,int windowId,long observedAt){
        if(!windowClass.contains(".")||windowClass.startsWith("android.")||windowClass.startsWith("androidx."))return false;
        if(windowClass.startsWith("."))windowClass=pkg+windowClass;
        if(windowId==activityWindow&&windowClass.equals(activity))return false;
        try{
            service.getPackageManager().getActivityInfo(new ComponentName(pkg,windowClass),0);
            activitySince=observedAt;
            activity=windowClass;
            activityWindow=windowId;
            return true;
        }catch(android.content.pm.PackageManager.NameNotFoundException|SecurityException ignored){/* Dialog classes are not activities. */}
        return false;
    }
    private boolean excluded(String pkg){return pkg.equals(service.getPackageName())||pkg.equals(launcher)||pkg.equals("com.android.settings")||pkg.startsWith("com.android.launcher")||store.splashExcluded().contains(pkg);}
    private List<CustomSkipRule> customRules(){
        try{return store.activeRules();}catch(IllegalArgumentException e){return Collections.emptyList();}
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
        return !genericUnconfirmedWindows.contains(activeWindow)&&defaultPopupAllowed();
    }
    private boolean catalogPopupAllowed(){
        return defaultPopupAllowed()&&popupAds.hasRules(foreground,activity,activityAge(),this::catalogRuleAvailable);
    }
    private boolean catalogRuleAvailable(PopupAdRules.Rule rule){
        if(rule.pageDown())return activeWindow>=0&&activityWindow==activeWindow&&lastPageDownVisit!=foregroundVisit;
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
        attemptedPopups.clear();confirmedPopups.clear();popupStep=0;
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
    private void note(SkipDiagnostics.Phase phase){
        diagnostics.note(foreground,diagnosticAttempt,phase);
        missedCapture.note(foreground,MissedAdCapture.Mode.SPLASH,phase);
    }
    private void schedule(){
        if(closed)return;
        long now=SystemClock.elapsedRealtime(), due=Long.MAX_VALUE;
        if(!service.busy&&preview.observes(foreground)){
            if(previewDue!=Long.MAX_VALUE)due=Math.max(now+QUICK_SCAN_MS,previewDue);
        }else if(!service.busy){
            if(scanSplash())due=Math.max(now+QUICK_SCAN_MS,nextSplashProbe);
            if(deadline>0&&diagnostics.observes(foreground,diagnosticAttempt))due=Math.min(due,Math.max(now,deadline));
            long captureProbe=missedCapture.nextProbe(foreground);
            if(captureProbe!=Long.MAX_VALUE)due=Math.min(due,Math.max(now+QUICK_SCAN_MS,captureProbe));
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
                // The initial tree can precede drawing, with no later accessibility event.
                if(inspectPreview()&&previewRetries>0){
                    previewRetries--;
                    previewDue=nextPreviewProbe;
                }
            }
            schedule();
            return;
        }
        if(excluded(foreground)||CustomSkipRule.protectedPackage(foreground))return;
        boolean checkCapture=missedCapture.nextProbe(foreground)<=now;
        boolean checkPopup=popupPending&&store.popupEnabled()&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&now>=pauseUntil&&now>=popupReadyAt();
        boolean retryingPopupRoot=popupRootRetryPending;
        if(checkPopup||!store.popupEnabled()){
            popupPending=false;
            popupRootRetryPending=false;
        }
        boolean checkMedia=mediaPending&&!popupVerifying&&!splashWaiting&&!splashVerifying
                &&now>=pauseUntil&&now>=nextMediaProbe;
        boolean checkSplash=scanSplash()&&now>=nextSplashProbe;
        if(!checkSplash&&!checkPopup&&!checkMedia){
            if(checkCapture)sampleMissedAdOnly();
            schedule();return;
        }
        if(checkSplash){
            long age=launchAge();
            lastSplashProbe=SystemClock.elapsedRealtime();
            nextSplashProbe=lastSplashProbe+(age<800?90:age<3000?175:330);
        }
        if(screenLocked()){
            if(checkSplash)note(SkipDiagnostics.Phase.GUARDED);
            if(checkCapture)missedCapture.sample(foreground,"",activeWindow,null,Collections.emptyList());
            stopCurrentInput();
            return;
        }
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(root==null){
            if(checkSplash)note(SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
            if(checkCapture)missedCapture.defer(foreground);
            if(checkPopup)retryUnreadablePopup(retryingPopupRoot);
            if(checkMedia)mediaPending=false;
            schedule();return;
        }
        if(!belongsTo(root,foreground)){
            if(checkSplash)note(SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
            if(checkCapture)missedCapture.defer(foreground);
            if(scanSplash()&&launchAge()<=1200){schedule();return;}
            deadline=0;
            if(checkCapture)schedule();
            return;
        }
        boolean captureWindow=checkCapture&&root.getWindowId()==activeWindow;
        boolean captureProtected=captureWindow&&PopupAdRules.protectedActivity(activity);
        boolean captureNodes=captureWindow&&!captureProtected&&missedCapture.wantsDetails(foreground);
        List<AccessibilityNodeInfo> nodes=checkSplash||checkPopup||captureNodes?UiDriver.walkComplete(root):Collections.emptyList();
        if(captureWindow){
            missedCapture.sample(foreground,activity,activeWindow,
                    captureProtected?null:screen(root),nodes);
        }else if(checkCapture)missedCapture.defer(foreground);
        if(checkSplash){
            long normalEnd=launchStarted+WINDOW_MS;
            TimedAd.Evidence evidence=!exactActivitySplash&&activeWindow==root.getWindowId()
                    ?TimedAd.inspect(root,nodes,screen(root)):null;
            if(evidence!=null&&evidence.seconds>=0)recentOutcome.observe(foreground,true,"上次观察到广告倒计时，尚未确认关闭。");
            boolean stillSame=launchAd.matches(evidence,now);
            if(now<normalEnd||stillSame){
                deadline=Math.max(normalEnd,launchAd.observe(evidence,now));
            }else{
                deadline=0;
                launchAd.clear();
            }
            List<CustomSkipRule> custom=customRules();
            Consumer<SkipDiagnostics.Phase> trace=diagnostics.observes(foreground,diagnosticAttempt)
                    ||missedCapture.observing(foreground,MissedAdCapture.Mode.SPLASH)?this::note:null;
            SplashHit hit=deadline>now?splashHit(root,rules,splashActivity(root),custom,launchAge(),trace,nodes,splashMode()):null;
            if(hit!=null){
                if(checkPopup){
                    popupPending=true;
                    popupRootRetryPending=retryingPopupRoot;
                }
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
            if(nodes.isEmpty())retryUnreadablePopup(retryingPopupRoot);
            else if(scanPopup(root,nodes))return;
            else if(popupRetries>0){popupRetries--;popupPending=true;}
            else if((genericPopupAllowed()||catalogPopupAllowed())
                    &&popupAd.observe(TimedAd.inspect(root,nodes,screen(root)),now)>now){
                // Some SDKs change their exit without posting a new content event.
                recentOutcome.observe(foreground,false,"上次观察到广告倒计时，尚未确认关闭。");
                popupPending=true;
                popupDue=now+650;
            }
        }
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
    private void sampleMissedAdOnly(){
        if(screenLocked()){
            missedCapture.sample(foreground,"",activeWindow,null,Collections.emptyList());
            return;
        }
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(activeWindow<0||!matchesWindow(root,foreground,activeWindow)){
            missedCapture.defer(foreground);
            return;
        }
        boolean guarded=PopupAdRules.protectedActivity(activity);
        List<AccessibilityNodeInfo> nodes=!guarded&&missedCapture.wantsDetails(foreground)
                ?UiDriver.walkComplete(root):Collections.emptyList();
        missedCapture.sample(foreground,activity,activeWindow,guarded?null:screen(root),nodes);
    }
    private void retryUnreadablePopup(boolean alreadyRetried){
        if(popupRetries>0){
            popupRetries--;
            popupPending=true;
        }else if(!alreadyRetried&&activeWindow>=0&&pendingWindow==null
                &&(catalogPopupAllowed()||genericPopupAllowed())){
            // A verified window may be temporarily unreadable without a later content event.
            popupRootRetryPending=true;
            popupPending=true;
        }
    }
    private long popupReadyAt(){return Math.max(nextPopupProbe,Math.max(popupDue,nextPopupClick));}
    private String splashActivity(AccessibilityNodeInfo root){return root.getWindowId()==activityWindow?activity:"";}
    private SplashMatch splashMode(){return exactActivitySplash?SplashMatch.EXACT_ACTIVITY_ONLY:SplashMatch.NORMAL;}
    private boolean inspectPreview(){
        List<CustomSkipRule> drafts=preview.rulesFor(foreground);
        if(drafts.isEmpty())return false;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean valid=matchesWindow(root,foreground,activeWindow);
        List<AccessibilityNodeInfo> nodes=valid?UiDriver.walkComplete(root):Collections.emptyList();
        Rect display=valid?screen(root):new Rect();
        boolean locked=screenLocked();
        boolean retry=false;
        for(int i=0;i<drafts.size();i++){
            CustomSkipRule rule=drafts.get(i);
            SkipDiagnostics.Phase result=!valid?SkipDiagnostics.Phase.WINDOW_UNAVAILABLE
                    :locked||PopupAdRules.protectedActivity(activity)?SkipDiagnostics.Phase.GUARDED
                    :previewRule(root,display,nodes,rule);
            preview.note(drafts,i,result);
            retry|=result==SkipDiagnostics.Phase.WINDOW_UNAVAILABLE||result==SkipDiagnostics.Phase.TREE_UNAVAILABLE
                    ||result==SkipDiagnostics.Phase.NO_READABLE_TARGET;
        }
        return retry;
    }
    private SkipDiagnostics.Phase previewRule(AccessibilityNodeInfo root,Rect display,List<AccessibilityNodeInfo> nodes,CustomSkipRule rule){
        SkipDiagnostics.Phase[] result={SkipDiagnostics.Phase.NO_RULE_MATCH};
        Consumer<SkipDiagnostics.Phase> trace=phase->result[0]=phase;
        List<CustomSkipRule> selection=Collections.singletonList(rule);
        if(CustomSkipRule.SPLASH.equals(rule.mode)){
            SplashHit hit=splashHit(root,SplashRules.EMPTY,splashActivity(root),selection,0,trace,nodes,SplashMatch.CUSTOM_ONLY);
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
        if(visit!=foregroundVisit||interaction!=userInteraction){schedule();return;}
        clickSplash(expected);
    }
    private void cancelDelayedSplash(){
        if(delayedSplash!=null)main.removeCallbacks(delayedSplash);
        delayedSplash=null;
        splashWaiting=false;
    }
    private long launchAge(){return Math.max(0,SystemClock.elapsedRealtime()-launchStarted);}
    private void clickSplash(SplashHit expected){
        if(!scanSplash()||!ready(foregroundVisit)){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        SplashHit hit=matchesWindow(root,foreground,expected.target.getWindowId())
                ?splashHit(root,rules,splashActivity(root),customRules(),launchAge(),null,UiDriver.walkComplete(root),splashMode()):null;
        if(hit==null||hit.rule!=expected.rule||hit.usesGesture()!=expected.usesGesture()
                ||!hit.target.equals(expected.target)){
            note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();return;
        }
        GestureDescription gesture=null;
        if(hit.usesGesture()){
            if(activeWindow<0||hit.target.getWindowId()!=activeWindow){
                note(SkipDiagnostics.Phase.TARGET_CHANGED);schedule();return;
            }
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
        ConfirmationWindow origin=new ConfirmationWindow(root);
        splashVerifying=true;
        Runnable verify=()->main.postDelayed(()->verifySplash(pkg,visit,interaction,attempt,target,origin,false),350);
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
        if(PopupAdRules.protectedActivity(activity)||nodes.isEmpty()
                ||("com.tencent.qqlive".equals(foreground)&&PlayerAd.blockedPlayer(nodes)))return false;
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
                String host=activity;
                main.postDelayed(()->finishCatalogPopup(visit,interaction,window,host,catalog),130);
                return true;
            }
        }
        if(hit==null&&genericPopupAllowed()){
            long now=SystemClock.elapsedRealtime();
            if(now<nextGenericProbe){
                popupPending=true;
                popupDue=nextGenericProbe;
            }else{
                nextGenericProbe=now+650;
                PopupMatch ad=PopupMatch.findUsingApp(root,activity,display,nodes);
                if(ad!=null){
                    popupVerifying=true;
                    long visit=foregroundVisit, interaction=userInteraction;
                    int window=root.getWindowId();
                    String host=activity;
                    main.postDelayed(()->finishGenericPopup(visit,interaction,window,host,ad),180);
                    return true;
                }
                if(beginAdMenu(root,nodes,display))return true;
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
    private boolean beginAdMenu(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,Rect display){
        boolean dislike=store.allowAdDislike()&&("com.sina.weibo.feed.DetailWeiboActivity".equals(activity)
                ||"com.sina.weibo.feed.SubCommentActiity".equals(activity));
        AccessibilityNodeInfo trigger=AdMenuEntry.find(root,nodes,display,dislike);
        if(trigger==null||!ready(foregroundVisit)||menuVisit==foregroundVisit&&trigger.equals(lastMenuTrigger))return false;
        String host=activity,pkg=foreground;long visit=foregroundVisit,input=userInteraction;
        ConfirmationWindow origin=new ConfirmationWindow(root,activityWindow,activity);
        menuVisit=visit;lastMenuTrigger=trigger;popupVerifying=true;
        // Do not count the entry click. Re-read both windows; the advertiser may open a different menu.
        main.postDelayed(()->{
            if(visit!=foregroundVisit)return;
            AccessibilityNodeInfo latest=service.getRootInActiveWindow();
            long started=SystemClock.uptimeMillis();
            if(input!=userInteraction||!ready(visit)||!store.popupEnabled()||!defaultPopupAllowed()
                    ||!host.equals(activity)||!matchesWindow(latest,pkg,origin.id)
                    ||!trigger.equals(AdMenuEntry.find(latest,UiDriver.walkComplete(latest),display,dislike))
                    ||!trigger.performAction(AccessibilityNodeInfo.ACTION_CLICK)){popupVerifying=false;schedule();return;}
            ownClicks.addLast(new ClickEcho(trigger,started,"",true));
            awaitMenu(visit,input,host,pkg,origin,trigger,SystemClock.elapsedRealtime()+2400);
        },130);
        return true;
    }
    private void awaitMenu(long visit,long input,String host,String pkg,ConfirmationWindow origin,
                           AccessibilityNodeInfo trigger,long until){
        main.postDelayed(()->{
            if(visit!=foregroundVisit)return;
            if(!store.popupEnabled()||!ready(visit)||input!=userInteraction||!host.equals(activity)||!defaultPopupAllowed()){
                popupVerifying=false;schedule();return;
            }
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(matchesWindow(root,pkg,activeWindow)&&origin.matchesDisplayOf(root)){
                List<AccessibilityNodeInfo> nodes=UiDriver.walkComplete(root);
                AdSurface.Exit exit=AdSurface.menuExit(root,nodes,screen(root));
                if(exit==null&&store.allowAdDislike()&&"com.sina.weibo".equals(pkg))exit=AdMenuEntry.dislike(root,nodes,screen(root),host);
                if(exit!=null){
                    String clickLabel=uniqueClickLabel(root,exit.button);
                    long at=SystemClock.uptimeMillis();
                    if(exit.button.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
                        ownClicks.addLast(new ClickEcho(exit.button,at,clickLabel,true));nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
                        confirmAdMenu(visit,input,host,pkg,origin,trigger,exit.button,0);return;
                    }
                }
            }
            if(SystemClock.elapsedRealtime()<until){awaitMenu(visit,input,host,pkg,origin,trigger,until);return;}
            popupVerifying=false;schedule();
        },120);
    }
    private void confirmAdMenu(long visit,long input,String host,String pkg,ConfirmationWindow origin,
                               AccessibilityNodeInfo trigger,AccessibilityNodeInfo exit,int attempt){
        main.postDelayed(()->{
            if(visit!=foregroundVisit)return;
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            boolean same=input==userInteraction&&pendingWindow==null&&host.equals(activity)&&matchesWindow(root,pkg,origin.id)&&origin.matchesDisplayOf(root);
            List<AccessibilityNodeInfo> nodes=same?UiDriver.walkComplete(root):Collections.emptyList();
            // The host can recycle the same view object for a content row. Verify actual
            // ad/menu semantics as well as host identity instead of treating a reused node ID as an ad.
            boolean confirmed=!nodes.isEmpty()&&AdMenuEntry.find(root,nodes,screen(root),store.allowAdDislike())==null
                    &&AdSurface.menuExit(root,nodes,screen(root))==null&&!visibleNode(nodes,exit);
            if(!confirmed&&input==userInteraction&&attempt<2){confirmAdMenu(visit,input,host,pkg,origin,trigger,exit,attempt+1);return;}
            if(!confirmed)android.util.Log.i("ClearScreenMenu","Menu result unconfirmed: sameHost="+host.equals(activity)
                    +", sameInput="+(input==userInteraction)+", sameWindow="+matchesWindow(root,pkg,origin.id)
                    +", completeNodes="+nodes.size()+", menuVisible="+(!nodes.isEmpty()&&AdSurface.menuExit(root,nodes,screen(root))!=null)
                    +", exitVisible="+(!nodes.isEmpty()&&visibleNode(nodes,exit)));
            if(confirmed)lastMenuTrigger=null;
            recordPopup(pkg,confirmed);finishPopupVerification();
        },attempt==0?320:430);
    }
    private void finishCatalogPopup(long visit,long interaction,int window,String host,PopupAdRules.Match expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(store.popupEnabled()&&ready(visit)&&host.equals(activity)&&!PopupAdRules.protectedActivity(activity)&&interaction==userInteraction&&catalogPopupAllowed()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(belongsTo(root,foreground)&&root.getWindowId()==window){
                PopupAdRules.Match current=popupAds.find(root,activity,screen(root),activityAge(),
                        UiDriver.walkComplete(root),this::catalogRuleAvailable);
                if(current!=null&&current.rule==expected.rule&&current.target.equals(expected.target)
                        &&current.anchor.equals(expected.anchor)&&(!current.rule.pageDown()||window==activityWindow)){
                    if(current.rule.pageDown())pageDown(root,current.target);
                    else clickObservedPopup(current.target,current.rule);
                    return;
                }
            }
        }
        schedule();
    }
    private void pageDown(AccessibilityNodeInfo root,AccessibilityNodeInfo pager){
        long visit=foregroundVisit,interaction=userInteraction;
        String pkg=foreground;
        ConfirmationWindow origin=new ConfirmationWindow(root,activityWindow,activity);
        lastPageDownVisit=visit;
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        if(!pager.performAction(ShortDramaAd.PAGE_DOWN)){
            recordPopup(pkg,false);
            schedule();
            return;
        }
        popupVerifying=true;
        main.postDelayed(()->{
            if(closed)return;
            boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
            AccessibilityNodeInfo current=service.getRootInActiveWindow();
            // The framework does not identify the caller of delayed scroll events.
            boolean confirmed=sameVisit&&interaction==userInteraction&&pendingWindow==null
                    &&activityWindow==origin.id&&activeWindow==origin.id&&activity.equals(origin.hostActivity)
                    &&matchesWindow(current,pkg,origin.id)&&origin.matchesDisplayOf(current)
                    &&ShortDramaAd.resumed(pager,screen(current),UiDriver.walkComplete(current));
            if(sameVisit){
                // Playback, not merely a missing hint, proves this individual ad ended.
                if(confirmed)lastPageDownVisit=-1;
                finishPopupVerification();
            }
            recordPopup(pkg,confirmed);
        },850);
    }
    private void finishGenericPopup(long visit,long interaction,int window,String host,PopupMatch expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(store.popupEnabled()&&ready(visit)&&host.equals(activity)&&!PopupAdRules.protectedActivity(activity)&&interaction==userInteraction&&genericPopupAllowed()){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(belongsTo(root,foreground)&&root.getWindowId()==window){
                PopupMatch current=PopupMatch.findUsingApp(root,activity,screen(root),UiDriver.walkComplete(root));
                if(current!=null&&current.touch==expected.touch&&expected.target.equals(current.target)&&expected.rule.context.equals(current.rule.context)){
                    clickObservedPopup(current.target,null,current.touch);
                    return;
                }
            }
        }
        schedule();
    }
    private void finishDelayedPopup(long visit,long interaction,int window,PopupMatch expected){
        if(visit!=foregroundVisit)return;
        popupVerifying=false;
        if(interaction!=userInteraction){schedule();return;}
        clickPopup(expected,window);
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
    private static final class ConfirmationWindow {
        final int id;
        private final int display;
        private final int hostWindow;
        private final String hostActivity;

        ConfirmationWindow(AccessibilityNodeInfo root){
            this(root,-1,"");
        }

        ConfirmationWindow(AccessibilityNodeInfo root,int hostWindow,String hostActivity){
            id=root.getWindowId();
            display=displayId(root);
            this.hostWindow=hostWindow;
            this.hostActivity=hostActivity;
        }

        boolean matchesDisplayOf(AccessibilityNodeInfo root){
            if(Build.VERSION.SDK_INT<30)return true;
            int current=displayId(root);
            if(display!=Display.INVALID_DISPLAY&&current!=Display.INVALID_DISPLAY)return display==current;
            return id>=0&&root.getWindowId()==id;
        }
    }
    private static boolean canReturnFrom(AccessibilityNodeInfo root,String pkg,int window){
        return window>=0&&matchesWindow(root,pkg,window)&&displayId(root)==Display.DEFAULT_DISPLAY;
    }
    private boolean returnedToPopupHost(AccessibilityNodeInfo root,String pkg,ConfirmationWindow origin){
        // Older Android releases can restore the host without emitting a window change.
        return origin.id>=0&&activeWindow==origin.id&&origin.hostWindow>=0&&origin.hostWindow!=origin.id
                &&activityWindow==origin.hostWindow&&activity.equals(origin.hostActivity)
                &&matchesWindow(root,pkg,origin.hostWindow);
    }
    private void clickPopup(PopupMatch expected,int window){
        long visit=foregroundVisit;
        if(!store.popupEnabled()||!ready(visit)){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(!matchesWindow(root,foreground,window)){schedule();return;}
        PopupMatch hit=PopupMatch.find(root,screen(root),popupRules());
        if(hit==null||hit.rule!=expected.rule||!Objects.equals(hit.target,expected.target)){schedule();return;}
        attemptedPopups.add(hit.rule);
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long interaction=userInteraction;
        ConfirmationWindow origin=new ConfirmationWindow(root,activityWindow,activity);
        Runnable verify=()->main.postDelayed(()->verifyPopup(pkg,visit,interaction,hit,origin,false),350);
        boolean sent;
        if(hit.rule.backsOut()){
            sent=canReturnFrom(root,pkg,window)
                    &&service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        }else if(hit.rule.gesture){
            GestureDescription gesture=hit.target.getWindowId()==window?tapGesture(hit.target):null;
            sent=gesture!=null&&sendGesture(gesture,verify,()->{
                if(visit==foregroundVisit&&pkg.equals(foreground))finishPopupVerification();
                recordPopup(pkg,false);
            });
            if(sent){rememberClick(hit.target);popupVerifying=true;return;}
        }else sent=hit.target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if(!sent){recordPopup(pkg,false);schedule();return;}
        if(hit.target!=null)rememberClick(hit.target);
        popupVerifying=true;
        verify.run();
    }
    private void clickObservedPopup(AccessibilityNodeInfo target,PopupAdRules.Rule rule){
        clickObservedPopup(target,rule,false);
    }
    private void clickObservedPopup(AccessibilityNodeInfo target,PopupAdRules.Rule rule,boolean touch){
        if(!ready(foregroundVisit)){schedule();return;}
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        if(!matchesWindow(root,foreground,target.getWindowId())){schedule();return;}
        ConfirmationWindow origin=new ConfirmationWindow(root,activityWindow,activity);
        if(rule==null){
            genericUnconfirmedWindows.add(origin.id);
        }else {
            catalogAttempts.merge(rule, 1, Integer::sum);
            catalogUnconfirmed.add(rule);
        }
        nextPopupClick=SystemClock.elapsedRealtime()+POPUP_COOLDOWN_MS;
        String pkg=foreground;
        long visit=foregroundVisit;
        long interaction=userInteraction;
        if(touch){
            GestureDescription gesture=tapGesture(target);
            if(gesture==null){recordPopup(pkg,false);schedule();return;}
            popupVerifying=true;
            boolean sent=sendGesture(gesture,()->main.postDelayed(()->verifyObservedPopup(pkg,visit,interaction,target,rule,origin,false),350),
                    ()->{if(visit==foregroundVisit)finishPopupVerification();recordPopup(pkg,false);});
            if(!sent){finishPopupVerification();recordPopup(pkg,false);}
            return;
        }
        String uniqueLabel=uniqueClickLabel(root,target);
        long started=SystemClock.uptimeMillis();
        if(!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
            recordPopup(pkg,false);
            schedule();
            return;
        }
        rememberClick(target,started,uniqueLabel);
        popupVerifying=true;
        main.postDelayed(()->verifyObservedPopup(pkg,visit,interaction,target,rule,origin,false),350);
    }
    private static String uniqueClickLabel(AccessibilityNodeInfo root,AccessibilityNodeInfo target){
        String expected=UiDriver.text(target);
        if(target.getWindowId()<0||target.getClassName()==null||expected.isEmpty())return "";
        boolean found=false;
        for(AccessibilityNodeInfo node:UiDriver.walkComplete(root)){
            if(!node.isVisibleToUser()||node.getWindowId()!=target.getWindowId()
                    ||!TextUtils.equals(node.getPackageName(),target.getPackageName())
                    ||!TextUtils.equals(node.getClassName(),target.getClassName())
                    ||(!expected.equals(label(node.getText()))&&!expected.equals(label(node.getContentDescription()))))continue;
            if(found||!target.equals(node))return "";
            found=true;
        }
        return found?expected:"";
    }
    private void verifyObservedPopup(String pkg,long visit,long interaction,AccessibilityNodeInfo target,
                                     PopupAdRules.Rule rule,ConfirmationWindow origin,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean uninterrupted=sameVisit&&interaction==userInteraction;
        boolean returnedToHost=returnedToPopupHost(root,pkg,origin);
        boolean verifiedWindow=uninterrupted&&pendingWindow==null&&(matchesWindow(root,pkg,activeWindow)||returnedToHost)
                &&origin.matchesDisplayOf(root);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        boolean confirmed=!nodes.isEmpty()&&!visibleNode(nodes,target)&&(rule==null||!popupAds.visible(root,activity,rule,nodes));
        if(!confirmed&&uninterrupted&&!retried){
            main.postDelayed(()->verifyObservedPopup(pkg,visit,interaction,target,rule,origin,true),500);
            return;
        }
        if(sameVisit){
            if(confirmed){
                if(returnedToHost)acceptWindow(pkg,"",origin.hostWindow,-1);
                if(rule==null)genericUnconfirmedWindows.remove(origin.id);
                else catalogUnconfirmed.remove(rule);
            }
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
    private void verifyPopup(String pkg,long visit,long interaction,PopupMatch hit,ConfirmationWindow origin,boolean retried){
        if(closed)return;
        CustomSkipRule rule=hit.rule;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        boolean uninterrupted=sameVisit&&interaction==userInteraction;
        boolean returnedToHost=returnedToPopupHost(root,pkg,origin);
        boolean verifiedWindow=uninterrupted&&pendingWindow==null&&(matchesWindow(root,pkg,activeWindow)||returnedToHost)
                &&origin.matchesDisplayOf(root);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        boolean confirmed=!nodes.isEmpty()&&!PopupMatch.promptVisible(nodes,rule)&&(hit.target==null||!visibleNode(nodes,hit.target));
        if(!confirmed&&uninterrupted&&!retried){
            main.postDelayed(()->verifyPopup(pkg,visit,interaction,hit,origin,true),500);
            return;
        }
        if(sameVisit){
            if(confirmed&&returnedToHost)acceptWindow(pkg,"",origin.hostWindow,-1);
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
        recentOutcome.observe(pkg,false,confirmed?"上次已确认广告关闭。":"上次尝试退出，但没有确认广告已关闭。");
        try{store.recordPopup(pkg,confirmed);}catch(RuntimeException ignored){/* History failure never retries a screen click. */}
        missedCapture.note(pkg,MissedAdCapture.Mode.POPUP,
                confirmed?SkipDiagnostics.Phase.CONFIRMED:SkipDiagnostics.Phase.UNCONFIRMED);
        if(confirmed)showFeedback(pkg,"净屏 · 弹窗已关闭");
    }
    private void verifySplash(String pkg,long visit,long interaction,SkipDiagnostics.Attempt attempt,
                              AccessibilityNodeInfo target,ConfirmationWindow origin,boolean retried){
        if(closed)return;
        AccessibilityNodeInfo root=service.getRootInActiveWindow();
        boolean sameVisit=visit==foregroundVisit&&pkg.equals(foreground);
        if(sameVisit&&interaction!=userInteraction){finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.USER_INPUT);return;}
        boolean verifiedWindow=sameVisit&&pendingWindow==null&&matchesWindow(root,pkg,activeWindow)
                &&origin.matchesDisplayOf(root);
        List<AccessibilityNodeInfo> nodes=verifiedWindow?UiDriver.walkComplete(root):Collections.emptyList();
        if(verifiedWindow&&!visibleNode(nodes,target)){finishSplash(pkg,visit,attempt,SkipDiagnostics.Phase.CONFIRMED);return;}
        if(sameVisit&&!retried){main.postDelayed(()->verifySplash(pkg,visit,interaction,attempt,target,origin,true),500);return;}
        finishSplash(pkg,visit,attempt,verifiedWindow&&!nodes.isEmpty()?SkipDiagnostics.Phase.TARGET_STILL_VISIBLE
                :belongsTo(root,pkg)||root==null?SkipDiagnostics.Phase.UNCONFIRMED:SkipDiagnostics.Phase.LEFT_WINDOW);
    }
    private void finishSplash(String pkg,long visit,SkipDiagnostics.Attempt attempt,SkipDiagnostics.Phase phase){
        if(closed)return;
        if(visit==foregroundVisit&&pkg.equals(foreground)){splashVerifying=false;schedule();}
        diagnostics.note(pkg,attempt,phase);
        if(visit==foregroundVisit)missedCapture.note(pkg,MissedAdCapture.Mode.SPLASH,phase);
        boolean confirmed=phase==SkipDiagnostics.Phase.CONFIRMED;
        recentOutcome.observe(pkg,true,confirmed?"上次已确认跳过开屏。":"上次找到开屏出口，但没有确认广告消失。");
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
        String normalized=normalizedLabel(value);
        return SKIP_LABEL.matcher(normalized).matches()||EXPLICIT_LABEL.matcher(normalized).matches();
    }
    private static String normalizedLabel(String value){
        if(value.isEmpty()||value.length()>80)return "";
        return SPACE.matcher(Normalizer.normalize(value,Normalizer.Form.NFKC)).replaceAll("").toLowerCase(Locale.ROOT);
    }
    static boolean explicitAd(String value){
        return EXPLICIT_LABEL.matcher(normalizedLabel(value)).matches();
    }
    static boolean rewardAd(String value){
        return !value.isEmpty()&&REWARDED_AD.matcher(value).find();
    }
    static String label(CharSequence value){
        return value==null?"":value.toString().trim();
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root){
        return match(root,SplashRules.EMPTY,"");
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root,SplashRules rules,String activity){
        return match(root,rules,activity,Collections.emptyList());
    }
    static AccessibilityNodeInfo match(AccessibilityNodeInfo root,SplashRules rules,String activity,long elapsed){
        SplashHit hit=splashHit(root,rules,activity,Collections.emptyList(),elapsed);
        return hit==null?null:hit.target;
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
    private enum SplashMatch { NORMAL, CUSTOM_ONLY, EXACT_ACTIVITY_ONLY }
    private static SplashHit splashHit(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom,long elapsed){
        return splashHit(root,rules,activity,custom,elapsed,null,UiDriver.walkComplete(root),SplashMatch.NORMAL);
    }
    private static SplashHit splashHit(AccessibilityNodeInfo root,SplashRules rules,String activity,List<CustomSkipRule> custom,
                                       long elapsed,Consumer<SkipDiagnostics.Phase> trace,List<AccessibilityNodeInfo> nodes,
                                       SplashMatch mode){
        if(root==null)return noHit(trace,SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
        Rect screen=new Rect();root.getBoundsInScreen(screen);
        if(screen.width()<100||screen.height()<100)return noHit(trace,SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
        String pkg=String.valueOf(root.getPackageName());
        if(CustomSkipRule.protectedPackage(pkg)||PopupAdRules.protectedActivity(activity))return noHit(trace,SkipDiagnostics.Phase.GUARDED);
        boolean exactActivityOnly=mode==SplashMatch.EXACT_ACTIVITY_ONLY;
        if(exactActivityOnly&&!reviewedInAppSplash(pkg,activity))
            return noHit(trace,SkipDiagnostics.Phase.PAGE_MISMATCH);
        int window=root.getWindowId();
        if(nodes.isEmpty())return noHit(trace,SkipDiagnostics.Phase.TREE_UNAVAILABLE);
        if("com.tencent.qqlive".equals(pkg)&&PlayerAd.blockedPlayer(nodes))return noHit(trace,SkipDiagnostics.Phase.GUARDED);
        List<CustomSkipRule> scoped=new ArrayList<>();
        for(CustomSkipRule rule:custom)if(rule.appliesTo(pkg,activity,CustomSkipRule.SPLASH))scoped.add(rule);
        if((mode==SplashMatch.CUSTOM_ONLY&&scoped.isEmpty())||(exactActivityOnly&&!scoped.isEmpty()))
            return noHit(trace,SkipDiagnostics.Phase.PAGE_MISMATCH);
        boolean useDefaults=mode!=SplashMatch.CUSTOM_ONLY&&scoped.stream().noneMatch(rule->rule.replaceDefaults);
        boolean useGeneric=useDefaults&&!exactActivityOnly;
        boolean onboarding=useGeneric&&activity!=null&&ONBOARDING_PAGE.matcher(activity).find();
        boolean checkCoveredSearch=useGeneric&&QIYI_LITE_PACKAGE.equals(pkg)&&QIYI_LITE_HOME.equals(activity);
        boolean ad=false;
        AccessibilityNodeInfo coveredSearch=null;
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser())continue;
            if(unsafeWindowNode(node,!checkCoveredSearch))return noHit(trace,SkipDiagnostics.Phase.GUARDED);
            if(node.isEditable()){
                if(coveredSearch!=null||!belongsTo(node,pkg)||node.getWindowId()!=window||node.isFocusable()
                        ||node.isAccessibilityFocused()||!TextUtils.equals(node.getClassName(),"android.widget.EditText")
                        ||!QIYI_LITE_RESOURCE.equals(node.getViewIdResourceName())
                        ||!TextUtils.isEmpty(node.getText())||!TextUtils.isEmpty(node.getContentDescription()))
                    return noHit(trace,SkipDiagnostics.Phase.GUARDED);
                coveredSearch=node;
            }
            if(useGeneric&&!ad&&belongsTo(node,pkg)&&node.getWindowId()==window){
                ad=adMarker(label(node.getText()))||adMarker(label(node.getContentDescription()));
            }
        }
        SplashRules.Prepared defaults=useDefaults?rules.prepare(pkg,activity,elapsed,nodes,exactActivityOnly):null;
        boolean readable=false,missingAd=false,unsafe=false,ambiguous=false;
        SplashHit best=null;
        Rect bounds=new Rect();
        for(AccessibilityNodeInfo node:nodes){
            if(!node.isVisibleToUser()||node.getWindowId()!=window||!belongsTo(node,pkg))continue;
            String text=label(node.getText()),desc=label(node.getContentDescription());
            if(TimedAd.lockedControl(node))continue;
            CustomSkipRule userRule=null;
            for(CustomSkipRule rule:scoped)if(rule.matchesButton(node)){userRule=rule;break;}
            SplashRules.Action action=userRule==null&&defaults!=null?defaults.action(node,text.isEmpty()?desc:text):null;
            if(coveredSearch!=null&&(action!=SplashRules.Action.CLICK||!coversQiyiLiteSearch(node,coveredSearch)))continue;
            boolean known=userRule!=null||action!=null;
            if(!known&&(!useGeneric||(!skipLabel(text)&&!skipLabel(desc))))continue;
            readable=true;
            boolean explicit=explicitAd(text)||explicitAd(desc);
            if(!known&&!explicit&&onboarding){unsafe=true;continue;}
            if(!known&&!explicit&&!ad&&!LiveRoomAd.launchExit(pkg,activity,root,node,screen)&&!AdSurface.launchControl(root,node,screen)){missingAd=true;continue;}
            if(!node.isEnabled()||node.isCheckable()||!safeActionLabel(text)||!safeActionLabel(desc)){unsafe=true;continue;}
            boolean paired=useGeneric && !known && !onboarding && !WECHAT_PACKAGE.equals(pkg) && ad && (skipLabel(text)||skipLabel(desc))
                    && pairedAdBadge(node,nodes,pkg,window,screen);
            boolean tap=action==SplashRules.Action.TAP || (paired && clickable(node)==null);
            if(tap&&!safeTouchLeaf(node)){unsafe=true;continue;}
            AccessibilityNodeInfo click=tap?node:userRule==null?clickable(node):ruleClickTarget(node,userRule);
            if(!belongsTo(click,pkg)||click.getWindowId()!=window||TimedAd.lockedControl(click)){unsafe=true;continue;}
            // A clickable search suggestion can literally be named “跳过广告”. Its row is content.
            if(!known && click!=null && !node.equals(click) && inSearchResults(activity,click)){
                unsafe=true;continue;
            }
            click.getBoundsInScreen(bounds);
            boolean horizontal=bounds.left>=screen.left+screen.width()*.5||((explicit||known)&&bounds.right<=screen.left+screen.width()*.5);
            boolean bottom=bounds.top>=screen.top+screen.height()*.75;
            boolean corner=horizontal&&(bounds.bottom<=screen.top+screen.height()*.27||((explicit||known)&&bottom));
            boolean adFooter=explicit&&ad&&bottom;
            boolean placement=action==SplashRules.Action.CLICK_LOWER_LEFT
                    ?node.equals(click)&&click.getChildCount()==0&&bounds.right<=screen.left+screen.width()*.18
                    &&bounds.top>=screen.top+screen.height()*.60&&bounds.bottom<=screen.top+screen.height()*.86
                    &&bounds.height()<=screen.height()*.10
                    :corner||adFooter;
            if(!screen.contains(bounds)||!(placement || paired && bounds.bottom<=screen.top+screen.height()*.27)
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
            if(coveredSearch!=null)return noHit(trace,SkipDiagnostics.Phase.GUARDED);
            if(unsafe)return noHit(trace,SkipDiagnostics.Phase.TARGET_UNSAFE);
            SkipDiagnostics.Phase reason=trace!=null&&useDefaults?rules.explainMiss(pkg,activity,elapsed,window,nodes):null;
            if(reason!=null)return noHit(trace,reason);
            return noHit(trace,missingAd?SkipDiagnostics.Phase.AD_EVIDENCE_MISSING
                    :readable?SkipDiagnostics.Phase.NO_RULE_MATCH:SkipDiagnostics.Phase.NO_READABLE_TARGET);
        }
        if(trace!=null)trace.accept(SkipDiagnostics.Phase.TARGET_FOUND);
        return best;
    }
    // Snapshot 13797162 exposes an unfocusable Home search field behind a native launch ad.
    // Require the reviewed click target, the ad's separate CTA, and the covered Home sibling.
    private static boolean coversQiyiLiteSearch(AccessibilityNodeInfo close,AccessibilityNodeInfo search){
        if(!close.isClickable()||!QIYI_LITE_RESOURCE.equals(close.getViewIdResourceName()))return false;
        AccessibilityNodeInfo closeLabel=childAt(childAt(close,0),1);
        if(closeLabel==null||!closeLabel.isVisibleToUser()||!TextUtils.equals(closeLabel.getText(),"关闭"))return false;
        AccessibilityNodeInfo creative=ancestorAt(close,2);
        AccessibilityNodeInfo creativeLink=childAt(creative,3);
        AccessibilityNodeInfo linkLabel=childAt(childAt(creativeLink,0),0);
        if(creativeLink==null||!creativeLink.isVisibleToUser()||!creativeLink.isClickable()||linkLabel==null
                ||!linkLabel.isVisibleToUser()||!TextUtils.equals(linkLabel.getText(),"点击跳转至详情页面或第三方应用"))
            return false;
        AccessibilityNodeInfo page=ancestorAt(creative,4);
        AccessibilityNodeInfo home=childAt(page,1);
        if(home==null||!home.equals(ancestorAt(search,5)))return false;
        Rect canvas=new Rect(),field=new Rect();
        creative.getBoundsInScreen(canvas);
        search.getBoundsInScreen(field);
        return !field.isEmpty()&&canvas.contains(field);
    }
    private static AccessibilityNodeInfo childAt(AccessibilityNodeInfo node,int index){
        return node!=null&&index<node.getChildCount()?node.getChild(index):null;
    }
    private static AccessibilityNodeInfo ancestorAt(AccessibilityNodeInfo node,int depth){
        for(int i=0;i<depth&&node!=null;i++)node=node.getParent();
        return node;
    }
    static boolean adMarker(String value){
        return !value.isEmpty()&&AD_MARKER.matcher(normalizedLabel(value)).matches();
    }
    // Web-rendered splash bars can expose two neighboring text leaves but no click action.
    // Require the visible ad badge immediately to the left in the same compact header row.
    private static boolean inSearchResults(String activity,AccessibilityNodeInfo target){
        if(activity==null || !activity.toLowerCase(Locale.ROOT).contains("search"))return false;
        for(AccessibilityNodeInfo p=target.getParent();p!=null;p=p.getParent()){
            String cls=String.valueOf(p.getClassName()),id=String.valueOf(p.getViewIdResourceName());
            if(cls.endsWith("RecyclerView")||cls.endsWith("ListView")||id.endsWith(":id/recycler_view"))return true;
        }
        return false;
    }
    private static boolean pairedAdBadge(AccessibilityNodeInfo target,List<AccessibilityNodeInfo> nodes,
                                         String pkg,int window,Rect screen){
        if(target.getChildCount()!=0)return false;
        Rect button=new Rect(),badge=new Rect();target.getBoundsInScreen(button);
        if(!screen.contains(button)||button.height()<12||button.height()>screen.height()/18
                ||button.width()>screen.width()/4||button.bottom>screen.top+screen.height()/4)return false;
        for(AccessibilityNodeInfo n:nodes){
            if(n.getWindowId()!=window||!belongsTo(n,pkg)||!n.isVisibleToUser()||n.isClickable()
                    ||n.getChildCount()!=0||!(adMarker(label(n.getText()))||adMarker(label(n.getContentDescription()))))continue;
            n.getBoundsInScreen(badge);int gap=button.left-badge.right;
            if(screen.contains(badge)&&badge.width()>0&&badge.width()<=screen.width()/5 && badge.height()>0
                    && gap>=0 && gap<=screen.width()/10 && Math.abs(badge.centerY()-button.centerY())<=button.height()/4
                    && badge.height()<=button.height()*2 && button.height()<=badge.height()*2)return true;
        }
        return false;
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
