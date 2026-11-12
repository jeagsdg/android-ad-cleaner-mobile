package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Original-image controls scoped to reviewed GKD v72 activities and selectors. */
final class MediaAssist {
    private static final String WECHAT = "com.tencent.mm";
    private static final String QQ = "com.tencent.mobileqq";
    private static final List<String> WECHAT_ALBUMS = Arrays.asList(
            "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI",
            "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI");
    private static final List<String> QQ_ALBUMS = Arrays.asList(
            "com.tencent.mobileqq.activity.SplashActivity",
            "com.tencent.qqnt.qbasealbum.WinkHomeActivity",
            "com.tencent.mobileqq.activity.photo.album.NewPhotoListActivity");
    private static final String FILE_SIZE = "[0-9]{1,5}(?:\\.[0-9]{1,2})? ?(?:[KMG](?:B)?|B)";
    private static final Pattern WECHAT_VIEW = Pattern.compile(
            "查看原图(?: ?(?:\\(" + FILE_SIZE + "\\)|（" + FILE_SIZE + "）))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENSITIVE_ACTION = Pattern.compile(
            "(?:发送|傳送|传送|登入|登陆|授權|授权|send|log ?in|authori[sz]e).*", Pattern.CASE_INSENSITIVE);

    private MediaAssist() {}

    static AccessibilityNodeInfo find(AccessibilityNodeInfo root, String activity, Set<String> enabled) {
        if (root == null || activity == null || enabled.isEmpty()) return null;
        String pkg = String.valueOf(root.getPackageName());
        boolean wechatView = WECHAT.equals(pkg) && enabled.contains("wechat-view")
                && activity.equals("com.tencent.mm.ui.LauncherUI");
        boolean wechatOriginal = WECHAT.equals(pkg) && enabled.contains("wechat-original")
                && WECHAT_ALBUMS.contains(activity);
        boolean qqView = QQ.equals(pkg) && enabled.contains("qq-view")
                && activity.equals("com.tencent.richframework.gallery.QQGalleryActivity");
        boolean qqOriginal = QQ.equals(pkg) && enabled.contains("qq-original") && QQ_ALBUMS.contains(activity);
        if (!wechatView && !wechatOriginal && !qqView && !qqOriginal) return null;

        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo node : UiDriver.walkComplete(root)) {
            if (!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable() || node.isEditable()
                    || node.isChecked() || node.getWindowId() != root.getWindowId()
                    || !pkg.equals(String.valueOf(node.getPackageName()))) continue;
            String text = clean(node.getText());
            String desc = clean(node.getContentDescription());
            if (sensitive(text) || sensitive(desc)) continue;
            boolean matches = wechatView && hasClass(node, "android.widget.Button")
                    && WECHAT_VIEW.matcher(text.isEmpty() ? desc : text).matches();
            matches |= qqView && desc.equals("查看原图") && (text.isEmpty() || text.equals("查看原图"));
            matches |= wechatOriginal && hasClass(node, "android.widget.ImageButton")
                    && desc.equals("未选中,原图,复选框") && followedByOriginalLabel(node, pkg);
            matches |= qqOriginal && hasClass(node, "android.widget.CheckBox")
                    && node.isCheckable() && followedByOriginalLabel(node, pkg);
            if (!matches || hasOtherControl(node)) continue;
            if (target != null && !target.equals(node)) return null;
            target = node;
        }
        return target;
    }

    private static boolean followedByOriginalLabel(AccessibilityNodeInfo node, String pkg) {
        AccessibilityNodeInfo parent = node.getParent();
        if (parent == null) return false;
        for (int i = 0; i + 1 < parent.getChildCount(); i++) {
            if (!node.equals(parent.getChild(i))) continue;
            AccessibilityNodeInfo label = parent.getChild(i + 1);
            return label != null && label.isVisibleToUser() && pkg.equals(String.valueOf(label.getPackageName()))
                    && clean(label.getText()).equals("原图");
        }
        return false;
    }

    private static boolean hasOtherControl(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> children = UiDriver.walkComplete(node);
        if (children.isEmpty()) return true;
        for (AccessibilityNodeInfo child : children) {
            if (!child.equals(node) && child.isVisibleToUser() && (child.isClickable() || child.isEditable() || child.isCheckable())) return true;
        }
        return false;
    }

    private static boolean hasClass(AccessibilityNodeInfo node, String expected) {
        return expected.equals(String.valueOf(node.getClassName()));
    }

    private static boolean sensitive(String value) {
        return CustomSkipRule.unsafeButton(value) || SENSITIVE_ACTION.matcher(value).matches();
    }

    private static String clean(CharSequence value) { return value == null ? "" : value.toString().trim(); }
}
