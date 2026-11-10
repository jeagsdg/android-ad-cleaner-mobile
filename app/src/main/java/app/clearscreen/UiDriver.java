package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Accessibility adapter: exact matching, unique rows, no blind coordinates or global toggles. */
public final class UiDriver {
    public static class Halt extends Exception{
        public final boolean terminal,retryable;
        public Halt(String message){this(message,false,false);}
        public Halt(String message,boolean terminal){this(message,terminal,false);}
        public Halt(String message,boolean terminal,boolean retryable){super(message);this.terminal=terminal;this.retryable=retryable&&!terminal;}
    }
    private static final class ConsentRequired extends Halt {
        ConsentRequired(){super("目标应用要求确认协议或授权，请手动处理后重试");}
    }
    public static final class Found {
        public final AccessibilityNodeInfo node,click;
        public final boolean checked; public final String label;
        Found(AccessibilityNodeInfo n,AccessibilityNodeInfo c,String text){node=n;click=c;checked=n.isChecked();label=text;}
    }
    private final AccessibilityService service;
    private final BooleanSupplier cancelled;
    private long deadlineNanos;
    private Rule rule;
    private Route route; private String expectedPackage;
    private int matchedWindow;
    private final JSONArray attempts=new JSONArray();
    public JSONArray attempts(){try{return new JSONArray(attempts.toString());}catch(JSONException e){throw new IllegalStateException(e);}}
    public Rule pinnedRule(){return rule.notification()?rule:rule.pin(route);}
    public UiDriver(AccessibilityService s,BooleanSupplier c){this(s,c,0);}
    UiDriver(AccessibilityService s,BooleanSupplier c,long budgetMillis){
        service=s;cancelled=c;
        if(budgetMillis>0)deadlineNanos=System.nanoTime()+budgetMillis*1_000_000L;
    }
    private boolean expired(){return deadlineNanos!=0&&System.nanoTime()-deadlineNanos>=0;}
    public void check() throws Halt {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Halt("已停止，已完成的改动仍保存在记录中",true);
        KeyguardManager k=(KeyguardManager)service.getSystemService(Context.KEYGUARD_SERVICE);
        if(k!=null&&k.isKeyguardLocked())throw new Halt("手机已锁屏，本次操作已停止",true);
        if(expired())throw new Halt("本组检查超时，可在清理结果中单独重试");
    }
    void pause(long ms)throws Halt{long end=System.nanoTime()+ms*1_000_000L;while(System.nanoTime()<end){check();try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();check();}}}
    boolean allowed(String pkg){return rule.notification()?Arrays.asList("com.android.settings","com.oplus.notificationmanager","com.coloros.notificationmanager").contains(pkg):Objects.equals(expectedPackage,pkg);}
    AccessibilityNodeInfo root()throws Halt{
        check();AccessibilityNodeInfo r=service.getRootInActiveWindow();if(r==null)throw new Halt("暂时无法读取页面，请保持手机亮屏重试",false,true);
        String pkg=String.valueOf(r.getPackageName());if(!allowed(pkg))throw new Halt("目标页面已离开前台，任务已停止",true);
        requireNoConsent(r);
        return r;
    }
    private static void requireNoConsent(AccessibilityNodeInfo root)throws Halt{
        for(AccessibilityNodeInfo n:walk(root))if(n.isVisibleToUser()&&n.isClickable()&&text(n).matches("同意|同意并继续|同意并使用|允许|允许所有|立即授权"))throw new ConsentRequired();
    }
    public void open(Rule r)throws Halt{open(r,null);}
    public void openForRestore(Rule r,String exactLabel)throws Halt{open(r,exactLabel);}
    private void open(Rule r,String exactLabel)throws Halt{
        rule=r;while(attempts.length()>0)attempts.remove(0);check();if(r.manual)throw new Halt("此项需要手动处理，请查看操作路径");
        String last="没有匹配的设置路径";boolean retryable=false;
        for(Route candidate:r.routes){route=candidate;JSONObject attempt=new JSONObject();CleanerService.put(attempt,"route",candidate.name);CleanerService.put(attempt,"package",candidate.pkg);
            try{openRoute(r,candidate,exactLabel==null?targetLabels(r):Collections.singletonList(exactLabel));
                // Route selection is read-only. A route is accepted only after an exact target is found.
                Found matched=null;
                boolean missing=false;
                List<String> targets=exactLabel==null?r.targets:Collections.singletonList(exactLabel);
                for(String label:targets){
                    try{
                        top();matched=exactLabel==null?find(label):findExact(label);
                        if(matched==null&&exactLabel!=null){pause(400);top();matched=findExact(label);}
                        if(matched!=null)break;
                        missing=true;
                    }catch(Halt probe){if(probe.terminal||probe instanceof ConsentRequired||expired())throw probe;missing|=probe.retryable;}
                }
                if(matched==null)throw new Halt("此入口未识别到目标开关",false,missing);
                matchedWindow=matched.node.getWindowId();
                top();requireWindow(matchedWindow);CleanerService.put(attempt,"result","matched");attempts.put(attempt);return;
            }catch(Halt e){CleanerService.put(attempt,"result",e.getMessage());attempts.put(attempt);if(e.terminal||e instanceof ConsentRequired||expired())throw e;last=e.getMessage();retryable|=e.retryable;}
        }
        throw new Halt("已检查 "+r.routes.size()+" 个入口："+last,false,retryable);
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
        awaitPage(previous,expected,timeout,"");
    }
    private void awaitPage(PageStamp previous,List<String> expected,long timeout,String declaredLauncher)throws Halt{
        String source=previous.pkg,before=previous.value,last="";
        long deadline=System.nanoTime()+timeout*1_000_000L,stableSince=System.nanoTime(),began=stableSince;
        while(System.nanoTime()<deadline){
            check();AccessibilityNodeInfo current=service.getRootInActiveWindow();
            if(current!=null){String pkg=String.valueOf(current.getPackageName());
                if(!allowed(pkg)&&!pkg.equals(source)&&!pkg.equals(declaredLauncher))throw new Halt("目标页面已离开前台，任务已停止",true);
                if(allowed(pkg)){
                    String value=fingerprint(current);long now=System.nanoTime();
                    if(!value.equals(last)){last=value;stableSince=now;}
                    boolean known=walk(current).stream().anyMatch(n->n.isVisibleToUser()&&expected.stream().anyMatch(name->entryMatches(n,name)));
                    boolean changed=!value.equals(before);
                    // A consent page left by the preceding group is not the newly opened route.
                    if(changed)requireNoConsent(current);
                    if(!loading(current)&&now-stableSince>=400_000_000L&&(changed||(known&&now-began>=900_000_000L))){root();return;}
                }else{last="";stableSince=System.nanoTime();}
            }else{last="";stableSince=System.nanoTime();}
            pause(100);
        }
        // A stable current page can still contain an exact target below the first screen.
        AccessibilityNodeInfo current=service.getRootInActiveWindow();
        if(current==null||!allowed(String.valueOf(current.getPackageName()))||loading(current))
            throw new Halt("页面尚未加载完成，未点击",false,true);
        root();
    }
    private List<String> targetLabels(Rule r){List<String> all=new ArrayList<>();for(String label:r.targets)all.addAll(r.labels(label));return all;}
    private void openRoute(Rule r,Route candidate,List<String> targets)throws Halt{
        check();
        if(!candidate.skipWhenPackagePresent.isEmpty()&&Route.installed(service,candidate.skipWhenPackagePresent))
            throw new Halt("本机已使用新版设置组件，已跳过旧入口");
        if(!candidate.pkg.equals(candidate.foreground)&&!Route.installed(service,candidate.foreground))
            throw new Halt("本机缺少此路径所需的设置组件");
        PageStamp previous=new PageStamp(service.getRootInActiveWindow());expectedPackage=candidate.foreground;Intent intent;
        if(r.notification()){intent=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,r.pkg).putExtra(Settings.EXTRA_CHANNEL_ID,r.channelId);}
        else{intent=candidate.launchIntent(service);if(intent==null)throw new Halt("没有可打开的入口，请使用手动路径");}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try{service.startActivity(intent);}catch(Exception e){throw new Halt("系统不允许直接打开此设置，请使用手动路径");}
        awaitPage(previous,candidate.path.isEmpty()?targets:candidate.path.get(0),8000,candidate.pkg);
        for(int i=0;i<candidate.path.size();i++){
            List<String> step=candidate.path.get(i);PageStamp before=new PageStamp(root());
            if(!tap(step))throw new Halt("未找到唯一入口「"+String.join(" / ",step)+"」，未改动",false,true);
            expectedPackage=candidate.destinations.get(i);
            awaitPage(before,i+1<candidate.path.size()?candidate.path.get(i+1):targets,8000);
        }
        for(String anchor:candidate.anchors){boolean present=false;for(AccessibilityNodeInfo n:walk(root()))if(n.isVisibleToUser()&&text(n).equals(anchor)){present=true;break;}if(!present)throw new Halt("页面标识不匹配，未操作",false,true);}
        if(r.notification())verifyChannel();
    }
    public void current(Rule r)throws Halt{rule=r;route=r.routes.get(0);expectedPackage=route.finalPackage();matchedWindow=root().getWindowId();if(r.notification())verifyChannel();}
    void verifyChannel()throws Halt{
        AccessibilityNodeInfo r=root();boolean title=false,app=false,entityPair=false;
        Rect screen=new Rect();r.getBoundsInScreen(screen);
        for(AccessibilityNodeInfo n:walk(r)){if(!n.isVisibleToUser())continue;String t=text(n);Rect b=new Rect();n.getBoundsInScreen(b);
            String rid=n.getViewIdResourceName();boolean header=rid!=null&&(rid.endsWith("/title")||rid.endsWith("/action_bar_title")||rid.endsWith("/toolbar_title"));
            title|=t.equals(rule.channelName)&&header&&b.bottom<screen.top+screen.height()*.22;
            app|=t.equals(rule.channelApp);
            if(t.equals(rule.channelName)&&"com.android.settings:id/entity_header_title".equals(rid)&&b.bottom<screen.top+screen.height()*.65){
                AccessibilityNodeInfo parent=n.getParent();if(parent!=null)for(AccessibilityNodeInfo sibling:walk(parent))if(sibling.isVisibleToUser()&&"com.android.settings:id/entity_header_summary".equals(sibling.getViewIdResourceName())&&text(sibling).equals(rule.channelApp))entityPair=true;
            }
        }
        if(!(title&&app)&&!entityPair)throw new Halt("未同时确认应用名与通知渠道名；保留全部通知开关");
    }
    static String text(AccessibilityNodeInfo n){CharSequence t=n.getText();if(t==null||t.toString().trim().isEmpty())t=n.getContentDescription();return t==null?"":t.toString().trim();}
    static List<AccessibilityNodeInfo> walk(AccessibilityNodeInfo root){
        return walk(root,false);
    }
    /** An empty result means the root is missing or the tree exceeds the traversal budget. */
    static List<AccessibilityNodeInfo> walkComplete(AccessibilityNodeInfo root){
        return walk(root,true);
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
            List<AccessibilityNodeInfo> nodes=walk(r);
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
            if(hits.size()>1)throw new Halt("入口有多个匹配，已停止以避免误点");
            if(hits.size()==1){check();return hits.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);}
            if(!visited.add(viewport(r,nodes))||!scroll(nodes,false))return false;pause(350);
        }return false;
    }
    private boolean containsToggle(AccessibilityNodeInfo n){for(AccessibilityNodeInfo c:walk(n))if(toggle(c))return true;return false;}
    public Found find(String label)throws Halt{return findLabels(rule.labels(label));}
    public Found findExact(String label)throws Halt{return findLabels(Collections.singletonList(label));}
    private Found findLabels(List<String> labels)throws Halt{
        if(rule.notification())verifyChannel();
        int layoutChecks=0;
        Set<String> visited=new HashSet<>();
        pages: for(int page=0;page<12;page++){
            AccessibilityNodeInfo r=root();List<Found> found=new ArrayList<>();Rect screen=new Rect();r.getBoundsInScreen(screen);
            List<AccessibilityNodeInfo> nodes=walk(r);
            for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&labels.contains(text(n))){
                AccessibilityNodeInfo row=n;
                for(int depth=0;depth<4&&row!=null;depth++,row=row.getParent()){
                    Rect b=new Rect();row.getBoundsInScreen(b);if(b.height()>screen.height()*.28)break;
                    List<AccessibilityNodeInfo> controls=new ArrayList<>();for(AccessibilityNodeInfo c:walk(row))if(toggle(c)&&c.isVisibleToUser())controls.add(c);
                    if(controls.size()>1)break;
                    if(controls.size()==1){AccessibilityNodeInfo control=controls.get(0);Rect cb=new Rect();control.getBoundsInScreen(cb);
                        if(!control.isEnabled()||!row.isEnabled())throw new Halt("开关不可操作，未点击");
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
            if(found.size()>1)throw new Halt("同名开关不唯一，未点击");
            if(found.size()==1)return found.get(0);
            if(!visited.add(viewport(r,nodes))||!scroll(nodes,false))return null;pause(300);
        }return null;
    }
    public void top()throws Halt{
        Set<String> visited=new HashSet<>();
        for(int i=0;i<12;i++){
            AccessibilityNodeInfo r=root();List<AccessibilityNodeInfo> nodes=walk(r);
            if(!visited.add(viewport(r,nodes))||!scroll(nodes,true))break;
            pause(200);
        }
    }
    private boolean scroll(List<AccessibilityNodeInfo> nodes,boolean back)throws Halt{
        AccessibilityNodeInfo best=null;long area=0;
        for(AccessibilityNodeInfo n:nodes)if(n.isScrollable()&&n.isVisibleToUser()){Rect b=new Rect();n.getBoundsInScreen(b);long a=(long)b.width()*b.height();if(a>area){best=n;area=a;}}
        if(best==null)return false;check();return best.performAction(back?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }
    private void requireWindow(int window)throws Halt{
        if(root().getWindowId()!=window)throw new Halt("设置页面已切换，任务已停止",true);
    }
    public boolean set(Found observed,boolean desired,Runnable beforeClick)throws Halt{
        requireWindow(matchedWindow);
        String label=observed.label;
        top();Found before=findExact(label);if(before==null)throw new Halt("操作前未找到目标开关");
        int window=before.node.getWindowId();
        if(window!=matchedWindow||window!=observed.node.getWindowId())throw new Halt("设置页面已切换，任务已停止",true);
        if(before.checked==desired)return true;
        if(before.checked!=observed.checked)throw new Halt("开关状态已变化，未覆盖你的设置");
        requireWindow(window);
        beforeClick.run();
        requireWindow(window);
        if(!before.click.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Halt("系统拒绝此次点击");
        long groupDeadline=deadlineNanos;
        // Finish read-back even if the group budget was nearly spent before this click.
        if(groupDeadline!=0&&groupDeadline-System.nanoTime()<6_000_000_000L)
            deadlineNanos=System.nanoTime()+6_000_000_000L;
        try{
            pause(550);
            if(root().getWindowId()!=window){
                if(!desired&&confirmDisable(label))pause(550);
                else throw new Halt("点击后出现未识别的新窗口，请手动核对结果");
            }
            for(int i=0;i<3;i++){if(root().getWindowId()!=window)throw new Halt("点击后出现新窗口，请手动核对结果");Found after=findExact(label);if(after!=null&&after.node.getWindowId()==window&&after.checked==desired){pause(450);Found settled=findExact(label);return settled!=null&&settled.node.getWindowId()==window&&settled.checked==desired;}pause(300);}
            return false;
        }finally{
            deadlineNanos=groupDeadline;
        }
    }
    /** Only the upstream's explicit disable-confirmation shape; never general consent or permission dialogs. */
    boolean confirmDisable(String label)throws Halt{
        AccessibilityNodeInfo r=root();boolean title=false;List<AccessibilityNodeInfo> buttons=new ArrayList<>();
        String wanted="确定关闭"+label;
        for(AccessibilityNodeInfo n:walk(r)){if(!n.isVisibleToUser())continue;if(toggle(n))return false;
            String t=text(n).replaceAll("[「」“”\"？?\\s]","");
            if(t.equals(wanted)||t.equals(wanted+"吗"))title=true;
            if(text(n).equals("确定")&&String.valueOf(n.getClassName()).endsWith("Button")&&n.isClickable()&&n.isEnabled())buttons.add(n);
        }
        if(!title||buttons.size()!=1)return false;check();return buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
