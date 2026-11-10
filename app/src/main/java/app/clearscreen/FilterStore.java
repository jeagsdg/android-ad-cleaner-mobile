package app.clearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

final class FilterStore {
    private final SharedPreferences prefs;
    FilterStore(Context context){prefs=context.getSharedPreferences("network-filter",Context.MODE_PRIVATE);}
    boolean enabled(){return prefs.getBoolean("enabled",false);}
    void enabled(boolean value){prefs.edit().putBoolean("enabled",value).apply();}
    String message(){return prefs.getString("message","尚未开启");}
    void message(String value){prefs.edit().putString("message",value).apply();}
    long blocked(){return prefs.getLong("blocked",0);}
    long queried(){return prefs.getLong("queried",0);}
    boolean keepsDomainDetails(){return prefs.getBoolean("keep-domain-details",!prefs.getString("recent","").isEmpty());}
    void setKeepDomainDetails(boolean enabled){
        SharedPreferences.Editor update=prefs.edit().putBoolean("keep-domain-details",enabled);
        if(!enabled)update.remove("recent");
        update.apply();
    }
    void counters(long queried,long blocked,Collection<String> recent){
        SharedPreferences.Editor update=prefs.edit().putLong("queried",queried).putLong("blocked",blocked);
        if(keepsDomainDetails())update.putString("recent",String.join("\n",recent));
        else update.remove("recent");
        update.apply();
    }
    List<String> recent(){String s=keepsDomainDetails()?prefs.getString("recent",""):"";return s.isEmpty()?new ArrayList<>():new ArrayList<>(Arrays.asList(s.split("\n")));}
    Set<String> allowed(){return new HashSet<>(prefs.getStringSet("allowed",Collections.emptySet()));}
    void allow(String host,boolean value){Set<String> set=allowed();if(value)set.add(host);else set.remove(host);prefs.edit().putStringSet("allowed",set).apply();}
    void clearHistory(){if(!AdFilterService.clearRecent())forgetRecent();}
    void forgetRecent(){prefs.edit().remove("recent").apply();}
}
