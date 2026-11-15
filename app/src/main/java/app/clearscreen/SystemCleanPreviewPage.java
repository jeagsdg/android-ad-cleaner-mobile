package app.clearscreen;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SystemCleanPreviewPage {
    private final MainActivity screen;
    private final Set<String> unselected = new HashSet<>();
    private String previewId = "";

    SystemCleanPreviewPage(MainActivity screen, Bundle saved) {
        this.screen = screen;
        if (saved != null) {
            previewId = saved.getString("systemPreviewId", "");
            List<String> choices = saved.getStringArrayList("systemPreviewUnselected");
            if (choices != null) unselected.addAll(choices);
        }
    }

    void save(Bundle out) {
        out.putString("systemPreviewId", previewId);
        out.putStringArrayList("systemPreviewUnselected", new ArrayList<>(unselected));
    }

    void render() {
        screen.header("", "系统广告改动预览", "确认开关和目标状态，再选择要应用的改动。");
        CleanerService service = CleanerService.get();
        SystemCleanPreview preview = service == null ? null : service.systemPreview();
        if (preview == null) {
            screen.paragraph(screen.card(screen.body), service != null && service.busy
                    ? "正在读取开关，完成后会返回预览。" : "读取本机当前设置后，才能确认可以调整的项目。");
            if (service == null || !service.busy) screen.action(screen.body, "检查系统广告开关", true, screen::chooseClean);
            return;
        }
        if (!preview.id.equals(previewId)) {
            previewId = preview.id;
            unselected.clear();
        }
        List<SystemCleanPreview.Entry> changes = new ArrayList<>();
        int already = 0;
        for (SystemCleanPreview.Entry item : preview.entries) {
            if (item.needsChange()) changes.add(item);
            else if (item.verified()) already++;
        }
        int unavailable = preview.entries.size() - changes.size() - already;
        LinearLayout summary = screen.card(screen.body);
        summary.addView(screen.bold("建议调整 " + changes.size() + " 项", 18, MainActivity.INK));
        screen.space(summary, 6);
        screen.paragraph(summary, "已符合 " + already + " 项 · 未能核对 " + unavailable + " 项"
                + (preview.unchecked > 0 ? " · 尚未检查 " + preview.unchecked + " 项" : ""));
        if (!preview.interruption.isEmpty()) screen.paragraph(summary, preview.interruption);

        Button apply = screen.button("应用所选改动", true, () -> apply(service, preview, changes));
        if (!changes.isEmpty()) {
            screen.sectionTitle("可调整的开关");
            for (SystemCleanPreview.Entry item : changes) {
                LinearLayout row = itemCard(item);
                screen.historyToggle(row, state(item.before) + " → " + state(item.desired),
                        !unselected.contains(item.key()), enabled -> {
                            if (enabled) unselected.remove(item.key()); else unselected.add(item.key());
                            refreshApply(apply, changes);
                        });
            }
            refreshApply(apply, changes);
            screen.body.addView(apply, new LinearLayout.LayoutParams(-1, -2));
        }
        if (already + unavailable > 0) {
            screen.sectionTitle("其余检查结果");
            for (SystemCleanPreview.Entry item : preview.entries) {
                if (item.needsChange()) continue;
                LinearLayout row = itemCard(item);
                screen.paragraph(row, item.verified() ? "已是目标状态 · " + state(item.before) : item.problem);
            }
        }
        screen.action(screen.body, "重新选择要检查的项目", false, screen::chooseClean);
    }

    private LinearLayout itemCard(SystemCleanPreview.Entry item) {
        LinearLayout row = screen.card(screen.body);
        row.addView(screen.bold(item.observedLabel, 16, MainActivity.INK));
        screen.space(row, 5);
        screen.paragraph(row, item.rule.title);
        if (!item.routeName.isEmpty()) screen.paragraph(row, item.routeName);
        return row;
    }

    private void refreshApply(Button apply, List<SystemCleanPreview.Entry> changes) {
        int count = 0;
        for (SystemCleanPreview.Entry item : changes) if (!unselected.contains(item.key())) count++;
        apply.setText(count == 0 ? "应用所选改动" : "应用所选改动（" + count + " 项）");
        apply.setEnabled(count > 0);
    }

    private void apply(CleanerService service, SystemCleanPreview preview, List<SystemCleanPreview.Entry> changes) {
        List<SystemCleanPreview.Entry> selection = new ArrayList<>();
        for (SystemCleanPreview.Entry item : changes) if (!unselected.contains(item.key())) selection.add(item);
        if (!selection.isEmpty() && service.applyPreview(preview, selection)) {
            screen.showPage(MainActivity.SYSTEM_CLEAN_PREVIEW);
        } else {
            screen.toast("预览已变化，请重新检查");
            screen.render();
        }
    }

    private static String state(boolean checked) { return checked ? "开" : "关"; }
}
