package app.clearscreen;

import org.json.*;
import java.util.*;

/** Declarative, bounded navigation; no downloaded or arbitrary shell code. */
public final class Rule {
    public final String id, title, group, pkg, activity, source, explanation;
    public final List<String> brands, targets;
    public final List<List<String>> path;
    public String channelId = "", channelName = "", channelApp = "";
    public Rule(JSONObject j) {
        id=j.optString("id"); title=j.optString("title"); group=j.optString("group");
        pkg=j.optString("package"); activity=j.optString("activity"); source=j.optString("source");
        explanation=j.optString("explanation", "仅调整列出的推荐开关，其他设置保持原样。");
        brands=strings(j.optJSONArray("brands")); targets=strings(j.optJSONArray("targets")); path=new ArrayList<>();
        JSONArray steps=j.optJSONArray("path"); if(steps!=null) for(int i=0;i<steps.length();i++) path.add(strings(steps.optJSONArray(i)));
        channelId=j.optString("channelId"); channelName=j.optString("channelName"); channelApp=j.optString("channelApp");
        if(notification()){targets.clear();targets.add("允许通知");}
    }
    public static List<String> strings(JSONArray a) { List<String> r=new ArrayList<>(); if(a!=null)for(int i=0;i<a.length();i++)r.add(a.optString(i)); return r; }
    public boolean matches(String manufacturer, String brand) { for(String b:brands)if(b.equalsIgnoreCase(manufacturer)||b.equalsIgnoreCase(brand))return true;return false; }
    public boolean notification() { return !channelId.isEmpty(); }
    public JSONObject json() { try {
        JSONObject j=new JSONObject();j.put("id",id);j.put("title",title);j.put("group",group);j.put("package",pkg);j.put("activity",activity);j.put("source",source);j.put("brands",new JSONArray(brands));j.put("targets",new JSONArray(targets));
        JSONArray p=new JSONArray();for(List<String> s:path)p.put(new JSONArray(s));j.put("path",p);j.put("channelId",channelId);j.put("channelName",channelName);j.put("channelApp",channelApp);return j;
    }catch(JSONException e){throw new IllegalStateException(e);} }
}
