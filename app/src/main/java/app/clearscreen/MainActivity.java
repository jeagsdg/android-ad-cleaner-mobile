package app.clearscreen;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity {
    static final int BG = 0xfff5f7f4, INK = 0xff19382e, MUTED = 0xff65756d;
    static final int GREEN = 0xff176d59, MINT = 0xffe0eee6;
    Store store;
    LinearLayout shell, body;
    int tab = 0;
    boolean onlyUnfinished = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        tab = state != null ? state.getInt("tab") : getIntent().getIntExtra("tab", 0);
        render();
    }
    @Override protected void onResume() { super.onResume(); if (store != null) render(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("tab", tab); super.onSaveInstanceState(state); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); tab = intent.getIntExtra("tab", 1); render(); }
    int dp(float value) { return (int) (getResources().getDisplayMetrics().density * value + .5f); }
    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1);
        view.setIncludeFontPadding(false);
        return view;
    }
    TextView bold(String value, int size, int color) {
        TextView view = text(value, size, color);
        view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    void space(LinearLayout parent, int height) { parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    void paragraph(LinearLayout parent, String value) { parent.addView(text(value, 14, MUTED)); }
    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(shape(Color.WHITE, 20));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(card, params);
        return card;
    }
    Button button(String value, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : GREEN);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(primary ? 0x28ffffff : 0x1a176d59),
                shape(primary ? GREEN : MINT, 14), null));
        button.setMinHeight(dp(50));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    void action(LinearLayout parent, String label, boolean primary, Runnable action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(button(label, primary, action), params);
    }
    void header(String eyebrow, String title, String sub) {
        body.addView(bold(eyebrow, 12, GREEN));
        space(body, 10);
        body.addView(bold(title, 26, INK));
        space(body, 10);
        paragraph(body, sub);
        space(body, 20);
    }
    void render() {
        shell = column();
        shell.setBackgroundColor(BG);
        shell.setFitsSystemWindows(true);
        setContentView(shell);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = column();
        body.setPadding(dp(24), dp(20), dp(24), dp(20));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab == 0) home(); else if (tab == 1) checks(); else if (tab == 2) history(); else about();
        if (tab != 0) shell.addView(button("返回清理", false, () -> { tab = 0; render(); }),
                new LinearLayout.LayoutParams(-1, dp(52)));
    }
    @Override public void onBackPressed() {
        if (tab != 0) { tab = 0; render(); } else super.onBackPressed();
    }
    void home() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(bold("净屏", 23, INK), new LinearLayout.LayoutParams(0, -2, 1));
        TextView help = text("使用说明  ›", 14, GREEN);
        help.setGravity(Gravity.CENTER);
        help.setMinHeight(dp(48));
        help.setOnClickListener(v -> { tab = 3; render(); });
        top.addView(help);
        body.addView(top);
        space(body, 16);

        LinearLayout device = card(body);
        device.addView(bold(store.deviceName(), 18, INK));
        space(device, 5);
        paragraph(device, store.deviceDescription());
        space(device, 14);
        CleanerService service = CleanerService.get();
        boolean busy = service != null && service.busy;
        device.addView(bold(busy ? "正在处理" : service == null ? "需要自动操作权限" : "自动操作已就绪", 15, GREEN));
        space(device, 5);
        paragraph(device, busy ? "请保持亮屏，可随时停止。" : service == null
                ? "首次清理前，需在系统设置中开启净屏服务。"
                : "开始前可查看并调整本次清理范围。");
        action(device, busy ? "停止当前任务" : "开始清理", true, () -> {
            CleanerService current = CleanerService.get();
            if (current != null && current.busy) current.stop(); else chooseClean();
        });
        if (service == null) action(device, "了解权限用途", false, this::accessibility);

        JSONArray results = store.read("scan");
        if (results.length() > 0) {
            int changed = 0, already = 0, unfinished = 0;
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                String status = item.optString("status");
                if (status.equals("changed")) changed++;
                else if (status.equals("already")) already++;
                else if (isUnfinished(status)) unfinished++;
            }
            LinearLayout result = card(body);
            result.addView(bold("最近一次结果", 17, INK));
            space(result, 8);
            result.addView(text(changed + " 项已调整  ·  " + already + " 项已是目标状态", 14, GREEN));
            space(result, 6);
            paragraph(result, unfinished + " 项未完成或跳过，可查看原因。" );
            action(result, "查看清理结果", false, () -> { onlyUnfinished = false; tab = 1; render(); });
        }

        LinearLayout more = card(body);
        more.addView(bold("更多操作", 17, INK));
        space(more, 8);
        paragraph(more, ChannelObserver.connected
                ? "通知渠道检查已开启，只识别已出现的营销通知。"
                : "通知渠道检查可选；开启后可识别已出现的营销通知。" );
        action(more, ChannelObserver.connected ? "管理通知访问" : "开启通知渠道检查", false, this::notificationPermission);
        action(more, "改动记录与恢复", false, () -> { tab = 2; render(); });
        action(more, "诊断报告", false, this::diagnostics);
    }

    void chooseClean() {
        CleanerService service = CleanerService.get();
        if (service == null) { accessibility(); return; }
        if (service.busy) { toast("已有任务正在执行"); return; }
        List<Rule> candidates = new ArrayList<>(store.plan());
        for (Rule rule : store.available()) if (rule.manual) candidates.add(rule);
        chooseRules(candidates, "清理范围（可取消勾选）");
    }

    void chooseRules(List<Rule> candidates, String title) {
        List<Rule> rules = new ArrayList<>();
        List<Rule> manualRules = new ArrayList<>();
        for (Rule rule : candidates) {
            if (rule.manual) manualRules.add(rule); else rules.add(rule);
        }
        int manualCount = manualRules.size();
        if (rules.isEmpty()) {
            String message = "本机暂未找到可自动处理的项目。未适配不会被显示为已清理。";
            if (manualCount > 0) message += " 有 " + manualCount + " 项可查看手动路径。";
            AlertDialog.Builder empty = new AlertDialog.Builder(this).setTitle("暂未找到适用项目").setMessage(message)
                .setPositiveButton("查看适配范围", (d,w) -> { tab = 3; render(); })
                .setNegativeButton("返回", null);
            if (manualCount > 0) empty.setNeutralButton("查看手动项", (d,w) -> showManualRules(manualRules));
            empty.show();
            return;
        }
        String[] labels = rules.stream().map(r -> r.title + " · " + String.join("、", r.targets)).toArray(String[]::new);
        boolean[] pick = new boolean[rules.size()];
        Arrays.fill(pick, true);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title);
        if (manualCount > 0) dialog.setNeutralButton("手动项（" + manualCount + "）", (d,w) -> showManualRules(manualRules));
        dialog.setMultiChoiceItems(labels, pick, (d,w,checked) -> pick[w] = checked)
            .setPositiveButton("开始清理", (d,w) -> {
                List<Rule> chosen = new ArrayList<>();
                for (int i = 0; i < pick.length; i++) if (pick[i]) chosen.add(rules.get(i));
                if (chosen.isEmpty()) { toast("请至少选择一项"); return; }
                CleanerService current = CleanerService.get();
                if (current == null) { toast("自动操作权限已断开，请重新开启"); return; }
                if (current.busy) { toast("已有任务正在执行"); return; }
                current.clean(chosen);
            }).setNegativeButton("取消", null).show();
    }

    void showManualRules(List<Rule> rules) {
        String[] titles = rules.stream().map(r -> r.title + "\n" + r.explanation).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle("需手动处理的项目")
            .setItems(titles, (d,w) -> ruleDetails(rules.get(w)))
            .setNegativeButton("关闭", null).show();
    }

    boolean isUnfinished(String status) {
        return !status.equals("changed") && !status.equals("already") && !status.equals("restored");
    }

    String appGroup(Rule r) {
        if (r.notification() && !r.channelApp.isEmpty()) return r.channelApp;
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(r.pkg, 0)).toString(); }
        catch (Exception ignored) { return r.pkg.isEmpty() ? "其他项目" : r.pkg; }
    }

    void checks() {
        header("清理结果", "逐项核对", "只统计实际回读确认的改动；未完成项目保留原因。 ");
        JSONArray scan = store.read("scan");
        if (scan.length() == 0) { paragraph(body, "还没有清理结果。"); return; }
        int unfinished = 0;
        for (int i = 0; i < scan.length(); i++) {
            JSONObject item = scan.optJSONObject(i);
            if (item != null && isUnfinished(item.optString("status"))) unfinished++;
        }
        if (unfinished > 0) {
            action(body, onlyUnfinished ? "显示全部结果" : "只看未完成（" + unfinished + "）", false,
                    () -> { onlyUnfinished = !onlyUnfinished; render(); });
            action(body, "重试未完成", true, () -> {
                if (CleanerService.get() == null) { accessibility(); return; }
                chooseRules(store.retryRules(), "重试范围（可取消勾选）");
            });
            space(body, 16);
        }
        LinkedHashMap<String,List<JSONObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < scan.length(); i++) {
            JSONObject item = scan.optJSONObject(i);
            if (item == null || item.optJSONObject("rule") == null) continue;
            if (onlyUnfinished && !isUnfinished(item.optString("status"))) continue;
            Rule rule = new Rule(item.optJSONObject("rule"));
            groups.computeIfAbsent(appGroup(rule), key -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String,List<JSONObject>> group : groups.entrySet()) {
            body.addView(bold(group.getKey(), 16, INK));
            space(body, 8);
            for (JSONObject item : group.getValue()) {
                Rule rule = new Rule(item.optJSONObject("rule"));
                LinearLayout c = card(body);
                String label = item.optString("label");
                c.addView(bold(label.isEmpty() ? rule.title : label, 16, INK));
                space(c, 6);
                c.addView(text(rule.title + " · " + statusName(item.optString("status")), 13, GREEN));
                space(c, 6);
                paragraph(c, item.optString("message"));
                action(c, "查看原设置路径", false, () -> ruleDetails(rule));
            }
        }
        action(body, "改动记录与恢复", false, () -> { tab = 2; render(); });
    }
    String statusName(String status) {
        switch (status) {
            case "available": return "可调整";
            case "already": return "已是目标状态";
            case "changed": return "已核验";
            case "restored": return "已恢复";
            case "missing": return "未找到";
            case "skipped": return "已跳过";
            case "failed": return "未完成";
            case "pending": case "unknown": return "结果待核对";
            case "restoring": case "restore_failed": return "恢复待核对";
            default: return status;
        }
    }
    void history() {
        header("改动记录", "原值已留好", "在这里核对改动，或恢复原始状态。卸载前请先完成需要的恢复。 ");
        JSONArray journal = store.read("journal");
        List<JSONObject> restorable = store.restorable();
        if (journal.length() == 0) {
            LinearLayout empty = card(body);
            empty.addView(bold("还没有改动记录", 18, INK));
            space(empty, 8);
            paragraph(empty, "清理后，这里会显示每个开关的状态及恢复入口。");
        }
        if (!restorable.isEmpty()) {
            action(body, "恢复全部（" + restorable.size() + " 项）", false, () -> confirmRestoreAll(restorable));
            space(body, 16);
        }
        for (int i = journal.length() - 1; i >= 0; i--) {
            JSONObject item = journal.optJSONObject(i);
            if (item == null || item.optJSONObject("rule") == null) continue;
            Rule rule = new Rule(item.optJSONObject("rule"));
            LinearLayout c = card(body);
            c.addView(bold(item.optString("label"), 17, INK));
            space(c, 6);
            String source = appGroup(rule);
            paragraph(c, source.equals(rule.title) || rule.title.startsWith(source + " · ")
                    ? rule.title : source + " · " + rule.title);
            space(c, 6);
            String status = item.optString("status");
            String before = item.optBoolean("before") ? "开" : "关";
            String after = item.optBoolean("after") ? "开" : "关";
            paragraph(c, status.equals("restored") ? "原值：" + before + " · 已恢复"
                    : before + " → " + after + " · " + statusName(status));
            space(c, 6);
            paragraph(c, new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(item.optLong("time")))
                    + " · " + item.optString("message"));
            boolean canRestore = false;
            for (JSONObject entry : restorable) if (entry.optString("id").equals(item.optString("id"))) canRestore = true;
            if (canRestore) action(c, "恢复原始状态", false, () -> confirmRestore(item));
            action(c, "查看原设置路径", false, () -> ruleDetails(rule));
        }
        action(body, "诊断报告", false, this::diagnostics);
    }

    void confirmRestore(JSONObject item) {
        if (CleanerService.get() == null) { accessibility(); return; }
        String before = item.optBoolean("before") ? "开" : "关";
        new AlertDialog.Builder(this).setTitle("恢复「" + item.optString("label") + "」")
            .setMessage("将核对当前状态，再尝试恢复为「" + before + "」。系统更新或路径变化时会停止，并保留手动路径。")
            .setPositiveButton("检查并恢复", (d,w) -> {
                CleanerService service = CleanerService.get();
                if (service == null) { toast("自动操作权限已断开，请重新开启"); return; }
                if (service.busy) { toast("已有任务正在执行"); return; }
                service.restore(item);
            }).setNegativeButton("取消", null).show();
    }

    void confirmRestoreAll(List<JSONObject> entries) {
        if (CleanerService.get() == null) { accessibility(); return; }
        new AlertDialog.Builder(this).setTitle("恢复全部改动？")
            .setMessage("将逐项检查并尝试恢复 " + entries.size() + " 项的原始状态。无法确认的项目会跳过，请保持屏幕亮起。")
            .setPositiveButton("检查并恢复", (d,w) -> {
                CleanerService service = CleanerService.get();
                if (service == null) { toast("自动操作权限已断开，请重新开启"); return; }
                if (service.busy) { toast("已有任务正在执行"); return; }
                service.restoreAll(entries);
            }).setNegativeButton("取消", null).show();
    }

    void diagnostics() {
        new AlertDialog.Builder(this).setTitle("诊断报告")
            .setMessage("报告包含设备型号、系统版本和构建标识、清理结果及改动记录，不包含通知正文。请确认内容后再分享。")
            .setPositiveButton("复制报告", (d,w) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("净屏诊断报告", store.exportText()));
                toast("诊断报告已复制");
            })
            .setNeutralButton("导出文件", (d,w) -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "净屏-诊断报告.txt");
                startActivityForResult(intent, 20);
            }).setNegativeButton("取消", null).show();
    }
    void about() {
        header("使用说明", "清理范围", "净屏 " + store.appVersion() + " · 本地预览版");
        LinearLayout capability = card(body);
        capability.addView(bold("当前版本的能力", 18, INK));
        space(capability, 10);
        paragraph(capability, "处理已匹配的系统推荐开关和明确的营销通知渠道。暂不提供网络过滤、开屏跳过、视频广告处理或广告组件挂起。");
        space(capability, 10);
        paragraph(capability, "关闭个性化推荐不等于清除全部广告。原生鸿蒙不支持安装此安卓应用。");

        LinearLayout privacy = card(body);
        privacy.addView(bold("数据留在你的手机", 18, INK));
        space(privacy, 10);
        paragraph(privacy, "App 没有网络权限。自动操作只在你启动任务时读取目标页面；不保存页面截图或聊天正文。通知模块只保存应用、渠道名称与渠道标识。改动账本保存在应用私有目录，卸载会删除。");
        action(privacy, "清除通知渠道缓存", false, () -> new AlertDialog.Builder(this)
                .setTitle("清除渠道缓存？").setMessage("不会改变任何通知设置。通知访问权限仍开启时，后续出现的渠道会重新记录。")
                .setPositiveButton("清除", (d,w) -> { store.save("channels", new JSONArray()); toast("已清除渠道缓存"); })
                .setNegativeButton("取消", null).show());

        LinearLayout local = card(body);
        local.addView(bold("本机适配", 18, INK));
        space(local, 10);
        paragraph(local, store.deviceName() + " · " + store.deviceDescription());
        space(local, 8);
        paragraph(local, "可尝试 " + store.available().size() + " 组适用规则。不同品牌和系统版本可能改变页面，厂商路径仍需实机验证。");
        action(local, "查看全部参考路径", false, () -> {
            List<Rule> rules = store.allRules();
            String[] titles = rules.stream().map(r -> String.join("/", r.brands) + " · " + r.title).toArray(String[]::new);
            new AlertDialog.Builder(this).setTitle("参考路径").setItems(titles, (d,w) -> ruleDetails(rules.get(w)))
                    .setNegativeButton("关闭", null).show();
        });
        LinearLayout credits = card(body);
        credits.addView(bold("建立在开源项目之上", 18, INK));
        space(credits, 10);
        paragraph(credits, "基于 android-ad-cleaner 的 MIT 规则与保护逻辑，迁移为安卓原生执行流程。上游脚本验证不代表手机端适配已通过。");
        action(credits, "查看开源许可", false, () -> new AlertDialog.Builder(this).setTitle("MIT · android-ad-cleaner")
                .setMessage(store.asset("upstream-license.txt")).setPositiveButton("关闭", null).show());
        action(body, "管理自动操作权限", false, this::accessibility);
        action(body, "管理通知访问权限", false, this::notificationPermission);
        action(body, "诊断报告", false, this::diagnostics);
    }
    void ruleDetails(Rule rule) {
        StringBuilder details = new StringBuilder(rule.explanation).append("\n\n处理范围：\n")
                .append(String.join("、", rule.targets)).append("\n\n手动路径：\n");
        details.append(rule.pathDescription());
        details.append("\n\n来源：").append(rule.source.isEmpty() ? "本机已出现的通知渠道" : rule.source)
                .append("\n自动恢复需要原页面仍可访问；应用升级可能改变路径。");
        new AlertDialog.Builder(this).setTitle(rule.title).setMessage(details)
                .setPositiveButton("打开设置入口", (d,w) -> openRule(rule))
                .setNegativeButton("关闭", null).show();
    }
    void openRule(Rule rule) {
        try {
            Intent intent;
            if (rule.notification()) intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, rule.pkg).putExtra(Settings.EXTRA_CHANNEL_ID, rule.channelId);
            else if (rule.pkg.equals("com.android.settings")) intent = new Intent(Settings.ACTION_SETTINGS);
            else if (!rule.activity.isEmpty()) intent = new Intent().setComponent(new ComponentName(rule.pkg,
                    rule.activity.startsWith(".") ? rule.pkg + rule.activity : rule.activity));
            else intent = getPackageManager().getLaunchIntentForPackage(rule.pkg);
            if (intent == null) throw new IllegalStateException();
            startActivity(intent);
        } catch (Exception e) { toast("此入口无法直接打开，请按路径手动进入"); }
    }
    void accessibility() {
        new AlertDialog.Builder(this).setTitle("自动操作，需要你的授权")
                .setMessage("净屏将读取目标设置页面的文字和开关，并在你发起清理或恢复时点击相应控件。不会读取聊天正文、保存截图或上传数据。\n\n请在系统页面中找到「净屏」开启服务。完成后随时可以关闭。若系统提示「受限设置」，请到应用信息右上角菜单中允许受限设置，再返回开启服务。")
                .setPositiveButton("前往系统设置", (d,w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("暂不开启", null).show();
    }
    void notificationPermission() {
        new AlertDialog.Builder(this).setTitle("发现营销通知渠道")
                .setMessage("系统的通知访问权限可以访问通知内容；净屏只提取应用名、渠道名称和渠道标识，在本机筛选候选项，不存储正文、不上传、不自动清除通知。\n\n只能发现已出现过的通知渠道，不代表手机上全部渠道。")
                .setPositiveButton("前往授权", (d,w) -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)))
                .setNegativeButton("取消", null).show();
    }
    void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != 19 && request != 20) || result != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException();
            out.write((request == 20 ? store.exportText() : store.export()).getBytes(StandardCharsets.UTF_8));
            toast("报告已导出");
        } catch (Exception e) { toast("导出未完成，请重试"); }
    }
}
