package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Rect;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Accessibility adapter: exact matching, unique rows, no blind coordinates or global toggles. */
public final class UiDriver {
    public static final class Halt extends Exception{public final boolean terminal;public Halt(String message){this(message,false);}public Halt(String message,boolean terminal){super(message);this.terminal=terminal;}}
    public static final class Found {
        public final AccessibilityNodeInfo node,click;
        public final boolean checked;
        Found(AccessibilityNodeInfo n,AccessibilityNodeInfo c){node=n;click=c;checked=n.isChecked();}
    }
    private final AccessibilityService service;
    private final BooleanSupplier cancelled;
    private Rule rule;
    public UiDriver(AccessibilityService s,BooleanSupplier c){service=s;cancelled=c;}
    public void check() throws Halt {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new Halt("已停止，已完成的改动仍保存在记录中",true);
        KeyguardManager k=(KeyguardManager)service.getSystemService(Context.KEYGUARD_SERVICE);
        if(k!=null&&k.isKeyguardLocked())throw new Halt("手机已锁屏，本次操作已停止",true);
    }
    void pause(long ms)throws Halt{long end=System.nanoTime()+ms*1_000_000L;while(System.nanoTime()<end){check();try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();check();}}}
    boolean allowed(String pkg){return rule.notification()?Arrays.asList("com.android.settings","com.oplus.notificationmanager","com.coloros.notificationmanager").contains(pkg):rule.pkg.equals(pkg);}
    AccessibilityNodeInfo root()throws Halt{
        check();AccessibilityNodeInfo r=service.getRootInActiveWindow();if(r==null)throw new Halt("暂时无法读取页面，请保持手机亮屏重试");
        String pkg=String.valueOf(r.getPackageName());if(!allowed(pkg))throw new Halt("目标页面已离开前台，任务已停止",true);
        for(AccessibilityNodeInfo n:walk(r))if(n.isVisibleToUser()&&n.isClickable()&&text(n).matches("同意|同意并继续|同意并使用|允许|允许所有|立即授权"))throw new Halt("遇到协议或权限申请，请自行处理后重试",true);
        return r;
    }
    public void open(Rule r)throws Halt{
        rule=r;check();Intent intent;
        if(r.notification()){intent=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,r.pkg).putExtra(Settings.EXTRA_CHANNEL_ID,r.channelId);}
        else if(r.pkg.equals("com.android.settings")){intent=new Intent(Settings.ACTION_SETTINGS);}
        else if(!r.activity.isEmpty()){String cls=r.activity.startsWith(".")?r.pkg+r.activity:r.activity;intent=new Intent().setComponent(new ComponentName(r.pkg,cls));}
        else{intent=service.getPackageManager().getLaunchIntentForPackage(r.pkg);if(intent==null)throw new Halt("没有可打开的入口，请使用手动路径");}
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        try{service.startActivity(intent);}catch(Exception e){throw new Halt("系统不允许直接打开此设置，请使用手动路径");}
        pause(1400);root();
        for(List<String> step:r.path){if(!tap(step))throw new Halt("未找到唯一入口「"+String.join(" / ",step)+"」，未改动");pause(650);root();}
        if(r.notification())verifyChannel();
    }
    public void current(Rule r)throws Halt{rule=r;root();if(r.notification())verifyChannel();}
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
    static List<AccessibilityNodeInfo> walk(AccessibilityNodeInfo r){List<AccessibilityNodeInfo> out=new ArrayList<>();if(r==null)return out;ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(r);while(!q.isEmpty()&&out.size()<1800){AccessibilityNodeInfo n=q.remove();out.add(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)q.add(c);}}return out;}
    static boolean toggle(AccessibilityNodeInfo n){String c=String.valueOf(n.getClassName());return n.isCheckable()&&(c.endsWith("Switch")||c.endsWith("SwitchCompat")||c.endsWith("CheckBox")||c.endsWith("ToggleButton"));}
    boolean tap(List<String> names)throws Halt{
        for(int page=0;page<6;page++){
            AccessibilityNodeInfo r=root();List<AccessibilityNodeInfo> hits=new ArrayList<>();
            for(String name:names){
                for(AccessibilityNodeInfo n:walk(r))if(n.isVisibleToUser()&&n.isEnabled()&&!n.isEditable()&&(name.startsWith("@id:")?name.substring(4).equals(n.getViewIdResourceName()):text(n).equals(name))){
                    AccessibilityNodeInfo click=n;for(int i=0;i<3&&!click.isClickable();i++){AccessibilityNodeInfo p=click.getParent();if(p==null)break;click=p;}
                    if(click.isClickable()&&!containsToggle(click)&&!hits.contains(click))hits.add(click);
                }
                if(!hits.isEmpty())break;
            }
            if(hits.isEmpty()&&names.contains("设置")){
                Rect screen=new Rect();r.getBoundsInScreen(screen);
                for(AccessibilityNodeInfo n:walk(r)){
                    String id=n.getViewIdResourceName();if(id==null)continue;String leaf=id.substring(id.lastIndexOf('/')+1).toLowerCase(Locale.ROOT);
                    Rect b=new Rect();n.getBoundsInScreen(b);
                    if((leaf.startsWith("setting")||leaf.contains("_setting"))&&n.isVisibleToUser()&&n.isEnabled()&&n.isClickable()&&!containsToggle(n)&&b.bottom<screen.top+screen.height()*.23&&b.width()<screen.width()*.25&&b.height()<screen.height()*.15&&!hits.contains(n))hits.add(n);
                }
            }
            if(hits.size()>1)throw new Halt("入口有多个匹配，已停止以避免误点");
            if(hits.size()==1){check();return hits.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);}
            if(!scroll(false))return false;pause(350);
        }return false;
    }
    private boolean containsToggle(AccessibilityNodeInfo n){for(AccessibilityNodeInfo c:walk(n))if(toggle(c))return true;return false;}
    public Found find(String label)throws Halt{
        if(rule.notification())verifyChannel();
        for(int page=0;page<7;page++){
            AccessibilityNodeInfo r=root();List<Found> found=new ArrayList<>();Rect screen=new Rect();r.getBoundsInScreen(screen);
            for(AccessibilityNodeInfo n:walk(r))if(n.isVisibleToUser()&&matchesLabel(text(n),label)){
                AccessibilityNodeInfo row=n;
                for(int depth=0;depth<4&&row!=null;depth++,row=row.getParent()){
                    Rect b=new Rect();row.getBoundsInScreen(b);if(b.height()>screen.height()*.28)break;
                    List<AccessibilityNodeInfo> controls=new ArrayList<>();for(AccessibilityNodeInfo c:walk(row))if(toggle(c)&&c.isVisibleToUser())controls.add(c);
                    if(controls.size()>1)break;
                    if(controls.size()==1){AccessibilityNodeInfo control=controls.get(0);Rect cb=new Rect();control.getBoundsInScreen(cb);
                        if(!control.isEnabled()||!row.isEnabled()||!screen.contains(cb))throw new Halt("开关不可操作或未完整显示，未点击");
                        AccessibilityNodeInfo click=control.isClickable()?control:row.isClickable()?row:null;
                        if(click!=null){if(found.stream().noneMatch(x->x.node.equals(control)))found.add(new Found(control,click));break;}
                    }
                }
            }
            if(found.size()>1)throw new Halt("同名开关不唯一，未点击");
            if(found.size()==1)return found.get(0);
            if(!scroll(false))return null;pause(300);
        }return null;
    }
    boolean matchesLabel(String value,String label){return rule.notification()&&label.equals("允许通知")?Arrays.asList("允许通知","显示通知","Show notifications","Allow notifications").contains(value):value.equals(label);}
    public void top()throws Halt{for(int i=0;i<7;i++){if(!scroll(true))break;pause(120);}}
    boolean scroll(boolean back)throws Halt{
        AccessibilityNodeInfo r=root(),best=null;long area=0;
        for(AccessibilityNodeInfo n:walk(r))if(n.isScrollable()&&n.isVisibleToUser()){Rect b=new Rect();n.getBoundsInScreen(b);long a=(long)b.width()*b.height();if(a>area){best=n;area=a;}}
        if(best==null)return false;check();return best.performAction(back?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
    }
    public boolean set(String label,boolean expected,boolean desired)throws Halt{
        top();Found before=find(label);if(before==null)throw new Halt("操作前未找到目标开关");
        if(before.checked==desired)return true;
        if(before.checked!=expected)throw new Halt("开关状态已变化，未覆盖你的设置");
        int window=before.node.getWindowId();
        check();if(!before.click.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new Halt("系统拒绝此次点击");
        pause(550);
        if(root().getWindowId()!=window){
            if(!desired&&confirmDisable(label))pause(550);
            else throw new Halt("点击后出现未识别的新窗口，请手动核对结果");
        }
        for(int i=0;i<3;i++){if(root().getWindowId()!=window)throw new Halt("点击后出现新窗口，请手动核对结果");Found after=find(label);if(after!=null&&after.checked==desired){pause(450);Found settled=find(label);return settled!=null&&settled.node.getWindowId()==window&&settled.checked==desired;}pause(300);}
        return false;
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
