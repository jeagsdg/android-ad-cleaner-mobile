package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Matches a unique close button and prompt inside the same compact panel. */
final class PopupMatch {
    private static final Set<String> AD_MARKERS = new HashSet<>(Arrays.asList(
            "广告", "廣告", "推广", "推廣", "ad", "advertisement", "插屏广告", "插屏廣告"));
    private static final Set<String> AD_ACTIONS = new HashSet<>(Arrays.asList(
            "关闭广告", "關閉廣告", "关闭此广告", "關閉此廣告", "跳过广告", "跳過廣告", "跳过此广告", "跳過此廣告",
            "close ad", "close this ad", "close advertisement", "dismiss ad", "dismiss this ad", "skip ad", "skip this ad"));
    // Local presets for observed application pages. See NOTICE for reference and provenance.
    static final List<CustomSkipRule> APP_PRESETS = Arrays.asList(
            new CustomSkipRule("com.ss.android.ugc.aweme", "com.ss.android.ugc.aweme.main.MainActivity",
                    CustomSkipRule.POPUP, "", "以后再说", "检测到更新"),
            CustomSkipRule.popupPreset("com.netease.cloudmusic", "com.netease.cloudmusic.activity.MainActivity",
                    "com.netease.cloudmusic:id/md_dialog_cm_close_btn", "com.netease.cloudmusic:id/updateVersionTitle"),
            CustomSkipRule.popupPreset("com.netease.cloudmusic",
                    "com.netease.cloudmusic.music.biz.rn.activity.MainProcessLayerReactNativeActivity",
                    "com.netease.cloudmusic:id/md_dialog_cm_close_btn", "com.netease.cloudmusic:id/updateVersionTitle"),
            CustomSkipRule.popupPreset("com.tongcheng.android", "com.tongcheng.android.TongchengMainActivity",
                    "com.tongcheng.android:id/iv_home_ad_close", "com.tongcheng.android:id/home_ad_dialog_pager"),
            CustomSkipRule.popupPreset("com.taobao.idlefish", "com.taobao.idlefish.maincontainer.activity.MainActivity",
                    "com.taobao.idlefish:id/bar_close", "=开启个性化推荐"),
            CustomSkipRule.popupPreset("com.wuba.zhuanzhuan", "",
                    "com.wuba.zhuanzhuan:id/ezz", "=新人礼包"),
            CustomSkipRule.popupPreset("com.wuba.zhuanzhuan", "",
                    "com.wuba.zhuanzhuan:id/ba3", "com.wuba.zhuanzhuan:id/d6f"));

    final CustomSkipRule rule;
    final AccessibilityNodeInfo target;

    private PopupMatch(CustomSkipRule rule, AccessibilityNodeInfo target) {
        this.rule = rule;
        this.target = target;
    }

    static PopupMatch find(AccessibilityNodeInfo root, Rect screen, List<CustomSkipRule> rules) {
        return find(root, screen, rules, UiDriver.walkComplete(root), null);
    }

