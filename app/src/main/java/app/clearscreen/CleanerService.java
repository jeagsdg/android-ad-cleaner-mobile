package app.clearscreen;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public final class CleanerService extends AccessibilityService {
    private static final long GROUP_CHECK_BUDGET_MS=30_000;
    private static java.lang.ref.WeakReference<CleanerService> reference=new java.lang.ref.WeakReference<>(null);
    public static CleanerService get(){return reference.get();}
    public volatile boolean busy;
    public volatile String progress="准备就绪";
    private volatile String stopReason;private int resultTab=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout overlay;private TextView overlayText;
    private SplashSkipper skipper;
    @Override protected void onServiceConnected(){reference=new java.lang.ref.WeakReference<>(this);if(skipper!=null)skipper.close();skipper=new SplashSkipper(this);SkipKeepAliveService.sync(this);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(skipper!=null)skipper.event(event);}
    // This callback interrupts feedback while the accessibility connection remains active.
    @Override public void onInterrupt(){if(skipper!=null)skipper.interruptFeedback();}
    @Override public boolean onUnbind(Intent intent){stopFor("无障碍连接已断开，任务已停止");if(skipper!=null)skipper.close();if(reference.get()==this)reference.clear();SkipKeepAliveService.sync(this);hideOverlay();return super.onUnbind(intent);}
    @Override public void onDestroy(){stopFor("无障碍服务已关闭，任务已停止");if(skipper!=null)skipper.close();worker.shutdownNow();if(reference.get()==this)reference.clear();SkipKeepAliveService.sync(this);hideOverlay();super.onDestroy();}
    public void stop(){stopFor("已手动停止");}
    private synchronized void stopFor(String reason){
        if(stopReason!=null)return;
        stopReason=reason;
        if(busy)note("正在停止…");
    }
    private void note(String s){progress=s;main.post(()->{if(overlayText!=null)overlayText.setText(s);});}
    /** One user-approved run: inspect each exact target, save original, change, and verify. */
    public synchronized void clean(List<Rule> rules){startClean(rules,false);}
    public synchronized void retry(List<Rule> rules){startClean(rules,true);}
    private void startClean(List<Rule> rules,boolean retry){
        if(busy||rules.isEmpty())return;
        List<Rule> selected=new ArrayList<>(rules);
        resultTab=1;
        start(()->runClean(selected,retry));
    }
    private void runClean(List<Rule> rules,boolean retry)throws JSONException{
        Store store=new Store(this);
        Policy policy=store.policy();
        JSONArray results=new JSONArray();
        if(retry){
            JSONArray previous=store.read("scan");
            rules=retryTargets(rules,previous,false);
            if(rules.isEmpty()){note("没有选中的未完成项目");return;}
            results=expandRetryResults(rules,previous);
        }
        store.setLastRun("上次任务未完整结束，请核对记录");
        if(!retry)for(Rule rule:rules)for(String label:rule.targets){
            JSONObject item=scanItem(rule,label);
            put(item,"status","queued");
            put(item,"message","此开关尚未检查");
            results.put(item);
        }
        store.save("scan",results);
        List<Rule> pending=rules;
        int retried=0;
        for(int pass=1;pass<=2&&stopReason==null&&!pending.isEmpty();pass++){
            int index=0;
            for(Rule rule:pending){
                if(stopReason!=null)break;
                note((pass==1?"清理 ":"自动补查 ")+(++index)+" / "+pending.size()+" · "+rule.title);
                if(pass==2)retried+=rule.targets.size();
                cleanRule(store,policy,rule,pass,results);
            }
            if(pass==1&&stopReason==null)pending=automaticRetry(rules,results);
        }
        if(stopReason!=null){recordUnrun(rules,results);store.save("scan",results);}
        store.setLastRun(stopReason!=null?stopReason+"。已完成的改动仍保存在记录中。":retried>0?"本轮清理结束，已自动补查 "+retried+" 项":"本轮清理结束");
        note(store.lastRun());
    }
    private void cleanRule(Store store,Policy policy,Rule rule,int pass,JSONArray results){
        UiDriver ui=new UiDriver(this,()->stopReason,GROUP_CHECK_BUDGET_MS);
        try{
            if(rule.notification()&&(!policy.classify(rule.channelName,rule.channelId,rule.pkg,rule.channelApp).recommended()||policy.criticalNotification(rule.channelName)))
                throw new UiDriver.Halt("通知渠道用途不明确或包含重要服务，未操作");
            ui.open(rule);
        }catch(UiDriver.Halt e){
            if(e.terminal)stopFor(e.getMessage());
            for(String label:rule.targets){
                JSONObject item=scanItem(rule,label);
                put(item,"pass",pass);put(item,"status","skipped");put(item,"message",e.getMessage());
                put(item,"attempts",ui.attempts());put(item,"retryable",e.retryable);
                upsertResult(results,item);
            }
            store.save("scan",results);
            return;
        }
        for(String label:rule.targets){
            if(stopReason!=null)break;
            JSONObject item=scanItem(rule,label);
            put(item,"pass",pass);put(item,"attempts",ui.attempts());
            inspectTarget(store,policy,ui,label,pass,item);
            upsertResult(results,item);
            store.save("scan",results);
        }
    }
    private void inspectTarget(Store store,Policy policy,UiDriver ui,String label,int pass,JSONObject item){
        try{
            if(policy.protectedSetting(label))throw new UiDriver.Halt("服务类功能受到保护，未操作");
            ui.top();
            UiDriver.Found found=ui.find(label);
            if(found==null){
                put(item,"status","missing");put(item,"message","未识别目标开关，未操作");put(item,"retryable",true);
                return;
            }
            String actual=found.label;
            if(policy.protectedSetting(actual))throw new UiDriver.Halt("服务类功能受到保护，未操作");
            put(item,"matchedLabel",actual);
            boolean desired=policy.desired(actual);
            if(found.checked!=desired&&!ui.set(found,desired,()->{
                put(item,"before",found.checked);
                put(item,"journalId",store.prepare(ui.pinnedRule(),actual,found.checked,desired));
            }))throw new UiDriver.Halt("未确认改变结果，请核对原设置");
            String record=item.optString("journalId");
            if(record.isEmpty()){
                put(item,"status","already");put(item,"message","已经是目标状态");
            }else{
                store.finish(record,"changed","已回读确认");
                put(item,"status","changed");put(item,"message",pass==1?"已回读确认":"补查后已回读确认");
            }
        }catch(UiDriver.Halt e){
            String record=item.optString("journalId");
            if(e.terminal||!record.isEmpty())stopFor(e.getMessage());
            if(!record.isEmpty())store.finish(record,"unknown",e.getMessage());
            put(item,"status","failed");put(item,"message",e.getMessage());
            put(item,"retryable",record.isEmpty()&&e.retryable);
        }
    }
    private static String scanRuleId(JSONObject item){
        JSONObject rule=item==null?null:item.optJSONObject("rule");
        return rule==null?"":rule.optString("id");
    }
    static void upsertResult(JSONArray results,JSONObject item){
        for(int i=0;i<results.length();i++){
            JSONObject old=results.optJSONObject(i);
            if(old!=null&&scanRuleId(old).equals(scanRuleId(item))&&old.optString("label").equals(item.optString("label"))){
                put(item,"previousMessage",old.optString("message"));
                try{results.put(i,item);}catch(JSONException e){throw new IllegalStateException(e);}
                return;
            }
        }
        results.put(item);
    }
    static List<Rule> automaticRetry(List<Rule> rules,JSONArray results){return retryTargets(rules,results,true);}
    static List<Rule> retryTargets(List<Rule> rules,JSONArray results,boolean automatic){
        Map<String,Map<String,JSONObject>> byRule=new HashMap<>();
        for(int i=0;i<results.length();i++){
            JSONObject item=results.optJSONObject(i);
            String id=scanRuleId(item);
            if(!id.isEmpty())byRule.computeIfAbsent(id,k->new HashMap<>()).put(item.optString("label"),item);
        }
        List<Rule> retry=new ArrayList<>();
        for(Rule rule:rules){
            Map<String,JSONObject> entries=byRule.get(rule.id);
            if(entries==null||rule.manual)continue;
            JSONObject group=automatic?null:entries.get("");
            List<String> groupTargets=group==null?Collections.emptyList():Rule.strings(group.optJSONObject("rule").optJSONArray("targets"));
            List<String> labels=new ArrayList<>();
            for(String label:rule.targets){
                JSONObject item=entries.get(label);
                if(item==null&&(groupTargets.isEmpty()||groupTargets.contains(label)))item=group;
                if(item!=null&&(!automatic||item.optBoolean("retryable"))&&unfinished(item))labels.add(label);
            }
            if(!labels.isEmpty())retry.add(rule.onlyTargets(labels));
        }
        return retry;
    }
    private static boolean unfinished(JSONObject item){
        String status=item.optString("status");
        return status.equals("missing")||status.equals("failed")||status.equals("skipped")||status.equals("queued");
    }
    static JSONArray expandRetryResults(List<Rule> rules,JSONArray previous)throws JSONException{
        JSONArray results=new JSONArray();
        Map<String,Rule> selected=new HashMap<>();
        for(Rule rule:rules)selected.put(rule.id,rule);
        Map<String,JSONObject> groups=new LinkedHashMap<>();
        Set<String> existing=new HashSet<>();
        for(int i=0;i<previous.length();i++){
            JSONObject item=previous.optJSONObject(i);
            if(item==null)continue;
            String id=scanRuleId(item),label=item.optString("label");
            if(label.isEmpty()&&selected.containsKey(id)&&unfinished(item))groups.put(id,item);
            else{results.put(item);existing.add(id+":"+label);}
        }
        for(Map.Entry<String,JSONObject> group:groups.entrySet()){
            JSONObject previousGroup=group.getValue();
            List<String> labels=Rule.strings(previousGroup.optJSONObject("rule").optJSONArray("targets"));
            if(labels.isEmpty())labels=selected.get(group.getKey()).targets;
            for(String label:labels){
                String key=group.getKey()+":"+label;
                if(existing.add(key))results.put(new JSONObject(previousGroup.toString()).put("label",label).put("key",key));
            }
        }
        return results;
    }
    private static JSONObject scanItem(Rule r,String label){JSONObject j=new JSONObject();put(j,"rule",r.json());put(j,"label",label);put(j,"key",r.id+":"+label);put(j,"build",Build.FINGERPRINT);return j;}
    static void recordUnrun(List<Rule> rules,JSONArray results){
        Map<String,JSONObject> existing=new HashMap<>();
        Set<String> groups=new HashSet<>();
        for(int i=0;i<results.length();i++){
            JSONObject item=results.optJSONObject(i);
            if(item==null||item.optJSONObject("rule")==null)continue;
            String id=scanRuleId(item),label=item.optString("label");
            if(label.isEmpty())groups.add(id);
            else existing.put(id+":"+label,item);
        }
        for(Rule rule:rules)if(!groups.contains(rule.id))for(String label:rule.targets){
            JSONObject item=existing.get(rule.id+":"+label);
            if(item==null){
                item=scanItem(rule,label);
                markUnrun(item);
                results.put(item);
            }else if(item.optString("status").equals("queued"))markUnrun(item);
        }
    }
    private static void markUnrun(JSONObject item){
        put(item,"status","queued");
        put(item,"message","任务停止前尚未执行，可在未完成列表中重试");
    }
    public synchronized void restore(JSONObject entry){restoreAll(Collections.singletonList(entry));}
    public synchronized void restoreAll(List<JSONObject> entries){
        if(busy||entries.isEmpty())return;
        Set<String> requested=new LinkedHashSet<>();
        for(JSONObject entry:entries)requested.add(entry.optString("id"));
        resultTab=2;
        start(()->{
            Store store=new Store(this);
            Map<String,JSONObject> available=new HashMap<>();
            for(JSONObject entry:store.restorable())available.put(entry.optString("id"),entry);
            requested.retainAll(available.keySet());
            if(requested.isEmpty()){note("所选改动已恢复或已有更新的记录");return;}
            int index=0;
            for(String id:requested){
                if(stopReason!=null)break;
                note("恢复 "+(++index)+" / "+requested.size());
                restoreEntry(store,available.get(id));
            }
            note(stopReason!=null?"恢复已停止，请查看记录":"本轮恢复结束，请查看记录");
        });
    }
    private void restoreEntry(Store store,JSONObject entry)throws JSONException{
        String id=entry.getString("id");Rule r=new Rule(entry.getJSONObject("rule"));String label=entry.getString("label");UiDriver ui=new UiDriver(this,()->stopReason,GROUP_CHECK_BUDGET_MS);note("恢复 · "+label);
        boolean attempted=false;
        try{if(!Build.FINGERPRINT.equals(entry.optString("build")))throw new UiDriver.Halt("系统版本已变化，请按原路径手动核对");
            if(entry.optLong("packageVersion",-1)>=0&&entry.optLong("packageVersion")!=store.packageVersion(r.pkg))throw new UiDriver.Halt("目标应用版本已变化，请按原路径手动核对");
            JSONObject versions=entry.optJSONObject("routeVersions");if(versions!=null){Iterator<String> keys=versions.keys();while(keys.hasNext()){String pkg=keys.next();if(versions.getLong(pkg)!=store.packageVersion(pkg))throw new UiDriver.Halt("设置入口涉及的应用版本已变化，请手动核对");}}
            ui.openForRestore(r,label);
            ui.top();
            UiDriver.Found f=ui.findExact(label);
            if(f==null){
                // The pinned route was matched. A recreated window can briefly lose its controls.
                // Recheck before preparing a write, using the same package and rule guards.
                ui.pause(400);
                ui.top();
                f=ui.findExact(label);
            }
            if(f==null)throw new UiDriver.Halt("找不到原开关，未操作");
            boolean before=entry.getBoolean("before"),after=entry.getBoolean("after");
            if(f.checked==before){store.finish(id,"restored","原始状态已确认");note("已经是原始状态");return;}
            if(f.checked!=after)throw new UiDriver.Halt("当前状态与记录不同，未覆盖");
            attempted=true;
            if(!ui.set(f,before,()->store.finish(id,"restoring","恢复前已核对当前状态")))throw new UiDriver.Halt("恢复结果未能确认，请手动核对");
            store.finish(id,"restored","已恢复原值并回读确认");note("已恢复 · "+label);
        }catch(UiDriver.Halt e){if(attempted||e.terminal)stopFor(e.getMessage());store.finish(id,"restore_failed",e.getMessage());note("恢复未完成，请查看记录");}
    }
    static void put(JSONObject j,String key,Object value){try{j.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
    private interface Work{void run()throws Exception;}
    private void start(Work work){
        busy=true;
        stopReason=null;
        showOverlay();
        worker.execute(()->{
            try{work.run();}
            catch(Exception failure){
                String reason="任务未完整结束，已保存的改动可在记录中核对";
                if(resultTab==1)try{new Store(this).setLastRun(reason);}catch(IllegalStateException ignored){}
                note(reason);
            }finally{main.post(this::finishWork);}
        });
    }
    private void finishWork(){
        hideOverlay();
        int completedTab=resultTab;
        busy=false;
        if(get()!=this)return;
        Intent results=new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab",completedTab);
        try{startActivity(results);}
        catch(ActivityNotFoundException|SecurityException ignored){
            // The results remain available from the app if the system blocks the return.
        }
    }
    private void showOverlay(){main.post(()->{overlay=new LinearLayout(this);overlay.setOrientation(LinearLayout.HORIZONTAL);overlay.setPadding(18,8,10,8);overlay.setGravity(Gravity.CENTER_VERTICAL);overlay.setBackgroundColor(Color.rgb(20,55,46));overlayText=new TextView(this);overlayText.setTextColor(Color.WHITE);overlayText.setTextSize(13);overlayText.setText("准备操作…");overlay.addView(overlayText,new LinearLayout.LayoutParams(0,-2,1));Button stop=new Button(this);stop.setText("停止");stop.setOnClickListener(v->stop());overlay.addView(stop);WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,android.graphics.PixelFormat.TRANSLUCENT);p.gravity=Gravity.BOTTOM;try{((WindowManager)getSystemService(WINDOW_SERVICE)).addView(overlay,p);}catch(WindowManager.BadTokenException|SecurityException ignored){hideOverlay();}});}
    private void hideOverlay(){if(overlay!=null){try{((WindowManager)getSystemService(WINDOW_SERVICE)).removeView(overlay);}catch(Exception ignored){}overlay=null;overlayText=null;}}
}
