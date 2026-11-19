package app.clearscreen;

import android.app.AlertDialog;
import android.widget.*;
import org.json.*;
import java.util.*;

final class CommunityRulesPage {
    static void show(MainActivity a){
        a.header("规则工具","我的订阅","每个来源单独管理。先看将启用的规则，再决定是否应用。");
        LinearLayout actions=a.card(a.body);
        if(a.networkFilterAvailable())a.action(actions,"添加订阅地址",true,()->input(a,true));
        a.linkAction(actions,"粘贴本地规则",()->input(a,false));
        JSONObject sources=CommunityRules.all(a);
        if(sources.length()==0)a.paragraph(actions,"还没有订阅。可以使用净屏规则备份，或兼容的 GKD 应用开屏规则。");
        List<String> keys=new ArrayList<>();sources.keys().forEachRemaining(keys::add);Collections.sort(keys);
        for(String key:keys){
            JSONObject row=sources.optJSONObject(key);LinearLayout c=a.card(a.body);
            a.paragraph(c,row.optBoolean("enabled")?"已启用":"已暂停");c.addView(a.heading(row.optString("name"),20,MainActivity.INK));
            a.paragraph(c,key+"\n"+CustomSkipRule.parse(row.optString("rules")).size()+" 条规则 · 来源版本 "+row.optInt("version"));
            a.linkAction(c,row.optBoolean("enabled")?"暂停此订阅":"启用此订阅",()->change(a,key,row.optBoolean("enabled")?"off":"on"));
            if(key.startsWith("https://")&&a.networkFilterAvailable())a.linkAction(c,"检查更新",()->fetch(a,key));
            a.linkAction(c,"移除此来源",()->new AlertDialog.Builder(a).setMessage("移除订阅及其规则？本地手写规则会保留。").setNegativeButton("取消",null).setPositiveButton("移除",(d,v)->change(a,key,"remove")).show());
        }
    }
    private static void change(MainActivity a,String key,String action){
        try{JSONObject all=CommunityRules.all(a);if(action.equals("remove"))all.remove(key);else all.getJSONObject(key).put("enabled",action.equals("on"));
            CommunityRules.save(a,all);a.render();}catch(Exception e){a.toast("未能修改订阅");}
    }
    private static void input(MainActivity a,boolean remote){
        LinearLayout form=a.skipDialogContent();EditText value=a.skipRuleField(form,remote?"公开 HTTPS 地址":"规则文件内容",remote?"https://…/rules.json":"粘贴 JSON 或兼容的 JSON5","");
        if(!remote){value.setSingleLine(false);value.setMinLines(3);}
        new AlertDialog.Builder(a).setTitle(remote?"添加订阅":"导入规则").setView(a.scrollDialogContent(form)).setNegativeButton("取消",null)
                .setPositiveButton("预览",(d,v)->{String s=value.getText().toString().trim();if(remote)fetch(a,s);else preview(a,null,s);}).show();
    }
    private static void fetch(MainActivity a,String key){
        if(!key.startsWith("https://")){a.toast("请输入 HTTPS 地址");return;}
        a.toast("正在读取订阅…");new Thread(()->{
            try{String raw=SubscriptionFetch.get(key);a.runOnUiThread(()->{if(!a.isDestroyed()&&a.tab==MainActivity.COMMUNITY_RULES)preview(a,key,raw);});}
            catch(Exception ex){a.runOnUiThread(()->{if(!a.isDestroyed())a.toast("读取失败："+ex.getMessage());});}
        },"subscription-read").start();
    }
    private static void preview(MainActivity a,String url,String raw){
        try{CommunityRules.Preview p=CommunityRules.parse(raw);String key=url==null?"本地 · "+p.name:url;
            JSONObject all=CommunityRules.all(a),prev=all.optJSONObject(key);
            Set<String> before=new HashSet<>(),after=new HashSet<>(),local=new HashSet<>();
            if(prev!=null)for(CustomSkipRule r:CustomSkipRule.parse(prev.getString("rules")))before.add(CommunityRules.key(r));
            for(CustomSkipRule r:p.rules)after.add(CommunityRules.key(r));
            for(CustomSkipRule r:a.store.customSkipRules())local.add(CommunityRules.key(r));
            int shared=0,repeat=0;for(String s:after){if(before.contains(s))shared++;if(local.contains(s))repeat++;}
            StringBuilder msg=new StringBuilder(p.name+" · "+p.rules.size()+" 条可用\n新增 "+(after.size()-shared)+" · 移除 "+(before.size()-shared)+" · 与手写规则重复 "+repeat);
            for(CustomSkipRule r:p.rules)msg.append("\n• ").append(r.pkg).append("　").append(r.label.isEmpty()?r.viewId:r.label);
            if(!p.omitted.isEmpty())msg.append("\n\n跳过的条件（未启用）：\n").append(android.text.TextUtils.join("\n",new LinkedHashSet<>(p.omitted)));
            boolean rollback=prev!=null&&p.version>0&&p.version<prev.optInt("version");
            if(rollback)msg.append("\n来源版本低于已保存版本，未覆盖。");
            AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle("订阅变更预览").setMessage(msg).setNegativeButton("关闭",null);
            if(!rollback&&!p.rules.isEmpty()){JSONObject staged=CommunityRules.stage(p,prev==null||prev.optBoolean("enabled"));
                b.setPositiveButton("应用这些规则",(d,v)->{try{JSONObject latest=CommunityRules.all(a);latest.put(key,staged);CommunityRules.save(a,latest);a.render();}catch(Exception e){a.toast("订阅未保存");}});}
            b.show();
        }catch(Exception e){new AlertDialog.Builder(a).setTitle("无法导入这份规则").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    }
}
