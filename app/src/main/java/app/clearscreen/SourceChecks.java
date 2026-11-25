package app.clearscreen;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.*;

/** Opt-in update discovery; fetching a subscription never enables or installs new rules. */
final class SourceChecks {
    private SourceChecks(){}
    private static final AtomicBoolean running=new AtomicBoolean();
    private static final long DAY=24L*60*60*1000;
    static void onForeground(MainActivity a){
        if(!a.networkFilterAvailable()||!running.compareAndSet(false,true))return;
        Context c=a.getApplicationContext();
        if(!c.getSharedPreferences("community-rules",0).contains("sources")){running.set(false);return;}
        new Thread(()->{
            try{
                JSONObject sources=CommunityRules.all(c);List<String> keys=new ArrayList<>();sources.keys().forEachRemaining(keys::add);
                long now=System.currentTimeMillis();for(String url:keys){
                    JSONObject prev=sources.getJSONObject(url);long age=now-prev.optLong("checked",0);
                    if(!url.startsWith("https://")||!prev.optBoolean("enabled")||!prev.optBoolean("checkUpdates")||(age>=0&&age<DAY))continue;
                    int version=-1;try{JSONObject offered=SubscriptionJson.read(SubscriptionFetch.get(url));
                        if(!prev.has("id")||prev.optLong("id") == offered.optLong("id",Long.MIN_VALUE))version=offered.optInt("version",-1);}catch(Exception ignored){}
                    final int offered=version;
                    new Handler(Looper.getMainLooper()).post(()->{
                        try{JSONObject latest=CommunityRules.all(c),row=latest.optJSONObject(url);
                            if(row==null||!row.optBoolean("enabled")||!row.optBoolean("checkUpdates")||row.optInt("version")!=prev.optInt("version"))return;
                            row.put("checked",now);if(offered>=0)row.put("available",offered);
                            CommunityRules.save(c,latest);
                            if(!a.isDestroyed()&&a.tab==MainActivity.COMMUNITY_RULES)a.render();
                        }catch(Exception ignored){}
                    });
                }
            }catch(Exception ignored){}finally{running.set(false);}
        },"subscription-versions").start();
    }
}
