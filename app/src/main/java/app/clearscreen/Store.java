package app.clearscreen;

import android.content.*;
import android.os.Build;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Store {
    static final String NOT_APPLIED="not_applied";
    private static final Object JOURNAL_LOCK=new Object();
    private static final Object SKIP_HISTORY_LOCK=new Object();
    private final Context context;
    private String cachedRulesJson;
    private List<CustomSkipRule> cachedRules = Collections.emptyList();
    public Store(Context c){context=c.getApplicationContext();}
    public String asset(String name){try(var in=context.getAssets().open(name);var out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("规则文件无法读取",e);}}
    public Policy policy(){try{return new Policy(new JSONObject(asset("policy.json")));}catch(JSONException e){throw new IllegalStateException(e);}}
    private SharedPreferences prefs(){return context.getSharedPreferences("local-ledger",Context.MODE_PRIVATE);}
    public boolean splashEnabled(){return prefs().getBoolean("splash-enabled",false);}
    public void setSplashEnabled(boolean enabled){prefs().edit().putBoolean("splash-enabled",enabled).apply();}
    public boolean popupEnabled(){return prefs().getBoolean("popup-enabled",false);}
    public void setPopupEnabled(boolean enabled){prefs().edit().putBoolean("popup-enabled",enabled).apply();}
    void enableAdFeatures(boolean splash, boolean popup){
        SharedPreferences.Editor edit=prefs().edit();
        if(splash)edit.putBoolean("splash-enabled",true);
        if(popup)edit.putBoolean("popup-enabled",true);
        edit.apply();
    }
    public boolean skipToastEnabled(){return prefs().getBoolean("skip-toast",true);}
    public void setSkipToastEnabled(boolean enabled){prefs().edit().putBoolean("skip-toast",enabled).apply();}
    boolean updateChecksEnabled(){return context.getResources().getBoolean(R.bool.network_filter_available)
            &&prefs().getBoolean("update-checks",true);}
    void setUpdateChecksEnabled(boolean enabled){prefs().edit().putBoolean("update-checks",enabled).apply();}
    long lastUpdateCheck(){return prefs().getLong("last-update-success",0);}
    void noteUpdateCheck(long now){prefs().edit().putLong("last-update-success",now).apply();}
    long lastUpdateAttempt(){return prefs().getLong("last-update-attempt",0);}
    void noteUpdateAttempt(long now){prefs().edit().putLong("last-update-attempt",now).apply();}
    ReleaseUpdates.Info cachedRelease(){
        SharedPreferences data=prefs();
        String saved=data.getString("public-release-info","");
        if(saved.isEmpty())return null;
        try{return ReleaseUpdates.parse(saved,data.getBoolean("public-release-download-via-api",false));}
        catch(java.io.IOException ignored){return null;}
    }
    void cacheRelease(ReleaseUpdates.Info release){
        JSONObject info=new JSONObject();
        CleanerService.put(info,"schema",1);
        CleanerService.put(info,"versionCode",release.versionCode);
        CleanerService.put(info,"versionName",release.versionName);
        CleanerService.put(info,"notes",release.notes);
        prefs().edit().putString("public-release-info",info.toString())
                .putBoolean("public-release-download-via-api",release.preferApiDownload).apply();
    }
    boolean keepsSkipDetails(){
        SharedPreferences data=prefs();
        return data.getBoolean("keep-skip-details",data.contains("splash-history")||data.contains("popup-history"));
    }
    void setKeepSkipDetails(boolean enabled){
        synchronized(SKIP_HISTORY_LOCK){
            SharedPreferences.Editor update=prefs().edit().putBoolean("keep-skip-details",enabled);
            if(!enabled)update.remove("splash-history").remove("popup-history");
            update.apply();
        }
    }
    public boolean skipKeepAliveEnabled(){return prefs().getBoolean("skip-keep-alive",false);}
    public void setSkipKeepAliveEnabled(boolean enabled){prefs().edit().putBoolean("skip-keep-alive",enabled).apply();}
    public Set<String> mediaAssistEnabled(){return new HashSet<>(prefs().getStringSet("media-assist-enabled",Collections.emptySet()));}
    public void setMediaAssistEnabled(Set<String> enabled){prefs().edit().putStringSet("media-assist-enabled",new HashSet<>(enabled)).apply();}
    public Set<String> skipExcluded(String mode){return new HashSet<>(prefs().getStringSet(mode+"-only-excluded",Collections.emptySet()));}
    public void setSkipExcluded(String mode,Set<String> packages){prefs().edit().putStringSet(mode+"-only-excluded",new HashSet<>(packages)).apply();}
    synchronized List<CustomSkipRule> customSkipRules(){
        String json=prefs().getString("custom-skip-rules","");
        if(!json.equals(cachedRulesJson)){
            cachedRules=json.isEmpty()?Collections.emptyList():Collections.unmodifiableList(CustomSkipRule.parse(json));
            cachedRulesJson=json;
        }
        return cachedRules;
    }
    synchronized void saveCustomSkipRules(List<CustomSkipRule> rules){
        String json=CustomSkipRule.export(rules);
        CustomSkipRule.parse(json);
        if(!prefs().edit().putString("custom-skip-rules",json).commit())throw new IllegalStateException("规则保存失败");
    }
    public Set<String> splashExcluded(){return new HashSet<>(prefs().getStringSet("splash-excluded",Collections.emptySet()));}
    public void setSplashExcluded(Set<String> packages){prefs().edit().putStringSet("splash-excluded",new HashSet<>(packages)).apply();}
    public synchronized void recordSplash(String pkg,boolean confirmed){
        recordSkip("splash",pkg,confirmed);
    }
    synchronized void recordPopup(String pkg,boolean confirmed){recordSkip("popup",pkg,confirmed);}
    private void recordSkip(String kind,String pkg,boolean confirmed){
        synchronized(SKIP_HISTORY_LOCK){
            String history=kind+"-history",count=kind+"-confirmed";
            SharedPreferences data=prefs();
            SharedPreferences.Editor update=data.edit();
            if(keepsSkipDetails()){
                JSONArray old=read(history),next=new JSONArray();JSONObject item=new JSONObject();
                CleanerService.put(item,"package",pkg);CleanerService.put(item,"confirmed",confirmed);
                CleanerService.put(item,"time",System.currentTimeMillis());next.put(item);
                for(int i=0;i<Math.min(19,old.length());i++)next.put(old.opt(i));
                update.putString(history,next.toString());
            }
            if(confirmed)update.putLong(count,data.getLong(count,0)+1);
            update.apply();
        }
    }
    public long splashCount(){return prefs().getLong("splash-confirmed",0);}
    public long popupCount(){return prefs().getLong("popup-confirmed",0);}
    public String lastRun(){return prefs().getString("last-run","");}
    public void setLastRun(String value){if(!prefs().edit().putString("last-run",value).commit())throw new IllegalStateException("记录保存失败");}
    public synchronized JSONArray read(String key){try{return new JSONArray(prefs().getString(key,"[]"));}catch(JSONException e){throw new IllegalStateException("本地记录损坏，已停止操作",e);}}
    public synchronized void save(String key,JSONArray value){if(!prefs().edit().putString(key,value.toString()).commit())throw new IllegalStateException("记录保存失败，已停止操作");}
    public List<Rule> allRules(){try{JSONArray a=new JSONArray(asset("rules.json"));List<Rule> r=new ArrayList<>();for(int i=0;i<a.length();i++)r.add(new Rule(a.getJSONObject(i)));return r;}catch(JSONException e){throw new IllegalStateException(e);}}
    public List<Rule> available(){List<Rule> result=new ArrayList<>();for(Rule r:allRules())if(r.matches(Build.MANUFACTURER,Build.BRAND)){for(Route route:r.routes)if(packageVersion(route.pkg)>=0){result.add(r);break;}}return result;}
    public List<Rule> plan(){
        List<Rule> result=new ArrayList<>();
        boolean channelsSupported=Build.VERSION.SDK_INT>=Build.VERSION_CODES.O;
        for(Rule rule:available())if(!rule.manual&&(channelsSupported||!rule.notification()))result.add(rule);
        if(!channelsSupported)return result;
        Policy policy=policy();
        JSONArray channels=read("channels");
        for(int i=0;i<channels.length();i++){
            JSONObject entry=channels.optJSONObject(i);
            if(entry==null||entry.optBoolean("uncertain"))continue;
            Rule rule=new Rule(entry);
            if(rule.notification()&&packageVersion(rule.pkg)>=0
                    &&policy.classify(rule.channelName,rule.channelId,rule.pkg,rule.channelApp).recommended()
                    &&!policy.criticalNotification(rule.channelName))result.add(rule);
        }
        return result;
    }
    public List<Rule> retryRules(){return CleanerService.retryTargets(plan(),read("scan"),false);}
    public List<Rule> resumeRules(){return CleanerService.resumableTargets(plan(),read("scan"));}
    public List<Rule> retrySelection(JSONObject item){return CleanerService.retrySelection(plan(),read("scan"),item);}
    public List<JSONObject> restorable(){
        List<JSONObject> result=new ArrayList<>();
        JSONArray journal=read("journal");
        Set<List<String>> seen=new HashSet<>();
        for(int i=journal.length()-1;i>=0;i--){
            JSONObject entry=journal.optJSONObject(i),rule=entry==null?null:entry.optJSONObject("rule");
            if(rule==null||entry.optString("id").isEmpty())continue;
            if(NOT_APPLIED.equals(entry.optString("status")))continue;
            if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O&&!rule.optString("channelId").isEmpty())continue;
            List<String> key=Arrays.asList(rule.optString("package"),rule.optString("id"),entry.optString("label"));
            if(seen.add(key)&&!entry.optString("status").equals("restored"))result.add(entry);
        }
        return result;
    }
    public String appVersion(){try{return context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;}catch(Exception e){return "未知版本";}}
    private JSONObject profile(){try{JSONArray a=new JSONArray(asset("device-profiles.json"));for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);if(!Rule.strings(p.optJSONArray("brands")).stream().anyMatch(b->b.equalsIgnoreCase(Build.BRAND)||b.equalsIgnoreCase(Build.MANUFACTURER)))continue;for(String model:Rule.strings(p.optJSONArray("models")))if(model.equalsIgnoreCase(Build.MODEL)||model.equalsIgnoreCase(Build.DEVICE))return p;}}catch(JSONException e){throw new IllegalStateException(e);}return new JSONObject();}
    public String deviceName(){return profile().optString("name",Build.MANUFACTURER+" "+Build.MODEL);}
    public String deviceDescription(){
        String family;
        switch(PermissionAccess.family()){
            case "coloros": family="ColorOS / 系列系统"; break;
            case "honor": family="MagicOS"; break;
            case "xiaomi": family="MIUI / HyperOS"; break;
            case "vivo": family="OriginOS / Funtouch OS"; break;
            case "meizu": family="Flyme"; break;
            case "samsung": family="三星系统"; break;
            case "huawei": family="华为 Android 兼容系统"; break;
            default: family=""; break;
        }
        return (family.isEmpty()?"":family+" · ")+"Android "+Build.VERSION.RELEASE;
    }
    public JSONObject deviceInfo(){JSONObject out=new JSONObject();try{out.put("name",deviceName());out.put("manufacturer",Build.MANUFACTURER);out.put("brand",Build.BRAND);out.put("model",Build.MODEL);out.put("device",Build.DEVICE);out.put("android",Build.VERSION.RELEASE);out.put("sdk",Build.VERSION.SDK_INT);out.put("display",Build.DISPLAY);out.put("fingerprint",Build.FINGERPRINT);out.put("securityPatch",Build.VERSION.SECURITY_PATCH);out.put("oemVerified",false);JSONObject packages=new JSONObject();for(Rule r:available())for(Route route:r.routes){if(packageVersion(route.pkg)>=0)packages.put(route.pkg,packageVersion(route.pkg));if(packageVersion(route.finalPackage())>=0)packages.put(route.finalPackage(),packageVersion(route.finalPackage()));}out.put("targetPackageVersions",packages);}catch(JSONException e){throw new IllegalStateException(e);}return out;}
    public long packageVersion(String pkg){try{android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(pkg,0);return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}catch(Exception e){return -1;}}
    public JSONObject routeVersions(Rule r){JSONObject versions=new JSONObject();Set<String> packages=new LinkedHashSet<>();packages.add(r.pkg);for(Route route:r.routes){packages.add(route.pkg);packages.add(route.foreground);packages.addAll(route.destinations);}for(String pkg:packages){long version=packageVersion(pkg);if(version>=0)try{versions.put(pkg,version);}catch(JSONException e){throw new IllegalStateException(e);}}return versions;}
    public String prepare(Rule rule,String label,boolean before,boolean after){
        synchronized(JOURNAL_LOCK){
            String id=UUID.randomUUID().toString();JSONArray a=read("journal");JSONObject j=new JSONObject();
            try{j.put("id",id);j.put("rule",rule.json());j.put("packageVersion",packageVersion(rule.pkg));j.put("routeVersions",routeVersions(rule));j.put("label",label);j.put("before",before);j.put("after",after);j.put("status","pending");j.put("time",System.currentTimeMillis());j.put("build",Build.FINGERPRINT);j.put("message","操作前已保存原值");a.put(j);save("journal",a);return id;}catch(JSONException e){throw new IllegalStateException(e);}
        }
    }
    public void finish(String id,String status,String message){
        synchronized(JOURNAL_LOCK){
            JSONArray journal=read("journal");
            for(int i=0;i<journal.length();i++){
                JSONObject entry=journal.optJSONObject(i);
                if(entry==null||!id.equals(entry.optString("id")))continue;
                try{entry.put("status",status);entry.put("message",message);entry.put("updated",System.currentTimeMillis());}
                catch(JSONException e){throw new IllegalStateException(e);}
                save("journal",journal);
                return;
            }
            throw new IllegalStateException("未找到原始改动记录，已停止操作");
        }
    }
    public String export(){
        JSONObject root=new JSONObject();
        try{
            root.put("app","净屏 "+appVersion());
            root.put("device",deviceInfo());
            root.put("permissions",PermissionAccess.diagnostics(context));
            if(context.getResources().getBoolean(R.bool.network_filter_available)){
                JSONObject network=new JSONObject();
                network.put("running",AdFilterService.running());
                network.put("status",new FilterStore(context).message());
                network.put("blockedDnsRequests",AdFilterService.blocked(context));
                root.put("networkFilter",network);
            }
            root.put("splashEnabled",splashEnabled());
            root.put("splashConfirmed",splashCount());
            root.put("lastRun",lastRun());
            root.put("journal",read("journal"));
            root.put("scan",read("scan"));
        }catch(JSONException e){throw new IllegalStateException(e);}
        return root.toString();
    }
    public String exportText(){try{return new JSONObject(export()).toString(2);}catch(JSONException e){throw new IllegalStateException(e);}}
    String compactDiagnosticReport() {
        try {
            JSONObject report = new JSONObject();
            report.put("app", "净屏 " + appVersion());
            report.put("manufacturer", Build.MANUFACTURER);
            report.put("model", Build.MODEL);
            report.put("android", Build.VERSION.RELEASE);
            report.put("sdk", Build.VERSION.SDK_INT);
            report.put("systemBuild", Build.DISPLAY);
            report.put("permissions", PermissionAccess.diagnostics(context));
            report.put("lastRun", lastRun());
            JSONArray scan = read("scan");
            JSONArray results = new JSONArray();
            for (int i = 0; i < scan.length(); i++) {
                JSONObject row = scan.optJSONObject(i);
                JSONObject rule = row == null ? null : row.optJSONObject("rule");
                if (rule == null) continue;
                JSONObject result = new JSONObject();
                result.put("id", rule.optString("id"));
                result.put("group", rule.optString("title"));
                result.put("package", rule.optString("package"));
                for (String key : Arrays.asList("label", "status", "message", "reason", "route", "routePackage",
                        "attemptCount", "attempts")) if (row.has(key)) result.put(key, row.get(key));
                results.put(result);
            }
            report.put("results", results);
            return report.toString(2);
        } catch (JSONException e) { throw new IllegalStateException("诊断报告暂时无法生成", e); }
    }
}
