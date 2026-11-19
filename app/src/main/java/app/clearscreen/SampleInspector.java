package app.clearscreen;
import android.app.AlertDialog;
import android.widget.*;

final class SampleInspector {
    static void open(MainActivity s,MissedAdCapture.Session session){
        show(s,session.sample,session.mode.key,session);
    }
    static void show(MainActivity s,RuleSample sample,String mode,MissedAdCapture.Session session){
        LinearLayout form=s.skipDialogContent();
        form.addView(s.heading("在这次广告上试规则",21,MainActivity.INK));
        s.paragraph(form,sample.pkg+"\n"+(sample.page.isEmpty()?"未获得页面类名":sample.page));
        EditText expr=s.skipRuleField(form,"目标选择器","gkd:[text=\"跳过\"]",
                sample.candidates.isEmpty()?"gkd:[text=\"跳过\"]":sample.candidates.get(0).selector());
        if(!sample.candidates.isEmpty())s.linkAction(form,"从样本选择控件",()->{
            String[] labels=new String[sample.candidates.size()];for(int i=0;i<labels.length;i++)labels[i]=sample.candidates.get(i).title();
            new AlertDialog.Builder(s).setTitle("样本中的广告控件").setItems(labels,(d,i)->expr.setText(sample.candidates.get(i).selector())).show();
        });
        TextView result=s.text("",15,MainActivity.INK);result.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(result);
        Runnable replay=()->{try{result.setText(sample.replay(expr.getText().toString().trim()));}catch(IllegalArgumentException e){result.setText(e.getMessage());}};
        replay.run();s.action(form,"重新匹配",true,replay);
        AlertDialog dialog=new AlertDialog.Builder(s).setView(s.scrollDialogContent(form)).setNegativeButton("关闭",null).create();
        s.linkAction(form,"用这个条件起草规则",()->{
            if(session!=null && MissedAdCapture.LOCAL.session(sample.pkg)!=session){dialog.dismiss();s.toast("样本已过期，请重新采集");return;}
            String rule=expr.getText().toString().trim();try{if(!RuleSelector.structured(rule))throw new IllegalArgumentException("请输入结构选择器");new RuleSelector(rule);
                AdvancedRuleEditor.fromSample(s,s.store.customSkipRules(),sample,mode,rule);dialog.dismiss();
            }catch(IllegalArgumentException e){result.setText(e.getMessage());}
        });s.linkAction(form,"导出脱敏样本",()->{
            if(session!=null && MissedAdCapture.LOCAL.session(sample.pkg)!=session){dialog.dismiss();return;}
            try{s.saveSample(sample.export().toString());}catch(org.json.JSONException e){s.toast("无法导出样本");}
        });dialog.show();
        if(session==null)return;
        android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable expire=dialog::dismiss;h.postDelayed(expire,Math.max(0,session.started+MissedAdCapture.RETAIN_MS-android.os.SystemClock.elapsedRealtime()));
        dialog.setOnDismissListener(d->h.removeCallbacks(expire));
    }
}
