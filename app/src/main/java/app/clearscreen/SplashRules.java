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
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Evaluates the fixed predicates compiled from the pinned MIT splash groups. */
final class SplashRules {
    enum Action { CLICK, TAP, CLICK_LOWER_LEFT }

    static final SplashRules EMPTY = new SplashRules(new JSONObject());
    private static final int MAX_DISTANCE = 12;
    private static final int MAX_VISITS = 96;
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
                    boolean skipLabel = SplashSkipper.skipLabel(label);
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
        if (selector == null) return false;
        JSONArray path = selector.optJSONArray("path");
        JSONArray relations = selector.optJSONArray("relations");
        int target = selector.optInt("target", -1);
        if (path == null || relations == null || relations.length() + 1 != path.length()
                || target < 0 || target >= path.length()
                || !nodeMatches(pkg, node, path.optJSONObject(target), exactPackage)) return false;
        PathCheck check = new PathCheck(pkg, path, relations, exactPackage);
        return check.check(node, target, -1) && check.check(node, target, 1);
    }

    private static final class PathCheck {
        private final String pkg;
        private final JSONArray path;
        private final JSONArray relations;
        private final boolean exactPackage;
        private int remaining = MAX_VISITS;

        PathCheck(String pkg, JSONArray path, JSONArray relations, boolean exactPackage) {
            this.pkg = pkg;
            this.path = path;
            this.relations = relations;
            this.exactPackage = exactPackage;
        }

        // Invert the relations to the right of an explicitly marked target.
        boolean check(AccessibilityNodeInfo node, int from, int direction) {
            int next = from + direction;
            if (next < 0 || next >= path.length()) return true;
            if (remaining <= 0) return false;
            JSONArray relation = relations.optJSONArray(Math.min(from, next));
            if (relation == null) return false;
            JSONArray slots = relation.optJSONArray(1);
            if (slots == null) return false;
            switch (relation.optString(0)) {
                case ">":
                    return direction < 0 ? ancestors(node, slots, next, direction)
                            : descendants(node, slots, next, direction, 1);
                case "<<":
                    if (slots.length() != 0) {
                        return direction > 0 ? indexedAncestors(node, slots, next, direction)
                                : new IndexedDescendants(slots, child -> nodeMatches(pkg, child,
                                        path.optJSONObject(next), exactPackage) && check(child, next, direction)).find(node);
                    }
                    return direction > 0 ? ancestors(node, slots, next, direction)
                            : descendants(node, slots, next, direction, 1);
                case "<":
                    if (direction < 0) return children(node, slots, next, direction);
                    AccessibilityNodeInfo parent = node.getParent();
                    return parent != null && (slots.length() == 0 || accepts(slots, childIndex(parent, node) + 1))
                            && consider(parent, next, direction);
                case "+":
                    return siblings(node, direction, slots, next, direction);
                case "-":
                    return siblings(node, -direction, slots, next, direction);
                default:
                    return false;
            }
        }

        private boolean consider(AccessibilityNodeInfo node, int at, int direction) {
            return remaining-- > 0 && nodeMatches(pkg, node, path.optJSONObject(at), exactPackage)
                    && check(node, at, direction);
        }

        private boolean ancestors(AccessibilityNodeInfo node, JSONArray slots, int next, int direction) {
            for (int distance = 1; distance <= lastSlot(slots) && remaining > 0; distance++) {
                node = node.getParent();
                if (node == null) return false;
                if (accepts(slots, distance) && consider(node, next, direction)) return true;
            }
            return false;
        }

        private boolean indexedAncestors(AccessibilityNodeInfo node, JSONArray slots, int next, int direction) {
            AccessibilityNodeInfo parent = node;
            for (int depth = 1; depth <= lastSlot(slots) && remaining > 0; depth++) {
                parent = parent.getParent();
                if (parent == null) return false;
                remaining--;
                if (nodeMatches(pkg, parent, path.optJSONObject(next), exactPackage)
                        && new IndexedDescendants(slots, node::equals).find(parent)
                        && check(parent, next, direction)) return true;
            }
            return false;
        }

        // Indexed << uses the descendant's one-based preorder position.
        private final class IndexedDescendants {
            private final JSONArray slots;
            private final int limit;
            private final Predicate<AccessibilityNodeInfo> match;
            private int position;

            IndexedDescendants(JSONArray slots, Predicate<AccessibilityNodeInfo> match) {
                this.slots = slots;
                this.limit = lastSlot(slots);
                this.match = match;
            }

            boolean find(AccessibilityNodeInfo parent) {
                for (int i = 0; i < parent.getChildCount() && position < limit && remaining > 0; i++) {
                    remaining--;
                    AccessibilityNodeInfo child = parent.getChild(i);
                    if (child == null) {
                        // An unreadable child makes later positions indeterminate.
                        position = limit;
                        return false;
                    }
                    if (accepts(slots, ++position) && match.test(child)) return true;
                    if (find(child)) return true;
                }
                return false;
            }
        }

        private boolean descendants(AccessibilityNodeInfo node, JSONArray slots, int next, int direction, int depth) {
            if (depth > lastSlot(slots)) return false;
            for (int i = 0; i < node.getChildCount() && remaining > 0; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (accepts(slots, depth)) {
                    if (consider(child, next, direction)) return true;
                } else {
                    remaining--;
                }
                if (child != null && descendants(child, slots, next, direction, depth + 1)) return true;
            }
            return false;
        }

        private boolean children(AccessibilityNodeInfo node, JSONArray slots, int next, int direction) {
            for (int i = 0; i < node.getChildCount() && i < MAX_VISITS && remaining > 0; i++) {
                if (accepts(slots, i + 1) && consider(node.getChild(i), next, direction)) return true;
            }
            return false;
        }

        private boolean siblings(AccessibilityNodeInfo node, int step, JSONArray slots, int next, int direction) {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent == null) return false;
            int index = childIndex(parent, node);
            if (index < 0) return false;
            for (int offset = 1; offset <= lastSlot(slots) && remaining > 0; offset++) {
                int sibling = index + step * offset;
                if (sibling < 0 || sibling >= parent.getChildCount()) return false;
                if (accepts(slots, offset) && consider(parent.getChild(sibling), next, direction)) return true;
            }
            return false;
        }
    }

    private static int childIndex(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        for (int i = 0; i < parent.getChildCount() && i < MAX_VISITS; i++) {
            if (child.equals(parent.getChild(i))) return i;
        }
        return -1;
    }

    private static int lastSlot(JSONArray slots) {
        return slots.length() == 0 ? MAX_DISTANCE : slots.optInt(slots.length() - 1);
    }

    private static boolean accepts(JSONArray slots, int position) {
        if (position < 1) return false;
        if (slots.length() == 0) return true;
        for (int i = 0; i < slots.length(); i++) {
            if (slots.optInt(i) == position) return true;
        }
        return false;
    }

    private static boolean nodeMatches(String pkg, AccessibilityNodeInfo node, JSONObject predicate, boolean exactPackage) {
        if (node == null || predicate == null || !node.isVisibleToUser()
                || (exactPackage && !pkg.equals(String.valueOf(node.getPackageName())))) return false;
        String cls = predicate.optString("class");
        String actual = String.valueOf(node.getClassName());
        if (!cls.equals("*") && !actual.equals(cls) && !actual.endsWith("." + cls)) return false;
        JSONArray tests = predicate.optJSONArray("tests");
        if (tests == null) return false;
        for (int i = 0; i < tests.length(); i++) {
            JSONArray alternatives = tests.optJSONArray(i);
            if (alternatives == null || !anyAlternative(pkg, node, alternatives)) return false;
        }
        return true;
    }

    private static boolean anyAlternative(String pkg, AccessibilityNodeInfo node, JSONArray alternatives) {
        for (int i = 0; i < alternatives.length(); i++) {
            JSONArray choice = alternatives.optJSONArray(i);
            if (choice == null || choice.length() == 0) continue;
            int matched = 0;
            while (matched < choice.length() && test(pkg, node, choice.optJSONArray(matched))) matched++;
            if (matched == choice.length()) return true;
        }
        return false;
    }

    private static boolean test(String pkg, AccessibilityNodeInfo node, JSONArray condition) {
        if (condition == null) return false;
        String field = condition.optString(0);
        String operator = condition.optString(1);
        switch (field) {
            case "id":
                return compare(node.getViewIdResourceName(), condition);
            case "resourceName":
                return compare(resourceName(node.getViewIdResourceName()), condition);
            case "vid":
                String id = node.getViewIdResourceName();
                return compare(id != null && id.startsWith(pkg + ":id/") ? resourceName(id) : null, condition);
            case "text":
                return compare(node.getText(), condition);
            case "desc":
                return compare(node.getContentDescription(), condition);
            case "text.length":
                return node.getText() != null && compare(node.getText().length(), operator, condition.optInt(2));
            case "desc.length":
                return node.getContentDescription() != null
                        && compare(node.getContentDescription().length(), operator, condition.optInt(2));
            case "childCount":
                return compare(node.getChildCount(), operator, condition.optInt(2));
            case "visibleToUser":
                return node.isVisibleToUser() == condition.optBoolean(2);
            case "clickable":
                return node.isClickable() == condition.optBoolean(2);
            default:
                return false;
        }
    }

    private static String resourceName(String id) {
        int separator = id == null ? -1 : id.indexOf(":id/");
        return separator > 0 ? id.substring(separator + 4) : null;
    }

    private static boolean compare(CharSequence value, JSONArray condition) {
        String actual = value == null ? null : value.toString();
        String expected = condition.isNull(2) ? null : condition.optString(2);
        switch (condition.optString(1)) {
            case "=": return Objects.equals(actual, expected);
            case "!=": return !Objects.equals(actual, expected);
            case "^=": return actual != null && expected != null && actual.startsWith(expected);
            case "$=": return actual != null && expected != null && actual.endsWith(expected);
            case "*=": return actual != null && expected != null && actual.contains(expected);
            default: return false;
        }
    }

    private static boolean compare(int actual, String operator, int expected) {
        switch (operator) {
            case "=": return actual == expected;
            case "<": return actual < expected;
            case "<=": return actual <= expected;
            case ">": return actual > expected;
            case ">=": return actual >= expected;
            default: return false;
        }
    }
}
