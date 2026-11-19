package app.clearscreen;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Browse reviewed controls without requiring a subscription URL or a rule editor. */
final class RuleDirectoryPage {
    private static final int PAGE_SIZE = 20;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean PACK_WORK = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int RULE_CHECK_DAYS = 1;
    private static final long RULE_CHECK_INTERVAL = RULE_CHECK_DAYS * 24 * 60 * 60 * 1000L;
    private static final long FAILED_CHECK_INTERVAL = 60 * 60 * 1000L;
    private static final String SETTINGS = "rule-update-preferences";
    private static final String AUTOMATIC = "automatic";
    private static final String NEXT_CHECK = "next-check";

    private final MainActivity screen;
    private String query = "";
    private String packageScope = "";
    private int filter, limit = PAGE_SIZE;
    private boolean applyingSelection;
    private static String packNotice = "";
    private static WeakReference<RuleDirectoryPage> active = new WeakReference<>(null);

    RuleDirectoryPage(MainActivity screen, Bundle saved) {
        this.screen = screen;
        if (saved != null) {
            query = saved.getString("ruleDirectoryQuery", "");
            packageScope = saved.getString("ruleDirectoryPackage", "");
            filter = Math.max(0, Math.min(3, saved.getInt("ruleDirectoryFilter")));
            limit = Math.max(PAGE_SIZE, saved.getInt("ruleDirectoryLimit"));
        }
    }

    void save(Bundle out) {
        out.putString("ruleDirectoryQuery", query);
        out.putString("ruleDirectoryPackage", packageScope);
        out.putInt("ruleDirectoryFilter", filter);
        out.putInt("ruleDirectoryLimit", limit);
    }

    void scopeTo(String packageName) {
        packageScope = packageName;
        query = "";
        filter = 0;
        limit = PAGE_SIZE;
    }

    void foregroundCheck() {
        if (!screen.networkFilterAvailable()) return;
        Context app = screen.getApplicationContext();
        android.content.SharedPreferences prefs = settings(app);
        if (prefs.getBoolean(AUTOMATIC, screen.store.updateChecksEnabled())
                && System.currentTimeMillis() >= prefs.getLong(NEXT_CHECK, 0L)) {
            refresh(false);
        }
    }

    void render() {
        active = new WeakReference<>(this);
        RulePacks packs = RulePacks.get(screen);
        RulePacks.State state = packs.state();
        RulePacks.Coverage coverage = state.coverage.get(packageScope);
        screen.header("", "精选规则", packageScope.isEmpty() ? "按应用查找经过审核的开屏和弹窗规则。"
                : coverage == null ? packageScope : coverage.name);
        showPack(state);
        screen.linkAction(screen.body,"我的订阅 · 导入与更新",()->screen.showPage(MainActivity.COMMUNITY_RULES));
        LinearLayout controls = screen.card(screen.body);
        controls.setFocusableInTouchMode(true);
        controls.requestFocus();
        EditText search = new EditText(screen);
        search.setSingleLine(true);
        search.setHint("搜索应用名称、包名或广告样式");
        search.setText(query);
        controls.addView(search);
        Spinner scope = new Spinner(screen);
        scope.setMinimumHeight(screen.dp(48));
        scope.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        scope.setContentDescription("按规则类型筛选");
        ArrayAdapter<String> choices = new ArrayAdapter<>(screen, android.R.layout.simple_spinner_item,
                new String[]{"全部规则", "开屏规则", "弹窗规则", "已停用"});
        choices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scope.setAdapter(choices);
        scope.setSelection(filter);
        controls.addView(scope, new LinearLayout.LayoutParams(-1, -2));
        TextView summary = screen.text("", 14, MainActivity.MUTED);
        controls.addView(summary);
        LinearLayout results = screen.column();
        screen.body.addView(results);
        Runnable redraw = () -> list(state.rules, results, summary);
        scope.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int at, long id) {
                if (filter == at) return;
                filter = at;
                limit = PAGE_SIZE;
                redraw.run();
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        screen.watchSkipSearch(search, () -> {
            query = search.getText().toString();
            limit = PAGE_SIZE;
            redraw.run();
        });
        redraw.run();
    }

