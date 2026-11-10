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
    static final int BG = 0xfff5f6f0, INK = 0xff162e24, MUTED = 0xff51645a;
    static final int GREEN = 0xff16624c, MINT = 0xffe9f1e9, LIME = 0xffd9eea6;
    static final int SKIP_MANAGEMENT = 7, CUSTOM_RULES = 9, POPUP_MANAGEMENT = 10;
    static final int START_FILTER_NOTIFICATIONS = 42, FILTER_NOTIFICATIONS = 43;
    static final int KEEP_ALIVE_NOTIFICATIONS = 44;
    static final int COLOROS_FILTER_NOTIFICATIONS = 45;
    Store store;
    LinearLayout shell, body;
    int tab = 0;
    int permissionParent, rulesParent = SKIP_MANAGEMENT;
    boolean onlyUnfinished = false;
    final Handler permissionHandler = new Handler(Looper.getMainLooper());
    PermissionAccess.State lastPermissionState;
    TextView filterStatus, filterCounter, keepAliveStatus;
    Button filterButton;
    TextView homeSplashStatus, homeSplashCount, homePopupStatus, homeCleanStatus;
    Button homeSplashButton;
    FilterStore filterStore;
    ScrollView pageScroll;
    int renderedTab=-1;
    boolean shownKeepAliveEnabled;
    final Runnable permissionRefresh = new Runnable() {
        @Override public void run() {
            PermissionAccess.State current = PermissionAccess.state(MainActivity.this);
            if (current != lastPermissionState) {
                lastPermissionState = current;
                if (tab == 4 || tab == SKIP_MANAGEMENT || tab == POPUP_MANAGEMENT) render();
            }
            if (tab == SKIP_MANAGEMENT && shownKeepAliveEnabled != store.skipKeepAliveEnabled()) render();
            refreshFilter();
            refreshHome();
            refreshKeepAlive();
            permissionHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        filterStore = new FilterStore(this);
        tab = state != null ? state.getInt("tab") : getIntent().getIntExtra("tab", 0);
        if (state != null) {
            permissionParent = state.getInt("permissionParent");
            rulesParent = state.getInt("rulesParent", SKIP_MANAGEMENT);
            onlyUnfinished = state.getBoolean("onlyUnfinished");
        }
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        lastPermissionState = PermissionAccess.state(this);
        if (store != null && store.skipKeepAliveEnabled()) SkipKeepAliveService.sync(this);
        if (store != null) render();
        permissionHandler.removeCallbacks(permissionRefresh);
        permissionHandler.postDelayed(permissionRefresh, 1000);
    }
    @Override protected void onPause() {
        permissionHandler.removeCallbacks(permissionRefresh);
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab);
        state.putInt("permissionParent", permissionParent);
        state.putInt("rulesParent", rulesParent);
        state.putBoolean("onlyUnfinished", onlyUnfinished);
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        tab = intent.getIntExtra("tab", 1);
        if (tab == 1) onlyUnfinished = false;
        render();
    }
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
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
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
        button.setBackground(touchBackground(primary ? GREEN : MINT, 14, primary ? 0x28ffffff : 0x1a16624c));
        button.setMinHeight(dp(48));
        button.setPadding(dp(16), dp(10), dp(16), dp(10));
        button.setOnClickListener(v -> action.run());
        return button;
    }
    void action(LinearLayout parent, String label, boolean primary, Runnable action) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(12);
        parent.addView(button(label, primary, action), params);
    }
    void linkAction(LinearLayout parent,String label,Runnable action){
        Button link=button(label,false,action);link.setTextColor(INK);link.setTypeface(null,Typeface.NORMAL);link.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);link.setPadding(0,dp(10),0,dp(10));
        link.setBackground(touchBackground(Color.TRANSPARENT, 10, 0x1416624c));
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_next);
        arrow.setBounds(0, 0, dp(18), dp(18));
        link.setCompoundDrawablesRelative(null, null, arrow, null);
        parent.addView(link, new LinearLayout.LayoutParams(-1, -2));
    }
    void header(String eyebrow, String title, String sub) {
        body.addView(bold(eyebrow, 12, GREEN));
        space(body, 10);
        body.addView(bold(title, 26, INK));
        space(body, 10);
        paragraph(body, sub);
        space(body, 18);
    }
    android.graphics.drawable.RippleDrawable touchBackground(int base, int radius, int ripple) {
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple),
                base == Color.TRANSPARENT ? null : shape(base, radius), shape(Color.WHITE, radius));
    }
    void showPage(int page) { tab = page; render(); }
    void pageBackBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(dp(12), 0, dp(12), 0);
        String label = tab == 5 ? "返回权限设置" : tab == CUSTOM_RULES ? "返回广告管理"
                : tab == 4 && permissionParent != 0 ? "返回上页" : "返回首页";
        Button back = button(label, false, this::backToParentPage);
        back.setTextColor(INK);
        back.setTextSize(14);
        back.setBackground(touchBackground(Color.TRANSPARENT, 12, 0x1416624c));
        android.graphics.drawable.Drawable arrow = getDrawable(R.drawable.ic_back);
        arrow.setBounds(0, 0, dp(18), dp(18));
        back.setCompoundDrawablesRelative(arrow, null, null, null);
        back.setCompoundDrawablePadding(dp(6));
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        shell.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }
    void render() {
        int restoreY=pageScroll!=null&&renderedTab==tab?pageScroll.getScrollY():0;
        renderedTab=tab;
        filterStatus=null;filterCounter=null;filterButton=null;keepAliveStatus=null;
        homeSplashStatus=null;homeSplashCount=null;homeSplashButton=null;homePopupStatus=null;homeCleanStatus=null;
        shell = column();
        shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(shell);
        shell.requestApplyInsets();
        if (tab != 0) pageBackBar();
        ScrollView scroll = new ScrollView(this);
        pageScroll=scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = column();
        body.setPadding(dp(20), dp(tab == 0 ? 12 : 4), dp(20), dp(24));
        int pageWidth = getResources().getConfiguration().screenWidthDp >= 640 ? dp(560) : -1;
        scroll.addView(body, new FrameLayout.LayoutParams(pageWidth, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab == 0) home(); else if (tab == 1) checks(); else if (tab == 2) history();
        else if (tab == 4) permissionSetup(); else if (tab == 5) permissionHelp(); else if(tab==6) networkPage();
        else if (tab == SKIP_MANAGEMENT) splashPage(); else if (tab == POPUP_MANAGEMENT) popupPage();
        else if (tab == CUSTOM_RULES) customRulesPage();
        else if(tab==8) supportPage(); else about();
        if(restoreY>0)scroll.post(()->scroll.scrollTo(0,restoreY));
    }
    void backToParentPage() {
        tab = tab == 5 ? 4 : tab == 4 ? permissionParent : tab == CUSTOM_RULES ? rulesParent : 0;
        render();
    }
    @Override public void onBackPressed() {
        if (tab != 0) backToParentPage(); else super.onBackPressed();
    }
    void home() {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_app);
        mark.setBackground(shape(GREEN, 11));
        mark.setClipToOutline(true);
        top.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = bold("净屏", 25, INK);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(0, -2, 1);
        nameLayout.setMarginStart(dp(10));
        top.addView(name, nameLayout);
        Button help = button("使用说明", false, () -> showPage(3));
        help.setTextSize(14);
        help.setBackground(touchBackground(Color.TRANSPARENT, 12, 0x1416624c));
        top.addView(help, new LinearLayout.LayoutParams(-2, -2));
        body.addView(top);
        space(body, 12);

        LinearLayout splash = card(body);
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff10392c, 0xff1a513b});
        surface.setCornerRadius(dp(24));
        splash.setBackground(surface);
        splash.setPadding(dp(20), dp(18), dp(20), dp(10));
        homeSplashStatus = bold("", 12, LIME);
        homeSplashStatus.setBackground(shape(0xff2f5b45, 20));
        homeSplashStatus.setPadding(dp(10), dp(5), dp(10), dp(5));
        splash.addView(homeSplashStatus, new LinearLayout.LayoutParams(-2, -2));
        space(splash, 10);
        splash.addView(bold("开屏广告跳过", 26, Color.WHITE));
        space(splash, 7);
        homeSplashCount = text("", 14, 0xffd7e8dd);
        splash.addView(homeSplashCount);
        space(splash, 14);
        homeSplashButton = button("开启开屏跳过", true, () -> {
            if (store.splashEnabled() && CleanerService.get() == null) accessibility();
            else toggleSplash();
        });
        homeSplashButton.setTextColor(INK);
        homeSplashButton.setBackground(touchBackground(LIME, 14, 0x20162e24));
        splash.addView(homeSplashButton, new LinearLayout.LayoutParams(-1, -2));
        Button manage = button("管理开屏跳过", false, () -> showPage(SKIP_MANAGEMENT));
        manage.setTextColor(0xffe5f1e8);
        manage.setTextSize(14);
        manage.setBackground(touchBackground(Color.TRANSPARENT, 12, 0x28ffffff));
        splash.addView(manage, new LinearLayout.LayoutParams(-1, -2));

        sectionTitle("广告与系统");
        LinearLayout features = card(body);
        features.setPadding(dp(8), dp(5), dp(8), dp(5));
        homePopupStatus = featureRow(features, R.drawable.ic_popup, "应用内弹窗", () -> showPage(POPUP_MANAGEMENT));
        filterStatus = featureRow(features, R.drawable.ic_network, "网络广告过滤", () -> showPage(6));
        homeCleanStatus = featureRow(features, R.drawable.ic_settings, "系统广告设置", () -> {
            CleanerService service = CleanerService.get();
            if (service != null && service.busy) service.stop();
            else chooseClean();
        });
        refreshFilter();
        refreshHome();

        sectionTitle("记录与工具");
        LinearLayout more = card(body);
        more.setPadding(dp(16), dp(4), dp(16), dp(4));
        linkAction(more, "查看清理结果", () -> { onlyUnfinished = false; showPage(1); });
        linkAction(more, "改动记录与恢复", () -> showPage(2));
        linkAction(more, "权限与帮助", this::accessibility);
        linkAction(more, "通知渠道检查", this::notificationPermission);
        linkAction(more, "诊断报告", this::diagnostics);
        Button support = button("支持作者 · 自愿赞赏", false, () -> showPage(8));
        support.setTextColor(MUTED);
        support.setTextSize(13);
        support.setTypeface(null, Typeface.NORMAL);
        support.setBackground(touchBackground(Color.TRANSPARENT, 12, 0x1416624c));
        body.addView(support, new LinearLayout.LayoutParams(-1, -2));
    }

    void sectionTitle(String label) {
        space(body, 6);
        TextView heading = bold(label, 14, MUTED);
        heading.setPaddingRelative(dp(2), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        body.addView(heading);
        space(body, 9);
    }

    TextView featureRow(LinearLayout parent, int icon, String title, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(62));
        row.setPaddingRelative(dp(8), dp(8), dp(8), dp(8));
        row.setBackground(touchBackground(Color.TRANSPARENT, 14, 0x1416624c));
        ImageView symbol = new ImageView(this);
        symbol.setImageResource(icon);
        symbol.setPadding(dp(9), dp(9), dp(9), dp(9));
        symbol.setBackground(shape(MINT, 11));
        row.addView(symbol, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout copy = column();
        LinearLayout.LayoutParams copyLayout = new LinearLayout.LayoutParams(0, -2, 1);
        copyLayout.setMarginStart(dp(11));
        copyLayout.setMarginEnd(dp(8));
        copy.addView(bold(title, 16, INK));
        space(copy, 4);
        TextView status = text("", 13, MUTED);
        copy.addView(status);
        row.addView(copy, copyLayout);
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_next);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setFocusable(true);
        row.setOnClickListener(view -> action.run());
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                info.setContentDescription(title + "，" + status.getText());
            }
        });
        for (View child : new View[]{symbol, copy, arrow}) child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return status;
    }

    void refreshHome() {
        if (homeSplashStatus == null) return;
        CleanerService service = CleanerService.get();
        boolean connected = service != null;
        boolean busy = connected && service.busy;
        boolean splash = store.splashEnabled();
        updateText(homeSplashStatus, !splash ? "未开启" : !connected ? "待连接无障碍" : busy ? "清理期间暂停" : "运行中");
        updateText(homeSplashButton, !splash ? "开启开屏跳过" : !connected ? "继续授权" : "暂停开屏跳过");
        long count = store.splashCount();
        updateText(homeSplashCount, count > 0 ? "已确认跳过 " + count + " 次" : "识别跳过按钮，更快进入应用。");
        updateText(homePopupStatus, !store.popupEnabled() ? "未开启 · 识别并关闭广告弹窗"
                : !connected ? "待连接无障碍" : busy ? "清理期间暂停" : "运行中 · 已确认 " + store.popupCount() + " 次");
        updateText(homeCleanStatus, busy ? "清理中 · 点此停止" : store.lastRun().isEmpty()
                ? "检查推荐开关 · 改动可恢复" : "上次任务有记录 · 见下方清理结果");
    }

    void chooseClean() {
        CleanerService service = CleanerService.get();
        if (service == null) { accessibility(); return; }
        if (service.busy) { toast("已有任务正在执行"); return; }
        try {
            List<Rule> candidates = new ArrayList<>(store.plan());
            for (Rule rule : store.available()) if (rule.manual) candidates.add(rule);
            chooseRules(candidates, false);
        } catch (IllegalStateException failure) {
            toast("暂时无法读取清理项目，请先查看诊断");
        }
    }

    void chooseRules(List<Rule> candidates, boolean retry) {
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
        int targets = rules.stream().mapToInt(rule -> rule.targets.size()).sum();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle((retry ? "重试范围" : "清理范围") + " · " + targets + " 个候选开关");
        if (manualCount > 0) dialog.setNeutralButton("手动项（" + manualCount + "）", (d,w) -> showManualRules(manualRules));
        dialog.setMultiChoiceItems(labels, pick, (d,w,checked) -> pick[w] = checked)
            .setPositiveButton(retry ? "开始重试" : "开始清理", null).setNegativeButton("取消", null);
        AlertDialog selection = dialog.create();
        selection.setOnShowListener(ignored -> selection.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(button -> {
                List<Rule> chosen = new ArrayList<>();
                for (int i = 0; i < pick.length; i++) if (pick[i]) chosen.add(rules.get(i));
                if (chosen.isEmpty()) { toast("请至少选择一项"); return; }
                CleanerService current = CleanerService.get();
                if (current == null) { toast("自动操作权限已断开，请重新开启"); return; }
                if (current.busy) { toast("已有任务正在执行"); return; }
                selection.dismiss();
                if (retry) current.retry(chosen); else current.clean(chosen);
            }));
        selection.show();
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
        header("系统广告设置", "清理结果", "逐项查看已核验的改动和未完成原因。");
        JSONArray scan;
        try { scan = store.read("scan"); }
        catch (IllegalStateException failure) { paragraph(card(body), "清理记录暂时无法读取。"); return; }
        String lastRun = store.lastRun();
        if (scan.length() == 0) {
            paragraph(card(body), lastRun.isEmpty() ? "还没有清理结果。" : lastRun);
            if (!lastRun.isEmpty()) action(body, "改动记录与恢复", false, () -> showPage(2));
            return;
        }
        int completed = 0, unfinished = 0;
        for (int i = 0; i < scan.length(); i++) {
            JSONObject item = scan.optJSONObject(i);
            if (item == null || item.optJSONObject("rule") == null) continue;
            if (isUnfinished(item.optString("status"))) unfinished++;
            else completed++;
        }
        if (completed + unfinished == 0) {
            paragraph(card(body), "这次没有可读取的清理明细。已保存的改动可从恢复记录查看。");
            action(body, "改动记录与恢复", false, () -> showPage(2));
            return;
        }
        boolean canFilter = completed > 0 && unfinished > 0;
        if (!canFilter) onlyUnfinished = false;
        LinearLayout summary = card(body);
        summary.addView(bold("已完成 " + completed + " 项 · 未完成 " + unfinished + " 项", 16, INK));
        if (!lastRun.isEmpty()) { space(summary, 6); paragraph(summary, lastRun); }
        if (canFilter) {
            action(body, onlyUnfinished ? "显示全部结果" : "只看未完成（" + unfinished + "）", false,
                    () -> { onlyUnfinished = !onlyUnfinished; render(); });
        }
        if (unfinished > 0) {
            action(body, "重试未完成", true, () -> {
                if (CleanerService.get() == null) { accessibility(); return; }
                try { chooseRules(store.retryRules(), true); }
                catch (IllegalStateException failure) { toast("暂时无法读取未完成项目"); }
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
            case "queued": return "尚未执行";
            case "skipped": return "已跳过";
            case "failed": return "未完成";
            case "pending": case "unknown": return "结果待核对";
            case "restoring": case "restore_failed": return "恢复待核对";
            default: return status;
        }
    }
    void history() {
        header("系统广告设置", "改动记录与恢复", "核对每项改动，按需恢复原始状态。");
        JSONArray journal;
        List<JSONObject> restorable;
        try {
            journal = store.read("journal");
            restorable = store.restorable();
        } catch (IllegalStateException failure) {
            paragraph(card(body), "改动记录暂时无法读取。自动恢复已停止。");
            return;
        }
        Set<String> restorableIds = new HashSet<>();
        for (JSONObject entry : restorable) restorableIds.add(entry.optString("id"));
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
            if (restorableIds.contains(item.optString("id"))) action(c, "恢复原始状态", false, () -> confirmRestore(item));
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
                if (clipboard == null) { toast("剪贴板暂不可用"); return; }
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("净屏诊断报告", store.exportText()));
                    toast("诊断报告已复制");
                } catch (IllegalStateException failure) { toast("本地记录无法读取，报告未生成"); }
            })
            .setNeutralButton("导出文件", (d,w) -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "净屏-诊断报告.txt");
                try { startActivityForResult(intent, 20); }
                catch (ActivityNotFoundException | SecurityException failure) { toast("此设备无法打开文件保存窗口，可以复制报告"); }
            }).setNegativeButton("取消", null).show();
    }
    void about() {
        header("使用说明", "清理范围", "净屏 " + store.appVersion() + " · 本地预览版");
        LinearLayout capability = card(body);
        capability.addView(bold("当前版本的能力", 18, INK));
        space(capability, 10);
        paragraph(capability, "设置清理处理已匹配的推荐开关和营销通知渠道。开屏跳过识别广告控件，应用内弹窗按内置和自定义规则处理，网络过滤拦截本地规则中的广告域名。微信和 QQ 的原图辅助可在跳过管理中单独开启。视频内嵌、同域名、缓存或应用自带加密 DNS 的广告可能保留。");
        space(capability, 10);
        paragraph(capability, "关闭个性化推荐不等于清除全部广告。原生鸿蒙不支持安装此安卓应用。");

        LinearLayout privacy = card(body);
        privacy.addView(bold("数据留在你的手机", 18, INK));
        space(privacy, 10);
        paragraph(privacy, "设置清理、开屏识别和弹窗匹配在本机执行。开屏跳过在应用进入前台的短时间内读取控件。开启弹窗处理后，会读取有内置或自定义弹窗规则的应用控件。跳过记录保留应用标识、时间和处理结果。通知检查仅保存应用和渠道标识。\n\n网络过滤使用本地 VPN，仅处理系统交给它的 DNS 请求；未拦截的查询交给当前网络的 DNS，不经过净屏服务器，不解密 HTTPS。只在本机保留最近 30 个被拦截域名和统计，可清除。诊断报告不包含这些域名。卸载会删除本地记录。");
        action(privacy, "清除通知渠道缓存", false, () -> new AlertDialog.Builder(this)
                .setTitle("清除渠道缓存？").setMessage("不会改变任何通知设置。通知访问权限仍开启时，后续出现的渠道会重新记录。")
                .setPositiveButton("清除", (d,w) -> { store.save("channels", new JSONArray()); toast("已清除渠道缓存"); })
                .setNegativeButton("取消", null).show());

        LinearLayout local = card(body);
        local.addView(bold("本机适配", 18, INK));
        space(local, 10);
        local.addView(bold(store.deviceName(), 15, INK));
        space(local, 4);
        paragraph(local, store.deviceDescription());
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
        action(credits,"网络规则许可",false,()->new AlertDialog.Builder(this).setTitle("Block List Project · Unlicense").setMessage(store.asset("dns-license.txt")).setPositiveButton("关闭",null).show());
        action(credits,"开屏规则许可",false,()->new AlertDialog.Builder(this).setTitle("ganlinte/GKD-subscription · MIT").setMessage(store.asset("splash-license.txt")).setPositiveButton("关闭",null).show());
        action(body, "管理自动操作权限", false, this::accessibility);
        action(body, "管理通知访问权限", false, this::notificationPermission);
        action(body, "诊断报告", false, this::diagnostics);
        action(body,"支持作者",false,()->{tab=8;render();});
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
        if (rule.notification()) {
            if (tryOpenRule(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, rule.pkg).putExtra(Settings.EXTRA_CHANNEL_ID, rule.channelId))) return;
        } else for (Route route : rule.routes) {
            if (!route.skipWhenPackagePresent.isEmpty() && Route.installed(this, route.skipWhenPackagePresent)) continue;
            if (!route.pkg.equals(route.foreground) && !Route.installed(this, route.foreground)) continue;
            if (tryOpenRule(route.launchIntent(this))) return;
        }
        toast("此入口无法直接打开，请按路径手动进入");
    }
    boolean tryOpenRule(Intent intent) {
        if (intent == null) return false;
        try { startActivity(intent); return true; }
        catch (ActivityNotFoundException | SecurityException failure) { return false; }
    }
    void accessibility() {
        if (tab != 4 && tab != 5) permissionParent = tab;
        showPage(4);
    }
    void permissionSetup() {
        PermissionAccess.State state = PermissionAccess.state(this);
        header("权限设置", "自动操作权限", "在系统设置中开启净屏的无障碍服务。");
        LinearLayout status = card(body);
        status.addView(bold(PermissionAccess.title(state), 18, GREEN));
        space(status, 10);
        paragraph(status, PermissionAccess.hint(state));
        space(status, 12);
        paragraph(status, "授权后，净屏可读取并操作屏幕控件，用于你发起的设置清理，以及单独开启的开屏、弹窗和原图辅助功能。你可以随时在系统设置撤销授权。");
        String primaryAction = state == PermissionAccess.State.READY ? "完成"
                : state == PermissionAccess.State.DISCONNECTED ? "管理系统授权" : "前往系统授权";
        action(status, primaryAction, true,
                () -> { if (PermissionAccess.state(this) == PermissionAccess.State.READY) backToParentPage();
                        else openAccessibilitySettings(); });
        if (state == PermissionAccess.State.READY) action(status, "管理系统授权", false, this::openAccessibilitySettings);
        action(status, "开启遇到问题", false, () -> { tab = 5; render(); });

        LinearLayout usage = card(body);
        usage.addView(bold("如何开启", 17, INK));
        space(usage, 10);
        paragraph(usage, "在系统的无障碍设置中找到「净屏」，开启服务后返回。入口可能位于「已下载的应用」或「已安装的服务」。网络过滤无需此权限。");
        if (state == PermissionAccess.State.OFF && PermissionAccess.family().equals("coloros"))
            paragraph(usage, "如果系统拒绝向净屏授予访问权限，点上方「开启遇到问题」查看 ColorOS 的处理路径。");
    }
    void openAccessibilitySettings() {
        PermissionAccess.Destination destination = PermissionAccess.openAccessibility(this, this::startActivity);
        if (destination == PermissionAccess.Destination.ACCESSIBILITY)
            toast("请找到「净屏」并开启服务，完成后返回");
        else if (destination == PermissionAccess.Destination.SETTINGS)
            toast("此系统未提供直达入口，请在设置中搜索「无障碍」，找到净屏");
        else if (destination == PermissionAccess.Destination.UNAVAILABLE)
            toast("无法打开系统设置，请手动打开设置并搜索「无障碍」");
    }
    void permissionHelp() {
        header("权限帮助", "按你看到的情况处理", "适用于支持的 Android 设备；净屏不会自动解除系统限制。");
        LinearLayout restricted = card(body);
        restricted.addView(bold("系统提示受限", 18, INK));
        space(restricted, 10);
        paragraph(restricted, PermissionAccess.restrictedHelp());
        action(restricted, "打开净屏应用详情", false, this::openOwnAppInfo);
        LinearLayout missing = card(body);
        missing.addView(bold("没有解除入口", 18, INK));
        space(missing, 10);
        if (PermissionAccess.showColorOsNotificationHelp()) {
            paragraph(missing, "若「权限管理」灰显并写着「未请求任何权限」，可尝试允许净屏发送网络过滤状态通知，再回到应用详情检查权限管理。是否出现解除入口由系统决定，此步骤仍待 ColorOS 实机验证。若覆盖安装后仍被拦截，可跳过重复安装。");
            if (PermissionAccess.needsNotificationPermission(this)) {
                action(missing, "申请过滤状态通知", false, () -> requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, COLOROS_FILTER_NOTIFICATIONS));
            } else {
                action(missing, "重新检查权限管理", false, this::openOwnAppInfo);
            }
        }
        paragraph(missing, "若系统始终没有提供解除入口，可以先使用独立的网络过滤。它拦截部分广告域名，开屏跳过和设置清理仍需要无障碍权限。");
        action(missing,"使用网络过滤",true,()->{tab=6;render();});
        action(missing, "保存当前安装包", false, this::saveInstalledApk);
        action(missing, "查看并复制诊断", false, this::diagnostics);
        LinearLayout disconnected = card(body);
        disconnected.addView(bold("开关已开，仍无法清理", 18, INK));
        space(disconnected, 10);
        paragraph(disconnected, "先返回净屏稍等系统连接。若一直显示服务未连接，可在系统中关闭净屏服务后重新开启，再返回检查。仍未恢复时再尝试重启手机。\n\n关闭服务会中断正在进行的任务；重新连接后由你决定是否继续。");
        action(disconnected, "打开无障碍设置", false, this::openAccessibilitySettings);
    }
    void openOwnAppInfo() {
        if (!PermissionAccess.openAppInfo(this, this::startActivity))
            toast("请长按桌面的净屏图标，打开应用信息或应用详情");
    }
    void saveInstalledApk() {
        // This release is a single APK. A split installation cannot be reinstalled from its base alone.
        if (getApplicationInfo().splitSourceDirs != null && getApplicationInfo().splitSourceDirs.length > 0) {
            toast("当前是分包安装，请使用原下载渠道的完整安装包");
            return;
        }
        new AlertDialog.Builder(this).setTitle("保存原签名安装包")
                .setMessage("将当前版本保存到你选择的位置，建议选择「下载」。然后打开手机自带文件管理，找到这个 APK 并覆盖安装。\n\n无需卸载，现有记录会保留。安装与授权仍由系统确认，此方法不保证解除限制。")
                .setPositiveButton("选择保存位置", (d,w) -> {
                    try {
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("application/vnd.android.package-archive")
                                .putExtra(Intent.EXTRA_TITLE, "ClearScreen-" + store.appVersion() + ".apk"), 21);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        toast("此系统无法打开文件保存窗口，请使用原下载渠道的 APK");
                    }
                }).setNegativeButton("取消", null).show();
    }
    void notificationPermission() {
        new AlertDialog.Builder(this).setTitle("发现营销通知渠道")
                .setMessage("系统的通知访问权限可以访问通知内容；净屏只提取应用名、渠道名称和渠道标识，在本机筛选候选项，不存储正文、不上传、不自动清除通知。\n\n只能发现已出现过的通知渠道，不代表手机上全部渠道。")
                .setPositiveButton("前往授权", (d,w) -> {
                    if (!PermissionAccess.openNotificationAccess(this::startActivity))
                        new AlertDialog.Builder(this).setTitle("暂时无法打开通知访问")
                                .setMessage("系统没有提供此入口，或禁止当前应用打开它。请在手机设置中搜索「通知使用权」或「通知访问权限」，选择净屏。\n\n若系统明确提示未知来源应用受限，可查看权限帮助；营销通知检查是可选功能，不影响网络过滤。")
                                .setPositiveButton("查看权限帮助", (dialog,which) -> {
                                    if (tab != 4 && tab != 5) permissionParent = tab;
                                    showPage(5);
                                })
                                .setNegativeButton("暂不开启", null).show();
                })
                .setNegativeButton("取消", null).show();
    }
    void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    void refreshFilter(){
        if(filterStatus!=null)updateText(filterStatus,AdFilterService.running()?"运行中 · 本地 DNS 过滤":AdFilterService.active==null&&filterStore.enabled()?"服务已停止，请重新开启":filterStore.message());
        if(filterCounter!=null)updateText(filterCounter,getString(R.string.dns_counter,AdFilterService.blocked(this)));
        if(filterButton!=null)updateText(filterButton,AdFilterService.active!=null?"停止网络过滤":"开启网络过滤");
    }
    void updateText(TextView view,String value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    void networkPage(){
        header("本地 DNS", "网络广告过滤", "在广告域名解析时拦截。无需开启无障碍服务。");
        LinearLayout status=card(body);filterStatus=bold("",17,GREEN);status.addView(filterStatus);space(status,10);filterCounter=text("",14,MUTED);status.addView(filterCounter);
        filterButton=button("开启网络过滤",true,this::toggleFilter);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(14);status.addView(filterButton,params);refreshFilter();
        LinearLayout recovery=card(body);recovery.addView(bold("拦截记录与放行",18,INK));space(recovery,8);
        paragraph(recovery,"如果应用加载异常，可先停止过滤，再按需放行完整域名。");
        linkAction(recovery,"最近拦截与放行",this::blockedDomains);linkAction(recovery,"管理放行列表",this::allowedDomains);
        linkAction(recovery,"手动添加放行域名",this::addAllowedDomain);
        linkAction(recovery,"清除最近域名记录",()->{new FilterStore(this).clearHistory();toast("已清除最近域名，不影响累计统计");});
        LinearLayout scope=card(body);scope.addView(bold("覆盖范围",17,INK));space(scope,8);
        paragraph(scope,"内置 235,866 条广告域名规则，来源 Block List Project（固定版本）。只匹配完整域名。应用自带 DNS、缓存广告、同域名内容和内嵌视频广告可能不受影响。\n\nAndroid 8.0 起可安装；Android 9 使用加密 DNS 时暂不叠加此过滤。网络异常会退出过滤并恢复系统解析。后台被系统结束后需重新开启，不伪装为持续保护。");
        action(scope,"管理过滤通知",false,()->requestFilterNotifications(false));
    }
    void toggleFilter(){
        if(AdFilterService.active!=null){startService(new Intent(this,AdFilterService.class).setAction(AdFilterService.STOP));refreshFilter();}
        else new AlertDialog.Builder(this).setTitle("开启本地网络过滤")
                .setMessage("系统会请求一次 VPN 授权，占用当前 VPN 位置，与其他 VPN 不能同时使用。仅 DNS 查询进入本地过滤，其他流量仍由原网络传输；未拦截的查询交给当前网络的 DNS，不解密 HTTPS。\n\n不能拦住全部广告。若应用加载异常，可停止过滤或放行被拦域名。运行时使用状态通知；不同系统仍可能限制后台运行。")
                .setPositiveButton("继续",(d,w)->requestFilterNotifications(true)).setNegativeButton("取消",null).show();
    }
    void requestFilterNotifications(boolean startAfter) {
        if (PermissionAccess.needsNotificationPermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    startAfter ? START_FILTER_NOTIFICATIONS : FILTER_NOTIFICATIONS);
        } else if (startAfter) prepareFilter();
        else openNotificationSettings();
    }
    void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try { startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException failure) { toast("请在净屏应用详情管理通知"); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        boolean allowed = grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (request == START_FILTER_NOTIFICATIONS) prepareFilter();
        else if (request == FILTER_NOTIFICATIONS)
            toast(allowed ? "过滤状态通知已允许" : "通知权限未开启，可在系统通知设置中管理");
        else if (request == COLOROS_FILTER_NOTIFICATIONS && tab == 5) {
            render();
            if (allowed) toast("过滤状态通知已允许，可重新检查应用详情里的权限管理");
            else new AlertDialog.Builder(this).setTitle("过滤状态通知未开启")
                    .setMessage("通知权限是可选的。你仍可检查应用详情里的权限管理；如需显示过滤状态通知，可从系统通知设置开启。")
                    .setPositiveButton("检查权限管理", (dialog, which) -> openOwnAppInfo())
                    .setNeutralButton("管理过滤通知", (dialog, which) -> openNotificationSettings())
                    .setNegativeButton("返回", null).show();
        } else if (request == KEEP_ALIVE_NOTIFICATIONS) {
            if (allowed && SkipKeepAliveService.notificationsAllowed(this)) {
                setKeepAlive(true);
            } else toast("开启前台保活需要通知权限，可在系统通知设置中开启");
        }
    }
    void prepareFilter(){
        try{Intent intent=android.net.VpnService.prepare(this);if(intent==null)startFilter();else startActivityForResult(intent,41);}catch(Exception e){toast("此设备无法打开 VPN 授权，请检查系统或工作资料限制");}
    }
    void startFilter(){try{startForegroundService(new Intent(this,AdFilterService.class).setAction("start"));}catch(Exception e){toast("系统未允许启动网络过滤，请保持净屏在前台重试");}refreshFilter();}
    void blockedDomains(){
        List<String> domains=new FilterStore(this).recent();Collections.reverse(domains);
        if(domains.isEmpty()){toast("暂无拦截域名，运行记录约每 5 秒更新");return;}
        new AlertDialog.Builder(this).setTitle("最近拦截 · 点选可放行").setItems(domains.toArray(new String[0]),(d,w)->new AlertDialog.Builder(this).setTitle("放行此域名？").setMessage(domains.get(w)+"\n放行后广告也可能重新出现。应用的 DNS 缓存可能需要稍后重新打开应用才会更新。")
                .setPositiveButton("放行",(x,y)->{new FilterStore(this).allow(domains.get(w),true);toast("已放行该域名");}).setNegativeButton("取消",null).show()).setNegativeButton("关闭",null).show();
    }
    void allowedDomains(){
        List<String> domains=new ArrayList<>(new FilterStore(this).allowed());Collections.sort(domains);if(domains.isEmpty()){toast("放行列表为空");return;}
        new AlertDialog.Builder(this).setTitle("放行列表 · 点选移除").setItems(domains.toArray(new String[0]),(d,w)->new AlertDialog.Builder(this).setTitle("移除此放行项？").setMessage(domains.get(w)).setPositiveButton("移除",(x,y)->{new FilterStore(this).allow(domains.get(w),false);toast("已恢复按规则过滤");}).setNegativeButton("取消",null).show()).setNegativeButton("关闭",null).show();
    }
    void addAllowedDomain(){
        EditText field=new EditText(this);field.setSingleLine(true);field.setHint("例如 ads.example.com");field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("放行完整域名").setView(field).setPositiveButton("添加",null).setNegativeButton("取消",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=DomainRules.normalize(field.getText().toString());if(value==null){field.setError("请输入完整域名，不带 https:// 或路径");return;}new FilterStore(this).allow(value,true);dialog.dismiss();toast("已加入放行列表");}));dialog.show();
    }
    void splashPage(){
        header("自动跳过","开屏广告","识别明确的跳过按钮，减少打开应用时的等待。");
        boolean connected = CleanerService.get() != null;
        LinearLayout state=card(body);state.addView(bold(adStatus(store.splashEnabled(), connected),17,GREEN));space(state,8);
        paragraph(state,"打开应用后短时间内尝试跳过一次。开始操作或进入授权页面后停止处理。已确认跳过 " + store.splashCount() + " 次。");
        action(state,store.splashEnabled()?"关闭开屏跳过":"开启开屏跳过",true,this::toggleSplash);
        if (store.splashEnabled() && !connected) linkAction(state,"继续授权",this::accessibility);

        LinearLayout options = card(body);
        options.addView(bold("按需排除应用", 17, INK));
        space(options, 8);
        paragraph(options, "已为开屏和弹窗共同排除 " + store.splashExcluded().size() + " 个应用。");
        linkAction(options, "选择不处理的应用", () -> skipExclusions(null));
        linkAction(options, "分别排除开屏或弹窗", this::chooseExclusionMode);
        linkAction(options, "最近跳过记录", () -> skipHistory(false));
        linkAction(options, "管理自定义规则", this::openCustomRules);

        LinearLayout feedback = card(body);
        Switch skipToast = new Switch(this);
        skipToast.setText("确认处理时显示提示");
        skipToast.setTextColor(INK);
        skipToast.setTextSize(16);
        skipToast.setMinHeight(dp(48));
        skipToast.setChecked(store.skipToastEnabled());
        skipToast.setOnCheckedChangeListener((button, checked) -> store.setSkipToastEnabled(checked));
        feedback.addView(skipToast, new LinearLayout.LayoutParams(-1, -2));
        paragraph(feedback, "确认跳过开屏广告或关闭弹窗后，短暂显示提示。");
        linkAction(feedback, "微信与 QQ 原图", this::mediaAssistSettings);

        LinearLayout keepAlive = card(body);
        keepAlive.addView(bold("前台保活", 17, INK));
        space(keepAlive, 8);
        keepAliveStatus = text("", 14, GREEN);
        keepAlive.addView(keepAliveStatus);
        refreshKeepAlive();
        space(keepAlive, 8);
        paragraph(keepAlive, "使用常驻通知帮助净屏留在后台。无障碍服务的连接和恢复由系统管理。");
        shownKeepAliveEnabled = store.skipKeepAliveEnabled();
        action(keepAlive, shownKeepAliveEnabled ? "关闭前台保活" : "开启前台保活", false, this::toggleKeepAlive);
        if (shownKeepAliveEnabled) linkAction(keepAlive, "重新检查运行状态", () -> {
            SkipKeepAliveService.sync(this);
            refreshKeepAlive();
        });
        if (!SkipKeepAliveService.notificationsAllowed(this)) linkAction(keepAlive, "管理系统通知", this::openNotificationSettings);
        linkAction(keepAlive, "检查后台限制", this::backgroundHelp);
    }
    String adStatus(boolean enabled, boolean connected) {
        return !enabled ? "未开启" : !connected ? "已开启 · 待连接无障碍" : "已开启 · 无障碍已连接";
    }
    void openCustomRules() {
        rulesParent = tab;
        showPage(CUSTOM_RULES);
    }
    void popupPage() {
        header("自动处理", "应用内弹窗", "匹配广告标识和关闭按钮，或使用应用专用规则。");
        boolean enabled = store.popupEnabled();
        boolean connected = CleanerService.get() != null;
        LinearLayout state = card(body);
        state.addView(bold(adStatus(enabled, connected), 17, GREEN));
        space(state, 8);
        paragraph(state, "内置美团、饿了么、滴滴、高德等应用的专用广告规则。已确认处理 " + store.popupCount() + " 次。");
        action(state, enabled ? "关闭弹窗处理" : "开启弹窗处理", true, this::togglePopup);
        if (enabled && !connected) linkAction(state, "继续授权", this::accessibility);

        LinearLayout options = card(body);
        options.addView(bold("记录与规则", 17, INK));
        linkAction(options, "最近弹窗处理记录", () -> skipHistory(true));
        linkAction(options, "选择不处理的应用", () -> skipExclusions(CustomSkipRule.POPUP));
        linkAction(options, "管理自定义规则", this::openCustomRules);
    }
    void backgroundHelp() {
        Boolean restricted = PermissionAccess.backgroundRestricted(this), exempt = PermissionAccess.batteryExempt(this);
        String state = restricted == null ? "此设备未提供后台限制状态。"
                : restricted ? "系统已限制净屏在后台运行。" : "系统未报告净屏处于后台受限状态。";
        String battery = exempt == null ? "暂时无法读取电池优化状态。"
                : exempt ? "净屏已被系统列入电池优化豁免。" : "净屏仍受系统电池优化管理。";
        new AlertDialog.Builder(this).setTitle("后台运行状态")
                .setMessage(state + "\n" + battery + "\n\n若无障碍经常断开，可在系统设置中检查净屏的后台运行权限。厂商的省电策略由本机系统管理。")
                .setPositiveButton("打开系统设置", (d, w) -> {
                    if (!PermissionAccess.openBatterySettings(this, this::startActivity)) toast("请从系统设置手动打开净屏应用详情");
                }).setNegativeButton("关闭", null).show();
    }
    void toggleSplash(){
        if (store.splashEnabled()) {
            store.setSplashEnabled(false);
            SkipKeepAliveService.sync(this);
            render();
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许识别开屏广告？")
                .setMessage("开启后，净屏会在应用进入前台的短时间内读取页面控件，尝试点击明确的广告跳过按钮。需要无障碍权限，可随时关闭或排除某个应用。")
                .setPositiveButton("开启", (d, w) -> {
                    store.setSplashEnabled(true);
                    SkipKeepAliveService.sync(this);
                    if (CleanerService.get() == null) accessibility(); else render();
                }).setNegativeButton("取消", null).show();
    }
    void togglePopup() {
        if (store.popupEnabled()) {
            store.setPopupEnabled(false);
            SkipKeepAliveService.sync(this);
            render();
            return;
        }
        new AlertDialog.Builder(this).setTitle("允许处理应用内弹窗？")
                .setMessage("开启后，净屏会通过无障碍服务识别明确的广告弹窗，也会检查内置与自定义规则。匹配时会点击关闭控件，已有兼容规则也可能执行返回操作。你可以随时关闭或排除某个应用。")
                .setPositiveButton("开启", (dialog, which) -> {
                    store.setPopupEnabled(true);
                    SkipKeepAliveService.sync(this);
                    if (CleanerService.get() == null) accessibility(); else render();
                }).setNegativeButton("取消", null).show();
    }
    void mediaAssistSettings() {
        String[] keys = {"wechat-view", "wechat-original", "qq-view", "qq-original"};
        String[] labels = {"微信 · 查看原图", "微信 · 勾选原图", "QQ · 查看原图", "QQ · 勾选原图"};
        Set<String> enabled = new HashSet<>(store.mediaAssistEnabled());
        LinearLayout content = skipDialogContent();
        paragraph(content, "查看原图会在图片浏览页点击「查看原图」。勾选原图会在选图页打开「原图」选项，图片由你自行发送。各项可独立开启，需要无障碍服务。");
        space(content, 8);
        for (int index = 0; index < keys.length; index++) {
            String key = keys[index];
            CheckBox option = new CheckBox(this);
            option.setText(labels[index]);
            option.setMinHeight(dp(48));
            option.setChecked(enabled.contains(key));
            option.setOnCheckedChangeListener((button, checked) -> {
                if (checked) enabled.add(key); else enabled.remove(key);
            });
            content.addView(option, new LinearLayout.LayoutParams(-1, -2));
        }
        new AlertDialog.Builder(this).setTitle("微信与 QQ 原图").setView(scrollDialogContent(content))
                .setPositiveButton("保存", (d, w) -> {
                    store.setMediaAssistEnabled(enabled);
                    if (!enabled.isEmpty() && CleanerService.get() == null) accessibility(); else render();
                }).setNegativeButton("取消", null).show();
    }
    void refreshKeepAlive() {
        if (keepAliveStatus == null) return;
        String status;
        if (!store.skipKeepAliveEnabled()) status = "已关闭";
        else if (!SkipKeepAliveService.notificationsAllowed(this)) status = "已开启 · 等待通知授权";
        else if (!store.splashEnabled() && !store.popupEnabled()) status = "已开启 · 等待开启开屏或弹窗功能";
        else if (CleanerService.get() == null) status = "已开启 · 等待无障碍连接";
        else status = SkipKeepAliveService.running() ? "已开启 · 运行中" : "已开启 · 等待前台服务启动";
        updateText(keepAliveStatus, status);
    }
    void toggleKeepAlive() {
        if (store.skipKeepAliveEnabled()) { setKeepAlive(false); return; }
        new AlertDialog.Builder(this).setTitle("开启前台保活？")
                .setMessage("净屏会在开屏或弹窗功能已开启、无障碍已连接时显示常驻通知。可随时关闭前台保活。")
                .setPositiveButton("继续", (d, w) -> {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, KEEP_ALIVE_NOTIFICATIONS);
                    } else if (SkipKeepAliveService.notificationsAllowed(this)) setKeepAlive(true);
                    else openNotificationSettings();
                }).setNegativeButton("取消", null).show();
    }
    void setKeepAlive(boolean enabled) {
        store.setSkipKeepAliveEnabled(enabled);
        SkipKeepAliveService.sync(this);
        render();
    }
    void customRulesPage() {
        header("开屏与弹窗", "自定义规则", "开屏规则随开屏跳过启用，弹窗规则随弹窗处理启用。规则保存在这台设备上。");
        List<CustomSkipRule> rules;
        try { rules = store.customSkipRules(); }
        catch (IllegalArgumentException | IllegalStateException failure) {
            paragraph(card(body), "已保存的规则暂时无法读取。可用净屏备份替换规则。");
            action(body, "还原规则备份", false, this::importSkipRules);
            return;
        }
        LinearLayout actions = card(body);
        actions.addView(bold("已保存 " + rules.size() + " / " + CustomSkipRule.MAX_RULES + " 条", 17, INK));
        action(actions, "添加规则", true, () -> editSkipRule(rules, -1));
        linkAction(actions, "还原规则备份", this::importSkipRules);
        linkAction(actions, "导出规则", () -> exportSkipRules(rules));

        if (rules.isEmpty()) {
            paragraph(card(body), "暂无自定义规则。可为某个应用添加开屏跳过或关闭弹窗规则。");
            return;
        }
        Map<String, String> apps = launcherApps();
        for (int index = 0; index < rules.size(); index++) {
            final int position = index;
            CustomSkipRule rule = rules.get(index);
            String name = apps.getOrDefault(rule.pkg, rule.pkg);
            LinearLayout entry = card(body);
            entry.addView(bold(name, 17, INK));
            space(entry, 8);
            paragraph(entry, skipRuleDetails(rule));
            if (rule.legacy || rule.gesture || rule.delayMs > 0 || rule.searches > 1) {
                linkAction(entry, rule.sequenceIndex >= 0 ? "删除整组规则" : "删除规则",
                        () -> confirmSkipRuleDelete(rules, position, null));
            }
            else linkAction(entry, "编辑规则", () -> editSkipRule(rules, position));
        }
    }
    String skipRuleDetails(CustomSkipRule rule) {
        StringBuilder detail = new StringBuilder(CustomSkipRule.POPUP.equals(rule.mode) ? "弹窗处理" : "开屏跳过");
        detail.append(" · ").append(rule.pkg);
        if (rule.legacy) detail.append(" · 兼容规则");
        if (!rule.context.isEmpty()) detail.append(rule.legacy ? "\n页面匹配条件  " : "\n弹窗提示  ").append(rule.context);
        if (!rule.label.isEmpty()) detail.append(rule.legacy ? "\n操作条件  " : "\n按钮文字或描述  ").append(rule.label);
        if (!rule.viewId.isEmpty()) detail.append("\n控件 ID  ").append(rule.viewId);
        if (!rule.activity.isEmpty()) detail.append("\n页面类名  ").append(rule.activity);
        if (rule.replaceDefaults) detail.append("\n使用此应用的自定义开屏匹配");
        if (rule.gesture) detail.append("\n使用手势点击");
        if (rule.delayMs > 0) detail.append("\n延迟 ").append(rule.delayMs).append(" 毫秒");
        if (rule.searches > 1) detail.append("\n最多搜索 ").append(rule.searches).append(" 次");
        if (rule.sequenceIndex >= 0) detail.append("\n连续弹窗 · 第 ").append(rule.sequenceIndex + 1).append(" 步");
        if (rule.legacy && CustomSkipRule.POPUP.equals(rule.mode)) {
            if (rule.times == 0) detail.append("\n观察匹配后进入下一步");
            else detail.append("\n每次进入应用最多确认 ").append(rule.times).append(" 次");
        }
        return detail.toString();
    }
    LinearLayout skipDialogContent() {
        LinearLayout content = column();
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        return content;
    }
    ScrollView scrollDialogContent(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }
    EditText skipRuleField(LinearLayout content, String label, String hint, String value) {
        space(content, 12);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setHint(hint);
        field.setText(value);
        TextView caption = bold(label, 14, INK);
        caption.setLabelFor(field.getId());
        content.addView(caption);
        content.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }
    void editSkipRule(List<CustomSkipRule> rules, int index) {
        boolean editing = index >= 0;
        if (!editing && rules.size() >= CustomSkipRule.MAX_RULES) {
            toast("最多保存 " + CustomSkipRule.MAX_RULES + " 条规则");
            return;
        }
        CustomSkipRule existing = editing ? rules.get(index) : null;
        LinearLayout content = skipDialogContent();
        RadioGroup mode = new RadioGroup(this);
        mode.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton splash = new RadioButton(this), popup = new RadioButton(this);
        splash.setId(View.generateViewId());
        splash.setText("开屏跳过");
        popup.setId(View.generateViewId());
        popup.setText("弹窗处理");
        mode.addView(splash);
        mode.addView(popup);
        content.addView(mode);
        TextView guidance = text("", 14, MUTED);
        content.addView(guidance);

        EditText pkg = skipRuleField(content, "应用包名", "如 com.example.app", editing ? existing.pkg : "");
        action(content, "从已安装应用选择", false, () -> chooseSkipRuleApp(pkg));
        EditText label = skipRuleField(content, "按钮文字或无障碍描述", "如 跳过 或 关闭", editing ? existing.label : "");
        LinearLayout popupFields = column();
        content.addView(popupFields);
        EditText context = skipRuleField(popupFields, "弹窗提示文字（必填）", "如 今日推荐活动", editing ? existing.context : "");
        EditText viewId = skipRuleField(content, "完整控件 ID", "如 com.example.app:id/skip", editing ? existing.viewId : "");
        EditText activity = skipRuleField(content, "页面类名（可选）", "如 com.example.app.MainActivity", editing ? existing.activity : "");
        TextView error = text("", 14, 0xff9d302a);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);
        content.addView(error);
        mode.setOnCheckedChangeListener((group, selected) -> {
            boolean isPopup = selected == popup.getId();
            popupFields.setVisibility(isPopup ? View.VISIBLE : View.GONE);
            guidance.setText(isPopup ? "填写弹窗上完整的提示文字和关闭按钮文字或描述。控件 ID 可选。"
                    : "填写完整的按钮文字或描述、控件 ID，至少填写一项。两项都填时会一起匹配。");
        });
        mode.check(editing && CustomSkipRule.POPUP.equals(existing.mode) ? popup.getId() : splash.getId());

        ScrollView form = scrollDialogContent(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(editing ? "编辑规则" : "添加规则")
                .setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (editing) builder.setNeutralButton("删除", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    boolean isPopup = mode.getCheckedRadioButtonId() == popup.getId();
                    CustomSkipRule rule = new CustomSkipRule(pkg.getText().toString(), activity.getText().toString(),
                            isPopup ? CustomSkipRule.POPUP : CustomSkipRule.SPLASH, viewId.getText().toString(),
                            label.getText().toString(), isPopup ? context.getText().toString() : "");
                    List<CustomSkipRule> next = new ArrayList<>(rules);
                    if (editing) next.set(index, rule); else next.add(rule);
                    store.saveCustomSkipRules(next);
                    dialog.dismiss();
                    render();
                    toast("规则已保存");
                } catch (IllegalArgumentException | IllegalStateException failure) {
                    error.setText(failure.getMessage());
                    error.setVisibility(View.VISIBLE);
                    form.post(() -> form.fullScroll(View.FOCUS_DOWN));
                }
            });
            if (editing) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmSkipRuleDelete(rules, index, dialog));
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }
    void confirmSkipRuleDelete(List<CustomSkipRule> rules, int index, AlertDialog editor) {
        CustomSkipRule rule = rules.get(index);
        boolean grouped = rule.sequenceIndex >= 0;
        List<CustomSkipRule> next = new ArrayList<>(rules);
        if (grouped) next.removeIf(entry -> entry.sequenceIndex >= 0 && entry.pkg.equals(rule.pkg));
        else next.remove(index);
        String title = grouped ? "删除整组弹窗规则？" : "删除这条规则？";
        String message = grouped ? "将删除 " + rule.pkg + " 的整组连续弹窗规则，共 " + (rules.size() - next.size()) + " 步。"
                : skipRuleDetails(rule);
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(grouped ? "删除整组" : "删除", (confirm, which) -> {
                    if (saveSkipRules(next, grouped ? "整组规则已删除" : "规则已删除") && editor != null) editor.dismiss();
                }).setNegativeButton("取消", null).show();
    }
    boolean saveSkipRules(List<CustomSkipRule> rules, String success) {
        try {
            store.saveCustomSkipRules(rules);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            toast(failure.getMessage());
            return false;
        }
        render();
        toast(success);
        return true;
    }
    void chooseSkipRuleApp(EditText packageField) {
        Map<String, String> apps = launcherApps();
        List<String> packages = new ArrayList<>(apps.keySet());
        packages.removeIf(CustomSkipRule::protectedPackage);
        java.text.Collator collator = java.text.Collator.getInstance();
        packages.sort((first, second) -> collator.compare(apps.get(first), apps.get(second)));
        if (packages.isEmpty()) { toast("请选择手动输入应用包名"); return; }
        LinearLayout content = skipDialogContent();
        EditText search = new EditText(this);
        search.setHint("搜索应用名称或包名");
        search.setSingleLine(true);
        content.addView(search);
        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(260)));
        List<String> shown = new ArrayList<>();
        Runnable refresh = () -> {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            List<String> labels = new ArrayList<>();
            for (String pkg : packages) {
                String name = apps.get(pkg);
                if (name.toLowerCase(Locale.ROOT).contains(query) || pkg.toLowerCase(Locale.ROOT).contains(query)) {
                    shown.add(pkg);
                    labels.add(name + "\n" + pkg);
                }
            }
            list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        };
        watchSkipSearch(search, refresh);
        refresh.run();
        AlertDialog picker = new AlertDialog.Builder(this).setTitle("选择已安装应用").setView(content)
                .setNegativeButton("取消", null).create();
        list.setOnItemClickListener((parent, row, position, id) -> {
            packageField.setText(shown.get(position));
            picker.dismiss();
        });
        picker.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        picker.show();
    }
    void watchSkipSearch(EditText search, Runnable refresh) {
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            public void onTextChanged(CharSequence text, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(android.text.Editable text) { }
        });
    }
    void importSkipRules() {
        LinearLayout content = skipDialogContent();
        paragraph(content, "粘贴净屏导出的规则 JSON。确认后将替换本机全部自定义规则。");
        EditText input = new EditText(this);
        input.setHint("粘贴净屏规则 JSON");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(4);
        input.setMaxLines(8);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(CustomSkipRule.MAX_JSON_LENGTH + 1)});
        content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog paste = new AlertDialog.Builder(this).setTitle("粘贴导入规则")
                .setView(scrollDialogContent(content))
                .setPositiveButton("预览", null).setNegativeButton("取消", null).create();
        paste.setOnShowListener(d -> paste.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String json = input.getText().toString();
                if (json.indexOf('\0') >= 0) throw new IllegalArgumentException("规则包含无效字符");
                previewSkipImport(CustomSkipRule.parse(json), paste);
            } catch (IllegalArgumentException failure) {
                input.setError(failure.getMessage());
            } catch (StackOverflowError failure) {
                input.setError("规则 JSON 嵌套过深");
            }
        }));
        paste.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        paste.show();
    }
    void previewSkipImport(List<CustomSkipRule> incoming, AlertDialog paste) {
        LinearLayout content = skipDialogContent();
        String existing;
        try { existing = "现有 " + store.customSkipRules().size() + " 条自定义规则"; }
        catch (IllegalArgumentException | IllegalStateException failure) { existing = "当前无法读取的自定义规则"; }
        paragraph(content, incoming.isEmpty() ? "备份内容为空。确认后会清空" + existing + "。"
                : "以下 " + incoming.size() + " 条规则将替换" + existing + "。请核对应用和按钮。");
        for (int index = 0; index < incoming.size(); index++) {
            space(content, 12);
            content.addView(text((index + 1) + ". " + skipRuleDetails(incoming.get(index)), 14, INK));
        }
        new AlertDialog.Builder(this).setTitle("预览导入规则").setView(scrollDialogContent(content))
                .setNegativeButton("返回编辑", null)
                .setPositiveButton("确认替换", (d, w) -> {
                    if (saveSkipRules(incoming, "已导入 " + incoming.size() + " 条规则")) paste.dismiss();
                }).show();
    }
    void exportSkipRules(List<CustomSkipRule> rules) {
        String json = CustomSkipRule.export(rules);
        LinearLayout content = skipDialogContent();
        TextView preview = text(json, 13, INK);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        content.addView(preview);
        new AlertDialog.Builder(this).setTitle("导出规则 JSON").setView(scrollDialogContent(content))
                .setPositiveButton("复制", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard == null) { toast("剪贴板暂不可用"); return; }
                    clipboard.setPrimaryClip(ClipData.newPlainText("净屏规则 JSON", json));
                    toast("规则 JSON 已复制");
                }).setNeutralButton("分享", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "净屏规则 JSON").putExtra(Intent.EXTRA_TEXT, json);
                    try { startActivity(Intent.createChooser(send, "分享净屏规则")); }
                    catch (ActivityNotFoundException failure) { toast("此设备暂时无法分享文本"); }
                }).setNegativeButton("关闭", null).show();
    }
    void supportPage(){
        header("支持作者","让净屏继续变好","自愿赞赏，不影响任何清理功能。");
        LinearLayout code=card(body);code.addView(bold("感谢你的支持",19,INK));space(code,10);
        paragraph(code,"如果净屏帮你少看了一些广告，欢迎支持后续维护。");space(code,16);
        ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("作者提供的微信赞赏码，保存后可在微信扫一扫中选择图片");
        try(java.io.InputStream input=getAssets().open("support-code.jpg")){image.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(input,null));code.addView(image,new LinearLayout.LayoutParams(-1,-2));}catch(Exception e){paragraph(code,"图片暂时无法显示，请重新打开此页面。");}
        action(code,"保存赞赏码",true,this::saveSupportCode);space(code,12);paragraph(code,Build.VERSION.SDK_INT>=29?"保存后，在微信「扫一扫 → 相册」中选择图片识别。":"保存图片后，可通过文件管理打开，再用微信识别。");
        LinearLayout other=card(body);other.addView(bold("反馈问题，同样是在支持",17,INK));space(other,8);paragraph(other,"遇到漏跳或误判，可以先排除对应应用，再提供诊断报告帮助改进。");linkAction(other,"诊断报告",this::diagnostics);
    }
    void saveSupportCode(){
        if(Build.VERSION.SDK_INT<29){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,"净屏-赞赏码.jpg"),22);}catch(Exception e){toast("此系统没有可用的图片保存窗口");}return;}
        android.net.Uri uri=null;
        try{ContentValues values=new ContentValues();values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"净屏-赞赏码.jpg");values.put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/净屏");values.put(android.provider.MediaStore.Images.Media.IS_PENDING,1);
            uri=getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new java.io.IOException();
            try(java.io.OutputStream output=getContentResolver().openOutputStream(uri)){copySupportCode(output);}
            ContentValues ready=new ContentValues();ready.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);if(getContentResolver().update(uri,ready,null,null)!=1)throw new java.io.IOException("相册未确认保存");toast("已保存到相册，可在微信扫一扫中选择图片");
        }catch(Exception e){if(uri!=null)try{getContentResolver().delete(uri,null,null);}catch(Exception ignored){}toast("图片保存未完成，请检查可用存储空间后重试");}
    }
    void copySupportCode(java.io.OutputStream output)throws java.io.IOException{if(output==null)throw new java.io.IOException("没有可用的保存位置");try(java.io.InputStream input=getAssets().open("support-code.jpg")){byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);}output.flush();}
    Map<String, String> launcherApps() {
        Map<String, String> apps = new TreeMap<>();
        android.content.pm.PackageManager manager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo info : manager.queryIntentActivities(launcher, 0)) {
            if (info.activityInfo == null || info.activityInfo.packageName.equals(getPackageName())) continue;
            apps.put(info.activityInfo.packageName, String.valueOf(info.loadLabel(manager)));
        }
        return apps;
    }
    void chooseExclusionMode() {
        String[] modes = {CustomSkipRule.SPLASH, CustomSkipRule.POPUP};
        String[] labels = {"仅排除开屏跳过 · " + store.skipExcluded(modes[0]).size() + " 个应用",
                "仅排除弹窗处理 · " + store.skipExcluded(modes[1]).size() + " 个应用"};
        new AlertDialog.Builder(this).setTitle("选择要排除的功能")
                .setItems(labels, (d, position) -> skipExclusions(modes[position])).setNegativeButton("取消", null).show();
    }
    void skipExclusions(String mode) {
        Map<String, String> applications = launcherApps();
        Set<String> excluded = mode == null ? store.splashExcluded() : store.skipExcluded(mode);
        for (String pkg : excluded) applications.putIfAbsent(pkg, pkg);
        List<String> packages = new ArrayList<>(applications.keySet()), shown = new ArrayList<>();
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),0);EditText search=new EditText(this);search.setHint("搜索应用名称或包名");search.setSingleLine(true);content.addView(search);
        ListView list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);content.addView(list,new LinearLayout.LayoutParams(-1,dp(280)));
        Runnable refresh=()->{String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();List<String> labels=new ArrayList<>();for(String pkg:packages)if(applications.get(pkg).toLowerCase(Locale.ROOT).contains(query)||pkg.toLowerCase(Locale.ROOT).contains(query)){shown.add(pkg);labels.add(applications.get(pkg));}list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_multiple_choice,labels));for(int i=0;i<shown.size();i++)list.setItemChecked(i,excluded.contains(shown.get(i)));};
        list.setOnItemClickListener((p,v,position,id)->{String pkg=shown.get(position);if(list.isItemChecked(position))excluded.add(pkg);else excluded.remove(pkg);});
        watchSkipSearch(search, refresh);refresh.run();
        String title = mode == null ? "排除应用 · 开屏与弹窗" : CustomSkipRule.SPLASH.equals(mode) ? "排除应用 · 开屏跳过" : "排除应用 · 弹窗处理";
        new AlertDialog.Builder(this).setTitle(title).setView(content).setPositiveButton("保存", (d, w) -> {
            if (mode == null) store.setSplashExcluded(excluded); else store.setSkipExcluded(mode, excluded);
            render();
            toast("排除列表已保存");
        }).setNegativeButton("取消", null).show();
    }
    void skipHistory(boolean popup) {
        String key = popup ? "popup-history" : "splash-history";
        String title = popup ? "最近弹窗处理记录" : "最近跳过记录";
        JSONArray history;
        try { history = store.read(key); }
        catch (IllegalStateException failure) { toast(failure.getMessage()); return; }
        Map<String, String> apps = launcherApps();
        List<String> labels = new ArrayList<>(), packages = new ArrayList<>();
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null || item.optString("package").isEmpty()) continue;
            String pkg = item.optString("package"), name = apps.getOrDefault(pkg, pkg);
            String status = item.optBoolean("confirmed") ? popup ? "已确认处理" : "已确认跳过" : "点击结果未确认";
            long time = item.optLong("time");
            packages.add(pkg);
            labels.add(name + " · " + status + (time > 0 ? "\n" + format.format(new Date(time)) : ""));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(title).setNegativeButton("关闭", null);
        if (labels.isEmpty()) dialog.setMessage("暂无记录");
        else dialog.setItems(labels.toArray(new String[0]), (d, position) -> {
            String pkg = packages.get(position);
            new AlertDialog.Builder(this).setTitle("排除此应用？")
                    .setMessage(apps.getOrDefault(pkg, pkg))
                    .setPositiveButton("排除两项", (confirm, which) -> excludeFromHistory(pkg, null))
                    .setNeutralButton(popup ? "仅排除弹窗" : "仅排除开屏", (confirm, which) ->
                            excludeFromHistory(pkg, popup ? CustomSkipRule.POPUP : CustomSkipRule.SPLASH))
                    .setNegativeButton("取消", null).show();
        });
        if (history.length() > 0) dialog.setNeutralButton("清除记录", (d, w) -> {
            try {
                store.save(key, new JSONArray());
                toast("已清除记录，累计次数保留");
            } catch (IllegalStateException failure) { toast(failure.getMessage()); }
        });
        dialog.show();
    }
    void excludeFromHistory(String pkg, String mode) {
        Set<String> excluded = mode == null ? store.splashExcluded() : store.skipExcluded(mode);
        excluded.add(pkg);
        if (mode == null) store.setSplashExcluded(excluded); else store.setSkipExcluded(mode, excluded);
        render();
        toast("已排除此应用");
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if(request==41){if(result==RESULT_OK)startFilter();else toast("未开启 VPN，网络设置未改变");return;}
        if ((request != 19 && request != 20 && request != 21 && request!=22) || result != RESULT_OK || data == null || data.getData() == null) return;
        try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException();
            if(request==22){copySupportCode(out);toast("赞赏码已保存");return;}
            if (request == 21) {
                try (java.io.InputStream in = new java.io.FileInputStream(getApplicationInfo().sourceDir)) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                }
                out.flush();
                toast("安装包已保存。请用手机自带文件管理找到它并覆盖安装，无需卸载");
                return;
            }
            out.write((request == 20 ? store.exportText() : store.export()).getBytes(StandardCharsets.UTF_8));
            toast("报告已导出");
        } catch (Exception e) { toast(request == 21 ? "安装包保存未完成，请重试" : request==22?"图片保存未完成，请重试":"导出未完成，请重试"); }
    }
}
