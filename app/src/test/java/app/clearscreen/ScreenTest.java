package app.clearscreen;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="zh-rCN-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenTest {
    @Test public void supportIsOptionalAndExportsOriginalImage()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNotNull(find(a.getWindow().getDecorView(),"系统广告设置"));find(a.getWindow().getDecorView(),"支持作者 · 自愿赞赏").performClick();assertNotNull(find(a.getWindow().getDecorView(),"保存赞赏码"));
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
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-xhdpi") public void narrowHomeKeepsMainActionAndNavigation()throws Exception{MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();capture(a,"home-narrow.png",720,1560);assertNotNull(find(a.getWindow().getDecorView(),"开启开屏跳过"));assertTrue(find(a.getWindow().getDecorView(),"使用说明").getWidth()>0);}
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
        assertNotNull(find(a.getWindow().getDecorView(), "前往系统授权"));
        assertNotNull(find(a.getWindow().getDecorView(), "开启遇到问题"));
    }
    @Test public void popupRulesAndPermissionReturnToPopupManagement() {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        clickFeature(activity, "应用内弹窗");
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
        find(activity.getWindow().getDecorView(), "管理自定义规则").performClick();
        assertEquals(MainActivity.CUSTOM_RULES, activity.tab);
        activity.onBackPressed();
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
        activity.accessibility();
        assertNotNull(find(activity.getWindow().getDecorView(), "前往系统授权"));
        activity.onBackPressed();
        assertEquals(MainActivity.POPUP_MANAGEMENT, activity.tab);
    }
    static void clickFeature(MainActivity activity, String title) {
        View row = find(activity.getWindow().getDecorView(), title);
        while (row != null && !row.isClickable() && row.getParent() instanceof View) row = (View) row.getParent();
        assertNotNull(row);
        assertTrue(row.performClick());
    }
    static View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View v=find(((ViewGroup)view).getChildAt(i),text);if(v!=null)return v;}return null;}
}
