package app.clearscreen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class RulePackEvidenceTest {
    @Test public void publishingPreflight() throws Exception {
        String planFile=System.getenv("CLEARSCREEN_RULE_REVIEW");
        JSONObject plan=planFile==null ? fixture() :
                new JSONObject(new String(Files.readAllBytes(Paths.get(planFile)),StandardCharsets.UTF_8));
        review(plan);
    }

    @Test public void ordinaryPageWithTheSameExitAndMarkerRejectsTheReview() throws Exception {
        JSONObject plan=fixture();
        JSONObject evidence=plan.getJSONArray("cases").getJSONObject(0);
        evidence.put("negative",evidence.getJSONObject("positive"));
        assertThrows(AssertionError.class,()->review(plan));
    }

    private JSONObject fixture() throws Exception{
        try(java.io.InputStream input=getClass().getResourceAsStream("/rule-pack-sample-review.json")){
            return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }

    private void review(JSONObject plan) throws Exception {
        JSONObject catalog=plan.getJSONObject("catalog");
        RulePack base=RulePack.bundled(new Store(RuntimeEnvironment.getApplication()));
        RulePack pack=RulePack.resolveVerified(catalog,base,catalog.getInt("minVersionCode"),null);
        JSONArray cases=plan.getJSONArray("cases");
        for(int i=0;i<cases.length();i++){
            JSONObject evidence=cases.getJSONObject(i);
            String id=evidence.getString("id");
            RulePack.Entry row=pack.entries.stream().filter(entry->entry.id.equals(id)).findFirst().get();
            assertEquals(id+" positive sample must have exactly one exit",1,
                    hits(row.rule,RuleSample.read(evidence.getJSONObject("positive").toString())));
            assertEquals(id+" ordinary page must have no valid exit",0,
                    hits(row.rule,RuleSample.read(evidence.getJSONObject("negative").toString())));
        }
    }

    private static int hits(JSONObject rule,RuleSample sample)throws Exception {
        assertTrue("Samples must be complete",DraftEvidence.complete(sample));
        assertEquals(rule.getString("package"),sample.pkg);
        JSONArray activities=rule.getJSONArray("activityIds");
        boolean allowed=false;
        for(int i=0;i<activities.length();i++)if(sample.page.equals(activities.getString(i)))allowed=true;
        if(!allowed)return 0;
        JSONArray required=rule.getJSONArray("required");
        for(int i=0;i<required.length();i++)if(count(sample,required.getJSONObject(i))==0)return 0;
        return count(sample,rule.getJSONObject("targetSelector"));
    }

    private static int count(RuleSample sample,JSONObject selector) {
        int matches=0;
        for(RuleSample.Item item:sample.items)
            if(SelectorNode.matches(sample.pkg,item,selector,true))matches++;
        return matches;
    }
}
