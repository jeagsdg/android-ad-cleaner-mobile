package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LegacySkipImportTest {
    private static final String PACKAGE = "com.ss.android.ugc.aweme";
    private static final String POPUP = "{\"popup_rules\":[{\"id\":\"=发现新版本\",\"action\":\"=稍后再说\"}]}";

    @Test public void preservesSelectorsAndSeparateLaunchAndPopupOptions() {
        String source = "{\"keywords\":[\"+跳过\"],\"keywords_append\":[\"tv_close_button\"],"
                + "\"popup_rules\":[{\"id\":\"+发现&-新版本\",\"action\":\"GLOBAL_ACTION_BACK\",\"times\":1}],"
                + "\"delay\":200,\"delay_popup\":350,\"click_way\":0,\"click_way_popup\":1,\"search_times_popup\":5}";
        LegacySkipImport.Result result = LegacySkipImport.parse(source, PACKAGE, List.of());
        assertEquals(3, result.rules.size());
        assertEquals(0, result.skipped);
        CustomSkipRule replacement = result.rules.get(0);
        assertEquals(PACKAGE, replacement.pkg);
        assertEquals(CustomSkipRule.SPLASH, replacement.mode);
        assertEquals("+跳过", replacement.label);
        assertTrue(replacement.replaceDefaults);
        assertEquals(200, replacement.delayMs);
        assertFalse(replacement.gesture);
        CustomSkipRule append = result.rules.get(1);
        assertEquals("tv_close_button", append.label);
        assertFalse(append.replaceDefaults);
        CustomSkipRule popup = result.rules.get(2);
        assertEquals(CustomSkipRule.POPUP, popup.mode);
        assertEquals("+发现&-新版本", popup.context);
        assertTrue(popup.backsOut());
        assertTrue(popup.gesture);
        assertEquals(350, popup.delayMs);
        assertEquals(5, popup.searches);
    }

    @Test public void resolvesOnlyUniqueVisiblePackageHashes() throws Exception {
        String aa = "com.example.Aa", bb = "com.example.BB";
        assertEquals(aa.hashCode(), bb.hashCode());
        assertEquals(313184810, PACKAGE.hashCode());
        JSONArray bundle = new JSONArray().put(bundleEntry(aa, POPUP)).put(bundleEntry(PACKAGE, POPUP))
                .put(bundleEntry("com.example.uninstalled", POPUP));
        LegacySkipImport.Result result = LegacySkipImport.parse(bundle.toString(), aa, List.of(aa, bb, PACKAGE, PACKAGE));
        assertEquals(1, result.rules.size());
        assertEquals(PACKAGE, result.rules.get(0).pkg);
        assertEquals(2, result.skipped);
        assertEquals(2, result.notes.size());
    }

    @Test public void reportsNonConvertibleOptionsAndDeduplicatesSelectors() {
        String source = "{\"keywords\":[\"跳过\"],\"keywords_append\":[\"跳过\",\"跳过广告\"],"
                + "\"popup_rules\":[{\"id\":\"新版本\",\"action\":\"稍后再说\",\"times\":5},"
                + "{\"id\":\"新版本\",\"action\":\"稍后再说\",\"times\":0}],\"filter_young_popups\":true}";
        LegacySkipImport.Result result = LegacySkipImport.parse(source, PACKAGE, List.of());
        assertEquals(2, result.rules.size());
        assertTrue(result.rules.get(0).replaceDefaults);
        assertFalse(result.rules.get(1).replaceDefaults);
        assertEquals(3, result.skipped);
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("青少年模式")));
    }

    @Test public void keepsPopupWhenExplicitSplashReplacementCannotBeConvertedCompletely() {
        String[] replacements = {"[]", "null", "[\"跳过\",\"确认购买\"]"};
        for (String replacement : replacements) {
            String source = "{\"keywords\":" + replacement + ",\"keywords_append\":[\"关闭广告\"],"
                    + POPUP.substring(1);
            LegacySkipImport.Result result = LegacySkipImport.parse(source, PACKAGE, List.of());
            assertEquals(1, result.rules.size());
            assertEquals(CustomSkipRule.POPUP, result.rules.get(0).mode);
            assertEquals(replacement.startsWith("[\"") ? 3 : 2, result.skipped);
            assertTrue(result.notes.stream().anyMatch(note -> note.contains("替换") && note.contains("整组略过")));
        }
    }

    @Test public void importsOnlyValidBoundsAndKeepsSavedCheckboxSelectorsReadable() {
        String source = "{\"keywords_append\":[\"10, 20, 30, 40\",\"10,20,30,40,2\",\"10,20,30,40,0\","
                + "\"100000,20,30,40\",\"30,20,10,40\",\"10,20,30,40,1,0\"]}";
        LegacySkipImport.Result result = LegacySkipImport.parse(source, PACKAGE, List.of());
        assertEquals(1, result.rules.size());
        assertEquals(5, result.skipped);
        assertEquals("10,20,30,40", result.rules.get(0).label);
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("复选框")));
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(PACKAGE);
        node.setText("按钮内容含 10,20,30,40");
        node.setBoundsInScreen(new Rect(10, 20, 30, 41));
        assertFalse(result.rules.get(0).matchesButton(node));
        node.setBoundsInScreen(new Rect(10, 20, 30, 40));
        assertTrue(result.rules.get(0).matchesButton(node));

        CustomSkipRule saved = new CustomSkipRule(PACKAGE, CustomSkipRule.SPLASH, "10,20,30,40,0", "", false, 0, false, 1);
        assertEquals(1, CustomSkipRule.parse(CustomSkipRule.export(List.of(saved))).size());
    }

    @Test public void combinesIdenticalPopupEntriesWithinTheRepeatLimit() {
        String repeated = "{\"popup_rules\":[{\"id\":\"新版本\",\"action\":\"稍后再说\"},"
                + "{\"id\":\"新版本\",\"action\":\"稍后再说\",times:2},]}";
        LegacySkipImport.Result result = LegacySkipImport.parse(repeated, PACKAGE, List.of());
        assertEquals(1, result.rules.size());
        assertEquals(3, result.rules.get(0).times);
        assertEquals(0, result.skipped);

        String overLimit = repeated.replace("times:2", "times:4").replace("{\"popup_rules\"", "{\"keywords_append\":[\"跳过\"],\"popup_rules\"");
        LegacySkipImport.Result limited = LegacySkipImport.parse(overLimit, PACKAGE, List.of());
        assertEquals(1, limited.rules.size());
        assertEquals(CustomSkipRule.SPLASH, limited.rules.get(0).mode);
        assertEquals(2, limited.skipped);
    }

    @Test public void keepsOtherAppsWhenResolvedRuleBodyIsMalformed() throws Exception {
        String broken = "com.example.broken", wrongType = "com.example.wrongtype";
        JSONArray bundle = new JSONArray().put(bundleEntry(broken, "{broken"))
                .put(bundleEntry(wrongType, "[]")).put(bundleEntry(PACKAGE, POPUP));
        LegacySkipImport.Result result = LegacySkipImport.parse(bundle.toString(), "", List.of(PACKAGE, broken, wrongType));
        assertEquals(1, result.rules.size());
        assertEquals(PACKAGE, result.rules.get(0).pkg);
        assertEquals(2, result.skipped);
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("JSON 无法读取")));
    }

    @Test public void importsBoundedRepeatCountAndOrderedStateOnlySteps() {
        String repeated = "{\"popup_rules\":[{\"id\":\"=新版本\",\"action\":\"=稍后再说\",\"times\":3}]}";
        CustomSkipRule standalone = LegacySkipImport.parse(repeated, PACKAGE, List.of()).rules.get(0);
        assertEquals(3, standalone.times);
        assertEquals(-1, standalone.sequenceIndex);

        String sequence = "{\"unite_popup_rules\":true,\"popup_rules\":["
                + "{\"id\":\"还剩&秒\",\"action\":\"还剩&秒\",\"times\":0},"
                + "{\"id\":\"=发现新版本\",\"action\":\"=稍后再说\"},]}";
        LegacySkipImport.Result result = LegacySkipImport.parse(sequence, PACKAGE, List.of());
        assertEquals(0, result.skipped);
        assertEquals(2, result.rules.size());
        assertEquals(0, result.rules.get(0).times);
        assertEquals(0, result.rules.get(0).sequenceIndex);
        assertEquals(1, result.rules.get(1).times);
        assertEquals(1, result.rules.get(1).sequenceIndex);
        assertEquals(2, CustomSkipRule.parse(CustomSkipRule.export(result.rules)).size());
    }

    @Test public void keepsIndependentSplashKeywordsWhenCombinedPopupSequenceIsInvalid() {
        String source = "{\"keywords_append\":[\"跳过广告\"],\"unite_popup_rules\":true,"
                + "\"popup_rules\":[{\"id\":\"=发现新版本\",\"action\":\"=稍后再说\"},"
                + "{\"id\":\"还剩&秒\",\"action\":\"还剩&秒\",\"times\":0},]}";
        LegacySkipImport.Result result = LegacySkipImport.parse(source, PACKAGE, List.of());
        assertEquals(1, result.rules.size());
        assertEquals(CustomSkipRule.SPLASH, result.rules.get(0).mode);
        assertEquals(2, result.skipped);
        assertTrue(result.notes.stream().anyMatch(note -> note.contains("联合弹窗")));
    }

    @Test public void rejectsMalformedAmbiguousOrInapplicableImports() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(POPUP, "抖音", List.of(PACKAGE)));
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(POPUP + "{}", PACKAGE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(POPUP + '\0' + "{}", PACKAGE, List.of()));
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse("[{\"not-a-hash\":\"{}\"}]", PACKAGE, List.of()));
        String disabled = "{\"ltt_service\":false,\"keywords\":[\"跳过\"]}";
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(disabled, PACKAGE, List.of()));
        String oneStep = POPUP.replace("{\"popup_rules\"", "{\"unite_popup_rules\":true,\"popup_rules\"");
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(oneStep, PACKAGE, List.of()));
        JSONArray duplicates = new JSONArray().put(bundleEntry(PACKAGE, POPUP)).put(bundleEntry(PACKAGE, POPUP));
        assertThrows(IllegalArgumentException.class, () -> LegacySkipImport.parse(duplicates.toString(), "", List.of(PACKAGE)));
    }

    private JSONObject bundleEntry(String pkg, String body) throws Exception {
        return new JSONObject().put(Integer.toString(pkg.hashCode()), body);
    }
}
