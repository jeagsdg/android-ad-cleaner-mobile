package app.clearscreen;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class AiRuleDraftTest {
    @Test public void outputCanOnlyUseDescribedText() throws Exception {
        String json="{\"mode\":\"popup\",\"context\":\"今日广告\",\"target\":\"关闭\"}";
        CustomSkipRule r=AiRuleDraft.parse("com.example.demo",json,"今日广告，按钮写着关闭");
        assertEquals("=关闭",r.label);assertEquals("=今日广告",r.context);assertFalse(r.gesture);
        assertThrows(Exception.class,()->AiRuleDraft.parse("com.example.demo",json,"关闭"));
        assertThrows(Exception.class,()->AiRuleDraft.parse("com.example.demo","{}","跳过"));
    }
    @Test public void savingDraftRequiresOneExitAndACompleteNonmatchingOrdinarySample() throws Exception {
        String pkg=RuleWorkbenchTest.PKG;
        android.view.accessibility.AccessibilityNodeInfo exit=RuleWorkbenchTest.node("exit","关闭广告",true);
        android.view.accessibility.AccessibilityNodeInfo normal=RuleWorkbenchTest.node("file","文件",true);
        RuleSample ad=RuleSample.capture(pkg,"Home",exit.getWindowId(),java.util.Collections.singletonList(exit));
        RuleSample ordinary=RuleSample.capture(pkg,"Home",normal.getWindowId(),java.util.Collections.singletonList(normal));
        String raw="{mode:'splash',target:'关闭广告'}";
        CustomSkipRule draft=AiRuleDraft.parse(pkg,raw,"关闭广告");
        DraftEvidence.validate(draft,ad,ordinary);
        assertThrows(IllegalArgumentException.class,()->DraftEvidence.validate(draft,ad,ad));
        ordinary.complete=false;
        assertThrows(IllegalArgumentException.class,()->DraftEvidence.validate(draft,ad,ordinary));
        assertThrows(Exception.class,()->AiRuleDraft.parse(pkg,raw+"extra","关闭广告"));
        assertThrows(Exception.class,()->AiRuleDraft.parse(pkg,raw.replace("}",",action:'tap'}"),"关闭广告"));
        assertThrows(IllegalArgumentException.class,()->DraftEvidence.validate(
                AiRuleDraft.parse(pkg,"{mode:'splash',target:'关闭'}","关闭"),ad,ad));
    }
}
