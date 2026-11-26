package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.*;

/** Compiled once per rule; lookup results are cached only for one window scan. */
final class RuleScope {
    private final JSONObject source;
    private final List<RuleSelector> require,any,exclude,excludeAll;
    private final List<String> excludedPages, pages;
    private final int delay,until;
    private static List<CustomSkipRule> trackedRules;
    private static final Set<String> eventPackages=new HashSet<>();
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final Runnable expire=RuleScope::clearEvent;
    private static final long SOURCE_LIFETIME_MS=250;
    private static AccessibilityNodeInfo eventSource;
    final boolean eventScoped;
    final int cooldown,maximum,order,priorityTime;final boolean resetByApp;
    RuleScope(JSONObject fields) throws JSONException {
        source=new JSONObject(fields.toString());
        for(Iterator<String> i=source.keys();i.hasNext();)if(!i.next().matches("require|any|exclude|excludeAll|pages|excludePages|delay|duration|cooldown|maximum|reset|order|priorityTime|root"))
            throw new IllegalArgumentException("未知的范围条件");
        if(source.has("root")&&!(source.get("root") instanceof Boolean))throw new IllegalArgumentException("查询范围无效");
        eventScoped=Boolean.FALSE.equals(source.opt("root"));
        require=compile("require");any=compile("any");exclude=compile("exclude");excludeAll=compile("excludeAll");
        excludedPages=strings("excludePages");pages=strings("pages");delay=millis("delay",0);until=millis("duration",Integer.MAX_VALUE);
        cooldown=millis("cooldown",1000);maximum=millis("maximum",Integer.MAX_VALUE);
        Object rawOrder=source.opt("order");if(rawOrder!=null&&!(rawOrder instanceof Integer))throw new IllegalArgumentException("规则顺序须为整数");
        order=rawOrder==null?0:(Integer)rawOrder;priorityTime=millis("priorityTime",0);
        String reset=source.optString("reset","activity");if(!reset.equals("activity")&&!reset.equals("app"))throw new IllegalArgumentException("不支持的重置条件");resetByApp=reset.equals("app");
    }
    // Keep one short-lived identity for comparison with freshly read nodes. Its text is never inspected or stored.
    static boolean needsEventSource(String pkg,Store store){
        List<CustomSkipRule> rules=store.activeRules();
        if(rules!=trackedRules){
            eventPackages.clear();
            for(CustomSkipRule rule:rules)if(rule.scope!=null&&rule.scope.eventScoped)eventPackages.add(rule.pkg);
            trackedRules=rules;
        }
        return eventPackages.contains(pkg);
    }
    static void observeEvent(AccessibilityEvent event){
        AccessibilityNodeInfo source;
        try{source=event.getSource();}catch(IllegalStateException unavailable){return;}
        if(source==null)return;
        setEventSource(source);
    }

    static void setEventSource(AccessibilityNodeInfo source){
        eventSource=source;
        main.postDelayed(expire,SOURCE_LIFETIME_MS);
    }

    @SuppressWarnings("deprecation")
    static void clearEvent(){
        if(eventSource==null)return;
        main.removeCallbacks(expire);
        eventSource.recycle();
        eventSource=null;
    }

    boolean permits(AccessibilityNodeInfo target,boolean targetAtEnd){
        return !eventScoped || inside(target,targetAtEnd,eventSource);
    }

    @SuppressWarnings("deprecation")
    private static boolean inside(AccessibilityNodeInfo target,boolean targetAtEnd,AccessibilityNodeInfo source){
        if(source==null)return true;
        if(source.getWindowId()!=target.getWindowId()
                ||!android.text.TextUtils.equals(source.getPackageName(),target.getPackageName()))return false;
        if(!targetAtEnd)return false;
        AccessibilityNodeInfo cursor=target;
        try{
            for(int depth=0;cursor!=null && depth<64;depth++){
                if(source.equals(cursor))return true;
                AccessibilityNodeInfo parent=cursor.getParent();
                if(cursor!=target && cursor!=source)cursor.recycle();
                cursor=parent;
            }
            return false;
        }finally{
            if(cursor!=null && cursor!=target && cursor!=source)cursor.recycle();
        }
    }

