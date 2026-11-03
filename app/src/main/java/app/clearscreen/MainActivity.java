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
    final Handler permissionHandler = new Handler(Looper.getMainLooper());
    PermissionAccess.State lastPermissionState;
    TextView filterStatus, filterCounter;
    Button filterButton;
    ScrollView pageScroll;
    int renderedTab=-1;
    final Runnable permissionRefresh = new Runnable() {
        @Override public void run() {
            PermissionAccess.State current = PermissionAccess.state(MainActivity.this);
            if (current != lastPermissionState) {
                lastPermissionState = current;
                if (tab == 0 || tab == 4) render();
            }
            refreshFilter();
            permissionHandler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new Store(this);
        tab = state != null ? state.getInt("tab") : getIntent().getIntExtra("tab", 0);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        lastPermissionState = PermissionAccess.state(this);
        if (store != null) render();
        permissionHandler.removeCallbacks(permissionRefresh);
        permissionHandler.postDelayed(permissionRefresh, 1000);
    }
    @Override protected void onPause() {
        permissionHandler.removeCallbacks(permissionRefresh);
        super.onPause();
    }
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
    void linkAction(LinearLayout parent,String label,Runnable action){
        Button link=button(label,false,action);link.setTextColor(INK);link.setTypeface(null,Typeface.NORMAL);link.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);link.setPadding(0,dp(10),0,dp(10));
        link.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14176d59),shape(Color.TRANSPARENT,10),null));
        android.graphics.drawable.Drawable chevron=new android.graphics.drawable.Drawable(){private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);public void draw(Canvas canvas){Rect bounds=getBounds();paint.setColor(MUTED);paint.setStrokeWidth(dp(1.5f));paint.setStyle(Paint.Style.STROKE);Path path=new Path();path.moveTo(bounds.left+dp(5),bounds.top+dp(3));path.lineTo(bounds.left+dp(10),bounds.top+dp(8));path.lineTo(bounds.left+dp(5),bounds.top+dp(13));canvas.drawPath(path,paint);}public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}};
        chevron.setBounds(0,0,dp(16),dp(16));link.setCompoundDrawablesRelative(null,null,chevron,null);parent.addView(link,new LinearLayout.LayoutParams(-1,-2));
        View divider=new View(this);divider.setBackgroundColor(BG);parent.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
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
        int restoreY=pageScroll!=null&&renderedTab==tab?pageScroll.getScrollY():0;
        renderedTab=tab;
        filterStatus=null;filterCounter=null;filterButton=null;
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
        pageScroll=scroll;
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        body = column();
        body.setPadding(dp(24), dp(20), dp(24), dp(20));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (tab == 0) home(); else if (tab == 1) checks(); else if (tab == 2) history();
        else if (tab == 4) permissionSetup(); else if (tab == 5) permissionHelp(); else if(tab==6) networkPage(); else if(tab==7) splashPage(); else if(tab==8) supportPage(); else about();
        if (tab != 0) shell.addView(button(tab == 5 ? "返回权限设置" : "返回清理", false,
                () -> { tab = tab == 5 ? 4 : 0; render(); }), new LinearLayout.LayoutParams(-1, -2));
        if(restoreY>0)scroll.post(()->scroll.scrollTo(0,restoreY));
    }
    @Override public void onBackPressed() {
        if (tab != 0) { tab = tab == 5 ? 4 : 0; render(); } else super.onBackPressed();
    }
    void home() {
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(bold("净屏",25,INK),new LinearLayout.LayoutParams(0,-2,1));
        TextView help=text("使用说明  ›",14,GREEN);help.setMinHeight(dp(48));help.setGravity(Gravity.CENTER);help.setOnClickListener(v->{tab=3;render();});top.addView(help);body.addView(top);
        paragraph(body,"打开应用，少等广告。");space(body,20);
        CleanerService service=CleanerService.get();boolean busy=service!=null&&service.busy;
        boolean selected=store.splashEnabled();
        LinearLayout splash=card(body);splash.setBackground(shape(INK,24));
        splash.addView(text(selected?(service==null?"等待无障碍授权":busy?"清理期间暂停识别":"开屏识别已开启"):"开屏识别未开启",12,0xffb7d9c8));space(splash,10);
        splash.addView(bold("开屏广告跳过",23,Color.WHITE));space(splash,10);
        splash.addView(text("识别明确的广告跳过按钮，\n更快进入你要用的应用。",14,0xffdcebe3));space(splash,14);
        splash.addView(text("累计确认跳过 "+store.splashCount()+" 次",13,0xffb7d9c8));
        action(splash,selected&&service==null?"继续授权":selected?"暂停开屏跳过":"开启开屏跳过",false,()->{if(store.splashEnabled()&&CleanerService.get()==null)accessibility();else toggleSplash();});
        Button manage=button("管理开屏跳过",false,()->{tab=7;render();});manage.setTextColor(0xffdcebe3);manage.setBackgroundColor(Color.TRANSPARENT);splash.addView(manage,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout network=card(body);network.addView(bold("网络广告过滤",18,INK));space(network,8);
        paragraph(network,"无需无障碍权限，尝试在广告加载前拦截。");space(network,8);
        filterStatus=text("",14,GREEN);network.addView(filterStatus);space(network,5);
        filterCounter=text("",12,MUTED);network.addView(filterCounter);
        filterButton=button("开启网络过滤",false,this::toggleFilter);LinearLayout.LayoutParams networkButtonParams=new LinearLayout.LayoutParams(-1,-2);networkButtonParams.topMargin=dp(14);network.addView(filterButton,networkButtonParams);
        linkAction(network,"管理网络过滤",()->{tab=6;render();});refreshFilter();

        LinearLayout device=card(body);device.addView(bold("系统广告设置",18,INK));space(device,8);
        device.addView(text(store.deviceName(),13,INK));space(device,4);
        paragraph(device,busy?"正在处理，请保持亮屏。":service!=null?"检查推荐与推广开关，改动可查看和恢复。":"开启无障碍后，自动检查推荐与推广开关。");
        action(device,busy?"停止当前任务":"开始清理",false,()->{CleanerService current=CleanerService.get();if(current!=null&&current.busy)current.stop();else chooseClean();});
        if(service==null)linkAction(device,"权限设置与帮助",this::accessibility);

        LinearLayout more=card(body);more.addView(bold("记录与工具",16,INK));space(more,6);
        JSONArray results=store.read("scan");
        if(results.length()>0){int changed=0,unfinished=0;for(int i=0;i<results.length();i++){JSONObject item=results.optJSONObject(i);if(item==null)continue;if(item.optString("status").equals("changed"))changed++;else if(isUnfinished(item.optString("status")))unfinished++;}
            paragraph(more,"上次清理：调整 "+changed+" 项 · "+unfinished+" 项待处理");linkAction(more,"查看清理结果",()->{onlyUnfinished=false;tab=1;render();});}
        linkAction(more,"改动记录与恢复",()->{tab=2;render();});
        linkAction(more,ChannelObserver.connected?"管理通知访问":"开启通知渠道检查",this::notificationPermission);
        linkAction(more,"诊断报告",this::diagnostics);
        TextView footer=text("支持作者  ·  自愿赞赏",13,MUTED);footer.setGravity(Gravity.CENTER);footer.setMinHeight(dp(48));footer.setOnClickListener(v->{tab=8;render();});body.addView(footer);
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
        paragraph(capability, "设置清理、开屏跳过与网络过滤可以分别使用。设置清理处理已匹配的推荐开关和明确营销通知渠道；开屏跳过只点击明确广告按钮；网络过滤拦截本地规则中的广告域名。不能保证移除所有广告，视频内嵌、同域名、缓存或应用自带加密 DNS 的广告可能保留。");
        space(capability, 10);
        paragraph(capability, "关闭个性化推荐不等于清除全部广告。原生鸿蒙不支持安装此安卓应用。");

        LinearLayout privacy = card(body);
        privacy.addView(bold("数据留在你的手机", 18, INK));
        space(privacy, 10);
        paragraph(privacy, "设置清理与开屏识别在本机执行，不保存页面截图、输入内容或页面正文。开屏跳过开启后，只在应用进入前台的短时间内读取控件。通知检查仅保存应用和渠道标识。\n\n网络过滤使用本地 VPN，仅处理系统交给它的 DNS 请求；未拦截的查询交给当前网络的 DNS，不经过净屏服务器，不解密 HTTPS。只在本机保留最近 30 个被拦截域名和统计，可清除。诊断报告不包含这些域名。卸载会删除本地记录。");
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
        tab = 4;
        render();
    }
    void permissionSetup() {
        PermissionAccess.State state = PermissionAccess.state(this);
        header("自动操作权限", state == PermissionAccess.State.READY ? "已经准备好了" : "开启后，回来就能用",
                "系统授权由你确认；每次清理仍由你发起。");
        LinearLayout status = card(body);
        status.addView(bold(PermissionAccess.title(state), 18, GREEN));
        space(status, 10);
        paragraph(status, PermissionAccess.hint(state));
        space(status, 12);
        paragraph(status, "授权后，净屏可在你启动清理时读取设置页并点击开关。若你另外开启开屏跳过，还会在应用进入前台的短时间内读取控件并尝试点击明确广告按钮。不保存或上传页面内容；你可以随时关闭功能或授权。");
        action(status, state == PermissionAccess.State.READY ? "返回开始清理" : "前往系统授权", true,
                () -> { if (PermissionAccess.state(this) == PermissionAccess.State.READY) { tab = 0; render(); }
                        else openAccessibilitySettings(); });
        if (state == PermissionAccess.State.READY) action(status, "管理系统授权", false, this::openAccessibilitySettings);
        action(status, "开启遇到问题", false, () -> { tab = 5; render(); });

        LinearLayout usage = card(body);
        usage.addView(bold("为什么需要这个权限", 17, INK));
        space(usage, 10);
        paragraph(usage, "无障碍权限具备查看和控制屏幕的能力，用于你主动发起的设置清理，以及另外开启后的开屏广告跳过。两项功能都可停止。\n\n网络过滤独立工作，不需要此权限。清理结束且不需要开屏跳过时，可在系统中关闭服务。");
        space(usage, 12);
        paragraph(usage, "进入系统页面后，找到「净屏」并开启服务。可能位于「已下载的应用」或「已安装的服务」中；返回后自动检查连接状态。");
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
        action(restricted, "打开净屏应用详情", false, () -> {
            if (!PermissionAccess.openAppInfo(this, this::startActivity))
                toast("请长按桌面的净屏图标，打开应用信息或应用详情");
        });
        LinearLayout missing = card(body);
        missing.addView(bold("没有解除入口", 18, INK));
        space(missing, 10);
        paragraph(missing, "如果权限管理灰显并提示「未请求任何权限」：新版可申请用于网络过滤状态的通知权限。申请后再进入应用详情检查权限管理入口；这是否能恢复厂商入口，仍需实机确认，不能保证解除限制。\n\n已有 ColorOS 16 用户反馈覆盖安装无效，无需反复重装。若仍无入口，可先用不依赖无障碍的网络过滤。不要卸载丢失恢复记录，也不需要关闭整机安全防护。");
        action(missing,"申请过滤状态通知",false,()->requestFilterNotifications(false));
        action(missing,"使用网络过滤",true,()->{tab=6;render();});
        action(missing, "保存当前安装包", false, this::saveInstalledApk);
        action(missing, "查看并复制诊断", false, this::diagnostics);
        LinearLayout disconnected = card(body);
        disconnected.addView(bold("开关已开，仍无法清理", 18, INK));
        space(disconnected, 10);
        paragraph(disconnected, "先返回净屏稍等系统连接。若一直显示服务未连接，可在系统中关闭净屏服务后重新开启，再返回检查。仍未恢复时再尝试重启手机。\n\n关闭服务会中断正在进行的任务；重新连接后由你决定是否继续。");
        action(disconnected, "打开无障碍设置", false, this::openAccessibilitySettings);
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
                                .setPositiveButton("查看权限帮助", (dialog,which) -> { tab=5; render(); })
                                .setNegativeButton("暂不开启", null).show();
                })
                .setNegativeButton("取消", null).show();
    }
    void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    void refreshFilter(){
        if(filterStatus!=null){FilterStore settings=new FilterStore(this);updateText(filterStatus,AdFilterService.running()?"运行中 · 本地 DNS 过滤":AdFilterService.active==null&&settings.enabled()?"服务已停止，请重新开启":settings.message());}
        if(filterCounter!=null)updateText(filterCounter,getString(R.string.dns_counter,AdFilterService.blocked(this)));
        if(filterButton!=null)updateText(filterButton,AdFilterService.active!=null?"停止网络过滤":"开启网络过滤");
    }
    void updateText(TextView view,String value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    void networkPage(){
        header("网络广告过滤","尽量拦在加载之前","无需无障碍 · 使用本地 VPN");
        LinearLayout status=card(body);filterStatus=bold("",17,GREEN);status.addView(filterStatus);space(status,10);filterCounter=text("",14,MUTED);status.addView(filterCounter);
        filterButton=button("开启网络过滤",true,this::toggleFilter);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(14);status.addView(filterButton,params);refreshFilter();
        LinearLayout recovery=card(body);recovery.addView(bold("拦截记录与放行",18,INK));space(recovery,8);
        paragraph(recovery,"先停止过滤确认是否恢复，再按需放行具体域名。放行只影响完整域名，不会自动放行全部子域名。");
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
    void requestFilterNotifications(boolean startAfter){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},startAfter?42:43);
        }else if(startAfter)prepareFilter();else {
            Intent intent=new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
            try{startActivity(intent);}catch(Exception e){toast("请在净屏应用详情管理通知");}
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==42)prepareFilter();else if(request==43)toast("现在可返回应用详情检查「权限管理」；厂商是否提供解除入口仍由系统决定");}
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
        header("开屏广告跳过","少等一下，直接进入","按控件识别，无需固定机型或屏幕坐标");
        LinearLayout state=card(body);state.addView(bold(store.splashEnabled()?"已开启 · 需保持无障碍连接":"默认关闭，由你选择开启",17,GREEN));space(state,10);
        paragraph(state,"应用进入前台后的 12 秒内，结合广告标识、跳过文字、应用专属控件和已知广告 SDK 识别，每次最多尝试一次。你开始输入、点击页面或进入授权界面后停止处理。无法确认时保留原页面。\n\n已有专属规则覆盖哔哩哔哩、知乎、华为/荣耀应用市场等候选控件；规则来源不等于这些 App 当前版本已实测。不处理任意关闭按钮，不保存页面内容。");
        action(state,store.splashEnabled()?"关闭开屏跳过":"开启开屏跳过",true,this::toggleSplash);
        if(CleanerService.get()==null)action(state,"开启无障碍服务",false,this::accessibility);
        LinearLayout options=card(body);options.addView(bold("按需排除应用",17,INK));space(options,8);paragraph(options,"累计确认跳过 "+store.splashCount()+" 次。已排除 "+store.splashExcluded().size()+" 个应用。遇到异常可排除该应用；系统更新、后台限制或按钮不可读取都可能影响效果。");action(options,"选择不处理的应用",false,this::splashExclusions);action(options,"最近跳过记录",false,this::splashHistory);
    }
    void toggleSplash(){
        if(store.splashEnabled()){store.setSplashEnabled(false);render();}
        else new AlertDialog.Builder(this).setTitle("允许识别开屏广告？").setMessage("开启后，净屏会在应用进入前台的短时间内读取页面控件，尝试点击明确的广告跳过按钮。需要无障碍权限，可随时关闭或排除某个应用。")
                .setPositiveButton("开启",(d,w)->{store.setSplashEnabled(true);if(CleanerService.get()==null)accessibility();else render();}).setNegativeButton("取消",null).show();
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
    void splashExclusions(){
        java.util.Map<String,String> applications=new java.util.TreeMap<>();
        for(android.content.pm.ResolveInfo info:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0))if(info.activityInfo!=null&&!info.activityInfo.packageName.equals(getPackageName()))applications.put(info.activityInfo.packageName,String.valueOf(info.loadLabel(getPackageManager())));
        List<String> packages=new ArrayList<>(applications.keySet()),shown=new ArrayList<>();Set<String> excluded=store.splashExcluded();
        LinearLayout content=column();content.setPadding(dp(16),0,dp(16),0);EditText search=new EditText(this);search.setHint("搜索应用名称");search.setSingleLine(true);content.addView(search);
        ListView list=new ListView(this);list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);content.addView(list,new LinearLayout.LayoutParams(-1,dp(280)));
        Runnable refresh=()->{String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);shown.clear();List<String> labels=new ArrayList<>();for(String pkg:packages)if(applications.get(pkg).toLowerCase(Locale.ROOT).contains(query)||pkg.contains(query)){shown.add(pkg);labels.add(applications.get(pkg));}list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_multiple_choice,labels));for(int i=0;i<shown.size();i++)list.setItemChecked(i,excluded.contains(shown.get(i)));};
        list.setOnItemClickListener((p,v,position,id)->{String pkg=shown.get(position);if(list.isItemChecked(position))excluded.add(pkg);else excluded.remove(pkg);});
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){refresh.run();}public void afterTextChanged(android.text.Editable e){}});refresh.run();
        new AlertDialog.Builder(this).setTitle("勾选后不处理开屏广告").setView(content).setPositiveButton("保存",(d,w)->{store.setSplashExcluded(excluded);render();toast("排除列表已保存");}).setNegativeButton("取消",null).show();
    }
    void splashHistory(){JSONArray history=store.read("splash-history");if(history.length()==0){toast("暂无跳过记录");return;}String[] labels=new String[history.length()];for(int i=0;i<labels.length;i++){JSONObject item=history.optJSONObject(i);String pkg=item.optString("package"),name=pkg;try{name=String.valueOf(getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)));}catch(Exception ignored){}labels[i]=name+" · "+(item.optBoolean("confirmed")?"已确认跳过":"点击结果未确认");}
        new AlertDialog.Builder(this).setTitle("最近记录 · 点选排除应用").setItems(labels,(d,w)->new AlertDialog.Builder(this).setTitle("不再处理此应用？").setMessage(labels[w]).setPositiveButton("排除",(x,y)->{Set<String> excluded=store.splashExcluded();excluded.add(history.optJSONObject(w).optString("package"));store.setSplashExcluded(excluded);render();}).setNegativeButton("取消",null).show()).setNeutralButton("清除记录",(d,w)->{store.save("splash-history",new JSONArray());toast("已清除记录，累计次数保留");}).setNegativeButton("关闭",null).show();
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
