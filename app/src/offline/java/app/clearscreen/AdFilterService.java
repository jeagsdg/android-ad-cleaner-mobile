package app.clearscreen;

import android.content.Context;

/** State adapter for shared screens. The offline APK has no VPN service. */
final class AdFilterService {
    static final String STOP = "app.clearscreen.FILTER_STOP";
    static final AdFilterService active = null;

    private AdFilterService() { }

    static void saveBypassedApps(Context context,java.util.Set<String> packages,java.util.function.Consumer<String> result){
        new FilterStore(context).setBypassedApps(packages);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->result.accept("已保存"));
    }
    static boolean running() { return false; }
    static String health() { return "offline build"; }
    static long blocked(Context context) { return 0; }
    static boolean clearRecent() { return false; }
    static void setKeepDomainDetails(Context context, boolean enabled) {
        new FilterStore(context).setKeepDomainDetails(enabled);
    }
}
