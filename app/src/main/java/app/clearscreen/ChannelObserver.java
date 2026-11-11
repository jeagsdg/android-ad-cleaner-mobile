package app.clearscreen;

import android.app.NotificationChannel;
import android.os.Build;
import android.service.notification.*;
import org.json.*;

/** Stores channel metadata only. Never reads message bodies or dismisses notifications. */
public final class ChannelObserver extends NotificationListenerService {
    public static volatile boolean connected;
    @Override public void onListenerConnected(){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O){connected=false;return;}
        connected=true;
        StatusBarNotification[] active=getActiveNotifications();
        if(active!=null)for(StatusBarNotification notification:active)remember(notification,getCurrentRanking());
    }
    @Override public void onListenerDisconnected(){connected=false;}
    @Override public void onNotificationPosted(StatusBarNotification n,RankingMap map){remember(n,map);}
    private void remember(StatusBarNotification n,RankingMap map){
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O)return;
        if(n.getPackageName().equals(getPackageName()))return;
        Ranking rank=new Ranking();if(map==null||!map.getRanking(n.getKey(),rank))return;
        NotificationChannel c=rank.getChannel();if(c==null||c.getName()==null)return;
        Store store=new Store(this);
        String name=c.getName().toString(),pkg=n.getPackageName(),app=pkg;
        try{app=getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}catch(Exception ignored){}
        Policy.Decision d=store.policy().classify(name,c.getId(),pkg,app);
        if(!d.candidate||d.excluded||d.protectedValue||store.policy().criticalNotification(name))return;
        synchronized(ChannelObserver.class){JSONArray a=store.read("channels"),out=new JSONArray();
            for(int i=0;i<a.length();i++){JSONObject j=a.optJSONObject(i);if(j!=null&&!(pkg.equals(j.optString("package"))&&c.getId().equals(j.optString("channelId"))))out.put(j);}
            try{JSONObject j=new JSONObject();j.put("id","channel:"+pkg+":"+c.getId());j.put("title",app+" · "+name);j.put("group","营销通知");j.put("package",pkg);j.put("targets",new JSONArray().put("允许通知"));j.put("channelId",c.getId());j.put("channelName",name);j.put("channelApp",app);j.put("uncertain",d.uncertain);j.put("seen",System.currentTimeMillis());out.put(j);while(out.length()>150)out.remove(0);store.save("channels",out);}catch(JSONException ignored){}
        }
    }
}