    private void showPack(RulePacks.State state) {
        LinearLayout card = screen.card(screen.body);
        card.addView(screen.bold(packName(state.version), 18, MainActivity.INK));
        if (!screen.networkFilterAvailable()) {
            screen.paragraph(card, "纯离线版随安装包更新规则，可以在本机启停单条规则。");
            return;
        }
        if (!packNotice.isEmpty()) screen.paragraph(card, packNotice);
        if (PACK_WORK.get()) screen.paragraph(card, "正在处理规则包…");
        else screen.action(card, "检查规则更新", true, () -> refresh(true));
        if (state.canRollback && !PACK_WORK.get()) {
            screen.linkAction(card, "返回" + packName(state.previousVersion), () -> rollback(state.previousVersion));
        }
        android.content.SharedPreferences prefs = settings(screen);
        screen.historyToggle(card, "自动检查规则更新", prefs.getBoolean(AUTOMATIC, screen.store.updateChecksEnabled()), enabled -> {
            prefs.edit().putBoolean(AUTOMATIC, enabled).apply();
            if (enabled) foregroundCheck();
        });
    }

    private void list(List<RulePacks.CatalogRule> all, LinearLayout results, TextView summary) {
        results.removeAllViews();
        String term = query.trim().toLowerCase(Locale.ROOT);
        List<RulePacks.CatalogRule> matches = new ArrayList<>();
        for (RulePacks.CatalogRule rule : all) {
            if (!packageScope.isEmpty() && !packageScope.equals(rule.packageName)) continue;
            if ((filter == 1 && !rule.splash) || (filter == 2 && rule.splash) || (filter == 3 && rule.enabled)) continue;
            if (rule.name.toLowerCase(Locale.ROOT).contains(term)
                    || rule.packageName.toLowerCase(Locale.ROOT).contains(term)
                    || rule.title.toLowerCase(Locale.ROOT).contains(term)
                    || rule.testedAppVersion.contains(term)) matches.add(rule);
        }
        Collator labels = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        matches.sort((left, right) -> {
            int byApp = labels.compare(left.name, right.name);
            return byApp == 0 ? labels.compare(left.title, right.title) : byApp;
        });
        summary.setText("找到 " + matches.size() + " 条规则");
        if (matches.isEmpty()) {
            screen.paragraph(screen.card(results), filter == 3 && term.isEmpty()
                    ? "当前没有停用的规则。" : "没有符合条件的规则，试试应用包名或其他关键词。");
            return;
        }
        LinearLayout rows = screen.card(results);
        rows.setPaddingRelative(screen.dp(8), screen.dp(4), screen.dp(8), screen.dp(4));
        for (RulePacks.CatalogRule rule : matches.subList(0, Math.min(matches.size(), limit))) {
            TextView status = screen.featureRow(rows, rule.splash ? R.drawable.ic_apps : R.drawable.ic_popup,
                    rule.name, () -> detail(rule));
            status.setText(rule.title + (rule.testedAppVersion.isEmpty() ? "" : " · " + rule.testedAppVersion)
                    + " · " + (rule.enabled ? "已启用" : "已停用"));
        }
        if (matches.size() > limit) screen.action(results, "显示更多规则", false, () -> {
            limit += PAGE_SIZE;
            list(all, results, summary);
        });
    }

