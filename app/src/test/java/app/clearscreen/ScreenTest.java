package app.clearscreen;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.util.ReflectionHelpers;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="zh-rCN-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenTest {
    @Test public void supportIsOptionalAndExportsOriginalImage()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNotNull(find(a.getWindow().getDecorView(),"系统广告设置"));find(a.getWindow().getDecorView(),"赞赏").performClick();assertNotNull(find(a.getWindow().getDecorView(),"保存赞赏码"));
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();a.copySupportCode(output);byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(output.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b));assertEquals("a7821f2dc8ea85999ff1159a7e399a7de3d3981dfba55f6acbbc897d1aff4508",hex.toString());
        find(a.getWindow().getDecorView(),"保存赞赏码").performClick();android.content.Intent save=org.robolectric.Shadows.shadowOf(a).getNextStartedActivityForResult().intent;assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT,save.getAction());assertEquals("image/jpeg",save.getType());find(a.getWindow().getDecorView(),"返回首页").performClick();assertNotNull(find(a.getWindow().getDecorView(),"系统广告设置"));
    }
    @Test @Config(sdk=34) public void android14HomeStartsAndNavigates()throws Exception{MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNotNull(find(a.getWindow().getDecorView(),"系统广告设置"));find(a.getWindow().getDecorView(),"使用说明").performClick();assertNotNull(find(a.getWindow().getDecorView(),"数据留在你的手机"));}
    @Test public void navigationEmptyStatesAndRecordPersistence()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        find(a.getWindow().getDecorView(),"改动记录与恢复").performClick();assertNotNull(find(a.getWindow().getDecorView(),"还没有改动记录"));capture(a,"history-empty.png",822,1782);
        Store store=new Store(a);Rule r=store.allRules().get(0);String id=store.prepare(r,"个性化广告推荐",true,false);store.finish(id,"changed","测试样例：已回读确认");
        MainActivity reopened=Robolectric.buildActivity(MainActivity.class).setup().get();find(reopened.getWindow().getDecorView(),"改动记录与恢复").performClick();assertNotNull(find(reopened.getWindow().getDecorView(),"个性化广告推荐"));assertNotNull(find(reopened.getWindow().getDecorView(),"恢复原始状态"));capture(reopened,"history-fixture.png",822,1782);
        find(reopened.getWindow().getDecorView(),"返回首页").performClick();
        find(reopened.getWindow().getDecorView(),"使用说明").performClick();assertNotNull(find(reopened.getWindow().getDecorView(),"数据留在你的手机"));capture(reopened,"about.png",822,1782);
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-xhdpi") public void narrowHomeKeepsMainActionAndNavigation()throws Exception{MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();capture(a,"home-narrow.png",720,1560);assertNotNull(find(a.getWindow().getDecorView(),"开启自动跳过"));assertTrue(find(a.getWindow().getDecorView(),"使用说明").getWidth()>0);}
    @Test public void resultFilterShowsOnlyUnfinishedItems() throws Exception {
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        Store store = new Store(a);
        Rule r = store.allRules().get(0);
        org.json.JSONArray scan = new org.json.JSONArray();
        scan.put(new org.json.JSONObject().put("rule", r.json()).put("label", "已处理的项目")
                .put("status", "changed").put("message", "已回读确认"));
        scan.put(new org.json.JSONObject().put("rule", r.json()).put("label", "需处理的项目")
                .put("status", "failed").put("message", "测试：未找到开关"));
        store.save("scan", scan);
        a.tab = 1;
        a.render();
        assertNotNull(find(a.getWindow().getDecorView(), "已处理的项目"));
        assertNotNull(find(a.getWindow().getDecorView(), "需处理的项目"));
        capture(a, "results-fixture.png", 822, 1782);
        find(a.getWindow().getDecorView(), "只看未完成（1）").performClick();
        assertNull(find(a.getWindow().getDecorView(), "已处理的项目"));
        assertNotNull(find(a.getWindow().getDecorView(), "需处理的项目"));
        assertNotNull(find(a.getWindow().getDecorView(), "测试：未找到开关"));
        find(a.getWindow().getDecorView(), "显示全部结果").performClick();
        assertNotNull(find(a.getWindow().getDecorView(), "已处理的项目"));
    }
    @Test public void allUnfinishedResultsHaveCountsAndRetryButNoRedundantFilter() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        Store store = new Store(activity);
        Rule rule = store.allRules().get(0);
        store.save("scan", new org.json.JSONArray().put(new org.json.JSONObject()
                .put("rule", rule.json()).put("label", "需处理的项目").put("status", "failed")));
        activity.tab = 1;
        activity.onlyUnfinished = true;
        activity.render();
        View page = activity.getWindow().getDecorView();
        assertNotNull(find(page, "已完成 0 项 · 未完成 1 项"));
        assertNotNull(find(page, "重试未完成"));
        assertNull(find(page, "显示全部结果"));
        assertNull(find(page, "只看未完成（1）"));
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-xhdpi")
    public void namedDevicesRemainReadableOnNarrowScreen() throws Exception {
        String[][] devices = {
                {"oppo", "PKC110", "OPPO Find X8 Pro"},
                {"honor", "BKQ-AN00", "荣耀 Magic8"},
                {"honor", "PPG-AN00", "荣耀 GT Pro"},
                {"xiaomi", "M2007J1SC", "小米 10 至尊纪念版"},
                {"vivo", "V2338A", "iQOO Neo9"}
        };
        for (int i = 0; i < devices.length; i++) {
            org.robolectric.shadows.ShadowBuild.setManufacturer(devices[i][0]);
            org.robolectric.shadows.ShadowBuild.setBrand(devices[i][0]);
            org.robolectric.shadows.ShadowBuild.setModel(devices[i][1]);
            MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
            find(activity.getWindow().getDecorView(), "使用说明").performClick();
            capture(activity, "about-device-" + i + ".png", 720, 1560);
            TextView name = (TextView) find(activity.getWindow().getDecorView(), devices[i][2]);
            assertNotNull(name);
            assertTrue(name.getWidth() > 0);
            assertTrue(name.getLayout().getEllipsisCount(0) == 0);
        }
    }
    static void capture(MainActivity a,String name,int w,int h)throws Exception{View v=a.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File dir=new File(System.getProperty("clearscreen.artifacts"),"screenshots");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
    @Test public void homeRendersAndPermissionFlowWorks()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        View view=a.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));view.layout(0,0,822,1782);
        Bitmap b=Bitmap.createBitmap(822,1782,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));File dir=new File(System.getProperty("clearscreen.artifacts"),"screenshots");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"home.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        clickFeature(a, "系统广告设置");
        assertNotNull(find(a.getWindow().getDecorView(), "打开手机设置"));
        assertNotNull(find(a.getWindow().getDecorView(), "开启遇到问题"));
    }
    @Test public void popupRulesAndPermissionReturnToPopupManagement() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        clickFeature(activity, "应用内弹窗");
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
        find(activity.getWindow().getDecorView(), "管理自定义规则").performClick();
        assertEquals(MainActivity.CUSTOM_RULES, activity.tab);
        find(activity.getWindow().getDecorView(), "返回弹窗管理").performClick();
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
        activity.accessibility();
        assertNotNull(find(activity.getWindow().getDecorView(), "打开手机设置"));
        activity.onBackPressed();
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-xhdpi")
    public void narrowAuthorizationShowsInstructionsAndPrimaryActionOnFirstScreen() throws Exception {
        new Store(RuntimeEnvironment.getApplication()).noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = withLargeText(controller);
            activity.accessibility();
            capture(activity, "v10-authorization-compact-320dp-font130.png", 640, 1440);
            assertNotNull(find(activity.body, "无障碍授权"));
            assertNotNull(find(activity.body, "在无障碍设置中找到「净屏」，打开开关后返回。入口可能在「已下载的应用」或「已安装的服务」里。"));
            View settings = find(activity.body, "打开手机设置");
            assertNotNull(settings);
            Rect visible = new Rect();
            assertTrue(settings.getGlobalVisibleRect(visible));
            assertEquals(settings.getHeight(), visible.height());
            assertTrue(settings.getHeight() >= activity.dp(48));
            assertEquals(activity.networkFilterAvailable(), find(activity.body, "只使用网络过滤时，无需此权限。") != null);
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void homePauseOnlyStopsSplashSkipping() {
        Context context = RuntimeEnvironment.getApplication();
        Store store = new Store(context);
        store.noteUpdateCheck(System.currentTimeMillis());
        store.enableAdFeatures(true, true);
        CleanerService service = Robolectric.buildService(CleanerService.class).create().get();
        service.onServiceConnected();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            clickFeature(activity, "暂停开屏跳过");
            assertFalse(store.splashEnabled());
            assertTrue(store.popupEnabled());
            assertNotNull(find(activity.body, "开启自动跳过"));
        } finally {
            controller.pause().stop().destroy();
            service.onDestroy();
        }
    }
    @Test public void historyShowsAnUnsentChangeWithoutARecoveryAction() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            Rule rule = activity.store.allRules().get(0);
            String id = activity.store.prepare(rule, "个性化广告推荐", true, false);
            activity.store.finish(id, Store.NOT_APPLIED, "保存原值后目标开关发生变化，未点击");
            clickFeature(activity, "改动记录与恢复");
            View history = activity.getWindow().getDecorView();
            assertNotNull(find(history, "原值：开 · 未执行改动"));
            assertNull(find(history, "恢复原始状态"));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void appAdManagementReturnsToItsOriginAfterRecreation() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            clickFeature(activity, "管理开屏跳过");
            assertEquals(MainActivity.SKIP_MANAGEMENT, activity.tab);
            clickFeature(activity, "管理应用与排除项");
            assertEquals(MainActivity.APP_ADS, activity.tab);

            controller.recreate();
            MainActivity recreated = controller.get();
            assertEquals(MainActivity.APP_ADS, recreated.tab);
            assertNotNull(find(recreated.getWindow().getDecorView(), "返回开屏管理"));
            recreated.onBackPressed();
            assertEquals(MainActivity.SKIP_MANAGEMENT, recreated.tab);

            recreated.onBackPressed();
            clickFeature(recreated, "应用广告管理");
            assertEquals(MainActivity.APP_ADS, recreated.tab);
            clickFeature(recreated, "返回首页");
            assertEquals(0, recreated.tab);
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test public void appCatalogKeepsExpandedScrollPositionAfterRecreation() throws Exception {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            clickFeature(activity, "应用广告管理");
            int entries = new JSONArray(activity.store.asset("app-ad-coverage.json")).length();
            String catalogTitle = activity.getString(R.string.app_coverage_show_catalog, entries);
            Switch catalog = (Switch) find(activity.getWindow().getDecorView(), catalogTitle);
            assertNotNull(catalog);
            catalog.performClick();
            assertTrue(catalog.isChecked());
            layoutAppList(activity.pageScroll);
            int firstPageBottom = activity.body.getHeight() - activity.pageScroll.getHeight();
            clickFeature(activity, "查看更多");
            layoutAppList(activity.pageScroll);
            activity.pageScroll.scrollTo(0, firstPageBottom + 100);
            int position = activity.pageScroll.getScrollY();
            assertTrue(position > firstPageBottom);

            controller.recreate();
            MainActivity recreated = controller.get();
            assertEquals(MainActivity.APP_ADS, recreated.tab);
            Switch restoredCatalog = (Switch) find(recreated.getWindow().getDecorView(), catalogTitle);
            assertNotNull(restoredCatalog);
            assertTrue(restoredCatalog.isChecked());
            layoutAppList(recreated.pageScroll);
            assertEquals(position, recreated.pageScroll.getScrollY());
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test public void appDetailOnlyShowsItsOwnDiagnosticAndCloseKeepsIt() {
        PackageInfo installed = new PackageInfo();
        installed.packageName = "tv.danmaku.bili";
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager()).installPackage(installed);
        Bundle state = new Bundle();
        state.putInt("tab", MainActivity.APP_AD_DETAIL);
        state.putString("appAdSelected", installed.packageName);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup(state);
        String other = "com.example.other";
        SkipDiagnostics.startLocal(other, 42);
        try {
            MainActivity activity = controller.get();
            clickFeature(activity, "开屏漏跳诊断");
            AlertDialog start = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("开始并打开", start.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            start.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(SkipDiagnostics.LOCAL.targets(other));

            clickFeature(activity, "开屏漏跳诊断");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(SkipDiagnostics.LOCAL.targets(installed.packageName));

            clickFeature(activity, "开屏漏跳诊断");
            AlertDialog result = ShadowAlertDialog.getLatestAlertDialog();
            TextView message = result.findViewById(android.R.id.message);
            assertTrue(message.getText().toString().contains(installed.packageName));
            assertFalse(message.getText().toString().contains(other));
            assertEquals("关闭", result.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            result.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(SkipDiagnostics.LOCAL.targets(installed.packageName));
            assertEquals(MainActivity.APP_AD_DETAIL, activity.tab);
        } finally {
            SkipDiagnostics.LOCAL.clear();
            controller.pause().stop().destroy();
        }
    }
    @Test @Config(qualifiers="zh-rCN-w320dp-h720dp-xhdpi")
    public void narrowAppDetailPutsPauseFirstAndKeepsIndividualChoices() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo installed = new PackageInfo();
        installed.packageName = "tv.danmaku.bili";
        shadowOf(context.getPackageManager()).installPackage(installed);
        Store store = new Store(context);
        store.noteUpdateCheck(System.currentTimeMillis());
        store.setSkipExcluded(CustomSkipRule.POPUP, java.util.Set.of(installed.packageName));
        Bundle state = new Bundle();
        state.putInt("tab", MainActivity.APP_AD_DETAIL);
        state.putString("appAdSelected", installed.packageName);
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup(state);
        try {
            MainActivity activity = withLargeText(controller);
            capture(activity, "v10-app-ad-controls-320dp-font130.png", 640, 1440);
            Switch pause = (Switch) find(activity.body, "暂停开屏和弹窗");
            assertNotNull(pause);
            Rect visible = new Rect();
            assertTrue(pause.getGlobalVisibleRect(visible));
            assertEquals(pause.getHeight(), visible.height());
            assertFalse(((Switch) find(activity.body, "允许关闭广告弹窗")).isChecked());
            pause.performClick();
            assertTrue(store.splashExcluded().contains(installed.packageName));
            assertNull(find(activity.body, "允许跳过开屏广告"));
            assertNull(find(activity.body, "开启开屏跳过"));
            assertNull(find(activity.body, "连接无障碍服务"));
            capture(activity, "v10-app-ad-paused-320dp-font130.png", 640, 1440);
            Switch pausedControl = (Switch) find(activity.body, "暂停开屏和弹窗");
            assertTrue(pausedControl.isChecked());
            pausedControl.performClick();
            assertFalse(store.splashExcluded().contains(installed.packageName));
            assertFalse(((Switch) find(activity.body, "允许关闭广告弹窗")).isChecked());
            assertTrue(store.skipExcluded(CustomSkipRule.POPUP).contains(installed.packageName));
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test @Config(sdk = 34)
    public void edgeToEdgeSearchViewportFitsTheKeyboardAndRestoresSystemInsets() {
        new Store(RuntimeEnvironment.getApplication()).noteUpdateCheck(System.currentTimeMillis());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        int originalSdk = Build.VERSION.SDK_INT;
        try {
            MainActivity activity = controller.get();
            activity.showPage(MainActivity.APP_ADS);
            WindowInsets bars = new WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 32, 0, 0))
                    .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(12, 44, 16, 0))
                    .build();
            WindowInsets keyboard = new WindowInsets.Builder(bars)
                    .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 320))
                    .setVisible(WindowInsets.Type.ime(), true)
                    .build();
            Runnable layout = () -> {
                activity.shell.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
                activity.shell.layout(0, 0, 640, 1000);
            };

            activity.shell.dispatchApplyWindowInsets(keyboard);
            layout.run();
            assertEquals(24, activity.shell.getPaddingBottom());
            int originalHeight = activity.pageScroll.getHeight();
            assertTrue(originalHeight > 296);

            // Supply synthetic insets to the API 35 branch with the cached API 34 framework.
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", Build.VERSION_CODES.VANILLA_ICE_CREAM);
            activity.shell.dispatchApplyWindowInsets(keyboard);
            layout.run();
            assertEquals(12, activity.shell.getPaddingLeft());
            assertEquals(44, activity.shell.getPaddingTop());
            assertEquals(16, activity.shell.getPaddingRight());
            assertEquals(320, activity.shell.getPaddingBottom());
            assertEquals(originalHeight - (320 - 24), activity.pageScroll.getHeight());

            activity.shell.dispatchApplyWindowInsets(bars);
            layout.run();
            assertEquals(24, activity.shell.getPaddingBottom());
            assertEquals(originalHeight, activity.pageScroll.getHeight());
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION.class, "SDK_INT", originalSdk);
            controller.pause().stop().destroy();
        }
    }

    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-xhdpi")
    public void sameNameUpdateOffersAnInAppDownloadAndOptionalStar() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File artifacts = new File(System.getProperty("clearscreen.artifacts"));
        String feed = new String(Files.readAllBytes(new File(artifacts.getParentFile(), "update.json").toPath()),
                StandardCharsets.UTF_8);
        ReleaseUpdates.Info release = ReleaseUpdates.parse(feed);
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        installed.versionName = release.versionName;
        installed.setLongVersionCode(release.versionCode - 1);
        shadowOf(context.getPackageManager()).installPackage(installed);
        Store store = new Store(context);
        store.cacheRelease(release);
        store.noteUpdateCheck(System.currentTimeMillis());

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            assertEquals(release.versionCode - 1, store.packageVersion(context.getPackageName()));
            View page = activity.getWindow().getDecorView();
            String updateTitle = "净屏 " + release.versionName + " 可更新";
            if (!activity.networkFilterAvailable()) {
                assertNull(find(page, updateTitle));
                return;
            }
            assertNotNull(find(page, updateTitle));
            capture(activity, "v10-update-home.png", 720, 1560);
            clickFeature(activity, "查看更新");
            assertEquals(MainActivity.UPDATE_PAGE, activity.tab);
            View details = activity.getWindow().getDecorView();
            assertNotNull(find(details, release.notes));
            assertTrue(find(details, "下载更新").isEnabled());
            assertNull(shadowOf(activity).getNextStartedActivity());
            capture(activity, "v10-update-page.png", 720, 1560);
            clickFeature(activity, "在 GitHub 给净屏点 Star");
            Intent project = shadowOf(activity).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, project.getAction());
            assertEquals(ReleaseUpdates.repositoryUri(), project.getData());
            assertEquals(MainActivity.UPDATE_PAGE, activity.tab);
            assertTrue(find(details, "下载更新").isEnabled());

            Configuration larger = new Configuration(activity.getResources().getConfiguration());
            larger.screenWidthDp = 320;
            larger.fontScale = 1.3f;
            controller.configurationChange(larger);
            MainActivity reopened = controller.get();
            assertEquals(MainActivity.UPDATE_PAGE, reopened.tab);
            capture(reopened, "v10-update-page-320dp-font130.png", 640, 1440);
            TextView primary = (TextView) find(reopened.getWindow().getDecorView(), "下载更新");
            assertTrue(primary.getHeight() >= reopened.dp(48));
            assertEquals(0, primary.getLayout().getEllipsisCount(0));
            clickFeature(reopened, "返回首页");
            assertEquals(0, reopened.tab);
        } finally {
            controller.pause().stop().destroy();
        }
    }
    @Test public void manualUpdateResultOpensDetailsOnlyFromItsOriginalPageVisit() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Store store = new Store(context);
        store.noteUpdateCheck(System.currentTimeMillis());
        long installed = store.packageVersion(context.getPackageName());
        assertTrue(installed >= 0);
        ReleaseUpdates.Info release = ReleaseUpdates.parse(new org.json.JSONObject().put("schema", 1)
                .put("versionCode", installed + 1).put("versionName", "1.0.0").put("notes", "测试更新").toString());
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            MainActivity activity = controller.get();
            if (!activity.networkFilterAvailable()) return;
            clickFeature(activity, "使用说明");
            View oldRequestPage = activity.body;
            clickFeature(activity, "返回首页");
            clickFeature(activity, "使用说明");
            View currentPage = activity.body;
            store.cacheRelease(release);

            activity.applyUpdateCheckResult(oldRequestPage, release);
            assertSame(currentPage, activity.body);
            assertNotNull(find(currentPage, "检查更新"));

            activity.applyUpdateCheckResult(currentPage, release);
            assertEquals(MainActivity.UPDATE_PAGE, activity.tab);
            assertNotNull(find(activity.body, "下载更新"));
        } finally {
            controller.pause().stop().destroy();
        }
    }
    private static MainActivity withLargeText(ActivityController<MainActivity> controller) {
        Configuration larger = new Configuration(controller.get().getResources().getConfiguration());
        larger.fontScale = 1.3f;
        controller.configurationChange(larger);
        return controller.get();
    }

    private static void layoutAppList(ScrollView list) {
        int width = 822;
        int height = 1500;
        list.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, width, height);
    }
    static void clickFeature(MainActivity activity, String title) {
        View row = find(activity.getWindow().getDecorView(), title);
        while (row != null && !row.isClickable() && row.getParent() instanceof View) row = (View) row.getParent();
        assertNotNull(row);
        assertTrue(row.performClick());
    }
    static View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View v=find(((ViewGroup)view).getChildAt(i),text);if(v!=null)return v;}return null;}
}
