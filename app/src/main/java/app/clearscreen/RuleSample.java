package app.clearscreen;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;
import org.json.*;

/** A local, redacted page tree; no live accessibility objects survive capture. */
final class RuleSample {
    final String pkg,page;
    final List<Item> items=new ArrayList<>();
    final List<Item> candidates=new ArrayList<>();
    boolean complete=true;
    private RuleSample(String pkg,String page){this.pkg=pkg;this.page=page;}
    static final class Item extends SelectorNode {
        String pkg,cls,id,text,desc; boolean click,visible,knownText,knownDesc;
        Item parent; Item[] children;
        final Rect bounds=new Rect();
        String getPackageName(){return pkg;} String getClassName(){return cls;}
        String getText(){return text;} String getContentDescription(){return desc;}
        boolean known(String field){return field.startsWith("text")?knownText:field.startsWith("desc")?knownDesc:true;}
        String getViewIdResourceName(){return id;} boolean isClickable(){return click;}
        boolean isVisibleToUser(){return visible;} int getChildCount(){return children.length;}
        SelectorNode getChild(int index){return children[index];} SelectorNode getParent(){return parent;}
        String selector(){if(id!=null)return "gkd:[id=\""+id+"\"]";
            if(text!=null)return "gkd:[text=\""+text+"\"]";
            return "gkd:[desc=\""+desc+"\"]";}
        String title(){String s=text!=null?text:desc;return (s!=null?s:"控件")+" · "+(id==null?cls:id.substring(id.lastIndexOf('/')+1));}
    }
    static RuleSample capture(String pkg,String page,int window,List<AccessibilityNodeInfo> nodes){
        RuleSample result=new RuleSample(pkg,page);Map<AccessibilityNodeInfo,Item> by=new HashMap<>();
        for(AccessibilityNodeInfo n:nodes){
            if(n.getWindowId()!=window||!pkg.contentEquals(n.getPackageName()==null?"":n.getPackageName()))continue;
            if(n.isPassword()||SplashSkipper.unsafeWindowNode(n,true))return null;
            if(by.containsKey(n))continue;
            Item item=new Item();item.pkg=pkg;item.cls=String.valueOf(n.getClassName());item.click=n.isClickable();item.visible=n.isVisibleToUser();
            String id=n.getViewIdResourceName();if(id!=null&&id.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_]+"))item.id=id;
            item.text=adText(n.getText());item.desc=adText(n.getContentDescription());
            item.knownText=n.getText()==null||item.text!=null;item.knownDesc=n.getContentDescription()==null||item.desc!=null;
            n.getBoundsInScreen(item.bounds);if(n.getChildCount()>1024)return null;
            item.children=new Item[n.getChildCount()];result.items.add(item);by.put(n,item);
            if(item.visible&&(item.text!=null||item.desc!=null||(item.id!=null&&item.id.toLowerCase(Locale.ROOT).matches(".*(skip|splash|dismiss|ad_close|close_ad).*"))))result.candidates.add(item);
        }
        for(Map.Entry<AccessibilityNodeInfo,Item> e:by.entrySet()){
            Item i=e.getValue();for(int k=0;k<i.children.length;k++){AccessibilityNodeInfo n=e.getKey().getChild(k);Item child=by.get(n);if(child!=null&&child!=i&&child.parent==null){i.children[k]=child;child.parent=i;}else result.complete=false;}
        }
        return result;
    }
    static String adText(CharSequence value){
        if(value==null||value.length()>50)return null;
        String s=value.toString().trim();
        return SplashSkipper.skipLabel(s)||s.matches("广告|廣告|推广|推廣|关闭|關閉|[xX×]|(?i:ad|advertisement|dismiss ad)")?s:null;
    }
    static RuleSample read(String json) throws JSONException {
        JSONObject doc=new JSONObject(json);if(!"clearscreen.sample.1".equals(doc.getString("schema")))throw new JSONException("不是净屏的页面样本");
        String pkg=doc.getString("package"),page=doc.optString("activity");
        if(!pkg.matches("[A-Za-z][A-Za-z0-9_.]+")||CustomSkipRule.protectedPackage(pkg))throw new JSONException("应用包名不支持");
        RuleSample r=new RuleSample(pkg,page);r.complete=doc.optBoolean("complete");JSONArray ns=doc.getJSONArray("nodes");
        if(ns.length()>4096)throw new JSONException("样本节点过多");
        for(int n=0;n<ns.length();n++){JSONObject a=ns.getJSONObject(n);Item v=new Item();v.pkg=pkg;v.cls=a.getString("class");
            String id=a.optString("id");if(id.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_]+"))v.id=id;
            v.text=adText(a.optString("text"));v.desc=adText(a.optString("desc"));v.knownText=a.optBoolean("textKnown");v.knownDesc=a.optBoolean("descKnown");
            v.visible=a.optBoolean("visible");v.click=a.optBoolean("clickable");JSONArray b=a.getJSONArray("bounds");v.bounds.set(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3));
            int count=a.getJSONArray("children").length();if(count>1024)throw new JSONException("子节点过多");v.children=new Item[count];r.items.add(v);
            if(v.visible&&(v.text!=null||v.desc!=null||v.id!=null&&v.id.toLowerCase(Locale.ROOT).matches(".*(skip|dismiss|close).*")))r.candidates.add(v);
        }
        for(int n=0;n<ns.length();n++){Item parent=r.items.get(n);JSONArray children=ns.getJSONObject(n).getJSONArray("children");
            for(int k=0;k<children.length();k++){int idx=children.getInt(k);if(idx==-1)continue;
                if(idx<0||idx>=ns.length()||r.items.get(idx).parent!=null)throw new JSONException("样本层级不完整");
                Item child=r.items.get(idx);parent.children[k]=child;child.parent=parent;
            }
        }
        for(Item v:r.items){Item slow=v,fast=v;while(fast!=null&&fast.parent!=null){slow=slow.parent;fast=fast.parent.parent;if(slow==fast)throw new JSONException("样本层级包含循环");}}
        return r;
    }
    JSONObject export() throws JSONException {
        JSONArray nodes=new JSONArray();Map<Item,Integer> indexes=new IdentityHashMap<>();
        for(int i=0;i<items.size();i++)indexes.put(items.get(i),i);
        for(Item item:items){
            JSONArray children=new JSONArray();for(Item child:item.children)children.put(child==null?-1:indexes.get(child).intValue());
            nodes.put(new JSONObject().put("class",item.cls).put("id",item.id).put("text",item.text)
                .put("desc",item.desc).put("textKnown",item.knownText).put("descKnown",item.knownDesc)
                .put("visible",item.visible).put("clickable",item.click).put("children",children)
                .put("bounds",new JSONArray().put(item.bounds.left).put(item.bounds.top).put(item.bounds.right).put(item.bounds.bottom)));
        }
        return new JSONObject().put("schema","clearscreen.sample.1").put("package",pkg).put("activity",page)
                .put("complete",complete).put("nodes",nodes);
    }
    String replay(String expression){
        if(!RuleSelector.structured(expression))throw new IllegalArgumentException("请使用 gkd: 开头的选择器");
        RuleSelector r=new RuleSelector(expression);int count=0;String first="";
        for(Item i:items)if(SelectorNode.matches(pkg,i,r.compiled,true)){if(count++==0)first=i.title();}
        return (count==0?"未命中：检查属性、页面层级或节点是否可见。":count==1?"唯一命中："+first:"命中 "+count+" 个控件，请补充父节点或相邻控件条件。")
                +"\n在 "+items.size()+" 个样本节点上回放；非广告正文已隐去"+(complete?"。":"，部分子树不可读。")+"未执行点击。";
    }
}