    private void detail(RulePacks.CatalogRule rule) {
        LinearLayout content = screen.skipDialogContent();
        screen.paragraph(content, rule.packageName);
        screen.paragraph(content, rule.title);
        screen.paragraph(content, rule.testedAppVersion.isEmpty()
                ? "目标应用版本暂无记录" : "适配证据版本 " + rule.testedAppVersion);
        screen.paragraph(content, "收录于" + packName(rule.verifiedVersion));
        if (!rule.sourceUrl.isEmpty()) {
            Uri source = Uri.parse(rule.sourceUrl);
            if ("https".equals(source.getScheme()) && source.getHost() != null) {
                screen.linkAction(content, "打开规则来源", () -> screen.openUpdateLink(source));
            }
        }
        if (!rule.matchCondition.isEmpty()) {
            screen.linkAction(content, "查看匹配条件", () -> {
                LinearLayout conditions = screen.skipDialogContent();
                screen.paragraph(conditions, rule.matchCondition);
                new AlertDialog.Builder(screen).setTitle("匹配条件").setView(screen.scrollDialogContent(conditions))
                        .setPositiveButton("关闭", null).show();
            });
        }
        screen.paragraph(content, "规则在下次进入目标应用时生效。应用排除和功能总开关仍然有效。");
        new AlertDialog.Builder(screen).setTitle(rule.name).setView(screen.scrollDialogContent(content))
                .setPositiveButton(rule.enabled ? "停用此规则" : "启用此规则", (dialog, which) -> changeRule(rule))
                .setNegativeButton("关闭", null).show();
    }

    private void changeRule(RulePacks.CatalogRule rule) {
        if (applyingSelection) { screen.toast("正在保存规则设置"); return; }
        applyingSelection = true;
        Context app = screen.getApplicationContext();
        WORKER.execute(() -> {
            boolean success = false;
            try { success = RulePacks.get(app).setRuleEnabled(rule.id, !rule.enabled); }
            finally {
                boolean saved = success;
                MAIN.post(() -> {
                    applyingSelection = false;
                    if (!screen.isDestroyed()) screen.toast(saved ? "已保存，下次打开目标应用时生效" : "规则已变化，请重新选择");
                    requestRender();
                });
            }
        });
    }

    private void refresh(boolean manual) {
        Context app = screen.getApplicationContext();
        packWork(() -> {
            RulePacks.Refresh result = RulePacks.get(app).refresh();
            boolean failed = result == RulePacks.Refresh.FAILED || result == RulePacks.Refresh.UNAVAILABLE;
            settings(app).edit().putLong(NEXT_CHECK, System.currentTimeMillis() +
                    (failed ? FAILED_CHECK_INTERVAL : RULE_CHECK_INTERVAL)).apply();
            if (!manual && result != RulePacks.Refresh.UPDATED) return "";
            switch (result) {
                case UPDATED: return "规则已更新，下次打开目标应用时生效。";
                case CURRENT: return "正在使用最新规则。";
                case ROLLED_BACK: return "已退回旧规则。新版本发布后可再次更新。";
                case UNAVAILABLE: return "暂时连不上规则源，继续使用当前规则。";
                default: return "规则包未能通过检查，继续使用当前规则。";
            }
        });
    }

    private void rollback(long previousVersion) {
        Context app = screen.getApplicationContext();
        new AlertDialog.Builder(screen).setTitle("返回" + packName(previousVersion) + "？")
                .setMessage("下次打开目标应用时使用旧规则。你的启停设置继续保留。")
                .setPositiveButton("返回上一版", (dialog, which) -> packWork(() -> RulePacks.get(app).rollback()
                        ? "已返回" + packName(previousVersion) + "。" : "暂时无法返回上一版。"))
                .setNegativeButton("取消", null).show();
    }

    private void packWork(Supplier<String> work) {
        if (!PACK_WORK.compareAndSet(false, true)) return;
        packNotice = "";
        requestRender();
        WORKER.execute(() -> {
            String result;
            try { result = work.get(); }
            catch (RuntimeException failure) { result = "规则处理未完成，继续使用当前规则。"; }
            String message = result;
            MAIN.post(() -> {
                PACK_WORK.set(false);
                packNotice = message;
                requestRender();
            });
        });
    }

    private static void requestRender() {
        RuleDirectoryPage page = active.get();
        if (page != null && !page.screen.isDestroyed() && page.screen.tab == MainActivity.RULE_DIRECTORY) {
            page.screen.render();
        }
    }

    private static android.content.SharedPreferences settings(Context context) {
        return context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE);
    }

    private static String packName(long version) { return version == 0 ? "内置规则" : "规则包 " + version; }
}
