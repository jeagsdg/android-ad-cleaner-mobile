package app.clearscreen;

import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class CleanRetryTest {
    Rule rule() throws Exception { return new Rule(new JSONObject("{\"id\":\"test\",\"package\":\"com.example.fixture\",\"targets\":[\"已成功\",\"暂未出现\",\"歧义开关\"]}")); }
    JSONObject result(Rule rule,String label,String status,boolean retry) throws Exception {
        return new JSONObject().put("rule",rule.json()).put("label",label).put("key",rule.id+":"+label).put("status",status).put("retryable",retry);
    }
    @Test public void retryOnlyUntouchedRecoverableFailures() throws Exception {
        Rule rule=rule();JSONArray scan=new JSONArray().put(result(rule,"已成功","changed",true)).put(result(rule,"暂未出现","missing",true)).put(result(rule,"歧义开关","failed",false));
        List<Rule> retry=CleanerService.automaticRetry(Collections.singletonList(rule),scan);
        assertEquals(1,retry.size());assertEquals(Collections.singletonList("暂未出现"),retry.get(0).targets);assertEquals(3,rule.targets.size());
    }
    @Test public void secondAttemptReplacesFailureAndKeepsEarlierReason() throws Exception {
        Rule rule=rule();JSONObject before=result(rule,"暂未出现","missing",true).put("message","页面尚未准备好");JSONArray scan=new JSONArray().put(before);
        CleanerService.upsertResult(scan,result(rule,"暂未出现","changed",false).put("pass",2).put("message","已回读确认"));
        assertEquals(1,scan.length());assertEquals("changed",scan.getJSONObject(0).getString("status"));assertEquals("页面尚未准备好",scan.getJSONObject(0).getString("previousMessage"));
        assertTrue(CleanerService.automaticRetry(Collections.singletonList(rule),scan).isEmpty());
    }
}
