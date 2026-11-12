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
import java.util.function.Predicate;
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
            return !protectedActivity(activity) && SplashRules.activityMatches(activity, entry);
        }

        boolean withinTime(long elapsedMs) {
            return elapsedMs < entry.optInt("matchTimeMs", Integer.MAX_VALUE);
        }

        int maxPerVisit() { return entry.optInt("maxPerVisit", 3); }

        boolean matchesTarget(AccessibilityNodeInfo node) {
            return SplashRules.targetMatches(pkg, node, target, true);
        }

        private boolean isReviewedWeiboVideoCard() {
            JSONArray snapshots = entry.optJSONArray("snapshots");
            return "com.sina.weibo".equals(pkg) && entry.optBoolean("exactActivity")
                    && snapshots != null && snapshots.length() == 1
                    && "https://i.gkd.li/import/13635551".equals(snapshots.optString(0));
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
        return hasRules(pkg, activity, elapsedMs, rule -> true);
    }

    boolean hasRules(String pkg, String activity, long elapsedMs, Predicate<Rule> available) {
        for (Rule rule : byPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (available.test(rule) && rule.withinTime(elapsedMs) && rule.activityMatches(activity)) return true;
        }
        return false;
    }

    static boolean protectedActivity(String activity) {
        return activity != null && PROTECTED_ACTIVITY.matcher(activity).find();
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen) {
        return find(root, activity, screen, 0);
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen, long elapsedMs) {
        return find(root, activity, screen, elapsedMs, UiDriver.walkComplete(root));
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen, long elapsedMs,
               List<AccessibilityNodeInfo> nodes) {
        return find(root, activity, screen, elapsedMs, nodes, rule -> true);
    }

    Match find(AccessibilityNodeInfo root, String activity, Rect screen, long elapsedMs,
               List<AccessibilityNodeInfo> nodes, Predicate<Rule> available) {
        if (root == null || screen.isEmpty()) return null;
        String pkg = String.valueOf(root.getPackageName());
        if (nodes.isEmpty()) return null;
        Match hit = null;
        for (Rule rule : byPackage.getOrDefault(pkg, Collections.emptyList())) {
            if (!available.test(rule) || !rule.withinTime(elapsedMs) || !rule.activityMatches(activity)) continue;
            Boolean requirementsPresent = null;
            for (AccessibilityNodeInfo node : nodes) {
                if (!node.isEnabled() || !rule.matchesTarget(node)) continue;
                AccessibilityNodeInfo click = clickTarget(root, node, pkg, screen);
                if (click == null) continue;
                if (requirementsPresent == null) requirementsPresent = rule.requirementsPresent(nodes);
                if (!requirementsPresent) break;
                if (hit != null && !hit.target.equals(click)) return null;
                if (hit == null) hit = new Match(rule, click);
            }
        }
        return hit != null && safePage(nodes, hit) ? hit : null;
    }

    boolean visible(AccessibilityNodeInfo root, String activity, Rule rule) {
        return visible(root, activity, rule, UiDriver.walkComplete(root));
    }

    boolean visible(AccessibilityNodeInfo root, String activity, Rule rule, List<AccessibilityNodeInfo> nodes) {
        if (root == null || !rule.pkg.equals(String.valueOf(root.getPackageName()))
                || !rule.activityMatches(activity)) return false;
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

    private static boolean safePage(List<AccessibilityNodeInfo> nodes, Match hit) {
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            if (SplashSkipper.unsafeWindowNode(node, false)) return false;
            String text = label(node.getText()), desc = label(node.getContentDescription());
            if (CustomSkipRule.sensitiveText(text)) return false;
            if (CustomSkipRule.sensitiveText(desc) && !isWeiboNewsDescription(node, desc, hit)) return false;
        }
        return true;
    }

    private static boolean isWeiboNewsDescription(AccessibilityNodeInfo node, String desc, Match hit) {
        if (!hit.rule.isReviewedWeiboVideoCard() || node.getWindowId() != hit.target.getWindowId()
                || !"com.sina.weibo".equals(String.valueOf(node.getPackageName()))
                || !"com.sina.weibo:id/tweet_message".equals(node.getViewIdResourceName())
                || !"android.view.View".equals(String.valueOf(node.getClassName()))
                || node.getChildCount() != 0 || node.isClickable() || node.isLongClickable() || node.isCheckable()
                || node.isEditable() || node.isFocusable() || node.isFocused() || node.isAccessibilityFocused()) {
            return false;
        }
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null || !"com.sina.weibo:id/weibo_content".equals(parent.getViewIdResourceName())) return false;
        Rect article = new Rect();
        Rect close = new Rect();
        node.getBoundsInScreen(article);
        hit.target.getBoundsInScreen(close);
        if (article.isEmpty() || Rect.intersects(article, close)) return false;

        // The reviewed post mentions Alipay in two app-development statements.
        String remaining = desc.replace("支付宝也将全面启动鸿蒙原生应用开发了", "")
                .replace("支付宝将基于HarmonyOS NEXT版本开发应用", "");
        return !remaining.equals(desc) && !CustomSkipRule.sensitiveText(remaining);
    }

    private static String label(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
