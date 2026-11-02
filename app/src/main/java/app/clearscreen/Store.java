package app.clearscreen;

import android.content.*;
import android.os.Build;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Store {
    private final Context context;
    public Store(Context c){context=c.getApplicationContext();}
    public String asset(String name){try(var in=context.getAssets().open(name);var out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException("规则文件无法读取",e);}}
    public Policy policy(){try{return new Policy(new JSONObject(asset("policy.json")));}catch(JSONException e){throw new IllegalStateException(e);}}
    private SharedPreferences prefs(){return context.getSharedPreferences("local-ledger",Context.MODE_PRIVATE);}
    public String lastRun(){return prefs().getString("last-run","");}
    public void setLastRun(String value){if(!prefs().edit().putString("last-run",value).commit())throw new IllegalStateException("记录保存失败");}
    public synchronized JSONArray read(String key){try{return new JSONArray(prefs().getString(key,"[]"));}catch(JSONException e){throw new IllegalStateException("本地记录损坏，已停止操作",e);}}
    public synchronized void save(String key,JSONArray value){if(!prefs().edit().putString(key,value.toString()).commit())throw new IllegalStateException("记录保存失败，已停止操作");}
    public List<Rule> allRules(){try{JSONArray a=new JSONArray(asset("rules.json"));List<Rule> r=new ArrayList<>();for(int i=0;i<a.length();i++)r.add(new Rule(a.getJSONObject(i)));return r;}catch(JSONException e){throw new IllegalStateException(e);}}
    public List<Rule> available(){List<Rule> result=new ArrayList<>();for(Rule r:allRules())if(r.matches(Build.MANUFACTURER,Build.BRAND)){try{context.getPackageManager().getApplicationInfo(r.pkg,0);result.add(r);}catch(Exception ignored){}}return result;}
    public long packageVersion(String pkg){try{android.content.pm.PackageInfo info=context.getPackageManager().getPackageInfo(pkg,0);return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}catch(Exception e){return -1;}}
    public synchronized String prepare(Rule rule,String label,boolean before,boolean after){
        String id=UUID.randomUUID().toString();JSONArray a=read("journal");JSONObject j=new JSONObject();
        try{j.put("id",id);j.put("rule",rule.json());j.put("packageVersion",packageVersion(rule.pkg));j.put("label",label);j.put("before",before);j.put("after",after);j.put("status","pending");j.put("time",System.currentTimeMillis());j.put("build",Build.FINGERPRINT);j.put("message","操作前已保存原值");a.put(j);save("journal",a);return id;}catch(JSONException e){throw new IllegalStateException(e);}
    }
    public synchronized void finish(String id,String status,String message){JSONArray a=read("journal");for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j!=null&&id.equals(j.optString("id"))){try{j.put("status",status);j.put("message",message);j.put("updated",System.currentTimeMillis());}catch(JSONException e){throw new IllegalStateException(e);}}}save("journal",a);}
    public String export(){JSONObject root=new JSONObject();try{root.put("app","净屏 0.1.0-preview");root.put("model",Build.MODEL);root.put("android",Build.VERSION.RELEASE);root.put("journal",read("journal"));root.put("scan",read("scan"));}catch(JSONException e){throw new IllegalStateException(e);}return root.toString();}
}
