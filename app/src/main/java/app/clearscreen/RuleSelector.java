package app.clearscreen;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.regex.*;
import org.json.*;

/** Compiles the supported selector language into the same predicates as the bundled catalog. */
final class RuleSelector {
    private static final Pattern NODE=Pattern.compile("(@)?(\\*|[A-Za-z][A-Za-z0-9_.]*)?");
    private static final Pattern REL=Pattern.compile("(<<|[<>+\\-])(n|[1-9][0-9]*|\\([1-9][0-9]*(?:,[1-9][0-9]*)*\\))?\\s+");
    private static final Pattern TEST=Pattern.compile("\\s*(text\\.length|desc\\.length|childCount|index|depth|name|clickable|visibleToUser|text|desc|id|vid)\\s*(\\^=|\\$=|\\*=|!=|<=|>=|=|<|>)\\s*(?:\"([^\"\\\\]*)\"|'([^'\\\\]*)'|(null|true|false|[0-9]+))\\s*");
    final JSONObject compiled;
    final String expression;
    static boolean structured(String s){return s.startsWith("gkd:");}
    RuleSelector(String expression) {
        this.expression=expression;String s=expression.substring(4).trim(); int at=0,target=-1;
        JSONArray path=new JSONArray(),relations=new JSONArray();
        try {
            while(at<s.length()){
                Matcher m=NODE.matcher(s).region(at,s.length());m.lookingAt();int start=at;at=m.end();
                if(m.group(1)!=null){if(target>=0)throw syntax(at);target=path.length();}
                JSONArray tests=new JSONArray();
                while(at<s.length()&&s.charAt(at)=='['){
                    at++;JSONArray alternatives=new JSONArray(), conjunction=new JSONArray();
                    while(true){Matcher t=TEST.matcher(s).region(at,s.length());if(!t.lookingAt())throw syntax(at);
                        String f=t.group(1),op=t.group(2),v=t.group(5);Object operand;
                        if(f.equals("clickable")||f.equals("visibleToUser")){
                            if(!(op.equals("=")||op.equals("!="))||!("true".equals(v)||"false".equals(v)))throw syntax(at);
                            operand=Boolean.valueOf(v);
                        }else if(f.endsWith(".length")||f.equals("childCount")||f.equals("index")||f.equals("depth")){
                            if(v==null||!v.matches("[0-9]+")||!op.matches("=|!=|<=|>=|<|>"))throw syntax(at);
                            operand=Integer.valueOf(v);
                        }else{
                            if(!op.matches("=|!=|\\^=|\\$=|\\*=")||(v!=null&&!(v.equals("null")&&(op.equals("=")||op.equals("!=")))))throw syntax(at);
                            operand=v!=null?JSONObject.NULL:t.group(3)!=null?t.group(3):t.group(4);
                        }
                        conjunction.put(new JSONArray().put(f).put(op).put(operand));at=t.end();
                        if(s.startsWith("&&",at)){at+=2;continue;}
                        alternatives.put(conjunction);
                        if(s.startsWith("||",at)){at+=2;conjunction=new JSONArray();continue;}
                        if(at>=s.length()||s.charAt(at++)!=']')throw syntax(at);break;
                    }
                    tests.put(alternatives);
                }
                if(at==start || (m.group(2)==null && tests.length()==0))throw syntax(at);
                path.put(new JSONObject().put("class",m.group(2)==null?"*":m.group(2)).put("tests",tests));
                if(at==s.length())break;
                if(!Character.isWhitespace(s.charAt(at)))throw syntax(at);
                while(at<s.length()&&Character.isWhitespace(s.charAt(at)))at++;
                Matcher r=REL.matcher(s).region(at,s.length());String op=">",steps="n";
                if(r.lookingAt()){op=r.group(1);steps=r.group(2)==null?"1":r.group(2);at=r.end();}
                JSONArray slots=new JSONArray();if(!steps.equals("n"))for(String x:steps.replace("(","").replace(")","").split(",")){int n=Integer.parseInt(x);if(n>12)throw syntax(at);slots.put(n);}
                relations.put(new JSONArray().put(op).put(slots));
            }
            if(path.length()==0||path.length()!=relations.length()+1||path.length()>8)throw syntax(at);
            compiled=new JSONObject().put("path",path).put("relations",relations).put("target",target<0?path.length()-1:target);
        }catch(JSONException|NumberFormatException e){throw syntax(at);}
    }
    boolean matches(AccessibilityNodeInfo node){return SplashRules.targetMatches(String.valueOf(node.getPackageName()),node,compiled,true);}
    private static IllegalArgumentException syntax(int i){return new IllegalArgumentException("选择器第 "+(i+1)+" 个字符附近的条件不支持");}
}
