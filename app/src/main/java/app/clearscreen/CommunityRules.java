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
        String name; int version;
    }
    static List<Object> list(Object o){
        List<Object> l=new ArrayList<>();if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++)l.add(a.opt(i));}
        else if(o!=null&&o!=JSONObject.NULL)l.add(o);return l;
    }
    private static boolean allows(JSONObject j,String keys){for(Iterator<String> i=j.keys();i.hasNext();)if(!i.next().matches(keys))return false;return true;}
    static Preview parse(String data) throws JSONException {
        JSONObject j=new JSONObject(data);Preview p=new Preview();p.name=j.optString("name","自定义订阅");p.version=j.optInt("version",0);
        if(j.has("schemaVersion")){p.rules.addAll(CustomSkipRule.parse(data));return p;}
        JSONArray apps=j.getJSONArray("apps");Set<String> seen=new HashSet<>();
        if(!list(j.opt("globalGroups")).isEmpty())p.omitted.add("跨应用规则组：仅接入按应用限定的广告规则");
        Set<Integer> disabled=new HashSet<>();for(Object obj:list(j.opt("categories")))if(obj instanceof JSONObject&&!((JSONObject)obj).optBoolean("enable",true))disabled.add(((JSONObject)obj).optInt("key"));
        for(int a=0;a<apps.length();a++){
            JSONObject app=apps.getJSONObject(a);String pkg=app.getString("id");
            if(!allows(app,"id|name|groups|exampleUrls|snapshotUrls")){p.omitted.add(pkg+"：含不支持的应用级条件");continue;}
            for(Object obj:list(app.opt("groups"))){
                if(!(obj instanceof JSONObject))continue;JSONObject g=(JSONObject)obj;String title=g.optString("name","未命名规则组");
                if(!title.contains("开屏")||!title.contains("广告")||!g.optBoolean("enable",true)||disabled.contains(g.optInt("categoryKey",-999))
                  ||!allows(g,"key|name|desc|rules|activityIds|snapshotUrls|exampleUrls|fastQuery|enable|categoryKey")){p.omitted.add(title+"：未接入此组的场景或限定条件");continue;}
                for(Object ro:list(g.opt("rules")))try{
                    JSONObject r=ro instanceof String?new JSONObject().put("matches",ro):(JSONObject)ro;
                    if(!allows(r,"key|name|desc|matches|activityIds|snapshotUrls|exampleUrls|fastQuery|action")||!r.optString("action","click").equals("click"))throw new IllegalArgumentException();
                    List<Object> selectors=list(r.opt("matches"));if(selectors.size()!=1||!(selectors.get(0) instanceof String))throw new IllegalArgumentException();
                    String selector="gkd:"+selectors.get(0);new RuleSelector(selector);
                    List<Object> pages=list(r.has("activityIds")?r.opt("activityIds"):g.opt("activityIds"));
                    if(pages.isEmpty())pages.add("");
                    List<CustomSkipRule> batch=new ArrayList<>();for(Object page:pages){
                        if(!(page instanceof String))throw new IllegalArgumentException();String activity=(String)page;
                        if(activity.startsWith("."))activity=pkg+activity;
                        batch.add(CustomSkipRule.advanced(pkg,activity,"splash",selector,"",false,0,false,1,1,-1,null));
                    }
                    for(CustomSkipRule rule:batch)if(seen.add(rule.pkg+"\n"+rule.activity+"\n"+rule.label))p.rules.add(rule);
                }catch(RuntimeException e){p.omitted.add(title+"：规则含不支持的选择条件或动作");}
            }
        }
        return p;
    }
    static String key(CustomSkipRule r){return CustomSkipRule.export(Collections.singletonList(r));}
    static List<CustomSkipRule> active(JSONObject sources,List<CustomSkipRule> local){
        List<CustomSkipRule> out=new ArrayList<>(local);Set<String> seen=new HashSet<>();for(CustomSkipRule r:local)seen.add(key(r));
        List<String> addresses=new ArrayList<>();sources.keys().forEachRemaining(addresses::add);Collections.sort(addresses);
        for(String url:addresses){JSONObject src=sources.optJSONObject(url);if(src==null||!src.optBoolean("enabled"))continue;
            for(CustomSkipRule r:CustomSkipRule.parse(src.optString("rules")))if(seen.add(key(r)))out.add(r);}
        return out;
    }
    static JSONObject stage(Preview p,boolean enable) throws JSONException {
        // Re-parse before persisting: invalid documents cannot replace the last good snapshot.
        String data=CustomSkipRule.export(p.rules);CustomSkipRule.parse(data);
        return new JSONObject().put("name",p.name).put("version",p.version).put("enabled",enable).put("rules",data);
    }
}
