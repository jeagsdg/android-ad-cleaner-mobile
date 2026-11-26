package app.clearscreen;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.nio.file.*;
import java.util.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class FocusCorpusTest {
 @Test public void replay() throws Exception {
    Path dir=Path.of(System.getProperty("clearscreen.focus.trees"));
    SplashRules sr=new SplashRules(RuntimeEnvironment.getApplication());
    PopupAdRules pr=new PopupAdRules(RuntimeEnvironment.getApplication());
    List<String> results=new ArrayList<>();
    try (var stream=Files.list(dir)) { for(Path file:stream.filter(f->f.toString().endsWith(".json")).sorted().toList()){
        JSONObject tree=new JSONObject(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8));String pkg=tree.getString("appId"),activity=tree.getString("activityId");
        JSONArray data=tree.getJSONArray("nodes");Map<Integer,AccessibilityNodeInfo> nodes=new LinkedHashMap<>();
        AccessibilityWindowInfo win=AccessibilityWindowInfo.obtain();shadowOf(win).setId(42);
        for(int i=0;i<data.length();i++){
            JSONObject obj=data.getJSONObject(i),attr=obj.getJSONObject("attr");
            AccessibilityNodeInfo n=AccessibilityNodeInfo.obtain(); n.setPackageName(pkg);n.setClassName(attr.optString("name"));
            n.setText(attr.isNull("text")?null:attr.getString("text"));n.setContentDescription(attr.isNull("desc")?null:attr.getString("desc"));n.setViewIdResourceName(attr.isNull("id")?null:attr.getString("id"));
            n.setBoundsInScreen(new Rect(attr.getInt("left"),attr.getInt("top"),attr.getInt("right"),attr.getInt("bottom")));
            n.setVisibleToUser(attr.optBoolean("visibleToUser"));n.setEnabled(attr.optBoolean("enabled",true));n.setClickable(attr.optBoolean("clickable"));n.setCheckable(attr.optBoolean("checkable"));n.setEditable(attr.optBoolean("editable"));n.setLongClickable(attr.optBoolean("longClickable"));n.setFocusable(attr.optBoolean("focusable"));
            shadowOf(n).setAccessibilityWindowInfo(win);
            AccessibilityNodeInfo parent=nodes.get(obj.optInt("pid",-1));if(parent!=null)shadowOf(parent).addChild(n);
            nodes.put(obj.getInt("id"),n);
        }
        AccessibilityNodeInfo root=nodes.get(0);Rect screen=new Rect(0,0,tree.getInt("screenWidth"),tree.getInt("screenHeight"));
        AccessibilityNodeInfo sh=SplashSkipper.match(root,sr,activity,Collections.emptyList());
        PopupAdRules.Match ph=pr.find(root,activity,screen);PopupMatch gh=PopupMatch.findUsingApp(root,activity,screen,UiDriver.walkComplete(root));
        results.add(file.getFileName()+"\t"+key(nodes,sh)+"\t"+key(nodes,ph==null?null:ph.target)+"\t"+key(nodes,gh==null?null:gh.target));
    }}
    Files.write(Path.of(System.getProperty("clearscreen.focus.out")),results);
 }
 private int key(Map<Integer,AccessibilityNodeInfo> map,AccessibilityNodeInfo n){
     for(var e:map.entrySet())if(e.getValue().equals(n))return e.getKey();return -1;
 }
}
