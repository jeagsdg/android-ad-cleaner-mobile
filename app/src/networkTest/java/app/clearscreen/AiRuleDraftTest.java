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
}