    static PopupMatch find(AccessibilityNodeInfo root, Rect screen, List<CustomSkipRule> rules,
                           List<AccessibilityNodeInfo> nodes, Consumer<SkipDiagnostics.Phase> trace) {
        if (root == null || screen.isEmpty()) return noMatch(trace, SkipDiagnostics.Phase.WINDOW_UNAVAILABLE);
        if (nodes.isEmpty()) return noMatch(trace, SkipDiagnostics.Phase.TREE_UNAVAILABLE);
        if (rules.isEmpty()) return noMatch(trace, SkipDiagnostics.Phase.PAGE_MISMATCH);
        String pkg = String.valueOf(root.getPackageName());
        int window = root.getWindowId();
        if (!safeWindow(nodes)) return noMatch(trace, SkipDiagnostics.Phase.GUARDED);
        PopupMatch hit = null;
        boolean candidate = false;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            if (node.getWindowId() != window || !pkg.equals(String.valueOf(node.getPackageName()))) continue;
            for (CustomSkipRule rule : rules) {
                if (!pkg.equals(rule.pkg)) continue;
                if (!(rule.backsOut() ? rule.matchesPrompt(node) : rule.matchesButton(node))) continue;
                candidate = true;
                AccessibilityNodeInfo click;
                if (rule.times == 0) {
                    if (!inPopup(root, node, screen, rule)) continue;
                    click = null;
                } else if (rule.backsOut()) {
                    if (!standaloneModal(root, screen) || !safePromptPanel(root, null, rule)) continue;
                    click = null;
                } else {
                    click = SplashSkipper.ruleClickTarget(node, rule);
                    if (click == null || click.getWindowId() != window || (!inPopup(root, click, screen, rule)
                            && (!rule.matchesLegacyTargetResource(node) || !inFloatingAd(root, click, screen, rule)))) continue;
                }
                if (hit != null && (!java.util.Objects.equals(hit.target, click) || !hit.rule.context.equals(rule.context)
                        || hit.rule.delayMs != rule.delayMs || hit.rule.gesture != rule.gesture
                        || hit.rule.sequenceIndex != rule.sequenceIndex || hit.rule.times != rule.times)) {
                    return noMatch(trace, SkipDiagnostics.Phase.AMBIGUOUS);
                }
                hit = new PopupMatch(rule, click);
            }
        }
        if (hit == null) return noMatch(trace, candidate ? SkipDiagnostics.Phase.NO_RULE_MATCH : SkipDiagnostics.Phase.NO_READABLE_TARGET);
        if (trace != null) trace.accept(SkipDiagnostics.Phase.TARGET_FOUND);
        return hit;
    }

    static PopupMatch findGenericAd(AccessibilityNodeInfo root, Rect screen) {
        return findGenericAd(root, screen, UiDriver.walkComplete(root));
    }

    static PopupMatch findGenericAd(AccessibilityNodeInfo root, Rect screen, List<AccessibilityNodeInfo> nodes) {
        if (root == null || screen.isEmpty()) return null;
        String pkg = String.valueOf(root.getPackageName());
        if (CustomSkipRule.protectedPackage(pkg)) return null;
        if (!safeWindow(nodes)) return null;
        Set<String> markers = new java.util.LinkedHashSet<>();
        for (AccessibilityNodeInfo node : nodes) {
            String label = label(node.getText()), description = label(node.getContentDescription());
            if (SplashSkipper.rewardAd(label) || SplashSkipper.rewardAd(description)) return null;
            if (!node.isVisibleToUser()) continue;
            if (node.getWindowId() != root.getWindowId() || !pkg.equals(String.valueOf(node.getPackageName()))) continue;
            if (AD_MARKERS.contains(label.toLowerCase(Locale.ROOT))) markers.add(label);
            if (AD_MARKERS.contains(description.toLowerCase(Locale.ROOT))) markers.add(description);
        }
        if (markers.isEmpty()) return null;
        PopupMatch hit = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser() || !node.isEnabled()) continue;
            String action = label(node.getText());
            if (!AD_ACTIONS.contains(action.toLowerCase(Locale.ROOT))) action = label(node.getContentDescription());
            if (!AD_ACTIONS.contains(action.toLowerCase(Locale.ROOT))) continue;
            for (String marker : markers) {
                CustomSkipRule rule = new CustomSkipRule(pkg, "", CustomSkipRule.POPUP, "", action, marker);
                if (!rule.matchesButton(node)) continue;
                AccessibilityNodeInfo click = SplashSkipper.ruleClickTarget(node, rule);
                if (click == null || click.getWindowId() != root.getWindowId()
                        || !inPopup(root, click, screen, rule, true)) continue;
                if (hit != null && !hit.target.equals(click)) return null;
                if (hit == null) hit = new PopupMatch(rule, click);
            }
        }
        return hit;
    }

    private static boolean safeWindow(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && SplashSkipper.unsafeWindowNode(node, false)) return false;
        }
        return true;
    }

    private static PopupMatch noMatch(Consumer<SkipDiagnostics.Phase> trace, SkipDiagnostics.Phase phase) {
        if (trace != null) trace.accept(phase);
        return null;
    }

    private static String label(CharSequence value) { return value == null ? "" : value.toString().trim(); }

    private static boolean inPopup(AccessibilityNodeInfo root, AccessibilityNodeInfo button, Rect screen, CustomSkipRule rule) {
        return inPopup(root, button, screen, rule, false);
    }

    private static boolean inPopup(AccessibilityNodeInfo root, AccessibilityNodeInfo button, Rect screen,
                                   CustomSkipRule rule, boolean genericAd) {
        AccessibilityNodeInfo panel = button.getParent();
        Rect viewport = new Rect();
        root.getBoundsInScreen(viewport);
        boolean embedded = screen.contains(viewport) && !compact(root, screen) && !viewport.equals(screen);
        for (int depth = 0; depth < 7 && panel != null; depth++, panel = panel.getParent()) {
            if (panel.isScrollable() || panel.getCollectionInfo() != null || panel.getCollectionItemInfo() != null) return false;
            if ((compact(panel, screen) || (embedded && !panel.equals(root) && compact(panel, viewport)))
                    && safePromptPanel(panel, rule.times == 0 ? null : button, rule, genericAd)) {
                if (rule.times == 0) return true;
                Rect bounds = new Rect(), target = new Rect();
                panel.getBoundsInScreen(bounds);
                button.getBoundsInScreen(target);
                return target.width() >= 12 && target.height() >= 12 && bounds.contains(target)
                        && target.height() < bounds.height() * .65;
            }
            if (panel.equals(root)) break;
        }
        return false;
    }

    private static boolean compact(AccessibilityNodeInfo panel, Rect screen) {
        Rect bounds = new Rect();
        panel.getBoundsInScreen(bounds);
        return !screen.isEmpty() && panel.isVisibleToUser() && bounds.width() >= screen.width() * .24
                && bounds.height() >= screen.height() * .08 && bounds.height() <= screen.height() * .65
                && bounds.contains(screen.centerX(), bounds.centerY())
                && bounds.centerY() >= screen.top + screen.height() * .25;
    }

    private static boolean standaloneModal(AccessibilityNodeInfo root, Rect screen) {
        Rect bounds = new Rect();
        root.getBoundsInScreen(bounds);
        return compact(root, screen) && bounds.left > screen.left && bounds.right < screen.right
                && bounds.top > screen.top && bounds.bottom < screen.bottom;
    }

    /** Imported exact ID pairs can also describe a small edge card outside a dialog. */
    private static boolean inFloatingAd(AccessibilityNodeInfo root, AccessibilityNodeInfo button, Rect screen, CustomSkipRule rule) {
        Rect hit = new Rect();
        button.getBoundsInScreen(hit);
        if (hit.width() < 12 || hit.height() < 12 || hit.width() > screen.width() * .25
                || hit.height() > screen.height() * .12 || !screen.contains(hit)) return false;
        AccessibilityNodeInfo panel = button.getParent();
        for (int depth = 0; depth < 4 && panel != null && !panel.equals(root); depth++, panel = panel.getParent()) {
            if (panel.isScrollable() || panel.getCollectionInfo() != null || panel.getCollectionItemInfo() != null) return false;
            Rect bounds = new Rect();
            panel.getBoundsInScreen(bounds);
            if (!panel.isVisibleToUser() || !screen.contains(bounds) || !bounds.contains(hit)
                    || bounds.width() > screen.width() * .70 || bounds.height() > screen.height() * .55
                    || hit.height() >= bounds.height() * .65) continue;
            boolean anchor = false;
            int controls = 0;
            for (AccessibilityNodeInfo node : UiDriver.walkComplete(panel)) {
                if (!node.isVisibleToUser()) continue;
                String text = label(node.getText()), desc = label(node.getContentDescription());
                if (node.isEditable() || node.isCheckable() || CustomSkipRule.sensitiveText(text)
                        || CustomSkipRule.sensitiveText(desc) || SplashSkipper.rewardAd(text) || SplashSkipper.rewardAd(desc)) return false;
                if (node.isEnabled() && node.isClickable() && ++controls > 4) return false;
                if (!node.equals(button) && rule.matchesCardAnchor(node)) anchor = true;
            }
            if (anchor) return true;
        }
        return false;
    }

    private static boolean safePromptPanel(AccessibilityNodeInfo panel, AccessibilityNodeInfo button, CustomSkipRule rule) {
        return safePromptPanel(panel, button, rule, false);
    }

    private static boolean safePromptPanel(AccessibilityNodeInfo panel, AccessibilityNodeInfo button,
                                           CustomSkipRule rule, boolean genericAd) {
        boolean hasPrompt = false;
        int controls = 0;
        for (AccessibilityNodeInfo node : UiDriver.walkComplete(panel)) {
            String text = label(node.getText()), desc = label(node.getContentDescription());
            if (genericAd && (CustomSkipRule.sensitiveText(text) || CustomSkipRule.sensitiveText(desc))) return false;
            if (!node.isVisibleToUser()) continue;
            if (node.isEditable() || node.isCheckable() || CustomSkipRule.sensitiveText(text)
                    || CustomSkipRule.sensitiveText(desc) || SplashSkipper.rewardAd(text)
                    || SplashSkipper.rewardAd(desc)) return false;
            if (node.isClickable() && node.isEnabled() && ++controls > 4) return false;
            if (!node.equals(button) && !node.isClickable() && node.getWindowId() == panel.getWindowId()
                    && rule.pkg.equals(String.valueOf(node.getPackageName()))
                    && (rule.matchesPrompt(node) || (genericAd && rule.context.equals(desc)))) hasPrompt = true;
        }
        return hasPrompt;
    }

    static boolean promptVisible(AccessibilityNodeInfo root, CustomSkipRule rule) {
        return promptVisible(UiDriver.walkComplete(root), rule);
    }

    static boolean promptVisible(List<AccessibilityNodeInfo> nodes, CustomSkipRule rule) {
        return nodes.isEmpty() || nodes.stream().anyMatch(node -> node.isVisibleToUser() && rule.matchesPrompt(node));
    }
}
