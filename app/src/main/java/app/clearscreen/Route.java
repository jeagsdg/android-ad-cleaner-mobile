package app.clearscreen;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import org.json.*;
import java.util.*;

/** A bounded entry route. Package transitions are explicit per step, never inferred. */
public final class Route {
    public final String pkg, activity, uri, foreground, source, reference, name, skipWhenPackagePresent;
    public final List<List<String>> path = new ArrayList<>();
    public final List<String> destinations = new ArrayList<>();
    public final List<String> anchors;

    public Route(JSONObject j, String fallbackPackage, String fallbackSource) {
        if (j == null) throw new IllegalArgumentException("缺少设置路径");
        pkg = j.optString("package", fallbackPackage);
        activity = j.optString("activity"); uri = j.optString("uri");
        if(!uri.isEmpty()&&!(uri.equals("gs://search/setting")&&pkg.equals("com.heytap.quicksearchbox")))throw new IllegalArgumentException("未经支持的应用入口");
        foreground = j.optString("foreground", pkg); source = j.optString("source", fallbackSource); reference = j.optString("reference");
        skipWhenPackagePresent = j.optString("skipWhenPackagePresent");
        name = j.optString("name", activity.isEmpty() ? "应用设置入口" : "设置直达入口");
        anchors = Rule.strings(j.optJSONArray("anchors"));
        JSONArray steps = j.optJSONArray("path"); String current = foreground;
        if (steps != null) for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            path.add(step == null ? Rule.strings(steps.optJSONArray(i)) : Rule.strings(step.optJSONArray("labels")));
            current = step == null ? current : step.optString("package", current); destinations.add(current);
        }
        if (path.size() > 8 || pkg.isEmpty()) throw new IllegalArgumentException("无效的设置路径");
        for (List<String> labels : path) if (labels.isEmpty()) throw new IllegalArgumentException("路径入口缺少名称");
    }
    public String finalPackage() { return destinations.isEmpty() ? foreground : destinations.get(destinations.size() - 1); }
    static boolean installed(Context context, String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }
    Intent launchIntent(Context context) {
        if (!uri.isEmpty()) return new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg);
        if (!activity.isEmpty()) return new Intent().setComponent(new ComponentName(pkg,
                activity.startsWith(".") ? pkg + activity : activity));
        if (pkg.equals("com.android.settings")) return new Intent(Settings.ACTION_SETTINGS);
        return context.getPackageManager().getLaunchIntentForPackage(pkg);
    }
    public JSONObject json() {
        JSONObject out = new JSONObject();
        try {
            out.put("package", pkg); out.put("activity", activity); out.put("uri", uri);
            out.put("foreground", foreground); out.put("source", source); out.put("name", name);
            out.put("reference", reference);
            if (!skipWhenPackagePresent.isEmpty()) out.put("skipWhenPackagePresent", skipWhenPackagePresent);
            out.put("anchors", new JSONArray(anchors)); JSONArray steps = new JSONArray();
            for (int i = 0; i < path.size(); i++) {
                JSONObject step = new JSONObject(); step.put("labels", new JSONArray(path.get(i)));
                step.put("package", destinations.get(i)); steps.put(step);
            }
            out.put("path", steps); return out;
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
}
