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
    @Test @Config(sdk=34) public void android14HomeStartsAndNavigates()throws Exception{MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();assertNotNull(find(a.getWindow().getDecorView(),"开始清理"));find(a.getWindow().getDecorView(),"适配范围与使用说明").performClick();assertNotNull(find(a.getWindow().getDecorView(),"数据留在你的手机"));}
    @Test public void navigationEmptyStatesAndRecordPersistence()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        find(a.getWindow().getDecorView(),"改动记录与恢复").performClick();assertNotNull(find(a.getWindow().getDecorView(),"还没有改动记录"));capture(a,"history-empty.png",822,1782);
        Store store=new Store(a);Rule r=store.allRules().get(0);String id=store.prepare(r,"个性化广告推荐",true,false);store.finish(id,"changed","测试样例：已回读确认");
        MainActivity reopened=Robolectric.buildActivity(MainActivity.class).setup().get();find(reopened.getWindow().getDecorView(),"改动记录与恢复").performClick();assertNotNull(find(reopened.getWindow().getDecorView(),"个性化广告推荐"));assertNotNull(find(reopened.getWindow().getDecorView(),"恢复原始状态"));capture(reopened,"history-fixture.png",822,1782);
        find(reopened.getWindow().getDecorView(),"返回清理").performClick();
        find(reopened.getWindow().getDecorView(),"适配范围与使用说明").performClick();assertNotNull(find(reopened.getWindow().getDecorView(),"数据留在你的手机"));capture(reopened,"about.png",822,1782);
    }
    @Test @Config(qualifiers="zh-rCN-w360dp-h780dp-xhdpi") public void narrowHomeKeepsMainActionAndNavigation()throws Exception{MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();capture(a,"home-narrow.png",720,1560);assertNotNull(find(a.getWindow().getDecorView(),"开始清理"));assertTrue(find(a.getWindow().getDecorView(),"适配范围与使用说明").getWidth()>0);}
    static void capture(MainActivity a,String name,int w,int h)throws Exception{View v=a.getWindow().getDecorView();v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File dir=new File(System.getProperty("clearscreen.artifacts"),"screenshots");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
    @Test public void homeRendersAndPermissionFlowWorks()throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).setup().get();
        View view=a.getWindow().getDecorView();view.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));view.layout(0,0,822,1782);
        Bitmap b=Bitmap.createBitmap(822,1782,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));File dir=new File(System.getProperty("clearscreen.artifacts"),"screenshots");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,"home.png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
        View start=find(view,"开始清理");assertNotNull(start);start.performClick();assertNotNull(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
    }
    static View find(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().equals(text))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View v=find(((ViewGroup)view).getChildAt(i),text);if(v!=null)return v;}return null;}
}
