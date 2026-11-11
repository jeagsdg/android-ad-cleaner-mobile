package app.clearscreen;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

final class FilterStore {
    static final int MAX_USER_HOSTS = 500;
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
    boolean allowsHost(String host){return prefs.getStringSet("allowed",Collections.emptySet()).contains(host);}
    void allow(String host,boolean value){Set<String> set=allowed();if(value)set.add(host);else set.remove(host);prefs.edit().putStringSet("allowed",set).apply();}
    Set<String> customHosts(){return new HashSet<>(prefs.getStringSet("custom-hosts",Collections.emptySet()));}
    boolean customHost(String host){return prefs.getStringSet("custom-hosts",Collections.emptySet()).contains(host);}
    boolean blockHost(String host,boolean removeAllow){
        if(!DomainRules.canBlockHost(host)||!host.equals(DomainRules.normalize(host)))return false;
        Set<String> hosts=customHosts();
        if(!hosts.contains(host)&&hosts.size()>=MAX_USER_HOSTS)return false;
        hosts.add(host);
        SharedPreferences.Editor update=prefs.edit().putStringSet("custom-hosts",hosts);
        if(removeAllow){Set<String> allow=allowed();allow.remove(host);update.putStringSet("allowed",allow);}
        update.apply();
        return true;
    }
    void removeCustomHost(String host){Set<String> hosts=customHosts();hosts.remove(host);prefs.edit().putStringSet("custom-hosts",hosts).apply();}
    Set<String> bypassedApps(){return new HashSet<>(prefs.getStringSet("bypassed-apps",Collections.emptySet()));}
    void setBypassedApps(Set<String> packages){prefs.edit().putStringSet("bypassed-apps",new HashSet<>(packages)).apply();}
    void clearHistory(){if(!AdFilterService.clearRecent())forgetRecent();}
    void forgetRecent(){prefs.edit().remove("recent").apply();}
}
