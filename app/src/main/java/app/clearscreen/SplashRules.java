package app.clearscreen;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Evaluates the fixed predicates compiled from the pinned MIT splash groups. */
final class SplashRules {
    enum Action { CLICK, TAP, CLICK_LOWER_LEFT }

    static final SplashRules EMPTY = new SplashRules(new JSONObject());
    private static final Pattern CLOSE_COUNTDOWN = Pattern.compile("(?:关闭|關閉)[\\s\\p{Zs}]*[0-9]{1,2}(?:秒|s)?");
    private final Map<String, List<JSONObject>> byPackage = new HashMap<>();
    private final Set<String> sdkResources = new HashSet<>();
    private final Set<String> clickableSdkIds = new HashSet<>();

    SplashRules(Context context) {
        this(parse(new Store(context).asset("splash-rules.json")));
    }

    private static JSONObject parse(String text) {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IllegalStateException("开屏规则读取失败", e);
        }
    }

    SplashRules(JSONObject source) {
        JSONArray rules = source.optJSONArray("rules");
        if (rules != null) {
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule != null) {
                    byPackage.computeIfAbsent(rule.optString("package"), key -> new ArrayList<>()).add(rule);
                }
            }
        }
        JSONArray sdk = source.optJSONArray("sdkRules");
        if (sdk != null) {
            for (int i = 0; i < sdk.length(); i++) {
                JSONObject item = sdk.optJSONObject(i);
                if (item == null) continue;
                String resourceName = item.optString("resourceName");
                String clickableId = item.optString("clickableResourceId");
                if (!resourceName.isEmpty()) sdkResources.add(resourceName);
                if (!clickableId.isEmpty()) clickableSdkIds.add(clickableId);
            }
        }
    }

    boolean matches(String pkg, String activity, AccessibilityNodeInfo node) {
        return matches(pkg, activity, node, 0);
    }

    boolean matches(String pkg, String activity, AccessibilityNodeInfo node, long elapsedMs) {
        return action(pkg, activity, node, elapsedMs) != null;
    }

    Action action(String pkg, String activity, AccessibilityNodeInfo node, long elapsedMs) {
        return prepare(pkg, activity, elapsedMs, null).action(node, UiDriver.text(node));
    }

    Prepared prepare(String pkg, String activity, long elapsedMs, List<AccessibilityNodeInfo> nodes) {
        return prepare(pkg, activity, elapsedMs, nodes, false);
    }

    Prepared prepare(String pkg, String activity, long elapsedMs, List<AccessibilityNodeInfo> nodes,
                     boolean exactActivityOnly) {
        List<JSONObject> available = byPackage.get(pkg);
        List<JSONObject> active = Collections.emptyList();
        if (available != null) {
            active = new ArrayList<>(available.size());
            for (JSONObject rule : available) {
                if (exactActivityOnly && !rule.optBoolean("exactActivity")) continue;
                if (inMatchWindow(rule, elapsedMs) && activityMatches(activity, rule)) active.add(rule);
            }
        }
        return new Prepared(pkg, active, nodes, !exactActivityOnly);
    }

    final class Prepared {
        private final String pkg;
        private final List<JSONObject> active;
        private final List<AccessibilityNodeInfo> nodes;
        private final boolean includeSdk;
        private final Map<JSONObject, Boolean> requirements = new IdentityHashMap<>();

        Prepared(String pkg, List<JSONObject> active, List<AccessibilityNodeInfo> nodes, boolean includeSdk) {
            this.pkg = pkg;
            this.active = active;
            this.nodes = nodes;
            this.includeSdk = includeSdk;
        }

        Action action(AccessibilityNodeInfo node, String label) {
            String id = node.getViewIdResourceName();
            if (includeSdk && id != null
                    && (sdkResources.contains(resourceName(id)) || (clickableSdkIds.contains(id) && node.isClickable()))) {
                return Action.CLICK;
            }
            for (JSONObject rule : active) {
                if (rule.optBoolean("requireClickable") && !node.isClickable()) continue;
                String evidence = rule.optString("evidence");
                if (evidence.equals("structure") || evidence.equals("activity") || evidence.equals("page")) {
                    boolean skipLabel = SplashSkipper.skipLabel(label)
                            || (node.getContentDescription() != null && SplashSkipper.skipLabel(node.getContentDescription().toString()));
                    if (evidence.equals("structure")) {
                        // Anonymous structural controls must themselves be clickable.
                        boolean countdownClose = rule.optBoolean("adCountdownClose")
                                && CLOSE_COUNTDOWN.matcher(label).matches();
                        if (!skipLabel && (!node.isClickable()
                                || (!label.isEmpty() && !countdownClose
                                && !label.matches("关闭|關閉|关闭广告|關閉廣告|[xX×]")))) continue;
                    } else if (!skipLabel) continue;
                }
                if (targetMatches(pkg, node, rule.optJSONObject("targetSelector")) && requirementsPresent(node, rule)) {
                    if (rule.optBoolean("lowerLeftAdControl")) return Action.CLICK_LOWER_LEFT;
                    return rule.optBoolean("tap") ? Action.TAP : Action.CLICK;
                }
            }
            return null;
        }

        private boolean requirementsPresent(AccessibilityNodeInfo node, JSONObject rule) {
            JSONArray required = rule.optJSONArray("required");
            if (required == null || required.length() == 0) return true;
            if (nodes == null) return requiredVisible(pkg, node, required);
            return requirements.computeIfAbsent(rule, ignored -> requiredInWindow(pkg, node.getWindowId(), nodes, required));
        }
    }

    SkipDiagnostics.Phase explainMiss(String pkg, String activity, long elapsedMs, int window,
                                      List<AccessibilityNodeInfo> nodes) {
        for (JSONObject rule : byPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (!inMatchWindow(rule, elapsedMs)) continue;
            JSONArray required = rule.optJSONArray("required");
            boolean wrongActivity = !activityMatches(activity, rule);
            if (!wrongActivity && (required == null || required.length() == 0)) continue;
            JSONObject target = rule.optJSONObject("targetSelector");
            boolean found = nodes.stream().anyMatch(node -> node.getWindowId() == window && targetMatches(pkg, node, target, true));
            if (!found) continue;
            if (wrongActivity) return SkipDiagnostics.Phase.PAGE_MISMATCH;
            if (!requiredInWindow(pkg, window, nodes, required)) return SkipDiagnostics.Phase.AD_EVIDENCE_MISSING;
        }
        return null;
    }

    private static boolean inMatchWindow(JSONObject rule, long elapsedMs) {
        return elapsedMs >= rule.optInt("matchDelay") && elapsedMs < rule.optInt("matchTime", Integer.MAX_VALUE);
    }

    static boolean activityMatches(String activity, JSONObject rule) {
        JSONArray included = rule.optJSONArray("activityIds");
        JSONArray excluded = rule.optJSONArray("excludeActivityIds");
        if ((included == null || included.length() == 0) && (excluded == null || excluded.length() == 0)) return true;
        if (activity == null || activity.isEmpty() || matchesAny(activity, excluded, false)) return false;
        return included == null || included.length() == 0 || matchesAny(activity, included, rule.optBoolean("exactActivity"));
    }

    private static boolean matchesAny(String value, JSONArray activities, boolean exact) {
        if (activities == null) return false;
        for (int i = 0; i < activities.length(); i++) {
            String activity = activities.optString(i);
            if (!activity.isEmpty() && (exact ? value.equals(activity) : value.startsWith(activity))) return true;
        }
        return false;
    }

    private static boolean requiredVisible(String pkg, AccessibilityNodeInfo candidate, JSONArray required) {
        if (required == null || required.length() == 0) return true;
        int window = candidate.getWindowId();
        AccessibilityNodeInfo root = candidate;
        for (int depth = 0; depth < 40; depth++) {
            AccessibilityNodeInfo parent = root.getParent();
            if (parent == null) {
                return requiredInWindow(pkg, window, UiDriver.walkComplete(root), required);
            }
            root = parent;
        }
        return false;
    }

    private static boolean requiredInWindow(String pkg, int window, List<AccessibilityNodeInfo> nodes, JSONArray required) {
        for (int i = 0; i < required.length(); i++) {
            JSONObject selector = required.optJSONObject(i);
            if (nodes.stream().noneMatch(node -> node.getWindowId() == window && targetMatches(pkg, node, selector, true))) {
                return false;
            }
        }
        return true;
    }

    private static boolean targetMatches(String pkg, AccessibilityNodeInfo node, JSONObject selector) {
        return targetMatches(pkg, node, selector, false);
    }

    static boolean targetMatches(String pkg, AccessibilityNodeInfo node, JSONObject selector, boolean exactPackage) {
        return SelectorNode.matches(pkg,SelectorNode.live(node),selector,exactPackage);
    }
    private static String resourceName(String id){
        int separator=id==null?-1:id.indexOf(":id/");
        return separator>0?id.substring(separator+4):null;
    }
}
