package app.clearscreen;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.util.*;

/** Reviewed subset of MIT selectors; no arbitrary expressions or downloaded code. */
final class SplashRules {
    static final SplashRules EMPTY=new SplashRules(new JSONObject());
    private final Map<String,List<JSONObject>> byPackage=new HashMap<>();
    private final Set<String> sdkIds=new HashSet<>();
    private final Set<String> sdkResources=new HashSet<>();
    SplashRules(Context context){this(parse(new Store(context).asset("splash-rules.json")));}
    private static JSONObject parse(String text){try{return new JSONObject(text);}catch(JSONException e){throw new IllegalStateException("开屏规则读取失败",e);}}
    SplashRules(JSONObject source){
        JSONArray rules=source.optJSONArray("rules");if(rules!=null)for(int i=0;i<rules.length();i++){JSONObject rule=rules.optJSONObject(i);if(rule!=null)byPackage.computeIfAbsent(rule.optString("package"),key->new ArrayList<>()).add(rule);}
        JSONArray sdk=source.optJSONArray("sdkRules");if(sdk!=null)for(int i=0;i<sdk.length();i++){JSONObject item=sdk.optJSONObject(i);if(item==null)continue;if(!item.optString("id").isEmpty())sdkIds.add(item.optString("id"));if(!item.optString("resourceName").isEmpty())sdkResources.add(item.optString("resourceName"));}
    }
    boolean matches(String pkg,String activity,AccessibilityNodeInfo node){
        String id=node.getViewIdResourceName();if(id==null)return false;
        if(sdkIds.contains(id))return true;
        int separator=id.indexOf(":id/");if(separator>0&&sdkResources.contains(id.substring(separator+4)))return true;
        for(JSONObject rule:byPackage.getOrDefault(pkg,Collections.emptyList())){
            if(!id.equals(rule.optString("id")))continue;
            List<String> activities=Rule.strings(rule.optJSONArray("activityIds")),excluded=Rule.strings(rule.optJSONArray("excludeActivityIds"));
            if((!activities.isEmpty()&&!activities.contains(activity))||excluded.contains(activity))continue;
            String cls=rule.optString("class");String actual=String.valueOf(node.getClassName());if(!cls.isEmpty()&&!actual.equals(cls)&&!actual.endsWith("."+cls))continue;
            if(rule.optBoolean("clickable")&&!node.isClickable())continue;
            if(rule.optBoolean("visibleToUser")&&!node.isVisibleToUser())continue;
            if(rule.optBoolean("childCount")&&node.getChildCount()!=0)continue;
            String text=node.getText()==null?"":node.getText().toString(),desc=node.getContentDescription()==null?"":node.getContentDescription().toString();
            if(text.length()>rule.optInt("textMax",Integer.MAX_VALUE)||!stringMatch(text,rule.optJSONObject("text"))||!stringMatch(desc,rule.optJSONObject("desc")))continue;
            return true;
        }
        return false;
    }
    private boolean stringMatch(String value,JSONObject constraint){if(constraint==null)return true;String expected=constraint.optString("value");switch(constraint.optString("op")){case "=":return value.equals(expected);case "^=":return value.startsWith(expected);case "*=":return value.contains(expected);default:return false;}}
}
