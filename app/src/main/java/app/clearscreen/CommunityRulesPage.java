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
        a.linkAction(actions,"从文件导入订阅",()->{
            try{a.startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"),49);}
            catch(android.content.ActivityNotFoundException e){a.toast("没有可用的文件选择器");}
        });
        a.linkAction(actions,"粘贴本地规则",()->input(a,false));
        JSONObject sources=CommunityRules.all(a);
        if(sources.length()==0)a.paragraph(actions,"还没有订阅。可以使用净屏规则备份，或兼容的 GKD 应用开屏规则。");
        List<String> keys=new ArrayList<>();sources.keys().forEachRemaining(keys::add);Collections.sort(keys);
        for(String key:keys){
            JSONObject row=sources.optJSONObject(key);LinearLayout c=a.card(a.body);
            a.paragraph(c,row.optBoolean("enabled")?"已启用":"已暂停");c.addView(a.heading(row.optString("name"),20,MainActivity.INK));
            List<CustomSkipRule> imported=CustomSkipRule.subscriptionRules(row.optString("rules"));
            Set<String> disabled=CommunityRules.disabled(row);int selected=0;
            for(CustomSkipRule rule:imported)if(!disabled.contains(CommunityRules.key(rule)))selected++;
            a.paragraph(c,key+"\n已选 "+selected+" / "+imported.size()+" 条规则 · 来源版本 "+row.optInt("version"));
            a.linkAction(c,"管理单条规则",()->manage(a,key));
            if(row.has("previous"))a.linkAction(c,"恢复上一版",()->restore(a,key));
            a.linkAction(c,row.optBoolean("enabled")?"暂停此订阅":"启用此订阅",()->change(a,key,row.optBoolean("enabled")?"off":"on"));
            if(key.startsWith("https://")&&a.networkFilterAvailable()){
                int pending=row.optInt("available",-1);a.linkAction(c,pending>row.optInt("version")?"查看新版 "+pending+" 的变更":"检查更新",()->fetch(a,key));
                a.historyToggle(c,"每天检查这个来源",row.optBoolean("checkUpdates"),on->{
                    try{JSONObject all=CommunityRules.all(a),r=all.getJSONObject(key);r.put("checkUpdates",on);
                        CommunityRules.save(a,all);if(on)SourceChecks.onForeground(a);}
                    catch(Exception e){a.toast("检查偏好未保存");}
                });
            }
            a.linkAction(c,"移除此来源",()->new AlertDialog.Builder(a).setMessage("移除订阅及其规则？本地手写规则会保留。").setNegativeButton("取消",null).setPositiveButton("移除",(d,v)->change(a,key,"remove")).show());
        }
    }
    private static void manage(MainActivity a,String key){
        try{
            JSONObject src=CommunityRules.all(a).getJSONObject(key);
            List<CustomSkipRule> rules=CustomSkipRule.subscriptionRules(src.getString("rules"));
            String[] labels=new String[rules.size()];boolean[] checked=new boolean[labels.length];
            Set<String> off=CommunityRules.disabled(src);
            for(int i=0;i<labels.length;i++){CustomSkipRule r=rules.get(i);
                labels[i]=r.pkg+"\n"+(r.label.isEmpty()?r.viewId:r.label);checked[i]=!off.contains(CommunityRules.key(r));}
            new AlertDialog.Builder(a).setTitle("管理单条规则").setMultiChoiceItems(labels,checked,(d,i,b)->checked[i]=b)
                    .setNegativeButton("取消",null).setPositiveButton("保存",(d,v)->{
                        try{JSONObject all=CommunityRules.all(a),current=all.getJSONObject(key);
                            if(!current.getString("rules").equals(src.getString("rules"))){a.toast("订阅已变更，请重新选择");return;}
                            for(int i=0;i<checked.length;i++){String k=CommunityRules.key(rules.get(i));if(checked[i])off.remove(k);else off.add(k);}
                            current.put("disabled",new JSONArray(off));CommunityRules.save(a,all);a.render();
                        }catch(Exception failed){a.toast("单条规则未保存");}
                    }).show();
        }catch(Exception e){a.toast("无法读取订阅规则");}
    }
    private static void restore(MainActivity a,String key){
        JSONObject src=CommunityRules.all(a).optJSONObject(key);if(src==null)return;
        JSONObject previous=src.optJSONObject("previous");if(previous==null)return;
        new AlertDialog.Builder(a).setTitle("恢复上一版？")
                .setMessage("将使用来源版本 "+previous.optInt("version")+" 的规则，保留你现在的暂停和单条启停选择。")
                .setNegativeButton("取消",null).setPositiveButton("恢复",(d,v)->{
                    try{JSONObject all=CommunityRules.all(a),current=all.getJSONObject(key);
                        JSONObject backup=current.getJSONObject("previous");
                        backup.put("enabled",current.optBoolean("enabled",true));backup.put("disabled",current.optJSONArray("disabled"));
                        all.put(key,backup);CommunityRules.save(a,all);a.render();
                    }catch(Exception e){a.toast("没有可恢复的版本");}
                }).show();
    }
    private static void change(MainActivity a,String key,String action){
        try{JSONObject all=CommunityRules.all(a);if(action.equals("remove"))all.remove(key);else all.getJSONObject(key).put("enabled",action.equals("on"));
            CommunityRules.save(a,all);a.render();}catch(Exception e){a.toast("未能修改订阅");}
    }
    static void readFile(MainActivity a,android.net.Uri uri){
        new Thread(()->{
            try(java.io.InputStream in=a.getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
                if(in==null)throw new java.io.IOException("文件不可读");byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>4*1024*1024)throw new java.io.IOException("文件超过 4 MB");out.write(b,0,n);}
                CommunityRules.Preview p=CommunityRules.parse(out.toString("UTF-8"));
                a.runOnUiThread(()->{if(!a.isDestroyed()&&a.tab==MainActivity.COMMUNITY_RULES)showPreview(a,null,p);});
            }catch(Exception e){a.runOnUiThread(()->{if(!a.isDestroyed())a.toast("无法导入："+e.getMessage());});}
        },"subscription-file").start();
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
        a.toast("正在核对订阅条件…");
        new Thread(()->{
            try{CommunityRules.Preview parsed=CommunityRules.parse(raw);
                a.runOnUiThread(()->{if(!a.isDestroyed()&&a.tab==MainActivity.COMMUNITY_RULES)showPreview(a,url,parsed);});
            }catch(Exception failure){a.runOnUiThread(()->{if(!a.isDestroyed())new AlertDialog.Builder(a).setTitle("无法读取规则文件").setMessage(failure.getMessage()).setPositiveButton("知道了",null).show();});}
        },"subscription-parse").start();
    }
    private static void showPreview(MainActivity a,String url,CommunityRules.Preview p){
        try{String key=url==null?"本地 · "+p.name+(p.subscriptionId==null?"":" #"+p.subscriptionId):url;
            JSONObject all=CommunityRules.all(a),prev=all.optJSONObject(key);
            Set<String> before=new HashSet<>(),after=new HashSet<>(),local=new HashSet<>();
            if(prev!=null)for(CustomSkipRule r:CustomSkipRule.subscriptionRules(prev.getString("rules")))before.add(CommunityRules.key(r));
            for(CustomSkipRule r:p.rules)after.add(CommunityRules.key(r));
            for(CustomSkipRule r:a.store.customSkipRules())local.add(CommunityRules.key(r));
            int shared=0,repeat=0;for(String s:after){if(before.contains(s))shared++;if(local.contains(s))repeat++;}
            StringBuilder msg=new StringBuilder(p.name+" · "+p.rules.size()+" 条可用\n新增 "+(after.size()-shared)+" · 移除 "+(before.size()-shared)+" · 与手写规则重复 "+repeat);
            int shown=0;
            for(CustomSkipRule r:p.rules){
                if(shown++==30){msg.append("\n其余条目可在「管理单条规则」查看。");break;}
                msg.append("\n• ").append(r.pkg).append("　").append(r.label.isEmpty()?r.viewId:r.label);
                if(r.scope!=null)msg.append("\n  ").append(r.scope.describe());
            }
            if(!p.omitted.isEmpty())msg.append("\n\n跳过的条件（未启用）：\n").append(android.text.TextUtils.join("\n",new LinkedHashSet<>(p.omitted)));
            if(prev!=null&&prev.has("id")&&(p.subscriptionId==null||prev.getLong("id")!=p.subscriptionId)){
                throw new IllegalArgumentException("来源的订阅 ID 已变化；请核对来源，原有规则已保留。");
            }
            boolean rollback=prev!=null&&p.version<prev.optInt("version");
            if(rollback)msg.append("\n来源版本低于已保存版本，未覆盖。");
            AlertDialog.Builder b=new AlertDialog.Builder(a).setTitle("订阅变更预览").setMessage(msg).setNegativeButton("关闭",null);
            if(!rollback&&!p.rules.isEmpty()){JSONObject staged=CommunityRules.stage(p,prev==null||prev.optBoolean("enabled"));
                b.setPositiveButton("应用这些规则",(d,v)->{try{JSONObject latest=CommunityRules.all(a),current=latest.optJSONObject(key);
                    if((prev==null)!=(current==null)||(prev!=null&&(!prev.getString("rules").equals(current.getString("rules"))
                            ||prev.optInt("version")!=current.optInt("version")||!java.util.Objects.equals(prev.opt("id"),current.opt("id"))))){
                        a.toast("来源已发生变更，请重新预览");return;
                    }
                    latest.put(key,CommunityRules.update(current,staged));CommunityRules.save(a,latest);a.render();}catch(Exception e){a.toast("订阅未保存");}});}
            b.show();
        }catch(Exception e){new AlertDialog.Builder(a).setTitle("无法导入这份规则").setMessage(e.getMessage()).setPositiveButton("知道了",null).show();}
    }
}
