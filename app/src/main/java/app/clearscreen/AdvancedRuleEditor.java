package app.clearscreen;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Edits a local advanced rule, or all steps of one popup sequence together. */
final class AdvancedRuleEditor {
    private final MainActivity screen;
    private final List<CustomSkipRule> rules;
    private final List<CustomSkipRule> original = new ArrayList<>();
    private final List<Step> steps = new ArrayList<>();
    private final int existingIndex;
    private final String mode;
    private final boolean sequence;
    private final int stepLimit;
    private final LinearLayout content, stepList;
    private final EditText packageField;
    private final CheckBox replaceDefaults;
    private final TextView error;

    static void add(MainActivity screen, List<CustomSkipRule> rules) {
        if (rules.size() == CustomSkipRule.MAX_RULES) {
            screen.toast("最多保存 " + CustomSkipRule.MAX_RULES + " 条规则");
            return;
        }
        String[] choices = {"开屏跳过", "单个弹窗", "连续弹窗"};
        new AlertDialog.Builder(screen).setTitle("添加高级规则").setItems(choices, (dialog, selected) -> {
            if (selected == 2 && rules.size() + 2 > CustomSkipRule.MAX_RULES) {
                screen.toast("连续弹窗至少需要两个可用步骤");
                return;
            }
            String mode = selected == 0 ? CustomSkipRule.SPLASH : CustomSkipRule.POPUP;
            new AdvancedRuleEditor(screen, rules, -1, mode, selected == 2).show();
        }).setNegativeButton("取消", null).show();
    }

    static void edit(MainActivity screen, List<CustomSkipRule> rules, int index) {
        CustomSkipRule rule = rules.get(index);
        new AdvancedRuleEditor(screen, rules, index, rule.mode, rule.sequenceIndex >= 0).show();
    }

    private AdvancedRuleEditor(MainActivity screen, List<CustomSkipRule> rules, int index, String mode, boolean sequence) {
        this.screen = screen;
        this.rules = rules;
        this.mode = mode;
        this.sequence = sequence;
        this.existingIndex = index;
        if (index >= 0) {
            CustomSkipRule selected = rules.get(index);
            if (sequence) {
                for (CustomSkipRule rule : rules) {
                    if (rule.sequenceIndex >= 0 && rule.pkg.equals(selected.pkg)) original.add(rule);
                }
                original.sort(Comparator.comparingInt(rule -> rule.sequenceIndex));
            } else original.add(selected);
        }
        stepLimit = Math.min(CustomSkipRule.MAX_SEQUENCE_STEPS,
                CustomSkipRule.MAX_RULES - rules.size() + original.size());

        content = screen.skipDialogContent();
        screen.paragraph(content, "文字默认完整匹配。可用 ~ 表示包含、+ 表示开头、- 表示结尾，用 & 连接同一控件的条件。也可填写完整控件 ID。按范围匹配须指定页面，并与可见控件的实际边界一致。");
        if (sequence) screen.paragraph(content, "按顺序核对弹窗。观察步骤只检查当前画面，最后一步须执行关闭。");
        packageField = screen.skipRuleField(content, "应用包名", "com.example.app",
                original.isEmpty() ? "" : original.get(0).pkg);
        screen.action(content, "从已安装应用选择", false, () -> screen.chooseSkipRuleApp(packageField));

        replaceDefaults = new CheckBox(screen);
        replaceDefaults.setMinHeight(screen.dp(48));
        replaceDefaults.setPaddingRelative(0, screen.dp(8), 0, screen.dp(8));
        replaceDefaults.setText("用此应用的规则替换默认开屏匹配");
        replaceDefaults.setChecked(!original.isEmpty() && original.get(0).replaceDefaults);
        if (CustomSkipRule.SPLASH.equals(mode)) content.addView(replaceDefaults);

        stepList = screen.column();
        content.addView(stepList);
        if (original.isEmpty()) {
            addStep(null);
            if (sequence) addStep(null);
        } else {
            for (CustomSkipRule rule : original) addStep(rule);
        }
        if (sequence) {
            screen.linkAction(content, "添加一步", () -> {
                if (steps.size() < stepLimit) addStep(null);
                else screen.toast("最多可保存 " + stepLimit + " 步");
            });
        }
        error = screen.text("", 14, 0xff9d302a);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        screen.linkAction(content, "试运行当前内容", () -> {
            try {
                screen.beginRulePreview(CustomSkipRule.parse(CustomSkipRule.export(drafts())));
                error.setVisibility(View.GONE);
            } catch (IllegalArgumentException failure) {
                showError(failure);
            }
        });
        screen.linkAction(content, "查看试运行结果", screen::showRulePreview);
        content.addView(error);
    }

