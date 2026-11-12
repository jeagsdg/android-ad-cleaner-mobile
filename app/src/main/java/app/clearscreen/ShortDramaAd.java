package app.clearscreen;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Validates the reviewed Hongguo playback page before asking its pager to advance. */
final class ShortDramaAd {
    // ViewPager2 exposes this resource ID as a custom accessibility action on older APIs too.
    @SuppressLint("InlinedApi")
    static final int PAGE_DOWN = android.R.id.accessibilityActionPageDown;
    private static final String PACKAGE = "com.phoenix.read";
    private static final String PAGER_ID = PACKAGE + ":id/l0";
    private static final String HINT_ID = PACKAGE + ":id/ank";
    private static final String PAGER = "androidx.viewpager.widget.ViewPager";
    private static final String LIST = "androidx.recyclerview.widget.RecyclerView";

    private ShortDramaAd() { }

    static AccessibilityNodeInfo target(AccessibilityNodeInfo root, AccessibilityNodeInfo hint,
                                        Rect display, List<AccessibilityNodeInfo> nodes) {
        if (root.getWindowId() < 0 || display.width() >= display.height()
                || !sameWindow(root, hint) || nodes.stream().anyMatch(ShortDramaAd::protectsPlayback)) return null;
        AccessibilityNodeInfo page = hint;
        for (int i = 0; i < 7; i++) {
            page = page.getParent();
            if (page == null || !page.isVisibleToUser() || !sameWindow(root, page)) return null;
        }
        AccessibilityNodeInfo parent = page.getParent();
        AccessibilityNodeInfo pager = isPlaybackList(parent) ? parent.getParent() : parent;
        if (!page.equals(visiblePage(pager, display)) || !sameWindow(root, pager) || !pager.isScrollable()
                || pager.getActionList().stream().noneMatch(action -> action.getId() == PAGE_DOWN)) return null;
        return pager;
    }

    static boolean resumed(AccessibilityNodeInfo previousPager, Rect display, List<AccessibilityNodeInfo> nodes) {
        if (nodes.stream().anyMatch(node -> node.isVisibleToUser()
                && (HINT_ID.equals(node.getViewIdResourceName()) || TextUtils.equals("广告", node.getContentDescription())
                || TextUtils.equals("广告", node.getText()) || node.isEditable() || node.isCheckable()))) return false;
        for (AccessibilityNodeInfo node : nodes) {
            if (!previousPager.equals(node)) continue;
            AccessibilityNodeInfo page = visiblePage(node, display);
            if (page == null) return false;
            List<AccessibilityNodeInfo> content = UiDriver.walkComplete(page);
            return content.stream().anyMatch(child -> visibleText(child, "选集"))
                    && content.stream().anyMatch(child -> visibleText(child, "全屏观看"));
        }
        return false;
    }

    private static AccessibilityNodeInfo visiblePage(AccessibilityNodeInfo pager, Rect display) {
        if (pager == null || !pager.isVisibleToUser() || !pager.isEnabled() || pager.isClickable()
                || !TextUtils.equals(PAGER, pager.getClassName()) || !PAGER_ID.equals(pager.getViewIdResourceName())
                || pager.getChildCount() == 0) return null;
        AccessibilityNodeInfo holder = pager;
        AccessibilityNodeInfo first = pager.getChild(0);
        // ViewPager2 may omit its internal RecyclerView from the accessibility tree.
        if (isPlaybackList(first)) {
            if (pager.getChildCount() != 1 || !sameWindow(pager, first) || !first.isVisibleToUser()) return null;
            holder = first;
        }
        Rect bounds = new Rect();
        pager.getBoundsInScreen(bounds);
        if (!display.contains(bounds) || bounds.width() < display.width() * .9
                || bounds.height() < display.height() * .9) return null;
        AccessibilityNodeInfo page = null;
        for (int i = 0; i < holder.getChildCount(); i++) {
            AccessibilityNodeInfo child = holder.getChild(i);
            if (child == null) return null;
            if (!child.isVisibleToUser()) continue;
            if (page != null || !sameWindow(pager, child)
                    || !TextUtils.equals("android.widget.FrameLayout", child.getClassName())) return null;
            Rect childBounds = new Rect();
            child.getBoundsInScreen(childBounds);
            if (!bounds.equals(childBounds)) return null;
            page = child;
        }
        return page;
    }

    private static boolean isPlaybackList(AccessibilityNodeInfo node) {
        return node != null && TextUtils.equals(LIST, node.getClassName()) && node.getViewIdResourceName() == null;
    }

    private static boolean protectsPlayback(AccessibilityNodeInfo node) {
        return node.isVisibleToUser() && (node.isEditable() || node.isCheckable()
                || visibleText(node, "选集") || visibleText(node, "全屏观看")
                || (PACKAGE + ":id/ttlive_player_render_view").equals(node.getViewIdResourceName()));
    }

    private static boolean visibleText(AccessibilityNodeInfo node, String text) {
        return node.isVisibleToUser() && TextUtils.equals(PACKAGE, node.getPackageName())
                && TextUtils.equals(text, node.getText());
    }

    private static boolean sameWindow(AccessibilityNodeInfo root, AccessibilityNodeInfo node) {
        return node != null && root.getWindowId() == node.getWindowId()
                && TextUtils.equals(PACKAGE, node.getPackageName());
    }
}
