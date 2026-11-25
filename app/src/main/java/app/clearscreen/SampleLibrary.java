package app.clearscreen;

import android.content.Context;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import java.io.*;
import java.util.*;
import org.json.*;

/** Only snapshots the user explicitly saves live here. Android Auto Backup does not copy them. */
final class SampleLibrary {
    static File folder(Context c){return new File(c.getNoBackupFilesDir(),"rule-samples-v1");}
    static File save(Context c,RuleSample sample)throws Exception{
        File dir=folder(c);if(!dir.exists()&&!dir.mkdirs())throw new IOException();
        byte[] body=sample.export().toString().getBytes("UTF-8");if(body.length>4*1024*1024)throw new IOException("样本过大");
        File tmp=File.createTempFile("writing-",".tmp",dir);File dest=new File(dir,System.currentTimeMillis()+"-"+UUID.randomUUID()+".json");
        try{try(FileOutputStream out=new FileOutputStream(tmp)){out.write(body);out.getFD().sync();}if(!tmp.renameTo(dest))throw new IOException("无法保存");}
        finally{if(tmp.exists())tmp.delete();}
        return dest;
    }
    static RuleSample read(File f)throws Exception{
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>4*1024*1024)throw new IOException();out.write(b,0,n);}
            return RuleSample.read(out.toString("UTF-8"));
        }
    }
    static void open(MainActivity a,java.util.function.Consumer<RuleSample> use,String onlyPkg){
        new Thread(()->{
            File[] files=folder(a).listFiles((d,n)->n.endsWith(".json"));List<File> valid=new ArrayList<>();List<String> labels=new ArrayList<>();
            if(files!=null){Arrays.sort(files,Comparator.comparing(File::getName).reversed());
                for(File file:files)try{RuleSample s=read(file);if(onlyPkg!=null&&!onlyPkg.equals(s.pkg))continue;
                    valid.add(file);String date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(file.lastModified());
                    labels.add(s.pkg+" · "+date+"\n"+(s.page.isEmpty()?"未知页面":s.page)+" · "+s.items.size()+" 个节点");
                }catch(Exception ignored){if(onlyPkg==null){valid.add(file);labels.add("无法读取的样本 · 可以删除");}}}
            a.runOnUiThread(()->{
                if(a.isDestroyed())return;
                if(valid.isEmpty()){a.toast(onlyPkg==null?"还没有保存样本":"这个应用还没有已保存的对照样本");return;}
                AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle(onlyPkg==null?"本机样本库":"选择同一应用的普通页面")
                        .setItems(labels.toArray(new String[0]),(d,i)->new Thread(()->{
                            try{RuleSample s=read(valid.get(i));a.runOnUiThread(()->{if(!a.isDestroyed())use.accept(s);});}
                            catch(Exception e){a.runOnUiThread(()->a.toast("无法读取该样本"));}
                        },"sample-open").start()).setNegativeButton("关闭",null);
                if(onlyPkg==null)builder.setNeutralButton("批量删除",(d,v)->{
                    boolean[] selected=new boolean[valid.size()];
                    new AlertDialog.Builder(a).setTitle("选择要从本机删除的样本").setMultiChoiceItems(labels.toArray(new String[0]),selected,(q,i,b)->selected[i]=b)
                            .setNegativeButton("取消",null).setPositiveButton("删除所选",(q,vv)->{int count=0;for(int i=0;i<valid.size();i++)if(selected[i]&&valid.get(i).delete())count++;a.toast("已删除 "+count+" 份样本");}).show();
                });
                builder.show();
            });
        },"sample-library").start();
    }
}
