package app.clearscreen;

import android.content.Context;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Packaged in-app ad dismissals evaluated as bounded predicates and native clicks. */
final class PopupAdRules {
    private static final Pattern PROTECTED_ACTIVITY = Pattern.compile(
            "checkout|payment|payactivity|wallet|privacy|permission|password|login|logon|sign.?in|"
                    + "authorization|biometric|authenticate|reward", Pattern.CASE_INSENSITIVE);
    private final Map<String, List<Rule>> byPackage = new HashMap<>();

    PopupAdRules(Context context) {
        this(load(context));
    }

    private static JSONArray load(Context context) {
        try {
            return new JSONObject(new Store(context).asset("popup-ad-rules.json")).getJSONArray("rules");
        } catch (JSONException | IllegalStateException e) {
            return new JSONArray();
        }
    }

    private PopupAdRules(JSONArray entries) {
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String pkg = entry.optString("package");
            if (!pkg.isEmpty() && !CustomSkipRule.protectedPackage(pkg) && entry.optJSONObject("targetSelector") != null) {
                byPackage.computeIfAbsent(pkg, key -> new ArrayList<>()).add(new Rule(pkg, entry));
            }
        }
    }

    static final class Rule {
        private final String pkg;
        private final JSONObject entry;
        private final JSONObject target;
        private final JSONArray requirements;

        Rule(String pkg, JSONObject entry) {
            this.pkg = pkg;
            this.entry = entry;
            target = entry.optJSONObject("targetSelector");
            requirements = entry.optJSONArray("required");
        }

        boolean activityMatches(String activity) {
            return (activity == null || !PROTECTED_ACTIVITY.matcher(activity).find())
                    && SplashRules.activityMatches(activity, entry);
        }

        boolean withinTime(long elapsedMs) {
            return elapsedMs < entry.optInt("matchTimeMs", Integer.MAX_VALUE);
        }

        int maxPerVisit() { return entry.optInt("maxPerVisit", 3); }

        boolean matchesTarget(AccessibilityNodeInfo node) {
            return SplashRules.targetMatches(pkg, node, target, true);
        }

        boolean requirementsPresent(List<AccessibilityNodeInfo> nodes) {
            if (requirements == null) return true;
            for (int i = 0; i < requirements.length(); i++) {
                JSONObject selector = requirements.optJSONObject(i);
                if (nodes.stream().noneMatch(node -> SplashRules.targetMatches(pkg, node, selector, true))) return false;
            }
            return true;
        }
    }

    static final class Match {
        final Rule rule;
        final AccessibilityNodeInfo target;

        Match(Rule rule, AccessibilityNodeInfo target) {
            this.rule = rule;
            this.target = target;
        }
    }

    boolean hasRules(String pkg, String activity) {
        return hasRules(pkg, activity, 0);
    }

    boolean hasRules(String pkg, String activity, long elapsedMs) {
        for (Rule rule : byPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (rule.withinTime(elapsedMs) && rule.activityMatches(activity)) return true;
        }
        return false;
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen) {
        return find(root, activity, screen, 0);
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen, long elapsedMs) {
        if (root == null || screen.isEmpty()) return null;
        String pkg = String.valueOf(root.getPackageName());
        if (!hasRules(pkg, activity, elapsedMs)) return null;
        List<AccessibilityNodeInfo> nodes = UiDriver.walkComplete(root);
        if (nodes.isEmpty()) return null;
        Match hit = null;
        for (Rule rule : byPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (!rule.withinTime(elapsedMs) || !rule.activityMatches(activity)) continue;
            for (AccessibilityNodeInfo node : nodes) {
                if (!node.isEnabled() || !rule.matchesTarget(node)) continue;
                AccessibilityNodeInfo click = clickTarget(root, node, pkg, screen);
                if (click == null || !rule.requirementsPresent(nodes)) continue;
                if (hit != null && !hit.target.equals(click)) return null;
                if (hit == null) hit = new Match(rule, click);
            }
        }
        return hit != null && safePage(nodes) ? hit : null;
    }

    boolean visible(AccessibilityNodeInfo root, String activity, Rule rule) {
        if (root == null || !rule.pkg.equals(String.valueOf(root.getPackageName()))
                || !rule.activityMatches(activity)) return false;
        List<AccessibilityNodeInfo> nodes = UiDriver.walkComplete(root);
        return nodes.isEmpty()
                || (nodes.stream().anyMatch(rule::matchesTarget) && rule.requirementsPresent(nodes));
    }

    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo root, AccessibilityNodeInfo node,
                                                      String pkg, Rect screen) {
        AccessibilityNodeInfo click = SplashSkipper.clickable(node);
        if (click == null || click.equals(root) || click.getWindowId() != root.getWindowId()
                || !pkg.equals(String.valueOf(click.getPackageName())) || !SplashSkipper.safeActionLabel(node)) return null;
        Rect bounds = new Rect();
        click.getBoundsInScreen(bounds);
        if (!screen.contains(bounds) || bounds.width() < 12 || bounds.height() < 12
                || bounds.width() > screen.width() * .45 || bounds.height() > screen.height() * .18) return null;
        if (!click.equals(node)) {
            Rect childBounds = new Rect();
            node.getBoundsInScreen(childBounds);
            if (!click.equals(node.getParent()) || !bounds.contains(childBounds)
                    || childBounds.width() * 2 < bounds.width() || childBounds.height() * 2 < bounds.height()) return null;
        }
        return SplashSkipper.safeClickTarget(click) ? click : null;
    }

    private static boolean safePage(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            if (node.isEditable() && node.isFocused()) return false;
            String text = label(node.getText()), desc = label(node.getContentDescription());
            if (CustomSkipRule.sensitiveText(text) || CustomSkipRule.sensitiveText(desc)
                    || SplashSkipper.rewardAd(text) || SplashSkipper.rewardAd(desc)) return false;
        }
        return true;
    }

    private static String label(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
