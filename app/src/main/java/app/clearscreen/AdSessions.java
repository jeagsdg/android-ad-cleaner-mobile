package app.clearscreen;

/** One observed ad episode per foreground visit. Inputs contain no screen contents. */
final class AdSessions {
    enum Metric { DETECTED, MATCHED, SAFE_EXIT, ATTEMPTED, ACCEPTED, CLOSED, EARLY,
        NO_SAFE_EXIT, UNCERTAIN, ACTION_FAILED, USER_INTERRUPTED, STILL_VISIBLE, MISCLICK_REPORTED,
        MANUAL_REPORTED, NATURAL_REPORTED }
    interface Sink { void record(Metric metric); void latency(long millis); }
    private final Sink sink;
    private boolean observed, matched, safe, attempted, accepted;
    private boolean exitHasTimer;
    private long visit, firstExit, earliestNaturalEnd;
    private long cleanSince=-1,lastEvidence;
    private Metric pendingFailure;
    AdSessions(Sink sink){this.sink=sink;}
    void observed(long currentVisit,long now,int seconds){
        if(observed && (currentVisit!=visit || cleanSince>=0 && now-cleanSince>=650
                || now-lastEvidence>=120000))leave();
        lastEvidence=now;cleanSince=-1;
        if(!observed){observed=true;visit=currentVisit;sink.record(Metric.DETECTED);}
        // Allow one second of display rounding. Repeated or frozen labels must
        // never extend the evidence for an early exit.
        if(seconds>=0){
            long lowerBound=now+Math.max(0,seconds-1)*1000L;
            if(earliestNaturalEnd==0||lowerBound<earliestNaturalEnd)earliestNaturalEnd=lowerBound;
        }
    }
    void candidate(long v,long now){
        observed(v,now,-1);
        if(!matched){matched=true;sink.record(Metric.MATCHED);}
        if(!safe){safe=true;firstExit=now;sink.record(Metric.SAFE_EXIT);}
    }
    void attempt(long v,long now){
        candidate(v,now);
        if(!attempted){attempted=true;sink.record(Metric.ATTEMPTED);}
    }
    void accepted(long v){
        if(observed&&v==visit&&attempted&&!accepted){accepted=true;sink.record(Metric.ACCEPTED);}
    }
    void linkExitTimer(long v,long end){
        if(!observed||v!=visit||end==Long.MAX_VALUE)return;
        earliestNaturalEnd=earliestNaturalEnd==0?end:Math.min(end,earliestNaturalEnd);
        exitHasTimer=true;
    }
    void finish(long v,long now,boolean closed,Metric failure){
        if(!observed||v!=visit||!attempted)return;
        if(closed&&accepted){
            sink.record(Metric.CLOSED);sink.latency(Math.max(0,now-firstExit));
            if(exitHasTimer&&now<earliestNaturalEnd)sink.record(Metric.EARLY);
        }else{
            if(failure!=null && pendingFailure!=failure)sink.record(failure);
            pendingFailure=failure;
            // A later tree can be another attempt on this same episode.
            // Keep its denominator until the visit ends or an exit is verified.
            return;
        }
        reset();
    }
    void leave(){
        if(observed)sink.record(safe?Metric.UNCERTAIN:Metric.NO_SAFE_EXIT);
        reset();
    }
    void clearFrame(long currentVisit,long now){
        if(!observed||currentVisit!=visit)return;
        if(cleanSince<0)cleanSince=now;
        else if(now-cleanSince>=650)leave();
    }
    private void reset(){observed=matched=safe=attempted=accepted=exitHasTimer=false;earliestNaturalEnd=0;pendingFailure=null;cleanSince=-1;}
}
