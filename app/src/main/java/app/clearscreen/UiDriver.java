package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Rect;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.json.*;

/** Accessibility adapter: exact matching, unique rows, no blind coordinates or global toggles. */
public final class UiDriver {
    public static class Halt extends Exception{
        public final boolean terminal,retryable;
        public final String reason;
        public Halt(String message){this(message,false,false);}
        public Halt(String message,boolean terminal){this(message,terminal,false);}
        public Halt(String message,boolean terminal,boolean retryable){
            this(message,terminal,retryable,terminal?"interrupted":retryable?"page_unavailable":"manual");
        }
        Halt(String message,boolean terminal,boolean retryable,String reason){
            super(message);this.terminal=terminal;this.retryable=retryable&&!terminal;this.reason=reason;
        }
    }
    static final class BeforeToggleClick extends Halt {
        BeforeToggleClick(Halt failure){super(failure.getMessage(),failure.terminal,failure.retryable,failure.reason);}
    }
    private static final class ConsentRequired extends Halt {
        ConsentRequired(){super("目标应用要求确认协议或授权，请手动处理后重试",false,false,"consent");}
    }
    /** Packages allowed to carry the user-approved run from one route to the next. */
    static final class Foreground {
        private String owner;
        private int window=-1;
        Foreground(String packageName){owner=packageName;}
        boolean owns(AccessibilityNodeInfo node){
            return node!=null&&owner.equals(String.valueOf(node.getPackageName()))&&(window<0||window==node.getWindowId());
        }
        void require(AccessibilityNodeInfo node)throws Halt{
            if(!owner.equals(String.valueOf(node.getPackageName())))throw foregroundLost();
            if(window>=0&&window!=node.getWindowId())throw windowChanged();
        }
        void claim(AccessibilityNodeInfo node){owner=String.valueOf(node.getPackageName());window=node.getWindowId();}
    }
    public static final class Found {
        public final AccessibilityNodeInfo node,click;
        public final boolean checked; public final String label;
        Found(AccessibilityNodeInfo n,AccessibilityNodeInfo c,String text){node=n;click=c;checked=n.isChecked();label=text;}
    }
    private final AccessibilityService service;
    private final Supplier<String> stopReason;
    private final Foreground foreground;
    private final long budgetMillis;
    private long deadlineNanos;
    private Rule rule;
    private Route route; private String expectedPackage;
    private int matchedWindow=-1;
    private String matchedPackage="";
    private final JSONArray attempts=new JSONArray();
    public JSONArray attempts(){try{return new JSONArray(attempts.toString());}catch(JSONException e){throw new IllegalStateException(e);}}
    public Rule pinnedRule(){return rule.notification()?rule:rule.pin(route);}
    String routeName(){return route==null?"":route.name;}
    String routePackage(){return matchedPackage;}
    void nextTarget(){deadlineNanos=budgetMillis>0?System.nanoTime()+budgetMillis*1_000_000L:0;}
    public UiDriver(AccessibilityService s,BooleanSupplier c){this(s,c,0);}
    UiDriver(AccessibilityService s,BooleanSupplier c,long budgetMillis){
        this(s,()->c.getAsBoolean()?"已停止，已完成的改动仍保存在记录中":null,budgetMillis);
    }
    UiDriver(AccessibilityService s,Supplier<String> stopReason,long budgetMillis){
        this(s,stopReason,budgetMillis,null);
    }
    UiDriver(AccessibilityService s,Supplier<String> stopReason,long budgetMillis,Foreground foreground){
        service=s;this.stopReason=stopReason;this.budgetMillis=budgetMillis;this.foreground=foreground;
        nextTarget();
    }
    private boolean expired(){return deadlineNanos!=0&&System.nanoTime()-deadlineNanos>=0;}
    public void check() throws Halt {
        String reason=stopReason.get();
        if(reason!=null)throw new Halt(reason,true);
        if(Thread.currentThread().isInterrupted())throw new Halt("清理任务被中断，已完成的改动仍保存在记录中",true);
        if(rule!=null&&rule.notification()&&Build.VERSION.SDK_INT<Build.VERSION_CODES.O)throw channelsUnavailable();
        KeyguardManager k=(KeyguardManager)service.getSystemService(Context.KEYGUARD_SERVICE);
        if(k!=null&&k.isKeyguardLocked())throw new Halt("手机已锁屏，本次操作已暂停",true,false,"screen_locked");
        if(expired())throw new Halt("本组检查超时，可在清理结果中单独重试",false,false,"timeout");
    }
    void pause(long ms)throws Halt{long end=System.nanoTime()+ms*1_000_000L;while(System.nanoTime()<end){check();try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();check();}}}
    boolean allowed(String pkg){return rule.notification()?Arrays.asList("com.android.settings","com.oplus.notificationmanager","com.coloros.notificationmanager").contains(pkg):Objects.equals(expectedPackage,pkg);}
    private static Halt foregroundLost(){return new Halt("目标页面已离开前台，任务已暂停",true,false,"foreground_lost");}
    private AccessibilityNodeInfo activeRoot()throws Halt{
        check();AccessibilityNodeInfo r=service.getRootInActiveWindow();if(r==null)throw new Halt("暂时无法读取页面，请保持手机亮屏重试",false,true);
        String pkg=String.valueOf(r.getPackageName());
        if(!allowed(pkg)||(!matchedPackage.isEmpty()&&!matchedPackage.equals(pkg)))throw foregroundLost();
        if(foreground!=null)foreground.claim(r);
        requireNoConsent(r);
        return r;
    }
    AccessibilityNodeInfo root()throws Halt{
        AccessibilityNodeInfo r=activeRoot();
        if(matchedWindow>=0&&r.getWindowId()!=matchedWindow)throw windowChanged();
        return r;
    }
    private static Halt windowChanged(){return new Halt("设置页面已切换，任务已暂停",true,false,"window_changed");}
    private static void requireNoConsent(AccessibilityNodeInfo root)throws Halt{
        for(AccessibilityNodeInfo n:complete(root))if(n.isVisibleToUser()&&n.isClickable()&&text(n).matches("同意|同意并继续|同意并使用|允许|允许所有|立即授权"))throw new ConsentRequired();
    }
    public void open(Rule r)throws Halt{open(r,null,false,null);}
    void openForTarget(Rule r,String label,JSONArray previous)throws Halt{
        open(r,label,false,previous);
    }
    public void openForRestore(Rule r,String exactLabel)throws Halt{open(r,exactLabel,true,null);}
    private void open(Rule r,String requested,boolean exact,JSONArray previous)throws Halt{
        rule=r;while(attempts.length()>0)attempts.remove(0);check();if(r.manual)throw new Halt("此项需要手动处理，请查看操作路径");
        List<String> targets=requested==null?r.targets:Collections.singletonList(requested);
        List<String> readyLabels=new ArrayList<>();
        for(String target:targets)if(exact)readyLabels.add(target);else readyLabels.addAll(r.labels(target));
        Halt last=new Halt("没有匹配的设置路径",false,false,"no_route");boolean retryable=false;
        for(Route candidate:orderedRoutes(r,previous)){route=candidate;JSONObject attempt=new JSONObject();
            CleanerService.put(attempt,"route",candidate.name);
            CleanerService.put(attempt,"package",candidate.pkg);
            CleanerService.put(attempt,"routeKey",candidate.key());
            matchedWindow=-1;matchedPackage="";
            long overallDeadline=deadlineNanos;
            long candidateDeadline=System.nanoTime()+(10_000L+4_000L*candidate.path.size())*1_000_000L;
            if(overallDeadline!=0&&candidateDeadline<overallDeadline)deadlineNanos=candidateDeadline;
            try{openRoute(r,candidate,readyLabels);
                // Route selection is read-only. A route is accepted only after an exact target is found.
                Found matched=null;
                Halt probeFailure=null;
                for(String label:targets){
                    try{
                        top();matched=exact?findExact(label):find(label);
                        if(matched==null&&exact){pause(400);top();matched=findExact(label);}
                        if(matched!=null)break;
                    }catch(Halt probe){
                        if(probe.terminal||probe instanceof ConsentRequired||expired())throw probe;
                        probeFailure=probe;
                    }
                }
                if(matched==null){
                    if(probeFailure!=null)throw probeFailure;
                    throw new Halt("此入口未识别到目标开关",false,true,"target_missing");
                }
                matchedWindow=matched.node.getWindowId();
                matchedPackage=String.valueOf(root().getPackageName());
                top();requireWindow(matchedWindow);CleanerService.put(attempt,"result","matched");attempts.put(attempt);return;
            }catch(Halt e){
                CleanerService.put(attempt,"result",e.getMessage());
                CleanerService.put(attempt,"reason",e.reason);
                attempts.put(attempt);
                if(e.terminal||e instanceof ConsentRequired)throw e;
                boolean canRetry=e.retryable||e.reason.equals("timeout");
                if(last.reason.equals("no_route")||canRetry||!retryable)last=e;
                retryable|=canRetry;
            }finally{deadlineNanos=overallDeadline;}
            check();
        }
        throw new Halt("已检查 "+attempts.length()+" 个入口："+last.getMessage(),false,retryable,last.reason);
    }
    private static List<Route> orderedRoutes(Rule rule,JSONArray previous){
        if(previous==null||rule.routes.size()<2)return rule.routes;
        Map<String,Integer> priorities=new HashMap<>();
        for(int i=0;i<previous.length();i++){
            JSONObject attempt=previous.optJSONObject(i);
            if(attempt==null)continue;
            String reason=attempt.optString("reason");
            int priority=reason.equals("target_missing")||reason.equals("navigation_timeout")||reason.equals("timeout")?1:2;
            priorities.put(attempt.optString("routeKey"),priority);
        }
        List<Route> ordered=new ArrayList<>(rule.routes);
        ordered.sort(Comparator.comparingInt(route->priorities.getOrDefault(route.key(),0)));
        return ordered;
    }
    static String fingerprint(AccessibilityNodeInfo root){
        if(root==null)return "";StringBuilder value=new StringBuilder().append(root.getWindowId()).append(':').append(root.getPackageName());
        for(AccessibilityNodeInfo node:walk(root))if(node.isVisibleToUser())value.append('|').append(text(node)).append(':').append(node.getViewIdResourceName()).append(':').append(node.isChecked()).append(':').append(node.getChildCount());
        return value.toString();
    }
    private static String viewport(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes){
        StringBuilder value=new StringBuilder().append(root.getWindowId());
        Rect bounds=new Rect();
        for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()){
            node.getBoundsInScreen(bounds);
            value.append('|').append(node.getViewIdResourceName()).append(':').append(text(node)).append(':')
                    .append(bounds.left).append(',').append(bounds.top).append(',').append(bounds.bottom);
        }
        return value.toString();
    }
    static boolean loading(AccessibilityNodeInfo root){
        List<AccessibilityNodeInfo> nodes=walk(root);if(nodes.size()<2)return true;
        boolean honorMarket="com.hihonor.appmarket".equals(String.valueOf(root.getPackageName()));
        for(AccessibilityNodeInfo n:nodes){
            if(!n.isVisibleToUser())continue;
            String label=text(n);
            if(String.valueOf(n.getClassName()).endsWith("ProgressBar")||label.matches("正在加载[.…]*|加载中[.…]*|请稍候[.…]*|Loading[.…]*"))return true;
            // A clean run waits for the market's known splash to leave before looking for its menus.
            if(honorMarket&&SplashSkipper.skipLabel(label)&&"com.hihonor.appmarket".equals(String.valueOf(n.getPackageName()))
                    &&("com.hihonor.appmarket:id/btn_skip".equals(n.getViewIdResourceName())
                    ||"com.hihonor.appmarket:id/tv_operation_skip".equals(n.getViewIdResourceName())))return true;
        }
        return false;
    }
    private static boolean entryMatches(AccessibilityNodeInfo node,String name){
        return name.startsWith("@id:")?name.substring(4).equals(node.getViewIdResourceName()):text(node).equals(name);
    }
    /** Wait only on the source or declared destination. Unexpected foreground changes still stop. */
    static final class PageStamp {
        final String pkg,value;
        PageStamp(AccessibilityNodeInfo root){pkg=root==null?"":String.valueOf(root.getPackageName());value=fingerprint(root);}
    }
    void awaitPage(PageStamp previous,List<String> expected,long timeout)throws Halt{
        awaitPage(previous,expected,timeout,"",true);
    }
    private void awaitPage(PageStamp previous,List<String> expected,long timeout,String declaredLauncher,boolean allowExisting)throws Halt{
        String source=previous.pkg,before=previous.value,last="";
        long deadline=System.nanoTime()+timeout*1_000_000L,stableSince=System.nanoTime(),began=stableSince;
        boolean visitedDestination=false;
        while(System.nanoTime()<deadline){
            check();AccessibilityNodeInfo current=service.getRootInActiveWindow();
            if(current!=null){String pkg=String.valueOf(current.getPackageName());
                if(!allowed(pkg)&&!pkg.equals(source)&&!pkg.equals(declaredLauncher))throw foregroundLost();
                if(allowed(pkg)){
                    if(foreground!=null)foreground.claim(current);
                    String value=fingerprint(current);long now=System.nanoTime();
                    if(!value.equals(last)){last=value;stableSince=now;}
                    boolean known=walk(current).stream().anyMatch(n->n.isVisibleToUser()&&expected.stream().anyMatch(name->entryMatches(n,name)));
                    boolean changed=!value.equals(before);
                    visitedDestination|=changed;
                    // A consent page left by the preceding group is not the newly opened route.
                    if(changed)requireNoConsent(current);
                    boolean expectedPage=known&&(changed||(allowExisting&&now-began>=900_000_000L));
                    long stableNanos=expectedPage?400_000_000L:1_200_000_000L;
                    if(!loading(current)&&now-stableSince>=stableNanos&&(changed||expectedPage)){root();return;}
                }else{
                    if(visitedDestination&&pkg.equals(source))throw foregroundLost();
                    if(foreground!=null)foreground.claim(current);
                    last="";stableSince=System.nanoTime();
                }
            }else{last="";stableSince=System.nanoTime();}
            pause(100);
        }
        throw new Halt("等待目标页面超时，未操作开关",false,true,"navigation_timeout");
    }
    private void openRoute(Rule r,Route candidate,List<String> targets)throws Halt{
        check();
        Intent intent;
        if(r.notification()){
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O)throw channelsUnavailable();
            if(foreground!=null&&!Route.installed(service,r.pkg))throw new Halt("通知所属应用已不可用",false,false,"package_unavailable");
            intent=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,r.pkg).putExtra(Settings.EXTRA_CHANNEL_ID,r.channelId);
        }else{
            if(!candidate.skipWhenPackagePresent.isEmpty()&&Route.installed(service,candidate.skipWhenPackagePresent))
                throw new Halt("本机已使用新版设置组件，已跳过旧入口",false,false,"superseded_route");
            if(!candidate.pkg.equals(candidate.foreground)&&!Route.installed(service,candidate.foreground))
                throw new Halt("本机缺少此路径所需的设置组件",false,false,"package_unavailable");
            intent=foreground==null?candidate.launchIntent(service):candidate.preflight(service);
            if(intent==null)throw new Halt("没有可打开的入口，请使用手动路径",false,false,"no_route");
        }
        PageStamp previous=sourceBeforeLaunch();
        expectedPackage=candidate.foreground;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        check();
        try{service.startActivity(intent);}catch(ActivityNotFoundException|SecurityException e){
            throw new Halt("系统不允许直接打开此设置，请使用手动路径",false,false,"entry_unavailable");
        }
        awaitPage(previous,candidate.path.isEmpty()?targets:candidate.path.get(0),8000,candidate.pkg,true);
        for(int i=0;i<candidate.path.size();i++){
            List<String> step=candidate.path.get(i);PageStamp before=new PageStamp(root());
            if(!tap(step))throw new Halt("未找到唯一入口「"+TextUtils.join(" / ",step)+"」，未改动",false,true,"entry_missing");
            expectedPackage=candidate.destinations.get(i);
            awaitPage(before,i+1<candidate.path.size()?candidate.path.get(i+1):targets,8000,"",false);
        }
        verifyAnchors(candidate);
        if(r.notification())verifyChannel();
    }
    private PageStamp sourceBeforeLaunch()throws Halt{
        AccessibilityNodeInfo source=service.getRootInActiveWindow();
        if(foreground==null)return new PageStamp(source);
        for(int read=0;source==null&&read<6;read++){
            pause(150);
            source=service.getRootInActiveWindow();
        }
        if(source==null)throw new Halt("暂时无法读取前台页面，请返回净屏后继续",true,false,"foreground_unavailable");
        foreground.require(source);
        return new PageStamp(source);
    }
    private void verifyAnchors(Route candidate)throws Halt{
        if(candidate.anchors.isEmpty())return;
        List<AccessibilityNodeInfo> nodes=complete(root());
        for(String anchor:candidate.anchors)
            if(nodes.stream().noneMatch(n->n.isVisibleToUser()&&text(n).equals(anchor)))
                throw new Halt("页面标识不匹配，未操作",false,true,"anchor_missing");
    }
    public void current(Rule r)throws Halt{
        rule=r;route=r.routes.get(0);expectedPackage=route.finalPackage();matchedWindow=-1;matchedPackage="";
        AccessibilityNodeInfo current=root();
        matchedWindow=current.getWindowId();matchedPackage=String.valueOf(current.getPackageName());
        if(r.notification())verifyChannel();
    }
    void verifyChannel()throws Halt{
        AccessibilityNodeInfo r=root();boolean title=false,app=false,entityPair=false;
        Rect screen=new Rect();r.getBoundsInScreen(screen);
        for(AccessibilityNodeInfo n:complete(r)){if(!n.isVisibleToUser())continue;String t=text(n);Rect b=new Rect();n.getBoundsInScreen(b);
            String rid=n.getViewIdResourceName();boolean header=rid!=null&&(rid.endsWith("/title")||rid.endsWith("/action_bar_title")||rid.endsWith("/toolbar_title"));
            title|=t.equals(rule.channelName)&&header&&b.bottom<screen.top+screen.height()*.22;
            app|=t.equals(rule.channelApp);
            if(t.equals(rule.channelName)&&"com.android.settings:id/entity_header_title".equals(rid)&&b.bottom<screen.top+screen.height()*.65){
                AccessibilityNodeInfo parent=n.getParent();if(parent!=null)for(AccessibilityNodeInfo sibling:walk(parent))if(sibling.isVisibleToUser()&&"com.android.settings:id/entity_header_summary".equals(sibling.getViewIdResourceName())&&text(sibling).equals(rule.channelApp))entityPair=true;
            }
        }
        if(!(title&&app)&&!entityPair)throw new Halt("未同时确认应用名与通知渠道名，未操作",false,false,"channel_unconfirmed");
    }
    private static Halt channelsUnavailable(){
        return new Halt("Android 8.0 起支持通知渠道，未操作",false,false,"channel_unsupported");
    }
    static String text(AccessibilityNodeInfo n){CharSequence t=n.getText();if(t==null||t.toString().trim().isEmpty())t=n.getContentDescription();return t==null?"":t.toString().trim();}
    static List<AccessibilityNodeInfo> walk(AccessibilityNodeInfo root){
        return walk(root,false);
    }
    /** An empty result means the root is missing or the tree exceeds the traversal budget. */
    static List<AccessibilityNodeInfo> walkComplete(AccessibilityNodeInfo root){
        return walk(root,true);
    }
    private static List<AccessibilityNodeInfo> complete(AccessibilityNodeInfo root)throws Halt{
        List<AccessibilityNodeInfo> nodes=walkComplete(root);
        if(nodes.isEmpty())throw new Halt("无法完整读取页面控件，未操作",false,true,"tree_incomplete");
        return nodes;
    }
    private static List<AccessibilityNodeInfo> walk(AccessibilityNodeInfo root,boolean requireComplete){
        final int limit=1800;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        if(root==null)return nodes;
        ArrayDeque<AccessibilityNodeInfo> pending=new ArrayDeque<>();
        pending.add(root);
        while(!pending.isEmpty()){
            AccessibilityNodeInfo node=pending.remove();
            nodes.add(node);
            int capacity=limit-nodes.size()-pending.size();
            int count=node.getChildCount();
            if(requireComplete&&count>capacity)return Collections.emptyList();
            int children=Math.min(count,capacity);
            for(int i=0;i<children;i++){
                AccessibilityNodeInfo child=node.getChild(i);
                if(child!=null)pending.add(child);
            }
        }
        return nodes;
    }
    static boolean toggle(AccessibilityNodeInfo n){String c=String.valueOf(n.getClassName());return n.isCheckable()&&(c.endsWith("Switch")||c.endsWith("SwitchCompat")||c.endsWith("CheckBox")||c.endsWith("ToggleButton"));}
    boolean tap(List<String> names)throws Halt{
        Set<String> visited=new HashSet<>();
        for(int page=0;page<12;page++){
            AccessibilityNodeInfo r=root();List<AccessibilityNodeInfo> hits=new ArrayList<>();
            List<AccessibilityNodeInfo> nodes=complete(r);
            for(String name:names){
                for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&n.isEnabled()&&!n.isEditable()&&entryMatches(n,name)){
                    AccessibilityNodeInfo click=n;for(int i=0;i<3&&!click.isClickable();i++){AccessibilityNodeInfo p=click.getParent();if(p==null)break;click=p;}
                    if(click.isClickable()&&!containsToggle(click)&&!hits.contains(click))hits.add(click);
                }
                if(!hits.isEmpty())break;
            }
            if(hits.isEmpty()&&names.contains("设置")){
                Rect screen=new Rect();r.getBoundsInScreen(screen);
                for(AccessibilityNodeInfo n:nodes){
                    String id=n.getViewIdResourceName();if(id==null)continue;String leaf=id.substring(id.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
                    Rect b=new Rect();n.getBoundsInScreen(b);
                    if((leaf.startsWith("setting")||leaf.contains("_setting"))&&n.isVisibleToUser()&&n.isEnabled()&&n.isClickable()&&!containsToggle(n)&&b.bottom<screen.top+screen.height()*.23&&b.width()<screen.width()*.25&&b.height()<screen.height()*.15&&!hits.contains(n))hits.add(n);
                }
            }
            if(hits.size()>1)throw new Halt("入口有多个匹配，未操作",false,false,"ambiguous");
            if(hits.size()==1){
                if(!fingerprint(r).equals(fingerprint(root())))throw new Halt("入口已变化，可重新检查",false,true,"entry_changed");
                AccessibilityNodeInfo entry=hits.get(0);
                requireActionWindow(entry);
                return entry.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            if(!visited.add(viewport(r,nodes))||!scroll(nodes,false))return false;pause(350);
        }return false;
    }
    private boolean containsToggle(AccessibilityNodeInfo n)throws Halt{for(AccessibilityNodeInfo c:complete(n))if(toggle(c))return true;return false;}
    public Found find(String label)throws Halt{return findLabels(rule.labels(label),12);}
    public Found findExact(String label)throws Halt{return findLabels(Collections.singletonList(label),12);}
    private Found findLabels(List<String> labels,int pageLimit)throws Halt{
        if(rule.notification())verifyChannel();
        int layoutChecks=0;
        Set<String> visited=new HashSet<>();
        pages: for(int page=0;page<pageLimit;page++){
            AccessibilityNodeInfo r=root();List<Found> found=new ArrayList<>();Rect screen=new Rect();r.getBoundsInScreen(screen);
            List<AccessibilityNodeInfo> nodes=complete(r);
            for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&labels.contains(text(n))){
                AccessibilityNodeInfo row=n;
                for(int depth=0;depth<4&&row!=null;depth++,row=row.getParent()){
                    Rect b=new Rect();row.getBoundsInScreen(b);if(b.height()>screen.height()*.28)break;
                    List<AccessibilityNodeInfo> controls=new ArrayList<>();for(AccessibilityNodeInfo c:complete(row))if(toggle(c)&&c.isVisibleToUser())controls.add(c);
                    if(controls.size()>1)break;
                    if(controls.size()==1){AccessibilityNodeInfo control=controls.get(0);Rect cb=new Rect();control.getBoundsInScreen(cb);
                        if(!control.isEnabled()||!row.isEnabled())throw new Halt("开关不可操作，未点击",false,false,"disabled");
                        if(cb.isEmpty()||!screen.contains(cb)){
                            if(layoutChecks++<2){
                                pause(180);
                                page--;
                                continue pages;
                            }
                            throw new Halt("开关未完整显示，未点击",false,true);
                        }
                        AccessibilityNodeInfo click=control.isClickable()?control:row.isClickable()?row:null;
                        if(click!=null){if(found.stream().noneMatch(x->x.node.equals(control)))found.add(new Found(control,click,text(n)));break;}
                    }
                }
            }
            if(found.size()>1)throw new Halt("同名开关不唯一，未点击",false,false,"ambiguous");
            if(found.size()==1)return found.get(0);
            if(page+1==pageLimit||!visited.add(viewport(r,nodes))||!scroll(nodes,false))return null;pause(300);
        }return null;
    }
    public void top()throws Halt{
        Set<String> visited=new HashSet<>();
        for(int i=0;i<12;i++){
            AccessibilityNodeInfo r=root();List<AccessibilityNodeInfo> nodes=complete(r);
            if(!visited.add(viewport(r,nodes))||!scroll(nodes,true))break;
            pause(200);
        }
    }
    private boolean scroll(List<AccessibilityNodeInfo> nodes,boolean back)throws Halt{
        AccessibilityNodeInfo best=null;long area=0;
        for(AccessibilityNodeInfo n:nodes)if(n.isScrollable()&&n.isVisibleToUser()){Rect b=new Rect();n.getBoundsInScreen(b);long a=(long)b.width()*b.height();if(a>area){best=n;area=a;}}
        if(best==null)return false;
        requireActionWindow(best);
        return best.performAction(back?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }
    private void requireWindow(int window)throws Halt{
        if(root().getWindowId()!=window)throw windowChanged();
    }
    private void requireActionWindow(AccessibilityNodeInfo action)throws Halt{
        check();
        AccessibilityNodeInfo page=service.getRootInActiveWindow();
        if(page==null)throw new Halt("暂时无法确认操作窗口，未点击",false,true,"page_unavailable");
        String pkg=String.valueOf(page.getPackageName());
        if(!allowed(pkg)||(!matchedPackage.isEmpty()&&!matchedPackage.equals(pkg)))throw foregroundLost();
        if(page.getWindowId()!=action.getWindowId())throw windowChanged();
    }
    private static boolean sameControl(Found expected,Found current){
        return current!=null&&current.node.equals(expected.node)&&current.click.equals(expected.click);
    }
    public boolean set(Found observed,boolean desired,Runnable beforeClick)throws Halt{
        requireWindow(matchedWindow);
        String label=observed.label;
        if(!rule.notification())verifyAnchors(route);
        List<String> exactLabel=Collections.singletonList(label);
        Found before=findLabels(exactLabel,1);
        if(!sameControl(observed,before))throw new Halt("操作前目标开关已变化，未点击",false,true,"target_changed");
        int window=before.node.getWindowId();
        if(window!=matchedWindow)throw windowChanged();
        if(before.checked==desired)return true;
        if(before.checked!=observed.checked)throw new Halt("开关状态已变化，未覆盖你的设置",false,false,"state_changed");
        beforeClick.run();
        Found current;
        try{
            current=findLabels(exactLabel,1);
            if(!sameControl(before,current)||current.checked!=before.checked)
                throw new Halt("保存原值后目标开关发生变化，未点击",false,false,"state_changed");
            requireActionWindow(current.click);
        }catch(Halt failure){throw new BeforeToggleClick(failure);}
        if(!current.click.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Halt("系统拒绝此次点击",false,false,"click_rejected");
        long groupDeadline=deadlineNanos;
        // Finish read-back even if the group budget was nearly spent before this click.
        if(groupDeadline!=0&&groupDeadline-System.nanoTime()<6_000_000_000L)
            deadlineNanos=System.nanoTime()+6_000_000_000L;
        try{
            pause(550);
            if(!desired&&confirmDisable(label))pause(550);
            if(activeRoot().getWindowId()!=window)throw new Halt("点击后出现未识别的新窗口，请回读核对",true,false,"window_changed");
            for(int i=0;i<3;i++){
                Found after=findLabels(exactLabel,1);
                if(sameControl(current,after)&&after.checked==desired){
                    pause(450);
                    Found settled=findLabels(exactLabel,1);
                    return sameControl(current,settled)&&settled.checked==desired;
                }
                pause(300);
            }
            return false;
        }finally{
            deadlineNanos=groupDeadline;
        }
    }
    /** Only the upstream's explicit disable-confirmation shape; never general consent or permission dialogs. */
    boolean confirmDisable(String label)throws Halt{
        AccessibilityNodeInfo r=activeRoot();boolean title=false;List<AccessibilityNodeInfo> buttons=new ArrayList<>();
        String wanted="确定关闭"+label;
        for(AccessibilityNodeInfo n:complete(r)){if(!n.isVisibleToUser())continue;if(toggle(n))return false;
            String t=text(n).replaceAll("[「」“”\"？?\\s]","");
            if(t.equals(wanted)||t.equals(wanted+"吗"))title=true;
            if(text(n).equals("确定")&&String.valueOf(n.getClassName()).endsWith("Button")&&n.isClickable()&&n.isEnabled())buttons.add(n);
        }
        if(!title||buttons.size()!=1||!fingerprint(r).equals(fingerprint(activeRoot())))return false;
        AccessibilityNodeInfo button=buttons.get(0);
        requireActionWindow(button);
        return button.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
