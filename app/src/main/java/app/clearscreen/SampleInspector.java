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
        RuleSample[] negative={null};
        Runnable replay=()->{try{String expression=expr.getText().toString().trim();result.setText(negative[0]==null?sample.replay(expression):sample.compare(negative[0],expression));}catch(IllegalArgumentException e){result.setText(e.getMessage());}};
        replay.run();s.action(form,"重新匹配",true,replay);
        java.util.function.Consumer<RuleSample> useNegative=other->{
            if(session!=null&&MissedAdCapture.LOCAL.session(sample.pkg)!=session)return;
            if(!sample.pkg.equals(other.pkg)){s.toast("请选择同一应用的普通页面");return;}
            negative[0]=other;replay.run();
        };
        s.linkAction(form,"选择普通页作反例",()->new AlertDialog.Builder(s).setTitle("从哪里选择普通页？")
            .setItems(new String[]{"打开样本文件","从本机样本库选择"},(d,i)->{if(i==0)s.pickComparison(useNegative);else SampleLibrary.open(s,useNegative,sample.pkg);}).show());
        AlertDialog dialog=new AlertDialog.Builder(s).setView(s.scrollDialogContent(form)).setNegativeButton("关闭",null).create();
        s.linkAction(form,"用这个条件起草规则",()->{
            if(session!=null && MissedAdCapture.LOCAL.session(sample.pkg)!=session){dialog.dismiss();s.toast("样本已过期，请重新采集");return;}
            String rule=expr.getText().toString().trim();try{if(!RuleSelector.structured(rule))throw new IllegalArgumentException("请输入结构选择器");new RuleSelector(rule);
                AdvancedRuleEditor.fromSample(s,s.store.customSkipRules(),sample,mode,rule);dialog.dismiss();
            }catch(IllegalArgumentException e){result.setText(e.getMessage());}
        });
        s.linkAction(form,"存入本机样本库",()->new AlertDialog.Builder(s).setTitle("保存这份脱敏样本？")
                .setMessage("将保存到净屏本机目录，直到你在样本库删除。不会同步或上传。").setNegativeButton("取消",null)
                .setPositiveButton("保存",(d,v)->{
                    if(session!=null&&MissedAdCapture.LOCAL.session(sample.pkg)!=session){s.toast("样本已过期");return;}
                    new Thread(()->{try{SampleLibrary.save(s,sample);s.runOnUiThread(()->s.toast("样本已保存"));}
                        catch(Exception e){s.runOnUiThread(()->s.toast("保存失败"));}},"sample-save").start();
                }).show());
        s.linkAction(form,"导出脱敏样本",()->{
            if(session!=null && MissedAdCapture.LOCAL.session(sample.pkg)!=session){dialog.dismiss();return;}
            try{s.saveSample(sample.export().toString());}catch(org.json.JSONException e){s.toast("无法导出样本");}
        });dialog.show();
        dialog.setOnDismissListener(d->s.clearComparison());
        if(session==null)return;
        android.os.Handler h=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable expire=dialog::dismiss;h.postDelayed(expire,Math.max(0,session.started+MissedAdCapture.RETAIN_MS-android.os.SystemClock.elapsedRealtime()));
        dialog.setOnDismissListener(d->{h.removeCallbacks(expire);s.clearComparison();});
    }
}
