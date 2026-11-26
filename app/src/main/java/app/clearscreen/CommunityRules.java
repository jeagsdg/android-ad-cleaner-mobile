package app.clearscreen;

import android.content.*;
import java.util.*;
import org.json.*;

/** Each source owns its snapshot. Updating it never replaces a user's local edits. */
final class CommunityRules {
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("community-rules",0);}
    static JSONObject all(Context c){
        try{return new JSONObject(prefs(c).getString("sources","{}"));}catch(JSONException e){throw new IllegalStateException("订阅记录不可读",e);}
    }
    static void save(Context c,JSONObject sources){
        if(!prefs(c).edit().putString("sources",sources.toString()).commit())throw new IllegalStateException("订阅保存失败");
    }
    static final class Preview {
        final List<CustomSkipRule> rules=new ArrayList<>(); final List<String> omitted=new ArrayList<>();
        String name; int version; Long subscriptionId;
    }
    static List<Object> list(Object o){
        List<Object> l=new ArrayList<>();if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++)l.add(a.opt(i));}
        else if(o!=null&&o!=JSONObject.NULL)l.add(o);return l;
    }
    private static boolean allows(JSONObject j,String keys){for(Iterator<String> i=j.keys();i.hasNext();)if(!i.next().matches(keys))return false;return true;}
    static Preview parse(String data) throws JSONException {
        JSONObject j=SubscriptionJson.read(data);Preview p=new Preview();p.name=j.optString("name","自定义订阅");p.version=j.optInt("version",0);
        if(j.has("id")){Object id=j.get("id");if(!(id instanceof Integer)&&!(id instanceof Long))throw new JSONException("订阅 ID 应为整数");p.subscriptionId=((Number)id).longValue();}
        if(j.has("schemaVersion")){p.rules.addAll(CustomSkipRule.subscriptionRules(j.toString()));return p;}
        JSONArray apps=j.optJSONArray("apps");if(apps==null)apps=new JSONArray();else apps=new JSONArray(apps.toString());Set<String> seen=new HashSet<>();
        int localAppCount=apps.length();
        int expandedRules=0;
        long expandedChars=0;
        // Expand a shared group only into the ordinary apps the source explicitly enabled.
        for(Object item:list(j.opt("globalGroups"))){
            if(!(item instanceof JSONObject))continue;JSONObject global=(JSONObject)item;
            if(global.optBoolean("matchAnyApp",true)||global.optBoolean("matchLauncher")||global.optBoolean("matchSystemApp")
                    ||global.has("disableIfAppGroupMatch")){p.omitted.add(global.optString("name")+"：未启用不限应用或系统范围");continue;}
            JSONObject template=new JSONObject(global.toString());
            for(String k:new String[]{"apps","matchAnyApp","matchLauncher","matchSystemApp"})template.remove(k);
            String shared=template.toString();
            int perApp=list(template.opt("rules")).size();
            for(Object target:list(global.opt("apps")))if(target instanceof JSONObject){
                JSONObject spec=(JSONObject)target;
                if(!spec.optBoolean("enable",true))continue;
                if(!allows(spec,"id|enable|activityIds|excludeActivityIds")){p.omitted.add(spec.optString("id")+"：全局组应用范围不能完整转换");continue;}
                if (++expandedRules > 4096 || perApp > 4096-expandedRules)
                    throw new JSONException("全局规则展开后超过 4096 项");
                expandedRules+=perApp;
                expandedChars+=shared.length()+spec.toString().length();
                if(expandedChars>4L*1024*1024)throw new JSONException("展开后的共享规则文本超过 4 MiB");
                JSONObject scoped=new JSONObject(shared);
                for(String k:new String[]{"activityIds","excludeActivityIds"})if(spec.has(k))scoped.put(k,spec.get(k));
                apps.put(new JSONObject().put("id",spec.getString("id")).put("groups",new JSONArray().put(scoped)));
            }
        }
        final String common="activityIds|excludeActivityIds|snapshotUrls|excludeSnapshotUrls|exampleUrls|fastQuery|matchRoot|matchDelay|matchTime|actionDelay|actionMaximum|actionCd|resetMatch|order|priorityTime";
        List<JSONObject> categories=new ArrayList<>();
        for(Object obj:list(j.opt("categories")))if(obj instanceof JSONObject)categories.add((JSONObject)obj);
        for(int a=0;a<apps.length();a++){
            JSONObject app=apps.getJSONObject(a);String pkg=app.getString("id");
            if(!allows(app,"id|name|groups|exampleUrls|snapshotUrls")){p.omitted.add(pkg+"：含不支持的应用级条件");continue;}
            for(Object obj:list(app.opt("groups"))){
                if(!(obj instanceof JSONObject))continue;JSONObject g=(JSONObject)obj;String title=g.optString("name","未命名规则组");
                boolean enabled=g.optBoolean("enable",true);
                if (a < localAppCount) for(JSONObject cat:categories){
                    String prefix=cat.optString("name");if((!prefix.isEmpty()&&title.startsWith(prefix))||
                            g.has("categoryKey")&&g.optInt("categoryKey")==cat.optInt("key",-1)){
                        if(!cat.isNull("enable"))enabled=cat.getBoolean("enable");break;
                    }
                }
                boolean startup=title.contains("开屏"),popup=!startup&&(title.contains("弹窗")||title.contains("全屏广告")||title.contains("局部广告"));
                if(!enabled||(!startup&&!popup)||!title.contains("广告")||!allows(g,"key|name|desc|rules|enable|categoryKey|"+common)){
                    p.omitted.add(title+"：未接入此组的场景或限定条件");continue;
                }
                for(Object ro:list(g.opt("rules")))try{
                    JSONObject r=ro instanceof String?new JSONObject().put("matches",ro):(JSONObject)ro;
                    for(Iterator<String> keys=r.keys();keys.hasNext();){
                        String key=keys.next();
                        if(!key.matches("key|name|desc|matches|anyMatches|excludeMatches|excludeAllMatches|action|"+common))
                            throw new IllegalArgumentException("无法保留字段 "+key);
                    }
                    if(!r.optString("action","click").equals("click"))throw new IllegalArgumentException("仅支持点击控件");

                    Object matchRoot=inherit(r,g,"matchRoot");
                    if(matchRoot!=null && !(matchRoot instanceof Boolean))throw new IllegalArgumentException("matchRoot 须为开关");
                    JSONArray matches=texts(r.opt("matches")),any=texts(r.opt("anyMatches"));
                    if(matches.length()==0){
                        if(any.length()!=1)throw new IllegalArgumentException("需要一个明确的点击目标");
                        matches=any;
                        any=new JSONArray();
                    }
                    String target=matches.getString(matches.length()-1);JSONObject scope=new JSONObject();
                    scope.put("root",Boolean.TRUE.equals(matchRoot));
                    JSONArray required=new JSONArray();for(int n=0;n<matches.length()-1;n++)required.put(matches.getString(n));
                    add(scope,"require",required);add(scope,"any",any);add(scope,"exclude",texts(r.opt("excludeMatches")));
                    add(scope,"excludeAll",texts(r.opt("excludeAllMatches")));
                    for(String key:new String[]{"activityIds","excludeActivityIds"}){
                        JSONArray raw=texts(inherit(r,g,key));JSONArray pages=new JSONArray();
                        for(int n=0;n<raw.length();n++){
                            String name=raw.getString(n);if(!name.matches("[.a-zA-Z_$][.a-zA-Z0-9_$]*"))throw new IllegalArgumentException();
                            pages.put(name.startsWith(".")?pkg+name:name);
                        }
                        add(scope,key.equals("activityIds")?"pages":"excludePages",pages);
                    }
                    for(String key:new String[]{"matchDelay","matchTime"})if(inherit(r,g,key)!=null)
                        scope.put(key.equals("matchDelay")?"delay":"duration",inherit(r,g,key));
                    if(startup){
                        for(String k:new String[]{"order","priorityTime"})if(inherit(r,g,k)!=null)scope.put(k,inherit(r,g,k));
                        for(String k:new String[]{"actionMaximum","actionCd","resetMatch"})if(inherit(r,g,k)!=null){
                            scope.put(k.equals("actionMaximum")?"maximum":k.equals("actionCd")?"cooldown":"reset",inherit(r,g,k));}
                    }else if(inherit(r,g,"priorityTime")!=null||inherit(r,g,"order")!=null||inherit(r,g,"actionMaximum")!=null||inherit(r,g,"actionCd")!=null||inherit(r,g,"resetMatch")!=null)
                        throw new IllegalArgumentException("弹窗继承次数条件不受支持");
                    Object wait=inherit(r,g,"actionDelay");if(wait!=null&&!(wait instanceof Integer))throw new IllegalArgumentException();
                    if(popup&&required.length()==0)throw new IllegalArgumentException("使用中广告规则须有同页提示和关闭条件");
                    CustomSkipRule rule=CustomSkipRule.advanced(pkg,"",startup?"splash":"popup","gkd:"+target,
                            startup?"":"gkd:"+required.getString(0),false,wait==null?0:(Integer)wait,false,1,1,-1,null);
                    if(scope.length()>0)rule.scope=new RuleScope(scope);
                    if(seen.add(key(rule)))p.rules.add(rule);
                }catch(JSONException|RuntimeException e){p.omitted.add(title+"："+(e.getMessage()==null?"不支持的条件或格式":e.getMessage()));}
            }
        }
        return p;
    }
    private static JSONArray texts(Object v){
        JSONArray a=new JSONArray();for(Object s:list(v)){if(!(s instanceof String)||((String)s).trim().isEmpty())throw new IllegalArgumentException("选择条件不能为空");a.put(s);}return a;
    }
    private static Object inherit(JSONObject r,JSONObject g,String k){return r.has(k)?r.opt(k):g.opt(k);}
    private static void add(JSONObject o,String k,JSONArray a)throws JSONException{if(a.length()>0)o.put(k,a);}
    static String key(CustomSkipRule r){return CustomSkipRule.export(Collections.singletonList(r.withPaused(false)));}
    static List<CustomSkipRule> active(JSONObject sources,List<CustomSkipRule> local){
        List<CustomSkipRule> out=new ArrayList<>(local);Set<String> seen=new HashSet<>();for(CustomSkipRule r:local)seen.add(key(r));
        List<String> addresses=new ArrayList<>();sources.keys().forEachRemaining(addresses::add);Collections.sort(addresses);
        for(String url:addresses){JSONObject src=sources.optJSONObject(url);if(src==null||!src.optBoolean("enabled"))continue;
            Set<String> disabled=disabled(src);
            List<CustomSkipRule> imported;
            try{imported=CustomSkipRule.subscriptionRules(src.optString("rules"));}
            catch(IllegalArgumentException unreadable){continue;}
            for(CustomSkipRule r:imported){r.fromSubscription=true;String id=key(r);if(!disabled.contains(id)&&seen.add(id))out.add(r);}}
        return out;
    }
    static Set<String> disabled(JSONObject src){
        Set<String> set=new LinkedHashSet<>();for(Object k:list(src.opt("disabled")))if(k instanceof String)set.add((String)k);return set;
    }
    static JSONObject update(JSONObject old,JSONObject next)throws JSONException{
        if(old==null)return next;
        next.put("checkUpdates",old.optBoolean("checkUpdates",false));next.put("checked",old.optLong("checked",0));next.put("enabled",old.optBoolean("enabled",true));next.put("disabled",continuingChoices(old,next));
        if(!next.getString("rules").equals(old.getString("rules"))){
            JSONObject backup=new JSONObject(old.toString());backup.remove("previous");next.put("previous",backup);
        }else next.put("previous",old.optJSONObject("previous"));
        return next;
    }
    // Prior releases omitted the default event scope from stored GKD rules.
    private static String priorKey(CustomSkipRule rule)throws JSONException{
        if(rule.scope==null || !rule.scope.eventScoped)return key(rule);
        JSONObject doc=new JSONObject(key(rule));
        JSONObject row=doc.getJSONArray("rules").getJSONObject(0);
        JSONObject scope=row.getJSONObject("scope");
        scope.remove("root");
        if(scope.length()==0)row.remove("scope");
        return doc.toString(2);
    }

    private static JSONArray continuingChoices(JSONObject from,JSONObject to)throws JSONException{
        Set<String> off=disabled(from);
        Map<String,Boolean> oldChoices=new HashMap<>();
        for(CustomSkipRule old:CustomSkipRule.subscriptionRules(from.getString("rules")))
            oldChoices.put(priorKey(old),off.contains(key(old)));
        for(CustomSkipRule rule:CustomSkipRule.subscriptionRules(to.getString("rules"))){
            Boolean disabled=oldChoices.get(priorKey(rule));
            if(Boolean.TRUE.equals(disabled))off.add(key(rule));
            if(Boolean.FALSE.equals(disabled))off.remove(key(rule));
        }
        return new JSONArray(off);
    }
    static JSONObject restore(JSONObject current)throws JSONException{
        JSONObject backup=new JSONObject(current.getJSONObject("previous").toString());
        backup.put("enabled",current.optBoolean("enabled",true));
        backup.put("disabled",continuingChoices(current,backup));
        backup.put("checkUpdates",current.optBoolean("checkUpdates",false));
        backup.put("checked",current.optLong("checked",0));
        backup.remove("available");
        return backup;
    }
    static JSONObject stage(Preview p,boolean enable) throws JSONException {
        // Re-parse before persisting: invalid documents cannot replace the last good snapshot.
        String data=CustomSkipRule.export(p.rules);CustomSkipRule.subscriptionRules(data);
        return new JSONObject().put("id",p.subscriptionId).put("name",p.name).put("version",p.version).put("enabled",enable).put("rules",data);
    }
}
