package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.View;
import android.view.accessibility.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAccessibilityNodeInfo;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UiDriverTest {
    public static class TestService extends AccessibilityService {volatile AccessibilityNodeInfo root;Runnable beforeRead;java.util.function.Consumer<android.content.Intent> launch;public AccessibilityNodeInfo getRootInActiveWindow(){if(beforeRead!=null)beforeRead.run();return root;}public void onAccessibilityEvent(AccessibilityEvent e){}public void onInterrupt(){}@Override public void startActivity(android.content.Intent intent){if(launch!=null)launch.accept(intent);else super.startActivity(intent);} }
    TestService service;Rule rule;UiDriver driver;
    @Before public void setup()throws Exception{service=Robolectric.buildService(TestService.class).create().get();rule=new Rule(new JSONObject("{\"id\":\"fixture\",\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"]}"));service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));driver=new UiDriver(service,()->false);driver.current(rule);}
    AccessibilityNodeInfo node(String label,String cls,Rect b){AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain();n.setPackageName("com.android.settings");n.setText(label);n.setClassName(cls);n.setVisibleToUser(true);n.setEnabled(true);n.setBoundsInScreen(b);return n;}
    AccessibilityNodeInfo row(String label,int y,boolean checked){AccessibilityNodeInfo row=node("","android.widget.LinearLayout",new Rect(0,y,800,y+100));AccessibilityNodeInfo title=node(label,"android.widget.TextView",new Rect(20,y+20,500,y+80));AccessibilityNodeInfo toggle=node("","android.widget.Switch",new Rect(600,y+20,740,y+80));toggle.setCheckable(true);toggle.setClickable(true);toggle.setChecked(checked);shadowOf(row).addChild(title);shadowOf(row).addChild(toggle);shadowOf(service.root).addChild(row);return toggle;}
    @Test public void selectsOnlyUniqueNamedSwitch()throws Exception{row("自动更新",100,true);AccessibilityNodeInfo target=row("个性化推荐",220,true);UiDriver.Found f=driver.find("个性化推荐");assertNotNull(f);assertEquals(target,f.node);assertTrue(f.checked);assertNull(driver.find("不存在"));}
    @Test public void duplicateLabelsStopInsteadOfGuessing()throws Exception{row("个性化推荐",100,true);row("个性化推荐",220,true);try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("不唯一"));}}
    @Test public void missingChildCannotMakeAnotherToggleLookUnique() throws Exception {
        String label = "个性化推荐";
        AccessibilityNodeInfo duplicate = row(label, 100, true);
        AccessibilityNodeInfo original = row(label, 220, false);
        java.util.List<AccessibilityNodeInfo> children = ReflectionHelpers.getField(shadowOf(service.root), "children");
        AccessibilityNodeInfo originalRow = children.set(1, null);
        assertEquals(2, service.root.getChildCount());
        assertNull(service.root.getChild(1));

        UiDriver.Halt failure = assertThrows(UiDriver.Halt.class, () -> driver.findExact(label));
        assertEquals("tree_incomplete", failure.reason);
        assertTrue(failure.retryable);
        assertTrue(duplicate.isChecked());
        assertFalse(original.isChecked());
        assertTrue(shadowOf(duplicate).getPerformedActions().isEmpty());
        assertTrue(shadowOf(original).getPerformedActions().isEmpty());

        children.set(1, originalRow);
        assertEquals("ambiguous", assertThrows(UiDriver.Halt.class, () -> driver.findExact(label)).reason);
    }
    @Test public void disabledSwitchCannotBeChanged()throws Exception{row("个性化推荐",100,true).setEnabled(false);try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("不可操作"));}}
    @Test public void clippedSwitchIsReadAgainAfterLayoutSettles()throws Exception{
        AccessibilityNodeInfo target=row("个性化推荐",100,true);
        target.setBoundsInScreen(new Rect(600,120,860,180));
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        service.beforeRead=()->{if(reads.incrementAndGet()==2)target.setBoundsInScreen(new Rect(600,120,740,180));};
        assertEquals(target,driver.find("个性化推荐").node);
        service.beforeRead=null;
        assertTrue(shadowOf(target).getPerformedActions().isEmpty());
        target.setBoundsInScreen(new Rect(600,120,860,180));
        try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.retryable);}
    }
    @Test public void otherForegroundAppStops()throws Exception{service.root.setPackageName("com.example.chat");try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("前台"));}}
    @Test public void cancellationPreventsAction()throws Exception{driver=new UiDriver(service,()->true);try{driver.current(rule);fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("停止"));}}
    @Test public void clickIsVerifiedAndRepeatedRunDoesNotToggleBack()throws Exception{
        AccessibilityNodeInfo target=row("个性化推荐",100,true);
        shadowOf(target).setOnPerformActionListener((action,args)->{target.setChecked(!target.isChecked());return true;});
        UiDriver.Found observed=driver.find("个性化推荐");
        assertTrue(driver.set(observed,false,()->assertTrue(target.isChecked())));
        assertFalse(target.isChecked());
        int count=shadowOf(target).getPerformedActions().size();
        assertTrue(driver.set(observed,false,()->fail("no write should be recorded")));
        assertFalse(target.isChecked());
        assertEquals(count,shadowOf(target).getPerformedActions().size());
    }
    @Test public void ignoredClickCannotReportSuccess()throws Exception{AccessibilityNodeInfo target=row("个性化推荐",100,true);shadowOf(target).setOnPerformActionListener((action,args)->true);assertFalse(driver.set(driver.find("个性化推荐"),false,()->{}));assertTrue(target.isChecked());}
    @Test public void restorationWritesSavedOriginalValue()throws Exception{AccessibilityNodeInfo target=row("个性化推荐",100,false);shadowOf(target).setOnPerformActionListener((action,args)->{target.setChecked(true);return true;});assertTrue(driver.set(driver.findExact("个性化推荐"),true,()->{}));assertTrue(target.isChecked());}
    @Test public void ledgerFailurePreventsAnyClick()throws Exception{
        AccessibilityNodeInfo target=row("个性化推荐",100,true);
        try{
            driver.set(driver.find("个性化推荐"),false,()->{throw new IllegalStateException("write failed");});
            fail("expected ledger failure");
        }catch(IllegalStateException expected){assertEquals("write failed",expected.getMessage());}
        assertTrue(target.isChecked());
        assertTrue(shadowOf(target).getPerformedActions().isEmpty());
    }
    @Test public void userChangeBeforeDispatchDoesNotBecomeARestoreRecord() throws Exception {
        String label = "个性化推荐";
        AccessibilityNodeInfo target = row(label, 100, true);
        Store store = new Store(service);
        String older = store.prepare(rule, label, true, false);
        store.finish(older, "changed", "已回读确认");
        String prepared = store.prepare(rule, label, true, false);
        JSONObject checkpoint = new JSONObject().put("status", "verify").put("reason", "write_prepared")
                .put("journalId", prepared).put("before", true);
        JSONObject staleCheckpoint = new JSONObject(checkpoint.toString());
        UiDriver.Found observed = driver.find(label);
        UiDriver.Halt halt = assertThrows(UiDriver.Halt.class,
                () -> driver.set(observed, false, () -> target.setChecked(false)));
        assertTrue(halt instanceof UiDriver.BeforeToggleClick);
        assertTrue(shadowOf(target).getPerformedActions().isEmpty());

        CleanerService cleaner = Robolectric.buildService(CleanerService.class).create().get();
        ReflectionHelpers.callInstanceMethod(cleaner, "handleCleanHalt", from(Store.class, store),
                from(JSONObject.class, checkpoint), from(UiDriver.Halt.class, halt));
        assertNull(ReflectionHelpers.getField(cleaner, "stopReason"));
        assertEquals("failed", checkpoint.getString("status"));
        assertFalse(checkpoint.has("journalId"));
        assertFalse(checkpoint.has("before"));
        assertEquals(Store.NOT_APPLIED, store.read("journal").getJSONObject(1).getString("status"));
        assertEquals(1, store.restorable().size());
        assertEquals(older, store.restorable().get(0).getString("id"));

        JSONObject recovery = ReflectionHelpers.callInstanceMethod(cleaner, "journalToVerify", from(Store.class, store),
                from(Rule.class, rule), from(String.class, label), from(JSONObject.class, staleCheckpoint));
        assertNull(recovery);
        assertEquals("checking", staleCheckpoint.getString("status"));
        assertFalse(staleCheckpoint.has("journalId"));
    }
    @Test public void attemptedClickRejectionRemainsPendingReadback() throws Exception {
        String label = "个性化推荐";
        AccessibilityNodeInfo target = row(label, 100, true);
        shadowOf(target).setOnPerformActionListener((action, args) -> false);
        Store store = new Store(service);
        String id = store.prepare(rule, label, true, false);
        JSONObject checkpoint = new JSONObject().put("status", "verify").put("reason", "write_prepared")
                .put("journalId", id).put("before", true);
        UiDriver.Found observed = driver.find(label);
        UiDriver.Halt halt = assertThrows(UiDriver.Halt.class, () -> driver.set(observed, false, () -> {}));
        assertEquals("click_rejected", halt.reason);
        assertEquals(1, shadowOf(target).getPerformedActions().size());

        CleanerService cleaner = Robolectric.buildService(CleanerService.class).create().get();
        ReflectionHelpers.callInstanceMethod(cleaner, "handleCleanHalt", from(Store.class, store),
                from(JSONObject.class, checkpoint), from(UiDriver.Halt.class, halt));
        assertEquals("verify", checkpoint.getString("status"));
        assertEquals(id, checkpoint.getString("journalId"));
        assertEquals("unknown", store.restorable().get(0).getString("status"));
    }
    @Test public void resumeKeepsOriginalValueWhenAConfirmedWriteWasManuallyRestored() throws Exception {
        String label = "个性化推荐";
        AccessibilityNodeInfo target = row(label, 100, true);
        service.launch = intent -> {};
        Store store = new Store(service);
        String id = store.prepare(rule, label, true, false);
        store.finish(id, "changed", "已回读确认");
        JSONObject item = new JSONObject().put("status", "verify").put("reason", "write_prepared")
                .put("journalId", id).put("before", true);
        CleanerService cleaner = Robolectric.buildService(CleanerService.class).create().get();
        JSONObject entry = ReflectionHelpers.callInstanceMethod(cleaner, "journalToVerify", from(Store.class, store),
                from(Rule.class, rule), from(String.class, label), from(JSONObject.class, item));
        assertEquals(id, entry.getString("id"));
        boolean resolved = ReflectionHelpers.callInstanceMethod(cleaner, "reconcileWrite", from(Store.class, store),
                from(UiDriver.class, driver), from(Policy.class, store.policy()), from(JSONObject.class, entry),
                from(JSONObject.class, item), from(JSONArray.class, new JSONArray().put(item)), from(boolean.class, true));
        assertTrue(resolved);
        assertEquals("restored", item.getString("status"));
        assertEquals("restored", store.read("journal").getJSONObject(0).getString("status"));
        assertTrue(target.isChecked());
        assertTrue(shadowOf(target).getPerformedActions().isEmpty());
    }
    @Test public void settingsIconDoesNotMatchUnrelatedSettingSwitch()throws Exception{AccessibilityNodeInfo icon=node("","android.widget.ImageView",new Rect(680,30,750,100));icon.setViewIdResourceName("com.android.settings:id/settings_icon");icon.setClickable(true);shadowOf(service.root).addChild(icon);AccessibilityNodeInfo unrelated=row("设置壁纸",240,true);unrelated.setViewIdResourceName("com.android.settings:id/securitySettingSwitchLayout");shadowOf(icon).setOnPerformActionListener((action,args)->true);assertTrue(driver.tap(java.util.Collections.singletonList("设置")));assertEquals(1,shadowOf(icon).getPerformedActions().size());assertEquals(0,shadowOf(unrelated).getPerformedActions().size());}
    @Test public void appNotificationPageIsNotChannelPage()throws Exception{rule.channelId="promo";rule.channelName="营销通知";rule.channelApp="测试应用";shadowOf(service.root).addChild(node("测试应用","android.widget.TextView",new Rect(0,0,500,80)));row("营销通知",200,true);try{driver.current(rule);fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("渠道名"));}}
    @Test public void onlyExplicitMatchingDisableDialogCanBeConfirmed()throws Exception{
        AccessibilityNodeInfo title=node("确定关闭“个性化推荐”吗？","android.widget.TextView",new Rect(20,200,700,300));shadowOf(service.root).addChild(title);
        AccessibilityNodeInfo ok=node("确定","android.widget.Button",new Rect(400,400,700,500));ok.setClickable(true);shadowOf(service.root).addChild(ok);shadowOf(ok).setOnPerformActionListener((a,b)->true);
        assertFalse(driver.confirmDisable("其他开关"));assertEquals(0,shadowOf(ok).getPerformedActions().size());assertTrue(driver.confirmDisable("个性化推荐"));
        title.setText("同意新的隐私协议");assertFalse(driver.confirmDisable("个性化推荐"));assertEquals(1,shadowOf(ok).getPerformedActions().size());
    }
    @Test public void aospChannelEntityHeaderAndEnglishToggleAreRecognized()throws Exception{
        rule.channelId="promo";rule.channelName="活动优惠";rule.channelApp="测试应用";
        AccessibilityNodeInfo header=node("","android.widget.LinearLayout",new Rect(0,500,800,750));
        AccessibilityNodeInfo title=node("活动优惠","android.widget.TextView",new Rect(20,510,780,590));title.setViewIdResourceName("com.android.settings:id/entity_header_title");
        AccessibilityNodeInfo app=node("测试应用","android.widget.TextView",new Rect(20,600,780,680));app.setViewIdResourceName("com.android.settings:id/entity_header_summary");
        shadowOf(header).addChild(title);shadowOf(header).addChild(app);shadowOf(service.root).addChild(header);row("Show notifications",800,true);
        driver.current(rule);assertNotNull(driver.find("允许通知"));
        app.setText("其他应用");try{driver.current(rule);fail();}catch(UiDriver.Halt expected){assertTrue(expected.getMessage().contains("渠道名"));}
    }
    @Test @Config(sdk={24,25}) public void oldAndroidRejectsChannelActionsWithoutOpeningAppNotificationSettings() throws Exception {
        Rule channel = new Rule(new JSONObject().put("id", "promo").put("package", "test.app")
                .put("channelId", "promo").put("channelName", "活动优惠").put("channelApp", "测试应用"));
        AccessibilityNodeInfo notifications = row("允许通知", 100, true);
        service.launch = intent -> fail("No settings intent should open for a channel on Android 7");
        assertEquals("channel_unsupported", assertThrows(UiDriver.Halt.class, () -> driver.open(channel)).reason);
        assertEquals("channel_unsupported", assertThrows(UiDriver.Halt.class,
                () -> driver.openForRestore(channel, "允许通知")).reason);
        assertEquals("channel_unsupported", assertThrows(UiDriver.Halt.class, () -> driver.current(channel)).reason);
        assertTrue(shadowOf(notifications).getPerformedActions().isEmpty());

        AccessibilityNodeInfo recommendation = row("个性化推荐", 350, true);
        driver.current(rule);
        assertEquals(recommendation, driver.find("个性化推荐").node);
    }
    @Test public void failedDirectEntryFallsBackWithoutTouchingAnyToggle()throws Exception{
        row("个性化推荐",100,true);java.util.concurrent.atomic.AtomicInteger launches=new java.util.concurrent.atomic.AtomicInteger();
        service.launch=intent->{if(launches.incrementAndGet()==1)throw new SecurityException("not exported");};
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"activity\":\".Private\"},{\"activity\":\".Public\"}]}"));
        driver.open(r);assertEquals(2,launches.get());assertEquals(2,driver.attempts().length());assertEquals(".Public",driver.pinnedRule().routes.get(0).activity);assertTrue(driver.find("个性化推荐").checked);
    }
    @Test public void wrongPageMarkerUsesNextRouteBeforeAnyWrite()throws Exception{
        AccessibilityNodeInfo target=row("个性化推荐",100,true);service.launch=intent->{};
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"activity\":\".First\",\"anchors\":[\"实际不存在的标题\"]},{\"activity\":\".Second\"}]}"));
        driver.open(r);assertEquals(".Second",driver.pinnedRule().routes.get(0).activity);assertTrue(shadowOf(target).getPerformedActions().isEmpty());
    }
    @Test public void foregroundChangeStopsAllFallbacks()throws Exception{
        java.util.concurrent.atomic.AtomicInteger launches=new java.util.concurrent.atomic.AtomicInteger();service.launch=intent->{launches.incrementAndGet();service.root.setPackageName("com.example.chat");};
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"activity\":\".First\"},{\"activity\":\".Second\"}]}"));
        try{driver.open(r);fail();}catch(UiDriver.Halt e){assertTrue(e.terminal);}assertEquals(1,launches.get());
    }
    @Test public void aliasReturnsActualLabelAndAmbiguousAliasesStop()throws Exception{
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"aliases\":{\"个性化推荐\":[\"关闭个性化推荐\"]}}"));
        driver.current(r);row("关闭个性化推荐",100,false);UiDriver.Found found=driver.find("个性化推荐");assertEquals("关闭个性化推荐",found.label);assertTrue(new Store(service).policy().desired(found.label));
        row("个性化推荐",220,true);try{driver.find("个性化推荐");fail();}catch(UiDriver.Halt expected){assertTrue(expected.getMessage().contains("不唯一"));}
    }
    @Test public void restoreUsesTheRecordedLabelEvenWhenAnotherAliasIsVisible()throws Exception{
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化广告推荐\"],\"aliases\":{\"个性化广告推荐\":[\"个性化推荐\"]}}"));
        AccessibilityNodeInfo recorded=row("个性化推荐",100,false);
        AccessibilityNodeInfo other=row("个性化广告推荐",220,false);
        service.launch=intent->{};
        driver.openForRestore(r,"个性化推荐");
        assertEquals(recorded,driver.findExact("个性化推荐").node);
        assertTrue(shadowOf(other).getPerformedActions().isEmpty());
    }
    @Test public void packageTransitionIsScopedToItsStep()throws Exception{
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"path\":[{\"labels\":[\"广告设置\"],\"package\":\"com.example.id\"}]}"));
        AccessibilityNodeInfo entry=node("广告设置","android.widget.Button",new Rect(0,100,700,200));entry.setClickable(true);shadowOf(service.root).addChild(entry);
        shadowOf(entry).setOnPerformActionListener((a,b)->{service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));service.root.setPackageName("com.example.id");row("个性化推荐",200,true);return true;});service.launch=intent->{};
        driver.open(r);assertEquals("com.example.id",driver.pinnedRule().pkg);assertTrue(driver.allowed("com.example.id"));assertFalse(driver.allowed("com.android.settings"));
    }
    @Test public void manualRuleNeverLaunchesOrClicks()throws Exception{
        service.launch=intent->{throw new AssertionError("manual rule launched");};Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"manual\":true}"));
        try{driver.open(r);fail();}catch(UiDriver.Halt e){assertTrue(e.getMessage().contains("手动"));}
    }
    @Test public void disabledChildDoesNotHideOtherValidTargetsOnPage()throws Exception{
        row("摇一摇广告",100,true).setEnabled(false);row("开屏广告",220,true);service.launch=intent->{};
        Rule r=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"activity\":\".Settings\",\"targets\":[\"摇一摇广告\",\"开屏广告\"]}"));driver.open(r);assertNotNull(driver.find("开屏广告"));
    }
    @Test public void stableWaitDoesNotAcceptLoadingPageOrClickItsControls()throws Exception{
        UiDriver.PageStamp previous=new UiDriver.PageStamp(service.root);
        AccessibilityNodeInfo loading=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));
        shadowOf(loading).addChild(node("正在加载","android.widget.ProgressBar",new Rect(0,200,700,300)));
        service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));AccessibilityNodeInfo toggle=row("个性化推荐",100,true);AccessibilityNodeInfo ready=service.root;service.root=loading;
        Thread delayed=new Thread(()->{try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}service.root=ready;});
        delayed.start();long start=System.nanoTime();driver.awaitPage(previous,java.util.Collections.singletonList("个性化推荐"),2500);delayed.join();
        assertTrue(System.nanoTime()-start>=750_000_000L);assertTrue(toggle.isChecked());assertTrue(shadowOf(toggle).getPerformedActions().isEmpty());
    }
    @Test public void waitCapturesSourceBeforeItMutatesAndStopsOnUnrelatedApp()throws Exception{
        UiDriver.PageStamp previous=new UiDriver.PageStamp(service.root);service.root.setPackageName("com.example.chat");
        try{driver.awaitPage(previous,java.util.Collections.emptyList(),300);fail();}catch(UiDriver.Halt e){assertTrue(e.terminal);assertFalse(e.retryable);}
    }
    @Test public void recognizesOnlyTheKnownHonorMarketSplashAsLoading(){
        String pkg="com.hihonor.appmarket";
        service.root.setPackageName(pkg);
        AccessibilityNodeInfo skip=node("跳过 5","android.widget.TextView",new Rect(600,50,740,100));
        skip.setPackageName(pkg);
        shadowOf(service.root).addChild(skip);
        skip.setViewIdResourceName(pkg+":id/btn_skip_quick_game_center");
        assertFalse(UiDriver.loading(service.root));
        skip.setViewIdResourceName(pkg+":id/btn_skip");
        assertTrue(UiDriver.loading(service.root));
        skip.setViewIdResourceName(pkg+":id/tv_operation_skip");
        assertTrue(UiDriver.loading(service.root));
        skip.setVisibleToUser(false);
        assertFalse(UiDriver.loading(service.root));
    }
    @Test public void ambiguousOnlyPageDoesNotScheduleAutomaticRetry()throws Exception{
        row("个性化推荐",100,true);row("个性化推荐",220,true);service.launch=intent->{};
        try{driver.open(rule);fail();}catch(UiDriver.Halt e){assertFalse(e.retryable);}
    }
    @Test public void repeatedViewportDoesNotKeepScrollingWhenTheListCannotMove()throws Exception{
        row("其他开关",100,true);
        service.root.setScrollable(true);
        shadowOf(service.root).setOnPerformActionListener((action,args)->true);
        assertNull(driver.find("个性化推荐"));
        assertEquals(1,shadowOf(service.root).getPerformedActions().size());
    }
    @Test public void expiredGroupBudgetSkipsBeforeLaunchingAnotherRoute()throws Exception{
        service.launch=intent->{throw new AssertionError("expired group launched a route");};
        UiDriver bounded=new UiDriver(service,()->false,1);
        Thread.sleep(10);
        try{bounded.open(rule);fail();}
        catch(UiDriver.Halt halt){
            assertFalse(halt.terminal);
            assertFalse(halt.retryable);
            assertTrue(halt.getMessage().contains("本组检查超时"));
        }
    }
    @Test public void consentInTargetAppSkipsGroupWithoutClickingOrTryingAnotherRoute()throws Exception{
        AccessibilityNodeInfo consent=node("同意并继续","android.widget.Button",new Rect(80,600,600,700));
        consent.setClickable(true);
        java.util.concurrent.atomic.AtomicInteger launches=new java.util.concurrent.atomic.AtomicInteger();
        service.launch=intent->{launches.incrementAndGet();shadowOf(service.root).addChild(consent);};
        Rule routes=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"activity\":\".First\"},{\"activity\":\".Second\"}]}"));
        try{driver.open(routes);fail();}
        catch(UiDriver.Halt halt){
            assertFalse(halt.terminal);
            assertFalse(halt.retryable);
            assertTrue(halt.getMessage().contains("确认协议"));
        }
        assertEquals(1,launches.get());
        assertTrue(shadowOf(consent).getPerformedActions().isEmpty());
    }
    @Test public void waitsPastPriorConsentPageDuringSamePackageLaunch()throws Exception{
        AccessibilityNodeInfo consent=node("同意并继续","android.widget.Button",new Rect(80,600,600,700));
        consent.setClickable(true);
        shadowOf(service.root).addChild(consent);
        UiDriver.PageStamp previous=new UiDriver.PageStamp(service.root);
        AccessibilityNodeInfo blocked=service.root;
        service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));
        AccessibilityNodeInfo target=row("个性化推荐",100,true);
        AccessibilityNodeInfo ready=service.root;
        service.root=blocked;
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        service.beforeRead=()->{if(reads.incrementAndGet()==3)service.root=ready;};
        driver.awaitPage(previous,java.util.Collections.singletonList("个性化推荐"),2500);
        assertEquals(target,driver.find("个性化推荐").node);
        assertTrue(shadowOf(consent).getPerformedActions().isEmpty());
    }
    @Test public void explicitLauncherMayHandOffOnlyToTheDeclaredPackage()throws Exception{
        String destination="com.example.device.settings";
        android.content.pm.PackageInfo installed=new android.content.pm.PackageInfo();
        installed.packageName=destination;
        shadowOf(service.getPackageManager()).installPackage(installed);
        AccessibilityNodeInfo launcher=service.root;
        service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));
        service.root.setPackageName(destination);
        AccessibilityNodeInfo target=row("个性化推荐",100,true);
        AccessibilityNodeInfo ready=service.root;
        service.root=node("","android.widget.LinearLayout",new Rect(0,0,800,1600));
        service.root.setPackageName("app.clearscreen");
        java.util.concurrent.atomic.AtomicInteger reads=new java.util.concurrent.atomic.AtomicInteger();
        service.launch=intent->{
            service.root=launcher;
            service.beforeRead=()->{if(reads.incrementAndGet()==3)service.root=ready;};
        };
        Rule route=new Rule(new JSONObject("{\"package\":\"com.android.settings\",\"targets\":[\"个性化推荐\"],\"routes\":[{\"activity\":\".Launcher\",\"foreground\":\""+destination+"\"}]}"));
        driver.open(route);
        assertEquals(destination,driver.pinnedRule().pkg);
        assertEquals(target,driver.find("个性化推荐").node);
        assertTrue(shadowOf(target).getPerformedActions().isEmpty());
    }
}
