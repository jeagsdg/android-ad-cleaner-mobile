package app.clearscreen;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Create a local rule from the words visible on the advertisement. */
final class LocalRuleForm {
    private final MainActivity screen;
    private final CustomSkipRule original;
    private final Map<String,String> appNames;
    private final EditText pkg,label,prompt,viewId,activity;
    private final RadioButton popup;
    private final TextView error;
    private final AlertDialog dialog;
    private final LinearLayout technical;
    private final ScrollView scroll;
    private final Button details;
    private final String originalKey;

    LocalRuleForm(MainActivity screen,CustomSkipRule original,String app,String mode,Bundle draft) {
        this.screen=screen;this.original=original;
        originalKey=original==null?"":CommunityRules.key(original);
        appNames=screen.launcherApps();
        LinearLayout form=screen.skipDialogContent();
        form.setFocusableInTouchMode(true);form.requestFocus();
        Button pick=screen.button("",false,()->{});
        form.addView(pick);

        RadioGroup types=new RadioGroup(screen);
        boolean narrow=screen.getResources().getConfiguration().screenWidthDp<360
                ||screen.getResources().getConfiguration().fontScale>1.15f;
        types.setOrientation(narrow?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        RadioButton splash=type(types,"开屏广告");
        popup=type(types,"使用中弹窗");
        screen.space(form,8);form.addView(types);
        label=screen.skipRuleField(form,"要点哪个按钮？","照着广告填写，如 跳过",value(draft,"label",original==null?"":original.label));
        LinearLayout popupArea=screen.column();form.addView(popupArea);
        prompt=screen.skipRuleField(popupArea,"同一弹窗上的广告提示","如 广告 或 限时推广",value(draft,"prompt",original==null?"":original.context));
        types.setOnCheckedChangeListener((g,id)->popupArea.setVisibility(id==popup.getId()?View.VISIBLE:View.GONE));
        types.check(CustomSkipRule.POPUP.equals(value(draft,"mode",mode))?popup.getId():splash.getId());
        details=screen.button("更多匹配条件",false,()->{});
        details.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);
        details.setTextSize(14);
        details.setPaddingRelative(0,screen.dp(4),0,screen.dp(4));
        details.setBackground(screen.touchBackground(android.graphics.Color.TRANSPARENT,8,0x1816624c));
        form.addView(details);
        technical=screen.column();form.addView(technical);
        pkg=screen.skipRuleField(technical,"应用包名","如 com.example.app",value(draft,"pkg",app));
        viewId=screen.skipRuleField(technical,"完整控件 ID（可选）","如 com.example.app:id/skip",value(draft,"id",original==null?"":original.viewId));
        activity=screen.skipRuleField(technical,"限定页面（可选）","填写页面类名",value(draft,"activity",original==null?"":original.activity));
        limit(pkg,200);limit(label,48);limit(prompt,120);limit(viewId,250);limit(activity,200);
        toggleDetails(draft!=null?draft.getBoolean("more"):!viewId.getText().toString().isEmpty()||!activity.getText().toString().isEmpty());
        details.setOnClickListener(v->toggleDetails(technical.getVisibility()!=View.VISIBLE));
        Runnable showApp=()->{
            String selected=pkg.getText().toString().trim();
            pick.setText(selected.isEmpty()?"选择应用":"应用　"+appNames.getOrDefault(selected,selected)+"　·　更换");
        };
        screen.watchSkipSearch(pkg,showApp);showApp.run();
        pick.setOnClickListener(v->screen.chooseInstalledApp(pkg::setText,"本机没有可选应用，可在更多匹配条件中填写包名。"));
        error=screen.text("",14,0xff9d302a);error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        error.setVisibility(View.GONE);form.addView(error);
        scroll=screen.scrollDialogContent(form);
        AlertDialog.Builder builder=new AlertDialog.Builder(screen).setTitle(original==null?"新增规则":"编辑规则")
                .setView(scroll).setPositiveButton("预览规则",null).setNegativeButton("取消",null);
        if(original!=null)builder.setNeutralButton("删除",null);
        dialog=builder.create();
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->preview());
            if(original!=null)dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                List<CustomSkipRule> current=screen.store.customSkipRules();
                int i=find(current,originalKey);
                if(i>=0)screen.confirmSkipRuleDelete(current,i,dialog);else problem("规则已被移除，请返回列表。");
            });
        });
        dialog.setOnDismissListener(d->{if(screen.ruleForm==this)screen.ruleForm=null;});
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                |WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private RadioButton type(RadioGroup group,String name) {
        RadioButton b=new RadioButton(screen);b.setId(View.generateViewId());b.setText(name);
        b.setMinHeight(screen.dp(48));group.addView(b);return b;
    }
    private static String value(Bundle b,String key,String fallback){return b==null?fallback:b.getString(key,fallback);}
    private static void limit(EditText field,int length){
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(length)});
    }
    private void toggleDetails(boolean show){
        technical.setVisibility(show?View.VISIBLE:View.GONE);details.setText(show?"收起更多条件":"更多匹配条件");
    }
    void show(){dialog.show();}
    static void fromSample(MainActivity screen,RuleSample sample,String mode){
        List<RuleSample.Item> exits=new ArrayList<>();
        for(RuleSample.Item item:sample.candidates){
            String word=item.text!=null?item.text:item.desc!=null?item.desc:"";
            if(item.visible && (SplashSkipper.skipLabel(word)||word.equals("关闭")
                    ||word.equals("关闭广告")||word.equals("关闭此广告")
                    ||item.click&&item.id!=null&&item.id.matches(".*(?:skip|ad_close|close_ad).*")))exits.add(item);
        }
        if(exits.isEmpty()){screen.addRuleForApp(sample.pkg,mode,null);return;}
        String[] labels=new String[exits.size()];
        for(int i=0;i<exits.size();i++)labels[i]=exits.get(i).title();
        new AlertDialog.Builder(screen).setTitle("选择广告上的退出控件").setItems(labels,(d,i)->{
            RuleSample.Item target=exits.get(i);Bundle preset=new Bundle();
            preset.putString("label",target.text!=null?target.text:target.desc!=null?target.desc:"");
            preset.putString("id",target.id==null?"":target.id);preset.putString("activity",sample.page);
            for(RuleSample.Item item:sample.items){
                if(item==target||!item.visible||item.text==null||!SplashSkipper.adMarker(item.text))continue;
                preset.putString("prompt",item.text);break;
            }
            screen.addRuleForApp(sample.pkg,mode,preset);
        }).setNegativeButton("取消",null).show();
    }
    private void problem(String message){
        error.setText(message);error.setVisibility(View.VISIBLE);scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private static int find(List<CustomSkipRule> rules,String key){
        for(int i=0;i<rules.size();i++)if(CommunityRules.key(rules.get(i)).equals(key))return i;
        return -1;
    }
    Bundle state(){
        Bundle b=new Bundle();
        b.putString("original",originalKey);b.putString("pkg",pkg.getText().toString());b.putString("mode",popup.isChecked()?"popup":"splash");
        b.putString("label",label.getText().toString());b.putString("prompt",prompt.getText().toString());
        b.putString("id",viewId.getText().toString());b.putString("activity",activity.getText().toString());
        b.putBoolean("more",technical.getVisibility()==View.VISIBLE);return b;
    }
    static void restore(MainActivity screen,Bundle b){
        if(b==null)return;
        List<CustomSkipRule> rules;
        try{rules=screen.store.customSkipRules();}
        catch(IllegalArgumentException|IllegalStateException unavailable){screen.toast("本机规则暂时无法读取");return;}
        String key=b.getString("original","");int i=find(rules,key);
        if(!key.isEmpty()&&i<0){screen.toast("原规则已变更，请从列表重新打开");return;}
        screen.ruleForm=new LocalRuleForm(screen,i<0?null:rules.get(i),"","splash",b);
        screen.ruleForm.show();
    }
    private void preview(){
        try{
            if(pkg.getText().toString().trim().isEmpty()){problem("请先选择要处理的应用。");return;}
            CustomSkipRule r=new CustomSkipRule(pkg.getText().toString(),activity.getText().toString(),
                    popup.isChecked()?"popup":"splash",viewId.getText().toString(),label.getText().toString(),
                    popup.isChecked()?prompt.getText().toString():"");
            if(original!=null)r.scope=original.scope;
            LinearLayout review=screen.skipDialogContent();
            String app=appNames.getOrDefault(r.pkg,r.pkg);
            review.addView(screen.bold(app+" · "+(popup.isChecked()?"使用中弹窗":"开屏广告"),18,MainActivity.INK));
            screen.paragraph(review,(r.context.isEmpty()?"":"看到「"+r.context+"」时，")+"查找"+
                    (r.label.isEmpty()?"指定控件":"「"+r.label+"」")+"。"+(r.viewId.isEmpty()?"":"\n控件　"+r.viewId)
                    +(r.activity.isEmpty()?"":"\n页面　"+r.activity));
            AlertDialog confirm=new AlertDialog.Builder(screen).setTitle("预览规则").setView(screen.scrollDialogContent(review))
                    .setPositiveButton("保存",null).setNegativeButton("返回修改",null).create();
            android.widget.CheckBox enabled=new android.widget.CheckBox(screen);
            enabled.setText("保存后启用此规则");enabled.setChecked(original==null||!original.paused);
            review.addView(enabled);
            boolean global=popup.isChecked()?screen.store.popupEnabled():screen.store.splashEnabled();
            if(!global||screen.store.splashExcluded().contains(r.pkg)||screen.store.skipExcluded(r.mode).contains(r.pkg))
                screen.paragraph(review,"此场景目前已关闭或已排除。保存后仍会遵循你的处理设置。");
            screen.linkAction(review,"在目标应用试运行",()->screen.beginRulePreview(Collections.singletonList(r)));
            screen.linkAction(review,"查看本条试运行结果",()->{
                List<CustomSkipRule> pending=RulePreview.LOCAL.rulesFor(r.pkg);
                if(pending.size()==1&&CommunityRules.key(r).equals(CommunityRules.key(pending.get(0))))screen.showRulePreview();
                else screen.toast("这条规则还没有试运行结果");
            });
            confirm.setOnShowListener(d->confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{
                    List<CustomSkipRule> next=new ArrayList<>(screen.store.customSkipRules());
                    int i=find(next,originalKey);
                    if(original!=null&&i<0)throw new IllegalArgumentException("原规则已变更，请返回列表。");
                    CustomSkipRule updated=r.withPaused(!enabled.isChecked());
                    if(original==null)next.add(updated);else next.set(i,updated);
                    screen.store.saveCustomSkipRules(next);
                    List<CustomSkipRule> previewed=RulePreview.LOCAL.rulesFor(r.pkg);
                    if(previewed.size()==1&&CommunityRules.key(r).equals(CommunityRules.key(previewed.get(0))))
                        RulePreview.LOCAL.clear();
                    confirm.dismiss();dialog.dismiss();
                    if(screen.tab==MainActivity.CUSTOM_RULES)screen.render();else screen.openCustomRules();
                    View saved=screen.body.findViewWithTag("local-rule:"+CommunityRules.key(updated));
                    if(saved!=null)screen.pageScroll.post(()->screen.pageScroll.smoothScrollTo(0,saved.getTop()));
                    screen.toast(enabled.isChecked()?"规则已保存":"规则已保存，当前已暂停");
                }catch(IllegalArgumentException|IllegalStateException invalid){confirm.dismiss();problem(invalid.getMessage());}
            }));
            confirm.show();
        }catch(IllegalArgumentException invalid){problem(invalid.getMessage());}
    }
}
