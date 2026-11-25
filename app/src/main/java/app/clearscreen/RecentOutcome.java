package app.clearscreen;

import android.os.SystemClock;

/** A single recent app, two derived outcomes. No page text or disk history. */
final class RecentOutcome {
    private String app="",splash="",popup="";
    private long splashAt,popupAt,lastSeen;
    void visit(String pkg,long now){
        if(!app.equals(pkg)||now-lastSeen>300_000){app=pkg;splash=popup="";}
        lastSeen=now;
    }
    void observe(String pkg,boolean startup,String outcome){
        if(!app.equals(pkg))return;
        long now=SystemClock.elapsedRealtime();
        if(startup){splash=outcome;splashAt=now;}else{popup=outcome;popupAt=now;}
    }
    String read(String pkg,boolean startup){
        if(!app.equals(pkg)||SystemClock.elapsedRealtime()-(startup?splashAt:popupAt)>300_000)return "";
        return startup?splash:popup;
    }
}
