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
    @Test public void manualRetryLeavesCompletedAndUnselectedTargetsInResults() throws Exception {
        Rule rule=rule();
        JSONArray scan=new JSONArray().put(result(rule,"已成功","changed",false))
                .put(result(rule,"暂未出现","missing",true)).put(result(rule,"歧义开关","failed",false));
        List<Rule> retry=CleanerService.retryTargets(Collections.singletonList(rule),scan,false);
        assertEquals(Arrays.asList("暂未出现","歧义开关"),retry.get(0).targets);

        List<Rule> selected=Collections.singletonList(rule.onlyTargets(Collections.singletonList("暂未出现")));
        JSONArray merged=CleanerService.expandRetryResults(selected,scan);
        CleanerService.upsertResult(merged,result(rule,"暂未出现","changed",false));
        assertEquals(3,merged.length());
        assertEquals("changed",merged.getJSONObject(0).getString("status"));
        assertEquals("changed",merged.getJSONObject(1).getString("status"));
        assertEquals("failed",merged.getJSONObject(2).getString("status"));
    }
    @Test public void legacyGroupRetryUsesRecordedTargetsAndRespectsCompletedRows() throws Exception {
        Rule current=rule();
        Rule older=current.onlyTargets(Arrays.asList("已成功","暂未出现"));
        JSONArray scan=new JSONArray().put(result(older,"","skipped",false).put("message","旧版按组记录"))
                .put(result(older,"已成功","changed",false));
        List<Rule> retry=CleanerService.retryTargets(Collections.singletonList(current),scan,false);
        assertEquals(Collections.singletonList("暂未出现"),retry.get(0).targets);
        JSONArray expanded=CleanerService.expandRetryResults(retry,scan);
        CleanerService.upsertResult(expanded,result(current,"暂未出现","already",false));
        assertEquals(2,expanded.length());
        assertEquals("旧版按组记录",expanded.getJSONObject(1).getString("previousMessage"));
        assertTrue(CleanerService.retryTargets(Collections.singletonList(current),expanded,false).isEmpty());
    }
    @Test public void legacyGroupKeepsUnselectedTargetsOutsideTheCurrentPlan() throws Exception {
        Rule rule=rule();
        Rule selected=rule.onlyTargets(Collections.singletonList("暂未出现"));
        JSONArray scan=new JSONArray().put(result(rule,"","skipped",false))
                .put(result(rule,"已成功","changed",false));
        JSONArray expanded=CleanerService.expandRetryResults(Collections.singletonList(selected),scan);
        CleanerService.upsertResult(expanded,result(selected,"暂未出现","already",false));
        assertEquals(3,expanded.length());
        assertEquals("歧义开关",expanded.getJSONObject(2).getString("label"));
        assertEquals("skipped",expanded.getJSONObject(2).getString("status"));
    }
}