    private int millis(String k,int fallback) throws JSONException{
        if(!source.has(k))return fallback;
        Object n=source.get(k);if(!(n instanceof Integer)||(Integer)n<0)throw new IllegalArgumentException("匹配时长必须是非负整数");return (Integer)n;
    }
    private List<String> strings(String key)throws JSONException{
        if(!source.has(key))return Collections.emptyList();JSONArray a=source.getJSONArray(key);List<String> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){Object x=a.get(i);if(!(x instanceof String)||((String)x).isEmpty())throw new IllegalArgumentException("范围条件不能为空");out.add((String)x);}
        return out;
    }
    private List<RuleSelector> compile(String k)throws JSONException{
        List<RuleSelector> rules=new ArrayList<>();for(String s:strings(k))rules.add(new RuleSelector("gkd:"+s));return rules;
    }
    static int compare(CustomSkipRule a,CustomSkipRule b,long elapsed){
        if(a==null||b==null)return 0;
        if(a.fromSubscription!=b.fromSubscription)return a.fromSubscription?1:-1;
        if(!a.fromSubscription)return 0;
        int pa=a.scope==null?0:a.scope.priorityTime,pb=b.scope==null?0:b.scope.priorityTime;
        if((elapsed<pa)!=(elapsed<pb))return elapsed<pa?-1:1;
        return Integer.compare(a.scope==null?0:a.scope.order,b.scope==null?0:b.scope.order);
    }
    JSONObject json()throws JSONException{return new JSONObject(source.toString());}
    String describe(){
        List<String> parts=new ArrayList<>();
        if(source.optBoolean("root"))parts.add("从完整窗口查询");
        if(eventScoped)parts.add("从事件控件查询；无来源时查当前窗口");
        if(!pages.isEmpty())parts.add("页面 "+android.text.TextUtils.join("、",pages));
        if(!excludedPages.isEmpty())parts.add("排除页面 "+android.text.TextUtils.join("、",excludedPages));
        if(!require.isEmpty())parts.add("同时满足 "+require.size()+" 个条件");
        if(!any.isEmpty())parts.add("任选其一 "+any.size()+" 项");
        if(!exclude.isEmpty()||!excludeAll.isEmpty())parts.add("排除条件 "+(exclude.size()+excludeAll.size())+" 项");
        if(order!=0)parts.add("顺序 "+order);
        if(priorityTime>0)parts.add("优先时段 "+priorityTime+" ms");
        if(maximum!=Integer.MAX_VALUE)parts.add((resetByApp?"每次进入应用":"每次进入页面")+"最多 "+maximum+" 次");
        if(delay>0)parts.add(delay+" ms 后开始");
        if(until!=Integer.MAX_VALUE)parts.add("窗口 "+until+" ms");
        return android.text.TextUtils.join(" · ",parts);
    }
    boolean allows(String activity,long elapsed,Window w){
        if(maximum==0||elapsed<delay || elapsed-delay>=until||(!excludedPages.isEmpty()&&(activity==null||activity.isEmpty())))return false;
        if(!pages.isEmpty()&&(activity==null||pages.stream().noneMatch(activity::startsWith)))return false;
        for(String p:excludedPages)if(activity.startsWith(p))return false;
        for(RuleSelector s:require)if(!w.has(s,eventScoped,false))return false;
        if(!any.isEmpty()&&any.stream().noneMatch(s->w.has(s,eventScoped,false)))return false;
        for(RuleSelector s:exclude)if(w.has(s,eventScoped,true))return false;
        // v1.13.2 source and public documentation differ on this operator. The
        // intersection accepts a page only when neither interpretation vetoes it.
        return excludeAll.stream().noneMatch(s->w.has(s,eventScoped,true));
    }
    static final class Window{
        final AccessibilityNodeInfo root;final List<AccessibilityNodeInfo> nodes;
        final Map<String,Boolean> results=new HashMap<>();
        Window(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes){this.root=root;this.nodes=nodes;}
        final AccessibilityNodeInfo source=eventSource;
        boolean has(RuleSelector s,boolean scoped,boolean veto){return results.computeIfAbsent(scoped+":"+veto+":"+s.expression,k->{
            for(AccessibilityNodeInfo n:nodes)if(n.getWindowId()==root.getWindowId()
                    &&android.text.TextUtils.equals(root.getPackageName(),n.getPackageName())&&s.matches(n)
                    &&(!scoped || veto&&!s.targetAtEnd() || inside(n,s.targetAtEnd(),source)))return true;return false;
        });}
    }
}
