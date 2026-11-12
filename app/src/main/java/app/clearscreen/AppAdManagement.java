package app.clearscreen;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Installed-app controls and a read-only view of the bundled rule catalog. */
final class AppAdManagement {
    private static final int PAGE_SIZE = 25;
    private static Map<String, Coverage> cachedCoverage;

    private final MainActivity screen;
    private boolean showCatalog;
    private int limit = PAGE_SIZE;
    private int listScroll;
    private boolean restoreListOnResume;
    private String query = "", selected = "";

    AppAdManagement(MainActivity screen, Bundle saved) {
        this.screen = screen;
        if (saved == null) return;
        showCatalog = saved.getBoolean("appAdCatalog");
        query = saved.getString("appAdQuery", "");
        selected = saved.getString("appAdSelected", "");
        limit = Math.max(PAGE_SIZE, saved.getInt("appAdLimit"));
        listScroll = saved.getInt("appAdScroll");
        restoreListOnResume = saved.getInt("tab") == MainActivity.APP_ADS;
    }

    void save(Bundle state) {
        if (screen.tab == MainActivity.APP_ADS && screen.pageScroll.isLaidOut()) {
            listScroll = screen.pageScroll.getScrollY();
        }
        state.putBoolean("appAdCatalog", showCatalog);
        state.putString("appAdQuery", query);
        state.putString("appAdSelected", selected);
        state.putInt("appAdLimit", limit);
        state.putInt("appAdScroll", listScroll);
    }

    void list() {
        screen.header("", "应用广告管理", "搜索应用，设置它的广告处理方式。");
        Map<String, Coverage> coverage = coverage();
        if (coverage == null) return;

        Map<String, String> installed = screen.launcherApps();
        LinearLayout controls = screen.card(screen.body);
        controls.setFocusableInTouchMode(true);
        controls.requestFocus();
        EditText search = new EditText(screen);
        search.setSingleLine(true);
        search.setText(query);
        search.setHint("搜索应用名称或包名");
        controls.addView(search);
        TextView summary = screen.text("", 14, MainActivity.MUTED);
        screen.space(controls, 8);
        controls.addView(summary);
        LinearLayout results = screen.column();
        screen.body.addView(results);
        Runnable refresh = () -> renderResults(coverage, installed, results, summary);
        screen.historyToggle(controls, screen.getString(R.string.app_coverage_show_catalog, coverage.size()), showCatalog, enabled -> {
            showCatalog = enabled;
            limit = PAGE_SIZE;
            listScroll = 0;
            refresh.run();
        });
        screen.linkAction(controls, "批量排除应用", screen::chooseExclusionMode);
        screen.watchSkipSearch(search, () -> {
            query = search.getText().toString();
            limit = PAGE_SIZE;
            listScroll = 0;
            refresh.run();
        });
        refresh.run();
    }

    private void renderResults(Map<String, Coverage> coverage, Map<String, String> installed,
                               LinearLayout results, TextView summary) {
        results.removeAllViews();
        String term = query.trim().toLowerCase(Locale.ROOT);
        List<String> packages = new ArrayList<>();
        for (String pkg : (showCatalog ? coverage.keySet() : installed.keySet())) {
            if (pkg.toLowerCase(Locale.ROOT).contains(term)
                    || label(coverage, installed, pkg).toLowerCase(Locale.ROOT).contains(term)) packages.add(pkg);
        }
        Collator labels = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
        packages.sort((left, right) -> {
            int byName = labels.compare(label(coverage, installed, left), label(coverage, installed, right));
            return byName == 0 ? left.compareTo(right) : byName;
        });
        long dedicated = packages.stream().filter(coverage::containsKey).count();
        summary.setText(screen.getString(R.string.app_coverage_result_count, packages.size(), dedicated));
        if (packages.isEmpty()) {
            screen.paragraph(screen.card(results), term.isEmpty() ? "暂未找到可启动的应用。" : "没有符合搜索条件的应用。");
            return;
        }
        LinearLayout card = screen.card(results);
        card.setPadding(screen.dp(8), screen.dp(5), screen.dp(8), screen.dp(5));
        for (String pkg : packages.subList(0, Math.min(limit, packages.size()))) {
            boolean canLaunch = installed.containsKey(pkg);
            String name = label(coverage, installed, pkg);
            Drawable icon = icon(pkg, canLaunch);
            Runnable open = () -> {
                listScroll = screen.pageScroll.getScrollY();
                selected = pkg;
                screen.showPage(MainActivity.APP_AD_DETAIL);
            };
            TextView status = icon == null ? screen.featureRow(card, R.drawable.ic_popup, name, open)
                    : screen.featureRow(card, icon, name, open);
            String scope = shortCoverage(pkg, coverage.get(pkg));
            status.setText(canLaunch ? scope : screen.getString(R.string.app_coverage_missing_launcher, scope));
        }
        if (limit < packages.size()) screen.action(results, "查看更多", false, () -> {
            limit += PAGE_SIZE;
            renderResults(coverage, installed, results, summary);
        });
    }
    void restoreSavedListPosition() {
        if (!restoreListOnResume) return;
        restoreListOnResume = false;
        if (screen.tab == MainActivity.APP_ADS) restoreListPosition();
    }

