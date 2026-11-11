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
    private static final long TARGET_CHECK_BUDGET_MS=36_000;
    private static java.lang.ref.WeakReference<CleanerService> reference=new java.lang.ref.WeakReference<>(null);
    public static CleanerService get(){return reference.get();}
    public volatile boolean busy;
    public volatile String progress="准备就绪";
    private volatile String stopReason;
    private volatile boolean returnToResults;
    private volatile UiDriver.Foreground taskForeground;
    private int resultTab=1;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout overlay;private TextView overlayText;
    private SplashSkipper skipper;
    boolean startRulePreview(List<CustomSkipRule> drafts) {
        return skipper != null && skipper.startRulePreview(drafts);
    }
    @Override protected void onServiceConnected(){reference=new java.lang.ref.WeakReference<>(this);if(skipper!=null)skipper.close();skipper=new SplashSkipper(this);SkipKeepAliveService.sync(this);}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(skipper!=null)skipper.event(event);}
    // This callback interrupts feedback while the accessibility connection remains active.
    @Override public void onInterrupt(){if(skipper!=null)skipper.interruptFeedback();}
    @Override public boolean onUnbind(Intent intent){returnToResults=false;stopFor("无障碍连接已断开，任务已暂停");if(skipper!=null)skipper.close();if(reference.get()==this)reference.clear();SkipKeepAliveService.sync(this);hideOverlay();return super.onUnbind(intent);}
    @Override public void onDestroy(){returnToResults=false;stopFor("无障碍服务已关闭，任务已暂停");if(skipper!=null)skipper.close();worker.shutdownNow();if(reference.get()==this)reference.clear();SkipKeepAliveService.sync(this);hideOverlay();super.onDestroy();}
    public void stop(){stopFor("已手动停止");}
    private synchronized void stopFor(String reason){
        if(stopReason!=null)return;
        stopReason=reason;
        if(busy)note("正在停止…");
    }
    private void note(String s){progress=s;main.post(()->{if(overlayText!=null)overlayText.setText(s);});}
    private enum RunMode { NEW, RESUME, RETRY }
    public synchronized void clean(List<Rule> rules){startClean(rules,RunMode.NEW);}
    public synchronized void resume(List<Rule> rules){startClean(rules,RunMode.RESUME);}
    public synchronized void retry(List<Rule> rules){startClean(rules,RunMode.RETRY);}
    private void startClean(List<Rule> rules,RunMode mode){
        if(busy||rules.isEmpty())return;
        List<Rule> selected=new ArrayList<>(rules);
        resultTab=1;
        start(()->runClean(selected,mode));
    }
    private void runClean(List<Rule> rules,RunMode mode)throws JSONException{
        Store store=new Store(this);
        Policy policy=store.policy();
        JSONArray results=new JSONArray();
        if(mode!=RunMode.NEW){
            JSONArray previous=store.read("scan");
            rules=mode==RunMode.RESUME?resumableTargets(rules,previous):retryTargets(rules,previous,false);
            if(rules.isEmpty()){note("没有选中的未完成项目");return;}
            results=expandRetryResults(rules,previous);
        }
        if(mode==RunMode.NEW){
            for(Rule rule:rules)for(String label:rule.targets){
                JSONObject item=scanItem(rule,label);
                status(item,"queued","queued","此开关尚未检查",false);
                results.put(item);
            }
        }
        store.save("scan",results);
        store.setLastRun("任务中断后可继续执行，已保存的原值可在改动记录中核对");
        UiDriver.Foreground foreground=taskForeground;
        List<Rule> pending=rules;
        int retried=0;
        for(int pass=1;pass<=2&&stopReason==null&&!pending.isEmpty();pass++){
            int index=0;
            for(Rule rule:pending){
                if(stopReason!=null)break;
                String prefix=(pass==1?"清理 ":"自动补查 ")+(++index)+" / "+pending.size()+" · "+rule.title;
                int processed=cleanRule(store,policy,rule,pass,results,foreground,prefix,mode!=RunMode.NEW);
                if(pass==2)retried+=processed;
            }
            if(pass==1&&stopReason==null)pending=automaticRetry(rules,results);
        }
        if(stopReason!=null){recordUnrun(rules,results);store.save("scan",results);}
        int completed=0, remaining=0;
        for(int i=0;i<results.length();i++){
            JSONObject item=results.optJSONObject(i);
            if(item==null||scanRuleId(item).isEmpty())continue;
            String state=item.optString("status");
            if(state.equals("changed")||state.equals("already")||state.equals("restored"))completed++;
            else if(unfinished(item)||state.equals("blocked"))remaining++;
        }
        String summary="已核验 "+completed+" 项 · 未完成 "+remaining+" 项";
        String outcome=stopReason!=null?stopReason:"本轮清理结束"+(retried>0?"，已自动补查 "+retried+" 项":"");
        store.setLastRun(outcome+"。"+summary);
        note(store.lastRun());
    }
    private UiDriver driver(UiDriver.Foreground foreground){
        return new UiDriver(this,()->stopReason,TARGET_CHECK_BUDGET_MS,foreground);
    }
    private int cleanRule(Store store,Policy policy,Rule rule,int pass,JSONArray results,
                          UiDriver.Foreground foreground,String prefix,boolean continuation)throws JSONException{
        UiDriver ui=null;
        UiDriver.Halt groupBlock=null;
        int processed=0;
        for(String label:rule.targets){
            if(stopReason!=null)break;
            note(prefix+" · "+(++processed)+" / "+rule.targets.size()+" · "+label);
            JSONObject previous=scanResult(results,rule.id,label);
            JSONObject item=previous==null?scanItem(rule,label):new JSONObject(previous.toString());
            put(item,"rule",rule.json());
            put(item,"build",Build.FINGERPRINT);
            put(item,"attemptCount",item.optInt("attemptCount",item.optInt("pass"))+1);
            put(item,"pass",pass);
            if(!item.optString("message").isEmpty()&&!item.optString("status").equals("queued")
                    &&!item.optString("status").equals("checking")&&!item.optString("status").equals("verify"))
                put(item,"previousMessage",item.optString("message"));
            status(item,item.optString("status").equals("verify")?"verify":"checking",
                    "checking","正在重新读取目标设置",false);
            upsertCheckpoint(results,item);
            store.save("scan",results);
            try{
                checkPolicy(rule,label,policy);
                JSONObject unresolved=journalToVerify(store,rule,label,item);
                if(item.optString("status").equals("restored"))continue;
                if(unresolved!=null){
                    put(item,"journalId",unresolved.optString("id"));
                    status(item,"verify","journal_readback","正在核对上次操作的实际状态",false);
                    store.save("scan",results);
                    ui=driver(foreground);
                    if(reconcileWrite(store,ui,policy,unresolved,item,results,continuation)){
                        recordRoute(item,ui);
                        ui=null;
                        continue;
                    }
                    ui=null;
                }
                if(groupBlock!=null)throw groupBlock;
                UiDriver.Found found=null;
                if(ui!=null){
                    ui.nextTarget();
                    ui.top();
                    found=ui.find(label);
                }
                if(ui==null||(found==null&&rule.routes.size()>1)){
                    JSONArray priorRoutes=ui==null?item.optJSONArray("attempts"):ui.attempts();
                    ui=driver(foreground);
                    ui.openForTarget(rule,label,priorRoutes);
                    found=ui.find(label);
                }
                if(found==null)throw new UiDriver.Halt("未识别目标开关，未操作",false,true,"target_missing");
                inspectTarget(store,policy,ui,found,pass,item,results);
            }catch(UiDriver.Halt e){
                if(e.reason.equals("consent"))groupBlock=e;
                handleCleanHalt(store,item,e);
            }finally{
                if(ui!=null)recordRoute(item,ui);
                store.save("scan",results);
                if(!item.optString("status").equals("changed")&&!item.optString("status").equals("already"))ui=null;
            }
        }
        return processed;
    }
    private void checkPolicy(Rule rule,String label,Policy policy)throws UiDriver.Halt{
        if(rule.manual||policy.protectedSetting(label))
            throw new UiDriver.Halt("此项需要手动处理，未操作",false,false,"protected");
        if(rule.notification()&&(!policy.classify(rule.channelName,rule.channelId,rule.pkg,rule.channelApp).recommended()
                ||policy.criticalNotification(rule.channelName)))
            throw new UiDriver.Halt("通知渠道用途不明确或包含重要服务，未操作",false,false,"protected");
    }
    private void inspectTarget(Store store,Policy policy,UiDriver ui,UiDriver.Found found,int pass,
                               JSONObject item,JSONArray results)throws UiDriver.Halt{
        String actual=found.label;
        checkPolicy(ui.pinnedRule(),actual,policy);
        put(item,"matchedLabel",actual);
        boolean desired=policy.desired(actual);
        if(found.checked!=desired&&!ui.set(found,desired,()->{
            put(item,"before",found.checked);
            put(item,"journalId",store.prepare(ui.pinnedRule(),actual,found.checked,desired));
            status(item,"verify","write_prepared","原值已保存，等待操作后回读",false);
            recordRoute(item,ui);
            store.save("scan",results);
        }))throw new UiDriver.Halt("未确认改变结果，请核对原设置",false,false,"write_unconfirmed");
        String record=item.optString("journalId");
        if(record.isEmpty())status(item,"already","already","已经是目标状态",false);
        else{
            store.finish(record,"changed","已回读确认");
            status(item,"changed","confirmed",pass==1?"已回读确认":"补查后已回读确认",false);
        }
    }
    private void handleCleanHalt(Store store,JSONObject item,UiDriver.Halt halt){
        boolean prepared=item.optString("reason").equals("write_prepared");
        if(halt.terminal)pauseFor(halt);
        if(prepared){
            String id=item.optString("journalId");
            store.finish(id,"unknown",halt.getMessage());
            stopFor(halt.getMessage());
        }
        String state=item.optString("status");
        if(prepared||state.equals("verify")||halt.reason.equals("journal_ambiguous"))state="verify";
        else if(halt.terminal)state="checking";
        else if(halt.reason.equals("protected"))state="blocked";
        else if(halt.reason.equals("target_missing"))state="missing";
        else if(halt.reason.equals("consent")||halt.reason.equals("entry_unavailable")||
                halt.reason.equals("package_unavailable")||halt.reason.equals("no_route"))state="skipped";
        else state="failed";
        status(item,state,halt.reason,halt.getMessage(),halt.retryable&&!state.equals("verify"));
    }
    private void pauseFor(UiDriver.Halt halt){
        if(halt.reason.startsWith("foreground")||halt.reason.equals("window_changed")||halt.reason.equals("screen_locked"))
            returnToResults=false;
        stopFor(halt.getMessage());
    }
    private static void status(JSONObject item,String status,String reason,String message,boolean retryable){
        put(item,"status",status);put(item,"reason",reason);put(item,"message",message);put(item,"retryable",retryable);
    }
    private static void recordRoute(JSONObject item,UiDriver ui){
        put(item,"attempts",ui.attempts());
        put(item,"route",ui.routeName());
        put(item,"routePackage",ui.routePackage());
    }
    private JSONObject journalToVerify(Store store,Rule rule,String label,JSONObject item)throws UiDriver.Halt{
        String expectedId=item.optString("journalId");
        List<JSONObject> possible=new ArrayList<>();
        for(JSONObject entry:store.restorable()){
            if(!rule.id.equals(scanRuleId(entry))||!rule.labels(label).contains(entry.optString("label")))continue;
            String state=entry.optString("status");
            if(expectedId.equals(entry.optString("id"))||state.equals("pending")||state.equals("unknown")||
                    state.equals("restoring")||state.equals("restore_failed"))possible.add(entry);
        }
        if(possible.size()>1)throw new UiDriver.Halt("此开关有多条待核对记录，请先查看改动记录",false,false,"journal_ambiguous");
        if(possible.size()==1&&(expectedId.isEmpty()||expectedId.equals(possible.get(0).optString("id"))))return possible.get(0);
        if(expectedId.isEmpty()){
            status(item,"checking","checking","正在读取目标设置",false);
            return null;
        }
        JSONArray journal=store.read("journal");
        for(int i=journal.length()-1;i>=0;i--){
            JSONObject entry=journal.optJSONObject(i);
            if(entry!=null&&expectedId.equals(entry.optString("id"))&&rule.id.equals(scanRuleId(entry))
                    &&rule.labels(label).contains(entry.optString("label"))&&entry.optString("status").equals("restored")){
                status(item,"restored","restored","已在改动记录中恢复，保留原始状态",false);
                return null;
            }
        }
        throw new UiDriver.Halt("无法对应上次的原值记录，请在改动记录中手动核对",false,false,"journal_ambiguous");
    }
    private boolean reconcileWrite(Store store,UiDriver ui,Policy policy,JSONObject entry,JSONObject item,
                                   JSONArray results,boolean continuation)throws JSONException,UiDriver.Halt{
        Rule saved=new Rule(entry.getJSONObject("rule"));
        String label=entry.getString("label"), id=entry.getString("id");
        checkPolicy(saved,label,policy);
        validateRecordedRoute(store,saved,entry);
        ui.openForRestore(saved,label);
        UiDriver.Found found=ui.findExact(label);
        if(found==null)throw new UiDriver.Halt("未找到上次记录中的开关，请手动核对",false,false,"journal_target_missing");
        boolean before=entry.getBoolean("before"),after=entry.getBoolean("after");
        if(before==after)throw new UiDriver.Halt("上次原值记录不完整，请手动核对",false,false,"journal_ambiguous");
        if(found.checked==before){
            String previousStatus=entry.optString("status");
            if(continuation&&(previousStatus.equals("restoring")||previousStatus.equals("restore_failed"))){
                store.finish(id,"restored","重新读取时已确认是原始状态");
                status(item,"restored","restored","已核对恢复结果，保留原始状态",false);
                return true;
            }
            item.remove("journalId");
            item.remove("before");
            status(item,"checking","checking","已核对原值，重新检查当前目标",false);
            store.save("scan",results);
            store.finish(id,"restored","重新读取时已确认是原始状态");
            return false;
        }
        if(found.checked!=after)throw new UiDriver.Halt("当前状态与记录不同，请手动核对",false,false,"state_changed");
        store.finish(id,"changed","重新读取后已确认");
        put(item,"matchedLabel",label);
        put(item,"before",before);
        if(after!=policy.desired(label))status(item,"blocked","policy_changed","已核对上次操作，当前规则需要手动处理",false);
        else status(item,"changed","confirmed","已重新读取，确认上次改动",false);
        return true;
    }
    private static String scanRuleId(JSONObject item){
        JSONObject rule=item==null?null:item.optJSONObject("rule");
        return rule==null?"":rule.optString("id");
    }
    static void upsertResult(JSONArray results,JSONObject item){
        replaceResult(results,item,true);
    }
    private static void upsertCheckpoint(JSONArray results,JSONObject item){
        replaceResult(results,item,false);
    }
    private static void replaceResult(JSONArray results,JSONObject item,boolean preserveMessage){
        for(int i=0;i<results.length();i++){
            JSONObject old=results.optJSONObject(i);
            if(old!=null&&scanRuleId(old).equals(scanRuleId(item))&&old.optString("label").equals(item.optString("label"))){
                if(preserveMessage)put(item,"previousMessage",old.optString("message"));
                try{results.put(i,item);}catch(JSONException e){throw new IllegalStateException(e);}
                return;
            }
        }
        results.put(item);
    }
    private static JSONObject scanResult(JSONArray results,String ruleId,String label){
        for(int i=0;i<results.length();i++){
            JSONObject item=results.optJSONObject(i);
            if(item!=null&&scanRuleId(item).equals(ruleId)&&item.optString("label").equals(label))return item;
        }
        return null;
    }
    static List<Rule> automaticRetry(List<Rule> rules,JSONArray results){return retryTargets(rules,results,true);}
    static List<Rule> retryTargets(List<Rule> rules,JSONArray results,boolean automatic){
        return selectTargets(rules,results,automatic,false);
    }
    static List<Rule> resumableTargets(List<Rule> rules,JSONArray results){
        return selectTargets(rules,results,false,true);
    }
    static List<Rule> retrySelection(List<Rule> rules,JSONArray results,JSONObject item){
        String selectedId=scanRuleId(item);
        String target=item==null?"":item.optString("label");
        for(Rule rule:retryTargets(rules,results,false))if(rule.id.equals(selectedId)){
            if(target.isEmpty())return Collections.singletonList(rule);
            if(rule.targets.contains(target))return Collections.singletonList(rule.onlyTargets(Collections.singletonList(target)));
        }
        return Collections.emptyList();
    }
    private static List<Rule> selectTargets(List<Rule> rules,JSONArray results,boolean automatic,boolean resumeOnly){
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
                if(item==null||!unfinished(item))continue;
                boolean resume=needsContinuation(item);
                if(resumeOnly&&!resume)continue;
                if(automatic&&(resume||!item.optBoolean("retryable")))continue;
                labels.add(label);
            }
            if(!labels.isEmpty())retry.add(rule.onlyTargets(labels));
        }
        return retry;
    }
    static boolean unfinished(JSONObject item){
        String status=item.optString("status");
        return status.equals("missing")||status.equals("failed")||status.equals("skipped")||status.equals("queued")||
                status.equals("checking")||status.equals("verify");
    }
    static boolean needsContinuation(JSONObject item){
        if(item==null||!unfinished(item))return false;
        String state=item.optString("status");
        if(state.equals("queued")||state.equals("checking")||state.equals("verify")||!item.optString("journalId").isEmpty())return true;
        // The 0.x records could store a terminal interruption as a failed target or a skipped group.
        String message=item.optString("message");
        return message.equals("目标页面已离开前台，任务已停止")||message.equals("设置页面已切换，任务已停止")||
                message.equals("手机已锁屏，本次操作已停止")||message.startsWith("清理任务被中断")||
                message.equals("无障碍连接已断开，任务已停止")||message.equals("无障碍服务已关闭，任务已停止")||
                message.equals("已手动停止");
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
        status(item,"queued","queued","任务暂停前尚未执行，可继续执行",false);
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
        String id=entry.getString("id");
        Rule r=new Rule(entry.getJSONObject("rule"));
        String label=entry.getString("label");
        UiDriver ui=driver(taskForeground);
        note("恢复 · "+label);
        boolean[] prepared={false};
        try{
            validateRecordedRoute(store,r,entry);
            ui.openForRestore(r,label);
            UiDriver.Found f=ui.findExact(label);
            if(f==null){
                ui.pause(400);
                ui.top();
                f=ui.findExact(label);
            }
            if(f==null)throw new UiDriver.Halt("找不到原开关，未操作");
            boolean before=entry.getBoolean("before"),after=entry.getBoolean("after");
            if(f.checked==before){store.finish(id,"restored","原始状态已确认");note("已经是原始状态");return;}
            if(f.checked!=after)throw new UiDriver.Halt("当前状态与记录不同，未覆盖");
            if(!ui.set(f,before,()->{
                store.finish(id,"restoring","恢复前已核对当前状态");
                prepared[0]=true;
            }))throw new UiDriver.Halt("恢复结果未能确认，请重新读取");
            store.finish(id,"restored","已恢复原值并回读确认");note("已恢复 · "+label);
        }catch(UiDriver.Halt e){
            if(e.terminal)pauseFor(e);
            if(prepared[0])stopFor(e.getMessage());
            store.finish(id,prepared[0]?"restore_failed":entry.optString("status","unknown"),e.getMessage());
            note("恢复未完成，请查看记录");
        }
    }
    private static void validateRecordedRoute(Store store,Rule rule,JSONObject entry)throws JSONException,UiDriver.Halt{
        if(!Build.FINGERPRINT.equals(entry.optString("build")))
            throw new UiDriver.Halt("系统版本已变化，请按原路径手动核对",false,false,"build_changed");
        long previousVersion=entry.optLong("packageVersion",-1);
        if(previousVersion>=0&&previousVersion!=store.packageVersion(rule.pkg))
            throw new UiDriver.Halt("目标应用版本已变化，请按原路径手动核对",false,false,"package_changed");
        JSONObject versions=entry.optJSONObject("routeVersions");
        if(versions==null)return;
        Iterator<String> packages=versions.keys();
        while(packages.hasNext()){
            String pkg=packages.next();
            if(versions.getLong(pkg)!=store.packageVersion(pkg))
                throw new UiDriver.Halt("设置入口涉及的应用版本已变化，请手动核对",false,false,"package_changed");
        }
    }
    static void put(JSONObject j,String key,Object value){try{j.put(key,value);}catch(JSONException e){throw new IllegalStateException(e);}}
    private interface Work{void run()throws Exception;}
    private void start(Work work){
        if(get()!=this||worker.isShutdown())return;
        busy=true;
        stopReason=null;
        returnToResults=true;
        taskForeground=new UiDriver.Foreground(getPackageName());
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
        UiDriver.Foreground foreground=taskForeground;
        taskForeground=null;
        if(get()!=this||!returnToResults||foreground==null||!foreground.owns(getRootInActiveWindow()))return;
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
