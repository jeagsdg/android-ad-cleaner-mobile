package app.clearscreen;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class LocalRuleFormTest {
    private static final String APP="example.reader";
    private static View find(View v,String value,boolean hint){
        if(v instanceof TextView){
            CharSequence text=hint?((TextView)v).getHint():((TextView)v).getText();
            if(text!=null&&value.contentEquals(text))return v;
        }
        if(v instanceof ViewGroup){
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){View match=find(g.getChildAt(i),value,hint);if(match!=null)return match;}
        }
        return null;
    }
    private static View dialogView(){
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView();
    }
    @Test public void createPreviewSavePausedEnableAndRoundTripBackup(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity screen=controller.get();
            screen.addRuleForApp(APP,CustomSkipRule.POPUP,null);
            ((EditText)find(dialogView(),"照着广告填写，如 跳过",true)).setText("关闭广告");
            ((EditText)find(dialogView(),"如 广告 或 限时推广",true)).setText("广告");
            Bundle draft=screen.ruleForm.state();
            ShadowAlertDialog.getLatestAlertDialog().dismiss();
            LocalRuleForm.restore(screen,draft);
            assertEquals(APP,screen.ruleForm.state().getString("pkg"));
            assertEquals("popup",screen.ruleForm.state().getString("mode"));
            assertEquals("广告",screen.ruleForm.state().getString("prompt"));
            assertEquals("关闭广告",((EditText)find(dialogView(),"照着广告填写，如 跳过",true)).getText().toString());
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(screen.store.customSkipRules().isEmpty());
            CheckBox enable=(CheckBox)find(dialogView(),"保存后启用此规则",false);
            assertNotNull(enable);enable.setChecked(false);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(MainActivity.CUSTOM_RULES,screen.tab);
            CustomSkipRule saved=screen.store.customSkipRules().get(0);
            assertTrue(saved.paused);assertTrue(screen.store.activeRules(APP).isEmpty());
            String backup=CustomSkipRule.export(screen.store.customSkipRules());
            assertTrue(CustomSkipRule.parse(backup).get(0).paused);
            screen.store.setLocalRulePaused(saved,false);
            assertEquals("关闭广告",screen.store.activeRules(APP).get(0).label);
            List<CustomSkipRule> merged=CustomSkipRule.appendNew(screen.store.customSkipRules(),CustomSkipRule.parse(backup));
            assertEquals(1,merged.size());assertFalse(merged.get(0).paused);
            screen.store.saveCustomSkipRules(Collections.emptyList());assertTrue(screen.store.activeRules(APP).isEmpty());
        }
    }
    @Test public void errorsLeaveDraftIntactAndNeverSaveOrActivate(){
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            MainActivity screen=controller.get();
            screen.addRuleForApp(APP);
            EditText button=(EditText)find(dialogView(),"照着广告填写，如 跳过",true);button.setText("取消下载");
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(screen.store.customSkipRules().isEmpty());
            assertEquals("取消下载",button.getText().toString());
            assertNotNull(screen.ruleForm);
        }
    }
    @Test public void mergesNewRulesWithoutReplacingOlderChoicesAndRejectsDuplicateDefinitions(){
        CustomSkipRule old=new CustomSkipRule(APP,"","splash","","跳过","").withPaused(true);
        CustomSkipRule extra=new CustomSkipRule("example.news","","popup","","关闭广告","推广");
        List<CustomSkipRule> merged=CustomSkipRule.appendNew(Collections.singletonList(old),
                java.util.Arrays.asList(old.withPaused(false),extra));
        assertEquals(2,merged.size());assertTrue(merged.get(0).paused);assertEquals("example.news",merged.get(1).pkg);
        String duplicate=CustomSkipRule.export(java.util.Arrays.asList(old,old.withPaused(false)));
        assertThrows(IllegalArgumentException.class,()->CustomSkipRule.parse(duplicate));
    }
}
