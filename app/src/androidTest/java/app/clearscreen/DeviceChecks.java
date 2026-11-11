package app.clearscreen;

import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.UiAutomation;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Real Android execution against a separate disposable fixture, never OEM evidence. */
public class DeviceChecks extends Instrumentation {
    private static final String FIXTURE_PACKAGE = "app.clearscreen.testfixture";
    private static final String POPUP_PROMPT = "有新版本可用";
    private static final String POPUP_DISMISSED = "已关闭测试弹窗";
    int assertions=0;
    String suite="clean";
    void require(boolean ok,String message){assertions++;if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null)suite=args.getString("suite","clean");start();}
    void await(CleanerService service)throws Exception{
        long deadline=SystemClock.uptimeMillis()+90_000;
        while(service.busy&&SystemClock.uptimeMillis()<deadline)Thread.sleep(100);
        require(!service.busy,"task timed out");
        // Starting the results activity and drawing its window are asynchronous on Android 15.
        deadline=SystemClock.uptimeMillis()+12_000;
        while(SystemClock.uptimeMillis()<deadline){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root!=null&&getTargetContext().getPackageName().equals(String.valueOf(root.getPackageName())))return;
            Thread.sleep(100);
        }
        AccessibilityNodeInfo current = service.getRootInActiveWindow();
        require(false,"results activity did not return to the foreground; progress=" + service.progress
                + "; root=" + (current == null ? "null" : current.getPackageName() + "/" + current.getWindowId())
                + "; scan=" + new Store(getTargetContext()).read("scan"));
    }
    private void useCleaner(CleanerService service, Runnable operation) throws Exception {
        Context context = getTargetContext();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(waitUntil(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            return root != null && context.getPackageName().equals(String.valueOf(root.getPackageName()));
        }), "cleaning starts from the NetScreen window");
        operation.run();
    }
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(suite.equals("network")){NetworkChecks.run(this);result.putString("stream","PASS: "+assertions+" network assertions on Android "+Build.VERSION.RELEASE+"\n");finish(RESULT_OK,result);return;}
        android.app.UiAutomation automation=getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        // Instrumentation restarts the target process: reconnect its service only in this disposable emulator.
        shell(automation,"settings put secure enabled_accessibility_services null");
        shell(automation,"settings put secure enabled_accessibility_services app.clearscreen/.CleanerService");
        shell(automation,"settings put secure accessibility_enabled 1");
        if (suite.equals("ad-latency")) {
            String benchmark=adLatency(new Store(getTargetContext()),connectService());
            result.putString("stream",benchmark+"PASS: "+assertions+" ad-latency assertions on Android "
                    +Build.VERSION.RELEASE+"; synthetic ads only\n");
            finish(RESULT_OK,result);
            return;
        }
        if (suite.equals("keepalive")) {
            keepAliveChecks(automation, connectService());
            result.putString("stream", "PASS: " + assertions + " keepalive assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated emulator only\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("popup-generic")) {
            genericPopupChecks(new Store(getTargetContext()), connectService());
            result.putString("stream", "PASS: " + assertions + " popup-generic assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated fixture only\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("splash")) {
            splashChecks(new Store(getTargetContext()), connectService());
            result.putString("stream", "PASS: " + assertions + " splash assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated fixture only\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("feedback-privacy")) {
            feedbackPrivacyChecks(automation, new Store(getTargetContext()), connectService());
            result.putString("stream", "PASS: " + assertions + " feedback/privacy assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated fixture only\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("rule-preview")) {
            rulePreviewChecks(new Store(getTargetContext()), connectService());
            result.putString("stream", "PASS: " + assertions + " rule-preview assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated fixture only\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("release-ui")) {
            releaseUiChecks(connectService());
            result.putString("stream", "PASS: " + assertions + " release UI assertions on Android "
                    + Build.VERSION.RELEASE + "; synthetic release feed\n");
            finish(RESULT_OK, result);
            return;
        }
        if (suite.equals("popup") || suite.equals("popup-legacy")) {
            popupChecks(new Store(getTargetContext()), connectService(), suite.equals("popup-legacy"));
            result.putString("stream", "PASS: " + assertions + " " + suite + " assertions on Android "
                    + Build.VERSION.RELEASE + "; isolated fixture only\n");
            finish(RESULT_OK, result);
            return;
        }
        shell(automation,"cmd notification disallow_listener app.clearscreen/.ChannelObserver");
        shell(automation,"cmd notification allow_listener app.clearscreen/.ChannelObserver");
        shell(automation,"pm grant app.clearscreen.testfixture android.permission.POST_NOTIFICATIONS");
        CleanerService service=connectService();
        Store store=new Store(getTargetContext());store.save("scan",new JSONArray());store.save("journal",new JSONArray());store.save("channels",new JSONArray());
        Rule rule=new Rule(new JSONObject("{\"id\":\"test-only\",\"title\":\"隔离测试页面\",\"package\":\"app.clearscreen.testfixture\",\"activity\":\".FixtureActivity\",\"targets\":[\"个性化推荐\",\"限制广告跟踪\",\"应用更新通知\"]}"));
        UiDriver ui=new UiDriver(service,()->false);ui.open(rule);
        require(ui.find("个性化推荐").checked,"initial recommendation on");require(!ui.find("限制广告跟踪").checked,"initial protection off");
        useCleaner(service,()->service.clean(Collections.singletonList(rule)));await(service);
        JSONArray entries=store.read("journal");require(entries.length()==2,"only two ad-related changes recorded");
        require(entries.getJSONObject(0).getString("status").equals("changed"),"verified journal entry");
        ui.open(rule);require(!ui.find("个性化推荐").checked,"recommendation disabled after fresh launch");require(ui.find("限制广告跟踪").checked,"inverse protection enabled");require(ui.find("应用更新通知").checked,"service notification untouched");
        useCleaner(service,()->service.clean(Collections.singletonList(rule)));await(service);require(store.read("journal").length()==2,"idempotent second run");
        JSONObject firstChange=entries.getJSONObject(0), secondChange=entries.getJSONObject(1);
        useCleaner(service,()->service.restore(firstChange));await(service);ui.open(rule);require(ui.find("个性化推荐").checked,"restore original on");
        useCleaner(service,()->service.restore(secondChange));await(service);ui.open(rule);require(!ui.find("限制广告跟踪").checked,"restore original off");require(ui.find("应用更新通知").checked,"service survives restore");
        JSONObject confirmationJson=rule.json();confirmationJson.remove("routes");confirmationJson.put("activity",".ConfirmActivity");confirmationJson.put("targets",new JSONArray().put("个性化推荐"));Rule confirmation=new Rule(confirmationJson);
        useCleaner(service,()->service.clean(Collections.singletonList(confirmation)));await(service);JSONArray confirmed=store.read("journal");require(confirmed.length()==3,"confirmation change recorded");require(confirmed.getJSONObject(2).getString("status").equals("changed"),"known disable dialog confirmed and read back");
        ui.open(confirmation);require(!ui.find("个性化推荐").checked,"confirmed setting persisted");
        JSONObject confirmationChange=confirmed.getJSONObject(2);
        useCleaner(service,()->service.restore(confirmationChange));await(service);ui.open(confirmation);
        JSONObject restoredConfirmation=store.read("journal").optJSONObject(2);
        UiDriver.Found restoredControl=ui.find("个性化推荐");
        require(restoredControl!=null&&restoredControl.checked,"confirmed setting restored. Journal "
                +(restoredConfirmation==null?"missing":restoredConfirmation.optString("status")+" / "
                +restoredConfirmation.optString("message"))+". Service "+service.progress);
        ui.open(rule);require(ui.tap(Collections.singletonList("发送测试通知")),"post fixture notifications through visible button");
        long channelDeadline=System.currentTimeMillis()+10000;while(store.read("channels").length()==0&&System.currentTimeMillis()<channelDeadline)Thread.sleep(200);
        JSONArray channels=store.read("channels");require(channels.length()==1,"marketing detected, weather warning excluded");Rule channel=new Rule(channels.getJSONObject(0));require(channel.channelName.equals("活动优惠"),"correct marketing channel");
        useCleaner(service,()->service.clean(Collections.singletonList(channel)));await(service);JSONArray withChannel=store.read("journal");require(withChannel.length()==4,"channel change recorded");require(withChannel.getJSONObject(3).getString("status").equals("changed"),"channel change verified");
        JSONObject channelChange=withChannel.getJSONObject(3);
        ui.open(channel);require(!ui.find("允许通知").checked,"AOSP channel disabled");useCleaner(service,()->service.restore(channelChange));await(service);ui.open(channel);require(ui.find("允许通知").checked,"AOSP channel restored");
        require(!store.export().contains("测试通知正文不得进入净屏记录"),"notification body not persisted in export");
        Rule alternate=new Rule(new JSONObject("{\"id\":\"route-fixture\",\"title\":\"备用入口与同义名称\",\"package\":\"app.clearscreen.testfixture\",\"targets\":[\"个性化广告推荐\",\"限制广告跟踪\"],\"aliases\":{\"个性化广告推荐\":[\"个性化推荐\"]},\"routes\":[{\"activity\":\".PrivateSettingsActivity\"},{\"activity\":\".FixtureActivity\",\"anchors\":[\"不存在的页面标题\"]},{\"activity\":\".RouteActivity\",\"path\":[[\"广告设置\"]]}]}"));
        ui.open(alternate);require(ui.attempts().length()==3,"private direct entry and wrong page fallback to menu");require(ui.find("个性化广告推荐").checked,"route selection never toggles setting");
        useCleaner(service,()->service.clean(Collections.singletonList(alternate)));await(service);JSONArray routed=store.read("journal");require(routed.length()==6,"alias and inverse changes recorded through fallback");
        JSONObject pinned=routed.getJSONObject(4);Rule pinnedRule=new Rule(pinned.getJSONObject("rule"));require(pinnedRule.routes.size()==1&&pinnedRule.routes.get(0).activity.equals(".RouteActivity"),"restoration pins successful menu entry");require(pinned.getString("label").equals("个性化推荐"),"actual alias label retained");
        require(store.read("scan").getJSONObject(0).getJSONArray("attempts").length()==3,"diagnostic retains bounded route attempts");
        List<JSONObject> toRestore=Arrays.asList(routed.getJSONObject(5),pinned);
        useCleaner(service,()->service.restoreAll(toRestore));await(service);ui.open(alternate);require(ui.find("个性化广告推荐").checked,"batch restore original recommendation");require(!ui.find("限制广告跟踪").checked,"batch restore inverse original");require(ui.find("应用更新通知").checked,"batch restore preserves service notification");
        useCleaner(service,()->{service.clean(Arrays.asList(alternate,rule));service.stop();});await(service);require(store.read("journal").length()==6,"immediate stop performs no writes");
        JSONArray stopped=store.read("scan");require(stopped.length()==5,"stopped run retains all untouched targets");boolean queued=false;for(int i=0;i<stopped.length();i++){JSONObject item=stopped.getJSONObject(i);if(item.getJSONObject("rule").optString("id").equals("test-only")&&item.optString("status").equals("queued"))queued=true;}require(queued,"unvisited rule remains queued for retry");
        boolean halted=false;try{new UiDriver(service,()->true).current(rule);}catch(UiDriver.Halt expected){halted=true;}require(halted,"cancellation prevents access");
        getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
        halted=false;try{ui.current(rule);}catch(UiDriver.Halt expected){halted=true;}require(halted,"foreign foreground stops");
        Rule delayed=new Rule(new JSONObject("{\"id\":\"delayed-fixture\",\"title\":\"延迟加载\",\"package\":\"app.clearscreen.testfixture\",\"activity\":\".DelayedActivity\",\"targets\":[\"个性化推荐\"]}"));
        Rule consent=new Rule(new JSONObject("{\"id\":\"consent-fixture\",\"title\":\"需先确认协议\",\"package\":\"app.clearscreen.testfixture\",\"activity\":\".ConsentActivity\",\"targets\":[\"个性化推荐\"]}"));
        long began=SystemClock.elapsedRealtime();
        useCleaner(service,()->service.clean(Arrays.asList(consent,delayed)));
        await(service);
        long elapsed = SystemClock.elapsedRealtime() - began;
        JSONArray batch=store.read("scan");
        require(batch.length()==2,"both groups retain visible results");
        require(batch.getJSONObject(0).optString("status").equals("skipped")
                &&batch.getJSONObject(0).optString("message").contains("确认协议"),"consent was not accepted");
        require(batch.getJSONObject(1).optString("status").equals("changed"),
                "later group cleaned after consent blocker: "+batch.getJSONObject(1).optString("message"));
        require(elapsed >= 2300, "waited for delayed controls: " + elapsed + " ms");
        splashChecks(store,service);
        result.putString("stream","PASS: "+assertions+" real-device assertions; Android "+Build.VERSION.RELEASE+"; isolated fixture only\n");finish(RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL after "+assertions+" assertions: "+android.util.Log.getStackTraceString(e));finish(0,result);}}
    static final int RESULT_OK=-1;

    private CleanerService connectService() throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 15_000;
        CleanerService service = CleanerService.get();
        while (service == null && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(200);
            service = CleanerService.get();
        }
        require(service != null, "accessibility service connected");
        return service;
    }

    private void keepAliveChecks(android.app.UiAutomation automation, CleanerService service) throws Exception {
        Context context = getTargetContext();
        Store store = new Store(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        boolean wasKeepAliveEnabled = store.skipKeepAliveEnabled();
        boolean wasSplashEnabled = store.splashEnabled();
        try {
            runOnMainSync(() -> {
                store.setSkipKeepAliveEnabled(false);
                SkipKeepAliveService.sync(context);
            });
            require(waitUntil(() -> !SkipKeepAliveService.running() && keepAliveNotification(manager) == null),
                    "keepalive starts from stopped state");
            if (Build.VERSION.SDK_INT >= 33) {
                shell(automation, "pm grant app.clearscreen android.permission.POST_NOTIFICATIONS");
            }
            require(SkipKeepAliveService.notificationsAllowed(context), "notifications authorized in isolated emulator");
            MainActivity activity = (MainActivity) startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(waitUntil(() -> {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                return root != null && context.getPackageName().equals(String.valueOf(root.getPackageName()));
            }), "MainActivity is the foreground window");

            runOnMainSync(() -> {
                store.setSplashEnabled(true);
                store.setSkipKeepAliveEnabled(true);
                SkipKeepAliveService.sync(activity);
            });
            waitUntil(() -> SkipKeepAliveService.running() && keepAliveNotification(manager) != null);
            require(SkipKeepAliveService.running(), "foreground keepalive service started");
            Notification notification = keepAliveNotification(manager);
            require(notification != null && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0,
                    "Android lists the ongoing keepalive notification");

            runOnMainSync(() -> {
                store.setSkipKeepAliveEnabled(false);
                SkipKeepAliveService.sync(activity);
            });
            waitUntil(() -> !SkipKeepAliveService.running() && keepAliveNotification(manager) == null);
            require(!SkipKeepAliveService.running(), "disabling stops foreground keepalive");
            require(keepAliveNotification(manager) == null, "disabling removes the active notification");
        } finally {
            runOnMainSync(() -> {
                store.setSplashEnabled(wasSplashEnabled);
                store.setSkipKeepAliveEnabled(wasKeepAliveEnabled);
                SkipKeepAliveService.sync(context);
            });
        }
    }

    private static Notification keepAliveNotification(NotificationManager manager) {
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            Notification notification = active.getNotification();
            if ("skip-keep-alive".equals(notification.getChannelId())) return notification;
        }
        return null;
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + 6_000;
        do {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private void popupChecks(Store store, CleanerService service, boolean legacy) throws Exception {
        boolean wasSplashEnabled = store.splashEnabled();
        boolean wasPopupEnabled = store.popupEnabled();
        boolean keptDetails = store.keepsSkipDetails();
        Set<String> originalExclusions = store.splashExcluded();
        List<CustomSkipRule> originalRules = store.customSkipRules();
        long popupCount = store.popupCount();
        long splashCount = store.splashCount();
        try {
            store.setKeepSkipDetails(true);
            store.setSplashEnabled(false);
            store.setPopupEnabled(false);
            store.setSplashExcluded(Collections.emptySet());
            if (legacy) {
                popupLegacyChecks(store, service, popupCount);
                return;
            }
            store.saveCustomSkipRules(Collections.singletonList(new CustomSkipRule(FIXTURE_PACKAGE, FIXTURE_PACKAGE + ".SplashActivity",
                    CustomSkipRule.POPUP, "", "以后再说", POPUP_PROMPT)));
            verifyPopupUnchanged(store, service, "popup", POPUP_PROMPT, popupCount, "popup disabled");

            store.setPopupEnabled(true);
            store.setSplashExcluded(Collections.singleton(FIXTURE_PACKAGE));
            verifyPopupUnchanged(store, service, "popup", POPUP_PROMPT, popupCount, "application excluded");

            Context context = getTargetContext();
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(waitUntil(() -> {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                return root != null && context.getPackageName().equals(String.valueOf(root.getPackageName()));
            }), "excluded popup left before clearing the exclusion");
            Thread.sleep(500);
            store.setSplashExcluded(Collections.emptySet());
            verifyPopupUnchanged(store, service, "popup-mismatch", "有新内容可用", popupCount, "prompt mismatch");
            verifyPopupUnchanged(store, service, "popup-sensitive", POPUP_PROMPT, popupCount, "sensitive dialog");

            launchPopup("popup-late");
            require(waitForFixtureText(service, POPUP_DISMISSED, 20_000), "late popup visibly dismissed");
            long deadline = SystemClock.uptimeMillis() + 4_000;
            while (store.popupCount() == popupCount && SystemClock.uptimeMillis() < deadline) Thread.sleep(100);
            require(store.popupCount() == popupCount + 1, "only one popup dismissal confirmed");
            JSONObject latest = store.read("popup-history").optJSONObject(0);
            require(latest != null && latest.optBoolean("confirmed")
                    && FIXTURE_PACKAGE.equals(latest.optString("package")), "popup confirmation recorded for fixture");
            require(store.splashCount() == splashCount, "popup dismissal does not affect splash statistics");
        } finally {
            store.setKeepSkipDetails(keptDetails);
            store.setPopupEnabled(false);
            store.saveCustomSkipRules(originalRules);
            store.setSplashExcluded(originalExclusions);
            store.setSplashEnabled(wasSplashEnabled);
            store.setPopupEnabled(wasPopupEnabled);
        }
    }

    private void popupLegacyChecks(Store store, CleanerService service, long popupCount) throws Exception {
        String source = "{\"unite_popup_rules\":true,\"delay_popup\":1000,\"click_way_popup\":1,"
                + "\"popup_rules\":[{\"id\":\"+正在&-版本\",\"action\":\"=正在检查版本\",\"times\":0},"
                + "{\"id\":\"+发现新版本\",\"action\":\"=以后再说\"}]}";
        LegacySkipImport.Result imported = LegacySkipImport.parse(source, FIXTURE_PACKAGE, Collections.singleton(FIXTURE_PACKAGE));
        require(imported.rules.size() == 2 && imported.skipped == 0, "legacy sequence imports both steps");
        store.saveCustomSkipRules(imported.rules);
        store.setPopupEnabled(true);

        String finalPrompt = "发现新版本 2.3";
        verifyPopupUnchanged(store, service, "popup-legacy-unprimed", finalPrompt, popupCount, "legacy first condition missing");

        launchPopup("popup-legacy");
        require(waitForFixtureText(service, "正在检查版本", 5_000), "legacy observation state visible");
        Thread.sleep(500);
        require(fixtureShows(service, "正在检查版本") && store.popupCount() == popupCount, "legacy zero-count step never clicks");
        require(waitForFixtureText(service, finalPrompt, 5_000), "legacy final condition visible");
        Thread.sleep(450);
        require(fixtureShows(service, finalPrompt), "legacy configured delay leaves the dialog briefly visible");
        require(waitForFixtureText(service, "已用手势关闭测试弹窗", 5_000), "legacy sequence dismissed by gesture");
        long deadline = SystemClock.uptimeMillis() + 4_000;
        while (store.popupCount() == popupCount && SystemClock.uptimeMillis() < deadline) Thread.sleep(100);
        require(store.popupCount() == popupCount + 1, "legacy sequence confirmed only the final action");
    }

    private void genericPopupChecks(Store store, CleanerService service) throws Exception {
        List<CustomSkipRule> rules = store.customSkipRules();
        Set<String> exclusions = store.splashExcluded();
        Set<String> popupExclusions = store.skipExcluded(CustomSkipRule.POPUP);
        boolean splash = store.splashEnabled(), popup = store.popupEnabled();
        long count = store.popupCount();
        try {
            store.setSplashEnabled(false);
            store.setPopupEnabled(true);
            store.setSplashExcluded(Collections.emptySet());
            store.setSkipExcluded(CustomSkipRule.POPUP, Collections.emptySet());
            store.saveCustomSkipRules(Collections.emptyList());
            verifyPopupUnchanged(store, service, "popup-generic-ordinary", "关闭", count, "ordinary close");
            verifyPopupUnchanged(store, service, "popup-generic-reward", "观看广告领取奖励", count, "reward");
            verifyPopupUnchanged(store, service, "popup-generic-sensitive", "请阅读隐私协议", count, "sensitive");
            launchPopup("popup-generic");
            require(waitForFixtureText(service, POPUP_DISMISSED, 5_000), "explicit close dismisses generic ad");
            long until = SystemClock.uptimeMillis() + 4_000;
            while (store.popupCount() == count && SystemClock.uptimeMillis() < until) Thread.sleep(100);
            require(store.popupCount() == count + 1, "generic ad confirmed once");
        } finally {
            store.setPopupEnabled(false);
            store.saveCustomSkipRules(rules);
            store.setSkipExcluded(CustomSkipRule.POPUP, popupExclusions);
            store.setSplashExcluded(exclusions);
            store.setSplashEnabled(splash);
            store.setPopupEnabled(popup);
        }
    }

    private String adLatency(Store store,CleanerService service)throws Exception{
        final int repetitions=8;
        List<CustomSkipRule> rules=store.customSkipRules();
        Set<String> shared=store.splashExcluded();
        Set<String> splashExclusions=store.skipExcluded(CustomSkipRule.SPLASH);
        Set<String> popupExclusions=store.skipExcluded(CustomSkipRule.POPUP);
        boolean splash=store.splashEnabled(),popup=store.popupEnabled(),toast=store.skipToastEnabled();
        StringBuilder report=new StringBuilder("Synthetic device fixtures only; milliseconds from control creation to dismissal callback.\n");
        int failures=0;
        try{
            store.setSplashEnabled(true);
            store.setPopupEnabled(true);
            store.setSkipToastEnabled(false);
            store.setSplashExcluded(Collections.emptySet());
            store.setSkipExcluded(CustomSkipRule.SPLASH,Collections.emptySet());
            store.setSkipExcluded(CustomSkipRule.POPUP,Collections.emptySet());
            store.saveCustomSkipRules(Collections.emptyList());
            for(String mode:Arrays.asList("bench-splash","bench-splash-delayed","bench-popup")){
                List<Long> times=new ArrayList<>();
                for(int i=0;i<repetitions;i++){
                    getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    long ready=SystemClock.uptimeMillis()+5000;
                    boolean foreground=false;
                    while(SystemClock.uptimeMillis()<ready){
                        AccessibilityNodeInfo root=service.getRootInActiveWindow();
                        if(root!=null&&getTargetContext().getPackageName().equals(String.valueOf(root.getPackageName()))){
                            foreground=true;
                            break;
                        }
                        Thread.sleep(50);
                    }
                    require(foreground,"main window visible before "+mode);
                    Thread.sleep(450);
                    long count=mode.equals("bench-popup")?store.popupCount():store.splashCount();
                    launchPopup(mode);
                    long millis=waitForAdResult(service,6000);
                    if(millis<0){failures++;continue;}
                    times.add(millis);
                    long verify=SystemClock.uptimeMillis()+2000;
                    while((mode.equals("bench-popup")?store.popupCount():store.splashCount())==count
                            &&SystemClock.uptimeMillis()<verify)Thread.sleep(50);
                }
                Collections.sort(times);
                report.append(mode).append(' ').append(times.size()).append('/').append(repetitions).append(" closed");
                if(!times.isEmpty())report.append(" p50=").append(percentile(times,50)).append(" p95=")
                        .append(percentile(times,95)).append(" samples=").append(times);
                report.append('\n');
            }
        }finally{
            store.setSplashEnabled(false);
            store.setPopupEnabled(false);
            store.saveCustomSkipRules(rules);
            store.setSplashExcluded(shared);
            store.setSkipExcluded(CustomSkipRule.SPLASH,splashExclusions);
            store.setSkipExcluded(CustomSkipRule.POPUP,popupExclusions);
            store.setSkipToastEnabled(toast);
            store.setSplashEnabled(splash);
            store.setPopupEnabled(popup);
        }
        require(failures==0,report.toString());
        return report.toString();
    }

    private long waitForAdResult(CleanerService service,long timeout)throws InterruptedException{
        final String prefix="关闭耗时毫秒=";
        long until=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<until){
            AccessibilityNodeInfo root=service.getRootInActiveWindow();
            if(root!=null&&FIXTURE_PACKAGE.equals(String.valueOf(root.getPackageName()))){
                for(AccessibilityNodeInfo node:UiDriver.walk(root)){
                    String label=UiDriver.text(node);
                    if(node.isVisibleToUser()&&label.startsWith(prefix))return Long.parseLong(label.substring(prefix.length()));
                }
            }
            Thread.sleep(50);
        }
        return -1;
    }

    private static long percentile(List<Long> sorted,int percentage){
        return sorted.get((int)Math.ceil(sorted.size()*percentage/100.0)-1);
    }

    private void verifyPopupUnchanged(Store store, CleanerService service, String mode, String prompt,
                                      long popupCount, String scenario) throws InterruptedException {
        String history = store.read("popup-history").toString();
        launchPopup(mode);
        require(waitForFixtureText(service, prompt, 5_000), scenario + ": expected dialog visible");
        Thread.sleep(1_500);
        require(store.popupCount() == popupCount && store.read("popup-history").toString().equals(history),
                scenario + ": no dismissal attempted");
        require(waitForFixtureText(service, prompt, 2_000), scenario + ": dialog remains visible");
    }

    private void launchPopup(String mode) {
        getTargetContext().startActivity(new Intent().setClassName(FIXTURE_PACKAGE, FIXTURE_PACKAGE + ".SplashActivity")
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private boolean waitForFixtureText(CleanerService service, String text, long timeout) throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + timeout;
        do {
            if (fixtureShows(service, text)) return true;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private boolean fixtureShows(CleanerService service, String text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || !FIXTURE_PACKAGE.equals(String.valueOf(root.getPackageName()))) return false;
        for (AccessibilityNodeInfo node : UiDriver.walk(root)) {
            if (node.isVisibleToUser() && text.equals(UiDriver.text(node))) return true;
        }
        return false;
    }

    private void feedbackPrivacyChecks(UiAutomation automation, Store store, CleanerService service) throws Exception {
        Context context = getTargetContext();
        store.setSplashEnabled(true);
        store.setSplashExcluded(Collections.emptySet());
        store.setSkipExcluded(CustomSkipRule.SPLASH, Collections.emptySet());
        store.setKeepSkipDetails(false);
        store.setSkipToastEnabled(true);
        long before = store.splashCount();
        SkipDiagnostics.LOCAL.start(FIXTURE_PACKAGE, store.packageVersion(FIXTURE_PACKAGE));
        launchSplashFixture(service, "plain");
        require(waitForFixtureText(service, "跳过 5", 6_000), "ordinary skip control remains visible");
        require(waitUntil(() -> SkipDiagnostics.LOCAL.summary(true, true)
                .contains(SkipDiagnostics.Phase.AD_EVIDENCE_MISSING.label)),
                "missing ad evidence observed: " + SkipDiagnostics.LOCAL.summary(true, true));
        require(!feedbackVisible(automation) && store.splashCount() == before, "no badge or count on miss");

        launchSplashFixture(service, "ad");
        require(waitForFixtureText(service, "已进入测试内容", 7_000), "ad visibly closes");
        long until = SystemClock.uptimeMillis() + 3_000;
        boolean seen = false;
        while (!seen && SystemClock.uptimeMillis() < until) {
            seen = feedbackVisible(automation);
            if (!seen) Thread.sleep(50);
        }
        require(seen, "confirmation badge is visible");
        android.graphics.Bitmap screen = feedbackScreenshot(automation);
        if (screen != null) {
            java.io.File file = new java.io.File(context.getExternalFilesDir(null), "v09-feedback.png");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            }
            screen.recycle();
        }
        require(waitUntil(() -> store.splashCount() == before + 1), "cumulative count retained");
        require(store.read("splash-history").length() == 0, "no detailed history without opt-in");
        String report = SkipDiagnostics.LOCAL.summary(true, true);
        require(report.contains("确认跳过控件消失") && !report.contains("已进入测试内容"), "sanitized confirmed outcome");
        Thread.sleep(1_200);
        require(!feedbackVisible(automation), "badge removes itself");

        store.setSkipToastEnabled(false);
        launchSplashFixture(service, "ad");
        require(waitForFixtureText(service, "已进入测试内容", 7_000), "disabling feedback preserves skipping");
        require(waitUntil(() -> store.splashCount() == before + 2), "second skip confirmed");
        require(!feedbackVisible(automation), "user can disable the confirmation badge");
        SkipDiagnostics.LOCAL.clear();
        require(SkipDiagnostics.LOCAL.summary(true, true).isEmpty(), "diagnostic can be cleared");
        store.setSplashEnabled(false);
        store.setSkipToastEnabled(true);
    }

    private void rulePreviewChecks(Store store, CleanerService service) throws Exception {
        boolean splash = store.splashEnabled();
        Set<String> excluded = store.splashExcluded();
        Set<String> splashExcluded = store.skipExcluded(CustomSkipRule.SPLASH);
        long before = store.splashCount();
        try {
            store.setSplashEnabled(true);
            store.setSplashExcluded(Collections.emptySet());
            store.setSkipExcluded(CustomSkipRule.SPLASH, Collections.emptySet());
            CustomSkipRule draft = new CustomSkipRule(FIXTURE_PACKAGE, FIXTURE_PACKAGE + ".SplashActivity",
                    CustomSkipRule.SPLASH, "", "跳过 5", "");
            Context context = getTargetContext();
            context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(waitUntil(() -> appNode(service, "开屏广告跳过") != null), "preview starts from NetScreen");
            java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
            require(waitUntil(() -> {
                runOnMainSync(() -> started.set(service.startRulePreview(Collections.singletonList(draft))));
                return started.get();
            }), "preview starts on the service main thread after pending actions finish");
            launchSplashFixture(service, "ad");
            require(waitUntil(() -> RulePreview.LOCAL.summary(true).contains("规则匹配通过")),
                    "preview observes the actual target");
            Thread.sleep(700);
            require(fixtureShows(service, "跳过 5") && store.splashCount() == before,
                    "preview leaves matched ad visible and counters unchanged");
            runOnMainSync(RulePreview.LOCAL::clear);
            launchSplashFixture(service, "ad");
            require(waitForFixtureText(service, "已进入测试内容", 7000),
                    "normal skipping resumes after leaving preview");
            require(waitUntil(() -> store.splashCount() == before + 1), "only actual close is counted");
        } finally {
            runOnMainSync(RulePreview.LOCAL::clear);
            store.setSplashEnabled(splash);
            store.setSplashExcluded(excluded);
            store.setSkipExcluded(CustomSkipRule.SPLASH, splashExcluded);
        }
    }

    private void launchSplashFixture(CleanerService service, String mode) throws Exception {
        Context context = getTargetContext();
        context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(waitUntil(() -> {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            return root != null && context.getPackageName().equals(String.valueOf(root.getPackageName()));
        }), "net screen active before launching fixture");
        Thread.sleep(300);
        context.startActivity(new Intent().setClassName(FIXTURE_PACKAGE, FIXTURE_PACKAGE + ".SplashActivity")
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private boolean feedbackVisible(UiAutomation automation) throws Exception {
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                             "dumpsys window windows"))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int read; (read = input.read(chunk)) != -1;) bytes.write(chunk, 0, read);
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains("ClearScreenSkipFeedback");
        }
    }

    private android.graphics.Bitmap feedbackScreenshot(UiAutomation automation) {
        long deadline = SystemClock.uptimeMillis() + 700;
        while (SystemClock.uptimeMillis() < deadline) {
            android.graphics.Bitmap screen = automation.takeScreenshot();
            if (screen == null) break;
            // The fixture is pale green here. The pill's dark fill must be composited near the bottom.
            for (int y = screen.getHeight() * 84 / 100; y < screen.getHeight() * 97 / 100; y += 3) {
                for (int x = screen.getWidth() * 40 / 100; x < screen.getWidth() * 60 / 100; x += 3) {
                    int color = screen.getPixel(x, y);
                    if (android.graphics.Color.red(color) < 56 && android.graphics.Color.green(color) > 35
                            && android.graphics.Color.green(color) < 93 && android.graphics.Color.blue(color) < 75) {
                        return screen;
                    }
                }
            }
            screen.recycle();
        }
        return null;
    }

    private void releaseUiChecks(CleanerService service) throws Exception {
        Context context = getTargetContext();
        Store store = new Store(context);
        boolean full = context.getResources().getBoolean(R.bool.network_filter_available);
        require((context.checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) == full, "installed variant network permission");
        String packageName = context.getPackageName();
        SharedPreferences preferences = context.getSharedPreferences("local-ledger", Context.MODE_PRIVATE);
        String previous = preferences.getString("public-release-info", null);
        boolean hadUpdateChoice = preferences.contains("update-checks");
        boolean updateChoice = preferences.getBoolean("update-checks", false);
        long previousCheck = store.lastUpdateCheck();
        String testNotes = "只用于验证本地更新提示。";
        ReleaseUpdates.Info release = ReleaseUpdates.parse(new JSONObject().put("schema", 1)
                .put("versionCode", store.packageVersion(packageName) + 1)
                .put("versionName", "9.9.9").put("notes", testNotes).toString());
        try {
            preferences.edit().remove("update-checks").apply();
            require(store.updateChecksEnabled() == full, "automatic update default follows installed variant");
            store.noteUpdateCheck(System.currentTimeMillis()); // Keep the synthetic feed isolated from GitHub.
            store.cacheRelease(release);
            context.startActivity(new Intent(context, MainActivity.class).putExtra("tab", 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            require(waitUntil(() -> appNode(service, "管理开屏跳过") != null), "release home visible");
            require((appNode(service, "净屏 9.9.9 可更新") != null) == full, "newer feed notice only on full variant");
            if (full) {
                AccessibilityNodeInfo link = appNode(service, "查看更新");
                AccessibilityNodeInfo details = link == null ? null : SplashSkipper.clickable(link);
                require(details != null && details.performAction(AccessibilityNodeInfo.ACTION_CLICK), "tap update notice");
                require(waitUntil(() -> appNode(service, testNotes) != null), "version notes visible before download");
                require(waitUntil(() -> appNode(service, "下载更新") != null),
                        "download needs a separate action");
                AccessibilityNodeInfo later = appNode(service, "返回首页");
                require(later != null && later.performAction(AccessibilityNodeInfo.ACTION_CLICK), "dismiss version notes");
                require(waitUntil(() -> appNode(service, "管理开屏跳过") != null), "returned to home");
            }
            AccessibilityNodeInfo networkRow = findAfterScroll(service, "网络广告过滤");
            require((networkRow != null) == full, "network page only on full variant");
            if (full) {
                require(networkRow.performAction(AccessibilityNodeInfo.ACTION_CLICK), "open network feature");
                require(waitUntil(() -> appNode(service, "开启网络过滤") != null), "network controls visible");
            }
        } finally {
            SharedPreferences.Editor editor = preferences.edit().putLong("last-update-check", previousCheck);
            if (previous == null) editor.remove("public-release-info");
            else editor.putString("public-release-info", previous);
            if (hadUpdateChoice) editor.putBoolean("update-checks", updateChoice);
            else editor.remove("update-checks");
            editor.apply();
        }
    }

    private AccessibilityNodeInfo appNode(CleanerService service, String text) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || !getTargetContext().getPackageName().contentEquals(
                root.getPackageName() == null ? "" : root.getPackageName())) return null;
        for (AccessibilityNodeInfo node : UiDriver.walk(root)) {
            if (!node.isVisibleToUser()) continue;
            String label = UiDriver.text(node);
            if (text.equalsIgnoreCase(label) || label.startsWith(text + "，")) return node;
        }
        return null;
    }

    private AccessibilityNodeInfo findAfterScroll(CleanerService service, String text) throws InterruptedException {
        for (int page = 0; page < 6; page++) {
            AccessibilityNodeInfo found = appNode(service, text);
            if (found != null) return found;
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null || !getTargetContext().getPackageName().equals(String.valueOf(root.getPackageName())))
                return null;
            boolean moved = false;
            for (AccessibilityNodeInfo node : UiDriver.walk(root)) {
                if (node.isVisibleToUser() && node.isScrollable()
                        && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    moved = true;
                    break;
                }
            }
            if (!moved) break;
            Thread.sleep(180);
        }
        return null;
    }

    void splashChecks(Store store,CleanerService service)throws Exception{
        store.setSplashEnabled(true);store.setSplashExcluded(Collections.emptySet());long count=store.splashCount();
        long expected=count;
        for(String mode:new String[]{"plain","consent","editable","ad","sdk","multilingual"}){
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long until=SystemClock.uptimeMillis()+6000;
            while(SystemClock.uptimeMillis()<until){
                AccessibilityNodeInfo root=service.getRootInActiveWindow();
                if(root!=null&&getTargetContext().getPackageName().equals(String.valueOf(root.getPackageName())))break;
                Thread.sleep(100);
            }
            require(SystemClock.uptimeMillis()<until,"净屏 is visible before launching "+mode);
            Thread.sleep(500);
            getTargetContext().startActivity(new Intent().setClassName("app.clearscreen.testfixture","app.clearscreen.testfixture.SplashActivity").putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            boolean shouldDismiss=mode.equals("ad")||mode.equals("sdk")||mode.equals("multilingual");
            boolean dismissed=waitForFixtureText(service,"已进入测试内容",shouldDismiss?7000:2400);
            require(shouldDismiss==dismissed,"splash mode "+mode+" guarded correctly");
            if(shouldDismiss){
                expected++;
                long confirmation=SystemClock.uptimeMillis()+4000;
                while(store.splashCount()<expected&&SystemClock.uptimeMillis()<confirmation)Thread.sleep(100);
                require(store.splashCount()==expected,"splash confirmed before leaving "+mode);
            }
        }
        require(store.splashCount()==count+3,"only confirmed ad skips counted");
        store.setSplashExcluded(Collections.singleton("app.clearscreen.testfixture"));getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(900);
        getTargetContext().startActivity(new Intent().setClassName("app.clearscreen.testfixture","app.clearscreen.testfixture.SplashActivity").putExtra("mode","ad").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));Thread.sleep(1800);
        require(store.splashCount()==count+3,"excluded application not clicked");store.setSplashEnabled(false);store.setSplashExcluded(Collections.emptySet());
    }
    void shell(android.app.UiAutomation a,String command)throws Exception{try(ParcelFileDescriptor fd=a.executeShellCommand(command);java.io.FileInputStream in=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] b=new byte[1024];while(in.read(b)!=-1){}}}
}