    void restoreListPosition() {
        int position = listScroll;
        if (position <= 0) return;
        ScrollView scroll = screen.pageScroll;
        if (scroll.isLaidOut()) {
            scroll.scrollTo(0, position);
        } else {
            scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                     int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    view.removeOnLayoutChangeListener(this);
                    scroll.scrollTo(0, position);
                }
            });
        }
    }

    private Drawable icon(String pkg, boolean installed) {
        if (installed) {
            try { return screen.getPackageManager().getApplicationIcon(pkg); }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return null;
    }

    void detail() {
        if (selected.isEmpty()) {
            screen.showPage(MainActivity.APP_ADS);
            return;
        }
        Map<String, Coverage> coverage = coverage();
        if (coverage == null) return;
        Map<String, String> installed = screen.launcherApps();
        boolean launchable = installed.containsKey(selected);
        boolean knownInstalled = launchable || screen.store.packageVersion(selected) >= 0;
        Coverage entry = coverage.get(selected);
        String appName = label(coverage, installed, selected);
        screen.header("", appName, "");
        boolean protectedApp = CustomSkipRule.protectedPackage(selected);
        if (!knownInstalled || protectedApp) {
            showBuiltInRules(entry, protectedApp, knownInstalled);
            return;
        }

        LinearLayout controls = screen.card(screen.body);
        controls.addView(screen.bold("此应用的处理偏好", 18, MainActivity.INK));
        boolean paused = screen.store.splashExcluded().contains(selected);
        screen.historyToggle(controls, "暂停开屏和弹窗", paused, checked -> {
            saveExclusion(null, checked);
            screen.render();
        });
        if (!paused) {
            screen.historyToggle(controls, "允许跳过开屏广告",
                    !screen.store.skipExcluded(CustomSkipRule.SPLASH).contains(selected),
                    checked -> saveExclusion(CustomSkipRule.SPLASH, !checked));
            screen.historyToggle(controls, "允许关闭广告弹窗",
                    !screen.store.skipExcluded(CustomSkipRule.POPUP).contains(selected),
                    checked -> saveExclusion(CustomSkipRule.POPUP, !checked));
            screen.space(controls, 8);
            screen.paragraph(controls, "全局开屏跳过" + (screen.store.splashEnabled() ? "已开启" : "未开启")
                    + " · 弹窗处理" + (screen.store.popupEnabled() ? "已开启" : "未开启"));
            if (!screen.store.splashEnabled()) screen.linkAction(controls, "开启开屏跳过", screen::toggleSplash);
            if (!screen.store.popupEnabled()) screen.linkAction(controls, "开启弹窗处理", screen::togglePopup);
            if (CleanerService.get() == null) screen.linkAction(controls, "连接无障碍服务", screen::accessibility);
        } else {
            screen.paragraph(controls, "关闭暂停开关即可恢复此应用的广告处理。");
        }
        if (screen.networkFilterAvailable()) {
            Set<String> bypass = screen.filterStore.bypassedApps();
            screen.historyToggle(controls, "绕过网络过滤", bypass.contains(selected), checked -> {
                if (checked) bypass.add(selected); else bypass.remove(selected);
                screen.saveFilterAppExceptions(bypass);
            });
            screen.space(controls, 4);
            screen.paragraph(controls, AdFilterService.active == null
                    ? "网络过滤未开启。此设置将在下次开启时生效。"
                    : "此设置在下次开启时生效。修改后，请停止并重新开启网络过滤。");
        }

        showBuiltInRules(entry, false, true);
        LinearLayout tools = screen.card(screen.body);
        tools.addView(screen.bold("诊断与规则", 18, MainActivity.INK));
        screen.linkAction(tools, "开屏漏跳诊断", () -> screen.launchDiagnostics(selected, appName));
        screen.linkAction(tools, "为此应用添加规则", () -> screen.addRuleForApp(selected));
        screen.linkAction(tools, "管理自定义规则", screen::openCustomRules);
        if (launchable) screen.linkAction(tools, "打开应用", () -> screen.openInstalledApp(selected));
    }

    private void showBuiltInRules(Coverage entry, boolean protectedApp, boolean knownInstalled) {
        LinearLayout builtIn = screen.card(screen.body);
        builtIn.addView(screen.bold("内置规则", 18, MainActivity.INK));
        screen.space(builtIn, 8);
        screen.paragraph(builtIn, protectedApp ? "此应用属于受保护范围。"
                : entry == null ? "可尝试通用识别，也可以添加本机规则。"
                : "开屏 " + entry.splash + " 条 · 应用内 " + entry.popup + " 条。命中取决于广告样式和应用版本。");
        if (!knownInstalled) screen.paragraph(builtIn, "这台设备暂未检测到此应用。");
        builtIn.addView(screen.text(selected, 14, MainActivity.MUTED));
    }

    private void saveExclusion(String mode, boolean excluded) {
        Set<String> packages = mode == null ? screen.store.splashExcluded() : screen.store.skipExcluded(mode);
        if (excluded) packages.add(selected); else packages.remove(selected);
        if (mode == null) screen.store.setSplashExcluded(packages);
        else screen.store.setSkipExcluded(mode, packages);
    }

    private Map<String, Coverage> coverage() {
        if (cachedCoverage != null) return cachedCoverage;
        try {
            JSONArray catalog = new JSONArray(screen.store.asset("app-ad-coverage.json"));
            Map<String, Coverage> entries = new HashMap<>();
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject entry = catalog.getJSONObject(i);
                entries.put(entry.getString("package"), new Coverage(entry.getString("name"),
                        entry.getInt("splash"), entry.getInt("popup")));
            }
            cachedCoverage = Collections.unmodifiableMap(entries);
            return cachedCoverage;
        } catch (JSONException | IllegalStateException failure) {
            LinearLayout status = screen.card(screen.body);
            screen.paragraph(status, "内置规则目录暂时无法读取。");
            screen.action(status, "重新读取目录", false, screen::render);
            return null;
        }
    }

    private static String label(Map<String, Coverage> coverage, Map<String, String> installed, String pkg) {
        String actual = installed.get(pkg);
        if (actual != null && !actual.isEmpty()) return actual;
        Coverage entry = coverage.get(pkg);
        return entry == null || entry.name.isEmpty() ? pkg : entry.name;
    }

    private static String shortCoverage(String pkg, Coverage entry) {
        if (CustomSkipRule.protectedPackage(pkg)) return "受保护应用";
        if (entry == null) return "可尝试通用识别";
        if (entry.splash > 0 && entry.popup > 0) return "开屏 · 应用内";
        return entry.splash > 0 ? "开屏专属" : "应用内专属";
    }

    private static final class Coverage {
        final String name;
        final int splash, popup;

        Coverage(String name, int splash, int popup) {
            this.name = name;
            this.splash = splash;
            this.popup = popup;
        }
    }
}
