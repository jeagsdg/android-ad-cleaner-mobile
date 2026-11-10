package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaAssistTest {
    private static final String WECHAT = "com.tencent.mm", QQ = "com.tencent.mobileqq";
    private static final String WECHAT_CHAT = "com.tencent.mm.ui.LauncherUI";
    private static final String WECHAT_ALBUM = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI";
    private static final String QQ_ALBUM = "com.tencent.qqnt.qbasealbum.WinkHomeActivity";
    private static final String QQ_GALLERY = "com.tencent.richframework.gallery.QQGalleryActivity";

    @Test public void wechatViewRequiresExactPageAndBoundedLabel() {
        AccessibilityNodeInfo root = node(WECHAT, "android.widget.FrameLayout", "", "", false);
        AccessibilityNodeInfo view = node(WECHAT, "android.widget.Button", "查看原图（1.36M）", "", true);
        add(root, view);
        assertNull(MediaAssist.find(root, WECHAT_CHAT, Set.of()));
        assertNull(MediaAssist.find(root, WECHAT_ALBUM, Set.of("wechat-view")));
        assertEquals(view, MediaAssist.find(root, WECHAT_CHAT, Set.of("wechat-view")));
        view.setText("查看原图并发送");
        assertNull(MediaAssist.find(root, WECHAT_CHAT, Set.of("wechat-view")));
        view.setText("查看原图");
        view.setPackageName(QQ);
        assertNull(MediaAssist.find(root, WECHAT_CHAT, Set.of("wechat-view")));
    }

    @Test public void wechatOriginalRequiresUncheckedDescriptionAndAdjacentLabel() {
        AccessibilityNodeInfo root = node(WECHAT, "android.widget.FrameLayout", "", "", false);
        AccessibilityNodeInfo original = node(WECHAT, "android.widget.ImageButton", "", "未选中,原图,复选框", true);
        AccessibilityNodeInfo label = node(WECHAT, "android.widget.TextView", "原图", "", false);
        add(root, original);
        assertNull(MediaAssist.find(root, WECHAT_ALBUM, Set.of("wechat-original")));
        add(root, label);
        assertEquals(original, MediaAssist.find(root, WECHAT_ALBUM, Set.of("wechat-original")));
        original.setContentDescription("已选中,原图,复选框");
        assertNull(MediaAssist.find(root, WECHAT_ALBUM, Set.of("wechat-original")));
    }

    @Test public void qqOriginalUsesOnlyAnUncheckedAdjacentCheckbox() {
        AccessibilityNodeInfo root = node(QQ, "android.widget.FrameLayout", "", "", false);
        AccessibilityNodeInfo checkbox = node(QQ, "android.widget.CheckBox", "", "", true);
        add(root, checkbox);
        add(root, node(QQ, "android.widget.TextView", "原图", "", false));
        add(root, node(QQ, "android.widget.Button", "发送", "", true));
        assertNull(MediaAssist.find(root, QQ_ALBUM, Set.of("qq-original")));
        checkbox.setCheckable(true);
        assertEquals(checkbox, MediaAssist.find(root, QQ_ALBUM, Set.of("qq-original")));
        checkbox.setChecked(true);
        assertNull(MediaAssist.find(root, QQ_ALBUM, Set.of("qq-original")));
    }

    @Test public void qqViewRejectsConflictingSendLabelAndAmbiguousTargets() {
        AccessibilityNodeInfo root = node(QQ, "android.widget.FrameLayout", "", "", false);
        AccessibilityNodeInfo view = node(QQ, "android.view.View", "", "查看原图", true);
        add(root, view);
        assertEquals(view, MediaAssist.find(root, QQ_GALLERY, Set.of("qq-view")));
        view.setText("发送");
        assertNull(MediaAssist.find(root, QQ_GALLERY, Set.of("qq-view")));
        view.setText("");
        add(root, node(QQ, "android.view.View", "", "查看原图", true));
        assertNull(MediaAssist.find(root, QQ_GALLERY, Set.of("qq-view")));
    }

    private static AccessibilityNodeInfo node(String pkg, String type, String text, String description, boolean clickable) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(pkg);
        node.setClassName(type);
        node.setText(text);
        node.setContentDescription(description);
        node.setClickable(clickable);
        node.setEnabled(true);
        node.setVisibleToUser(true);
        return node;
    }

    private static void add(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        shadowOf(parent).addChild(child);
    }
}
