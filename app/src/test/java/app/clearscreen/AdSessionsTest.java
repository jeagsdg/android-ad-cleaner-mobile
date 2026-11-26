package app.clearscreen;
import java.util.EnumMap;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdSessionsTest {
    private static final class Counts implements AdSessions.Sink {
        final EnumMap<AdSessions.Metric,Integer> values=new EnumMap<>(AdSessions.Metric.class);
        long duration;
        public void record(AdSessions.Metric m){values.merge(m,1,Integer::sum);}
        public void latency(long ms){duration+=ms;}
        int get(AdSessions.Metric m){return values.getOrDefault(m,0);}
    }
    @Test public void deduplicatesScansAndVerifiesOneExitBeforeTheConservativeCountdownDeadline(){
        Counts counts=new Counts();AdSessions s=new AdSessions(counts);
        for(int i=0;i<30;i++)s.observed(4,100+i*100,15);
        s.candidate(4,3600);s.attempt(4,3670);s.attempt(4,3700);s.accepted(4);
        s.linkExitTimer(4,14100);
        s.finish(4,4000,true,null);s.finish(4,4100,true,null);s.leave();
        for(AdSessions.Metric m:new AdSessions.Metric[]{AdSessions.Metric.DETECTED,AdSessions.Metric.MATCHED,
                AdSessions.Metric.SAFE_EXIT,AdSessions.Metric.ATTEMPTED,AdSessions.Metric.ACCEPTED,
                AdSessions.Metric.CLOSED,AdSessions.Metric.EARLY})assertEquals(m.name(),1,counts.get(m));
        assertEquals(400,counts.duration);
    }
    @Test public void neverCreditsLateCallbacksRejectedActionsOrExpiredTimersAsEarly(){
        Counts counts=new Counts();AdSessions s=new AdSessions(counts);
        s.observed(1,100,3);s.leave();assertEquals(1,counts.get(AdSessions.Metric.NO_SAFE_EXIT));
        s.attempt(2,500);s.finish(2,550,false,AdSessions.Metric.ACTION_FAILED);s.finish(2,580,true,null);s.leave();
        assertEquals(1,counts.get(AdSessions.Metric.UNCERTAIN));assertEquals(0,counts.get(AdSessions.Metric.CLOSED));
        s.observed(3,1000,2);s.attempt(3,1200);s.accepted(3);s.finish(2,1250,true,null);
        s.finish(3,2450,true,null);
        assertEquals(1,counts.get(AdSessions.Metric.CLOSED));assertEquals(0,counts.get(AdSessions.Metric.EARLY));
    }
    @Test public void separateAdsOnTheSameActivityDoNotShareOneDenominator(){
        Counts counts=new Counts();AdSessions s=new AdSessions(counts);
        s.observed(1,100,15);s.clearFrame(1,16100);
        s.observed(1,22000,7);s.attempt(1,22500);s.linkExitTimer(1,28000);s.accepted(1);s.finish(1,22900,true,null);
        assertEquals(2,counts.get(AdSessions.Metric.DETECTED));
        assertEquals(1,counts.get(AdSessions.Metric.NO_SAFE_EXIT));
        assertEquals(1,counts.get(AdSessions.Metric.EARLY));
    }
}