    private void addStep(CustomSkipRule existing) {
        Step step = new Step(existing);
        steps.add(step);
        updateStepTitles();
    }

    private void updateStepTitles() {
        for (int i = 0; i < steps.size(); i++)
            steps.get(i).title.setText(screen.getString(R.string.rule_step_number, i + 1));
    }

    private void show() {
        ScrollView form = screen.scrollDialogContent(content);
        String title = sequence ? "连续弹窗规则"
                : CustomSkipRule.POPUP.equals(mode) ? "高级弹窗规则" : "高级开屏规则";
        AlertDialog.Builder builder = new AlertDialog.Builder(screen).setTitle(title)
                .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (existingIndex >= 0) builder.setNeutralButton(sequence ? "删除整组" : "删除", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    List<CustomSkipRule> next = new ArrayList<>(rules);
                    next.removeAll(original);
                    int position = existingIndex < 0 ? next.size() : Math.min(existingIndex, next.size());
                    List<CustomSkipRule> draft = drafts();
                    next.addAll(position, draft);
                    screen.store.saveCustomSkipRules(next);
                    if (RulePreview.LOCAL.packageName().equals(draft.get(0).pkg)) RulePreview.LOCAL.clear();
                    dialog.dismiss();
                    screen.render();
                    screen.toast(sequence ? "连续弹窗规则已保存" : "规则已保存");
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    showError(failure);
                    form.post(() -> form.fullScroll(View.FOCUS_DOWN));
                }
            });
            if (existingIndex >= 0) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                        view -> screen.confirmSkipRuleDelete(rules, existingIndex, dialog));
            }
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private List<CustomSkipRule> drafts() {
        List<CustomSkipRule> result = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            try {
                result.add(steps.get(i).rule(sequence ? i : -1));
            } catch (IllegalArgumentException failure) {
                if (!sequence) throw failure;
                throw new IllegalArgumentException("第 " + (i + 1) + " 步 · " + failure.getMessage(), failure);
            }
        }
        return result;
    }

    private void showError(RuntimeException failure) {
        error.setText(failure.getMessage());
        error.setVisibility(View.VISIBLE);
    }

    private EditText numberField(LinearLayout parent, String label, int value) {
        EditText field = screen.skipRuleField(parent, label, "", Integer.toString(value));
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private static int number(EditText field, String label) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(label + "须填写整数", failure);
        }
    }

    private final class Step {
        private final CustomSkipRule existing;
        private final TextView title;
        private final EditText activity, prompt, target, delay, searches, times;
        private final CheckBox gesture;
        private final RadioGroup operation;
        private final RadioButton click, back, observe;

        Step(CustomSkipRule existing) {
            this.existing = existing;
            boolean popup = CustomSkipRule.POPUP.equals(mode);
            LinearLayout fields = screen.card(stepList);
            title = screen.bold("", 17, MainActivity.INK);
            if (sequence) fields.addView(title);
            activity = screen.skipRuleField(fields, "页面类名（可选）", "com.example.app.MainActivity",
                    existing == null ? "" : existing.activity);
            prompt = popup ? screen.skipRuleField(fields, "弹窗提示或提示控件 ID", "如 =今日推荐活动",
                    existing == null ? "" : existing.context) : null;
            operation = new RadioGroup(screen);
            click = operation("点击目标控件");
            back = operation("用返回关闭独立弹窗");
            observe = operation("观察匹配后进入下一步");
            operation.addView(click);
            if (popup) operation.addView(back);
            if (sequence) operation.addView(observe);
            if (popup) fields.addView(operation);

            target = screen.skipRuleField(fields, "目标控件", "如 =关闭广告 或 com.example.app:id/close",
                    existing == null || existing.backsOut() ? "" : existing.label);
            gesture = new CheckBox(screen);
            gesture.setMinHeight(screen.dp(48));
            gesture.setPaddingRelative(0, screen.dp(8), 0, screen.dp(8));
            gesture.setText("使用手势点击已匹配控件");
            gesture.setChecked(existing != null && existing.gesture);
            fields.addView(gesture);

            LinearLayout actionOptions = screen.column();
            fields.addView(actionOptions);
            delay = numberField(actionOptions, "执行前等待毫秒数（0–5000）", existing == null ? 0 : existing.delayMs);
            times = popup ? numberField(actionOptions, "每次进入应用最多处理次数（1–4）",
                    existing == null || existing.times == 0 ? 1 : existing.times) : null;
            searches = popup ? numberField(fields, "每次搜索的尝试次数（1–10）",
                    existing == null ? 1 : existing.searches) : null;

            operation.setOnCheckedChangeListener((group, choice) -> {
                target.setEnabled(choice != back.getId());
                target.setHint(choice == observe.getId() ? "留空时使用弹窗提示" : "如 =关闭广告 或 com.example.app:id/close");
                gesture.setVisibility(choice == click.getId() ? View.VISIBLE : View.GONE);
                actionOptions.setVisibility(choice == observe.getId() ? View.GONE : View.VISIBLE);
            });
            operation.check(existing == null ? click.getId()
                    : existing.times == 0 ? observe.getId() : existing.backsOut() ? back.getId() : click.getId());
            if (sequence) {
                screen.linkAction(fields, "移除此步骤", () -> {
                    if (steps.size() <= 2) {
                        screen.toast("连续弹窗至少需要两步");
                        return;
                    }
                    steps.remove(this);
                    stepList.removeView(fields);
                    updateStepTitles();
                });
            }
        }

        private RadioButton operation(String label) {
            RadioButton button = new RadioButton(screen);
            button.setMinHeight(screen.dp(48));
            button.setPaddingRelative(0, screen.dp(8), 0, screen.dp(8));
            button.setId(View.generateViewId());
            button.setText(label);
            return button;
        }

        CustomSkipRule rule(int sequenceIndex) {
            int selected = operation.getCheckedRadioButtonId();
            boolean waiting = selected == observe.getId();
            boolean backingOut = selected == back.getId();
            String targetSelector = target.getText().toString();
            if (!backingOut && CustomSkipRule.BACK.equals(targetSelector.trim())) {
                throw new IllegalArgumentException("如需使用返回动作，请选择「用返回关闭独立弹窗」");
            }
            int repeats = waiting ? 0 : times == null ? 1 : number(times, "处理次数");
            if (!waiting && repeats < 1) throw new IllegalArgumentException("处理次数须在 1 到 4 之间");
            return CustomSkipRule.advanced(packageField.getText().toString(), activity.getText().toString(),
                    mode, backingOut ? CustomSkipRule.BACK : targetSelector,
                    prompt == null ? "" : prompt.getText().toString(), replaceDefaults.isChecked(),
                    waiting ? 0 : number(delay, "等待毫秒数"), !waiting && !backingOut && gesture.isChecked(),
                    searches == null ? 1 : number(searches, "搜索次数"),
                    repeats, sequenceIndex, existing);
        }
    }
}
