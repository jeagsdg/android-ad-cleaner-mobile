package app.clearscreen;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Interoperable local import. Ignore screenshots, device data and non-advertising text. */
final class SnapshotImport {
    static RuleSample read(InputStream raw)throws IOException,JSONException{
        if(raw==null)throw new IOException("无法打开样本");
        BufferedInputStream in=new BufferedInputStream(raw);in.mark(4);boolean zip=in.read()=='P'&&in.read()=='K';in.reset();
        if(!zip)return json(bytes(in,4*1024*1024));
        try(ZipInputStream archive=new ZipInputStream(in)){
            ZipEntry entry;int left=16*1024*1024;
            while((entry=archive.getNextEntry())!=null){
                if(entry.isDirectory())continue;
                if(entry.getName().toLowerCase(Locale.ROOT).endsWith(".json"))return json(bytes(archive,Math.min(left,4*1024*1024)));
                byte[] b=new byte[8192];int n;while((n=archive.read(b))!=-1){left-=n;if(left<0)throw new IOException("压缩包过大");}
            }
        }
        throw new IOException("ZIP 中未找到页面 JSON");
    }
    private static String bytes(InputStream in,int limit)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("样本超过可读取大小");out.write(b,0,n);}
        return out.toString("UTF-8");
    }
    static RuleSample json(String s)throws JSONException{
        JSONObject j=SubscriptionJson.read(s);if(j.has("schema"))return RuleSample.read(j);
        String pkg=j.getString("appId");JSONArray nodes=j.getJSONArray("nodes");
        if(nodes.length()>4096)throw new JSONException("节点过多");
        Map<Integer,Integer> pos=new HashMap<>();JSONArray out=new JSONArray();boolean complete=true;
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i),a=n.optJSONObject("attr");
            if(a==null){complete=false;continue;}
            if(a.optBoolean("password")||a.optBoolean("editable"))throw new JSONException("包含输入控件的外部页面不能导入");
            int key=n.getInt("id");if(pos.put(key,out.length())!=null)throw new JSONException("节点 ID 重复");
            String text=a.isNull("text")?null:RuleSample.adText(a.optString("text")),desc=a.isNull("desc")?null:RuleSample.adText(a.optString("desc"));
            out.put(new JSONObject().put("class",a.optString("name")).put("id",a.opt("id")).put("text",text).put("desc",desc)
                    .put("textKnown",text!=null||a.isNull("text")).put("descKnown",desc!=null||a.isNull("desc"))
                    .put("visible",a.optBoolean("visibleToUser",true)).put("clickable",a.optBoolean("clickable"))
                    .put("bounds",new JSONArray().put(a.getInt("left")).put(a.getInt("top")).put(a.getInt("right")).put(a.getInt("bottom")))
                    .put("children",new JSONArray()));
        }
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optJSONObject("attr")==null)continue;
            Integer current=pos.get(n.getInt("id")),parent=pos.get(n.getInt("pid"));if(parent!=null){
                JSONArray children=out.getJSONObject(parent).getJSONArray("children");int index=n.optInt("index",children.length());
                if(index<0||index>=1024)throw new JSONException("子节点索引无效");
                while(children.length()<=index)children.put(-1);
                if(children.getInt(index)!=-1)throw new JSONException("子节点顺序重复");children.put(index,current.intValue());
            }else if(n.getInt("pid")!=-1)complete=false;}
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i),a=n.optJSONObject("attr");if(a==null)continue;
            JSONArray children=out.getJSONObject(pos.get(n.getInt("id"))).getJSONArray("children");
            int declared=a.optInt("childCount",children.length());if(declared>1024)throw new JSONException("子节点过多");
            if(children.length()!=declared)complete=false;
            while(children.length()<declared)children.put(-1);
            for(int k=0;k<children.length();k++)if(children.getInt(k)==-1)complete=false;
        }
        return RuleSample.read(new JSONObject().put("schema","clearscreen.sample.1").put("package",pkg)
            .put("activity",j.isNull("activityId")?"":j.getString("activityId")).put("complete",complete).put("nodes",out));
    }
}
