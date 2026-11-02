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
    static final int BG=0xfff5f7f4,INK=0xff19382e,MUTED=0xff65756d,GREEN=0xff176d59,MINT=0xffe0eee6,LINE=0xffe0e6e0;
    Store store;LinearLayout shell,body;int tab=0;
    @Override public void onCreate(Bundle state){super.onCreate(state);store=new Store(this);tab=state!=null?state.getInt("tab"):getIntent().getIntExtra("tab",0);render();}
    @Override protected void onResume(){super.onResume();if(store!=null)render();}
    @Override protected void onSaveInstanceState(Bundle b){b.putInt("tab",tab);super.onSaveInstanceState(b);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);tab=intent.getIntExtra("tab",1);render();}
    int dp(float v){return (int)(getResources().getDisplayMetrics().density*v+.5f);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);t.setIncludeFontPadding(false);return t;}
    TextView bold(String value,int size,int color){TextView t=text(value,size,color);t.setTypeface(null,Typeface.BOLD);return t;}
    void space(LinearLayout p,int h){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    void paragraph(LinearLayout p,String s){p.addView(text(s,14,MUTED));}
    LinearLayout card(LinearLayout p){LinearLayout c=column();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(shape(Color.WHITE,20));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);p.addView(c,lp);return c;}
    Button button(String value,boolean primary,Runnable action){Button b=new Button(this);b.setText(value);b.setTextSize(15);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);b.setTextColor(primary?Color.WHITE:GREEN);b.setStateListAnimator(null);b.setElevation(0);b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(primary?0x28ffffff:0x1a176d59),shape(primary?GREEN:MINT,14),null));b.setMinHeight(dp(50));b.setPadding(dp(16),dp(10),dp(16),dp(10));b.setOnClickListener(v->action.run());return b;}
    void action(LinearLayout p,String label,boolean primary,Runnable r){Button b=button(label,primary,r);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);p.addView(b,lp);}
    void header(String eyebrow,String title,String sub){body.addView(bold(eyebrow,12,GREEN));space(body,10);body.addView(bold(title,29,INK));space(body,10);paragraph(body,sub);space(body,24);}
    void render(){
        shell=column();shell.setBackgroundColor(BG);shell.setFitsSystemWindows(true);setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=column();body.setPadding(dp(24),dp(28),dp(24),dp(20));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(tab==0)home();else if(tab==1)checks();else if(tab==2)history();else about();
        if(tab!=0){Button back=button("返回清理",false,()->{tab=0;render();});shell.addView(back,new LinearLayout.LayoutParams(-1,dp(52)));}
    }
    @Override public void onBackPressed(){if(tab!=0){tab=0;render();}else super.onBackPressed();}
    void home(){
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(bold("净屏",24,INK),new LinearLayout.LayoutParams(0,-2,1));TextView badge=text("离线工具 · 预览版",11,GREEN);badge.setPadding(dp(10),dp(6),dp(10),dp(6));badge.setBackground(shape(MINT,12));top.addView(badge);body.addView(top);space(body,28);
        LinearLayout hero=column();hero.setPadding(dp(24),dp(24),dp(24),dp(24));hero.setBackground(shape(MINT,26));body.addView(hero);
        MarkView icon=new MarkView(this);hero.addView(icon,new LinearLayout.LayoutParams(dp(46),dp(50)));space(hero,20);
        hero.addView(bold("让手机，清静些。",28,INK));space(hero,12);hero.addView(text("关闭系统推荐与营销通知开关。\n无需电脑，无需 Root。",15,GREEN));
        CleanerService service=CleanerService.get();boolean busy=service!=null&&service.busy;
        action(hero,busy?"停止当前任务":"开始清理",true,()->{CleanerService current=CleanerService.get();if(current!=null&&current.busy)current.stop();else chooseClean();});space(hero,10);
        TextView hint=text(service==null?"首次使用需开启自动操作权限":"自动操作已就绪 · 运行时请保持亮屏",12,GREEN);hint.setGravity(Gravity.CENTER);hero.addView(hint);space(body,22);
        JSONArray results=store.read("scan");if(results.length()>0){int changed=0,already=0,skipped=0;for(int i=0;i<results.length();i++){String status=results.optJSONObject(i).optString("status");if(status.equals("changed"))changed++;else if(status.equals("already"))already++;else skipped++;}
            LinearLayout c=card(body);c.addView(bold(store.lastRun().isEmpty()?"最近一次结果":store.lastRun(),17,INK));space(c,10);c.addView(text(changed+" 项已调整  ·  "+already+" 项已关闭或受保护",14,GREEN));space(c,6);paragraph(c,skipped+" 项未完成或跳过，查看详情了解原因。");action(c,"查看清理结果",false,()->{tab=1;render();});
        }else{paragraph(body,"先核对开关，再保存原值、调整并确认结果。遇到不明确的页面会跳过。");space(body,18);}
        LinearLayout more=card(body);more.addView(bold("也清理营销通知",17,INK));space(more,8);paragraph(more,"可选。按已出现的通知渠道查找营销推送，保留消息、订单和天气预警。");action(more,ChannelObserver.connected?"通知渠道检查已开启":"开启通知渠道检查",false,this::notificationPermission);
        action(body,"改动记录与恢复",false,()->{tab=2;render();});
        TextView detail=text("适配范围与使用说明",13,MUTED);detail.setGravity(Gravity.CENTER);detail.setMinHeight(dp(52));detail.setOnClickListener(v->{tab=3;render();});body.addView(detail);
    }
    void chooseClean(){
        CleanerService service=CleanerService.get();if(service==null){accessibility();return;}if(service.busy){toast("已有任务正在执行");return;}
        List<Rule> rules=store.available();JSONArray channels=store.read("channels");for(int i=0;i<channels.length();i++){JSONObject j=channels.optJSONObject(i);if(j!=null&&!j.optBoolean("uncertain"))rules.add(new Rule(j));}
        if(rules.isEmpty()){new AlertDialog.Builder(this).setTitle("暂未找到适用项目").setMessage("本机没有匹配的系统设置路径。可开启通知渠道检查，在营销通知出现后再清理。也可查看全部参考路径。未适配不会被显示为已清理。").setPositiveButton("查看适配范围",(d,w)->{tab=3;render();}).setNegativeButton("返回",null).show();return;}
        String[] labels=rules.stream().map(r->r.title+" · "+String.join("、",r.targets)).toArray(String[]::new);boolean[] pick=new boolean[rules.size()];Arrays.fill(pick,true);
        new AlertDialog.Builder(this).setTitle("清理范围（可取消勾选）").setMultiChoiceItems(labels,pick,(d,w,b)->pick[w]=b).setPositiveButton("开始清理",(d,w)->{List<Rule> chosen=new ArrayList<>();for(int i=0;i<pick.length;i++)if(pick[i])chosen.add(rules.get(i));if(chosen.isEmpty()){toast("请至少选择一项");return;}CleanerService current=CleanerService.get();if(current==null){toast("自动操作权限已断开，请重新开启");return;}current.clean(chosen);}).setNegativeButton("取消",null).show();
    }
    void checks(){
        header("清理结果","逐项核对结果","只统计实际回读确认的改动；未匹配的项目保留原因。");
        JSONArray scan=store.read("scan");if(scan.length()==0)paragraph(body,"还没有清理结果。");
        for(int i=0;i<scan.length();i++){JSONObject item=scan.optJSONObject(i);if(item==null)continue;Rule r=new Rule(item.optJSONObject("rule"));LinearLayout c=card(body);String label=item.optString("label");c.addView(bold(label.isEmpty()?r.title:label,17,INK));space(c,8);paragraph(c,r.title+" · "+statusName(item.optString("status")));space(c,6);paragraph(c,item.optString("message"));action(c,"查看原设置路径",false,()->ruleDetails(r));}
        action(body,"改动记录与恢复",false,()->{tab=2;render();});
    }
    String statusName(String s){switch(s){case "available":return "可调整";case "already":return "已是目标状态";case "changed":return "已核验";case "restored":return "已恢复";case "missing":return "未找到";case "skipped":return "已跳过";case "failed":return "未完成";case "pending":case "unknown":return "结果待核对";case "restoring":case "restore_failed":return "恢复待核对";default:return s;}}
    void history(){header("改动记录","原值已留好","在这里核对改动，或恢复原始状态。卸载前请先完成需要的恢复。");JSONArray journal=store.read("journal");if(journal.length()==0){LinearLayout c=card(body);c.addView(bold("还没有改动记录",20,INK));space(c,10);paragraph(c,"清理后，这里会显示每个开关的前后状态及恢复入口。");action(c,"返回清理",true,()->{tab=0;render();});}
        for(int i=journal.length()-1;i>=0;i--){JSONObject j=journal.optJSONObject(i);if(j==null)continue;LinearLayout c=card(body);c.addView(bold(j.optString("label"),18,INK));space(c,8);paragraph(c,new Rule(j.optJSONObject("rule")).title);space(c,8);paragraph(c,j.optString("status").equals("restored")?"原值："+(j.optBoolean("before")?"开":"关")+" · 已恢复":(j.optBoolean("before")?"开":"关")+" → "+(j.optBoolean("after")?"开":"关")+"  ·  "+statusName(j.optString("status")));space(c,6);paragraph(c,new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(j.optLong("time")))+" · "+j.optString("message"));if(!j.optString("status").equals("restored"))action(c,"恢复原始状态",false,()->{if(CleanerService.get()==null){accessibility();return;}new AlertDialog.Builder(this).setTitle("恢复「"+j.optString("label")+"」").setMessage("将核对当前状态，再尝试恢复为「"+(j.optBoolean("before")?"开":"关")+"」。系统更新或路径变化时会停止，并保留手动路径。").setPositiveButton("检查并恢复",(d,w)->{if(CleanerService.get()!=null)CleanerService.get().restore(j);}).setNegativeButton("取消",null).show();});action(c,"查看原设置路径",false,()->ruleDetails(new Rule(j.optJSONObject("rule"))));}
        if(journal.length()>0)action(body,"导出本地记录",false,()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"净屏-改动记录.json");startActivityForResult(i,19);});
    }
    void about(){header("使用说明","清理范围","净屏 0.1.0 · 本地预览版");LinearLayout c=card(body);c.addView(bold("当前版本的能力",18,INK));space(c,12);paragraph(c,"处理已匹配的系统推荐开关和明确的营销通知渠道。暂不提供网络过滤、开屏跳过、视频广告处理或广告组件挂起。");space(c,12);paragraph(c,"关闭个性化推荐不等于清除全部广告。原生鸿蒙不支持安装此安卓应用。");
        c=card(body);c.addView(bold("数据留在你的手机",18,INK));space(c,12);paragraph(c,"App 没有网络权限。自动操作只在你启动任务时读取目标页面；不保存页面截图或聊天正文。通知模块只保存应用、渠道名称与渠道标识。改动账本保存在应用私有目录，卸载会删除。");action(c,"清除通知渠道缓存",false,()->new AlertDialog.Builder(this).setTitle("清除渠道缓存？").setMessage("不会改变任何通知设置。通知访问权限仍开启时，后续出现的渠道会重新记录。").setPositiveButton("清除",(d,w)->{store.save("channels",new JSONArray());toast("已清除渠道缓存");}).setNegativeButton("取消",null).show());
        c=card(body);c.addView(bold("建立在开源项目之上",18,INK));space(c,12);paragraph(c,"基于 android-ad-cleaner 的 MIT 规则与保护逻辑，迁移为安卓原生执行流程。保留 36 份上游脚本来源，当前迁移 "+store.allRules().size()+" 条设置路径。");space(c,8);paragraph(c,"上游提交：b4371258\n上游脚本验证不代表手机端适配已通过。");action(c,"查看开源许可",false,()->new AlertDialog.Builder(this).setTitle("MIT · android-ad-cleaner").setMessage(store.asset("upstream-license.txt")).setPositiveButton("关闭",null).show());
        c=card(body);c.addView(bold("本机适配",18,INK));space(c,10);paragraph(c,Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE);space(c,8);paragraph(c,"可尝试 "+store.available().size()+" 条系统路径。不同品牌、版本可能改变页面，所有厂商路径仍需实机验证。");action(c,"查看全部参考路径",false,()->{String[] titles=store.allRules().stream().map(r->String.join("/",r.brands)+" · "+r.title).toArray(String[]::new);new AlertDialog.Builder(this).setTitle("参考路径").setItems(titles,(d,w)->ruleDetails(store.allRules().get(w))).setNegativeButton("关闭",null).show();});
        action(body,"管理自动操作权限",false,this::accessibility);action(body,"管理通知访问权限",false,this::notificationPermission);
    }
    void ruleDetails(Rule r){StringBuilder s=new StringBuilder("处理范围：\n").append(String.join("、",r.targets)).append("\n\n手动路径：\n");if(r.notification())s.append("系统设置 → 通知管理 → ").append(r.channelApp).append(" → ").append(r.channelName);else{s.append(r.pkg);for(List<String> step:r.path)s.append(" → ").append(String.join(" / ",step));if(!r.activity.isEmpty())s.append("\n直达页面：").append(r.activity);}s.append("\n\n来源：").append(r.source.isEmpty()?"本机已出现的通知渠道":r.source).append("\n自动恢复需要原页面仍可访问；应用升级可能改变路径。");new AlertDialog.Builder(this).setTitle(r.title).setMessage(s).setPositiveButton("打开设置入口",(d,w)->openRule(r)).setNegativeButton("关闭",null).show();}
    void openRule(Rule r){try{Intent i;if(r.notification())i=new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,r.pkg).putExtra(Settings.EXTRA_CHANNEL_ID,r.channelId);else if(r.pkg.equals("com.android.settings"))i=new Intent(Settings.ACTION_SETTINGS);else if(!r.activity.isEmpty())i=new Intent().setComponent(new ComponentName(r.pkg,r.activity.startsWith(".")?r.pkg+r.activity:r.activity));else i=getPackageManager().getLaunchIntentForPackage(r.pkg);if(i==null)throw new IllegalStateException();startActivity(i);}catch(Exception e){toast("此入口无法直接打开，请按路径手动进入");}}
    void accessibility(){new AlertDialog.Builder(this).setTitle("自动操作，需要你的授权").setMessage("净屏将读取目标设置页面的文字和开关，并在你发起清理或恢复时点击相应控件。不会读取聊天正文、保存截图或上传数据。\n\n请在系统页面中找到「净屏」开启服务。完成后随时可以关闭。若系统提示「受限设置」，请到应用信息右上角菜单中允许受限设置，再返回开启服务。").setPositiveButton("前往系统设置",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).setNegativeButton("暂不开启",null).show();}
    void notificationPermission(){new AlertDialog.Builder(this).setTitle("发现营销通知渠道").setMessage("系统的通知访问权限可以访问通知内容；净屏只提取应用名、渠道名称和渠道标识，在本机筛选候选项，不存储正文、不上传、不自动清除通知。\n\n只能发现已出现过的通知渠道，不代表手机上全部渠道。").setPositiveButton("前往授权",(d,w)->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))).setNegativeButton("取消",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==19&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(var out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IllegalStateException();out.write(store.export().getBytes(StandardCharsets.UTF_8));toast("记录已导出");}catch(Exception e){toast("导出未完成，请重试");}}}
    static final class MarkView extends View{final Paint p=new Paint(3);final Path path=new Path();MarkView(Context c){super(c);setContentDescription("净屏标志");path.moveTo(17,27);path.lineTo(24,34);path.lineTo(37,19);}protected void onDraw(Canvas c){float s=getWidth()/54f;c.save();c.scale(s,s);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(GREEN);c.drawRoundRect(8,2,43,55,9,9,p);c.drawLine(21,48,30,48,p);p.setStrokeWidth(3.6f);p.setStrokeCap(Paint.Cap.ROUND);c.drawPath(path,p);c.restore();}}
}
