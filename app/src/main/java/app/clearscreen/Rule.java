package app.clearscreen;

import org.json.*;
import java.util.*;

/** Bundled, declarative rules only. A successful route is pinned in the restoration ledger. */
public final class Rule {
    public final String id, title, group, pkg, activity, source, explanation;
    public final boolean manual;
    public final List<String> brands, targets;
    public final List<List<String>> path;
    public final List<Route> routes = new ArrayList<>();
    private final Map<String,List<String>> aliases = new LinkedHashMap<>();
    public String channelId = "", channelName = "", channelApp = "";

    public Rule(JSONObject j) {
        if (j == null) throw new IllegalArgumentException("缺少规则内容");
        id = j.optString("id"); title = j.optString("title"); group = j.optString("group", "系统推荐");
        pkg = j.optString("package"); activity = j.optString("activity"); source = j.optString("source");
        explanation = j.optString("explanation", "仅调整列出的广告或推荐开关，保留其他功能。");
        manual = j.optBoolean("manual");
        brands = strings(j.optJSONArray("brands")); targets = strings(j.optJSONArray("targets"));
        JSONObject synonyms = j.optJSONObject("aliases");
        if (synonyms != null) for (String key : targets) aliases.put(key, strings(synonyms.optJSONArray(key)));
        JSONArray entries = j.optJSONArray("routes");
        if (entries == null || entries.length() == 0) routes.add(new Route(j, pkg, source));
        else for (int i = 0; i < entries.length(); i++) routes.add(new Route(entries.optJSONObject(i), pkg, source));
        if (routes.size() > 6) throw new IllegalArgumentException("替代路径过多");
        path = routes.get(0).path;
        channelId = j.optString("channelId"); channelName = j.optString("channelName"); channelApp = j.optString("channelApp");
        if (notification()) { targets.clear(); targets.add("允许通知"); }
    }
    public static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) for (int i = 0; i < array.length(); i++) out.add(array.optString(i));
        return out;
    }
    public boolean matches(String manufacturer, String brand) {
        for (String value : brands) {
            if (value.equalsIgnoreCase(manufacturer) || value.equalsIgnoreCase(brand)) return true;
            if (value.equalsIgnoreCase("oppo")
                    && ("oplus".equalsIgnoreCase(manufacturer) || "oplus".equalsIgnoreCase(brand))) return true;
        }
        return false;
    }
    public boolean notification() { return !channelId.isEmpty(); }
    public List<String> labels(String target) {
        List<String> out = new ArrayList<>(); out.add(target);
        if (notification() && target.equals("允许通知")) out.addAll(Arrays.asList("显示通知", "Show notifications", "Allow notifications"));
        else if (aliases.containsKey(target)) out.addAll(aliases.get(target));
        return out;
    }
    public Rule pin(Route route) {
        JSONObject out = json();
        try {
            out.put("package", route.finalPackage()); out.put("activity", route.activity);
            out.put("routes", new JSONArray().put(route.json()));
            return new Rule(out);
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public Rule onlyTargets(List<String> selected) {
        JSONObject out=json();
        try{out.put("targets",new JSONArray(selected));return new Rule(out);}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    public String pathDescription() {
        if (notification()) return "系统设置 → 通知管理 → " + channelApp + " → " + channelName;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < routes.size(); i++) {
            Route route = routes.get(i);
            if (i > 0) text.append("\n\n");
            text.append(i + 1).append(". ").append(route.name).append("\n").append(route.pkg);
            for (List<String> step : route.path) {
                List<String> labels = new ArrayList<>();
                for (String label : step) {
                    if (!label.startsWith("@id:")) labels.add(label);
                    else if (label.endsWith(":id/setting_menu")) labels.add("右上角设置");
                }
                text.append(" → ").append(labels.isEmpty() ? "相应的菜单按钮" : String.join(" / ", labels));
            }
            if (!route.activity.isEmpty()) text.append("\n直达页面：").append(route.activity);
        }
        return text.toString();
    }
    public JSONObject json() {
        try {
            JSONObject out = new JSONObject();
            out.put("id", id); out.put("title", title); out.put("group", group); out.put("package", pkg);
            out.put("activity", activity); out.put("source", source); out.put("explanation", explanation); out.put("manual", manual);
            out.put("brands", new JSONArray(brands)); out.put("targets", new JSONArray(targets));
            JSONArray entries = new JSONArray(); for (Route route : routes) entries.put(route.json()); out.put("routes", entries);
            JSONObject synonyms = new JSONObject(); for (Map.Entry<String,List<String>> item : aliases.entrySet()) synonyms.put(item.getKey(), new JSONArray(item.getValue())); out.put("aliases", synonyms);
            out.put("channelId", channelId); out.put("channelName", channelName); out.put("channelApp", channelApp);
            return out;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
