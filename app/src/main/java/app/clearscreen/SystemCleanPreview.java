package app.clearscreen;

import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** A read-only observation of selected settings, tied to the exact routes that were read. */
final class SystemCleanPreview {
    final String id = UUID.randomUUID().toString();
    final List<Entry> entries;
    final int unchecked;
    final String interruption;

    SystemCleanPreview(List<Entry> entries, int unchecked, String interruption) {
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.unchecked = unchecked;
        this.interruption = interruption;
    }

    static final class Entry {
        final Rule rule;
        final String target;
        final Rule pinnedRule;
        final String observedLabel;
        final String routeName;
        final boolean before, desired;
        final String problem;
        final JSONObject routeStamp;

        private Entry(Rule rule, String target, Rule pinnedRule, String observedLabel, String routeName,
                      boolean before, boolean desired, String problem, JSONObject routeStamp) {
            this.rule = rule;
            this.target = target;
            this.pinnedRule = pinnedRule;
            this.observedLabel = observedLabel;
            this.routeName = routeName;
            this.before = before;
            this.desired = desired;
            this.problem = problem;
            this.routeStamp = routeStamp;
        }

        static Entry verified(Store store, Rule rule, String target, Rule pinnedRule, UiDriver.Found found,
                              String routeName, boolean desired) {
            JSONObject stamp = new JSONObject();
            CleanerService.put(stamp, "build", Build.FINGERPRINT);
            CleanerService.put(stamp, "packageVersion", store.packageVersion(pinnedRule.pkg));
            CleanerService.put(stamp, "routeVersions", store.routeVersions(pinnedRule));
            return new Entry(rule, target, pinnedRule, found.label, routeName, found.checked, desired, "", stamp);
        }

        static Entry unavailable(Rule rule, String target, String problem) {
            return new Entry(rule, target, null, target, "", false, false, problem, null);
        }

        boolean verified() { return pinnedRule != null; }
        boolean needsChange() { return verified() && before != desired; }
        String key() { return key(rule.id, target); }
        static String key(String ruleId, String target) { return ruleId + '\0' + target; }

        JSONObject approval() throws JSONException {
            return new JSONObject().put("pinnedRule", pinnedRule.json()).put("label", observedLabel)
                    .put("before", before).put("desired", desired).put("routeStamp", routeStamp);
        }

        static Entry readApproval(Rule rule, String target, JSONObject value) throws JSONException {
            return new Entry(rule, target, new Rule(value.getJSONObject("pinnedRule")), value.getString("label"),
                    "", value.getBoolean("before"), value.getBoolean("desired"), "", value.getJSONObject("routeStamp"));
        }

        void verifyTarget(UiDriver.Found current, boolean currentDesired) throws UiDriver.Halt {
            if (!needsChange() || !observedLabel.equals(current.label) || desired != currentDesired) {
                throw new UiDriver.Halt("设置与预览时不同，请重新检查", false, false, "preview_changed");
            }
        }
    }
}
