package app.clearscreen;

import android.content.Context;
import android.app.AlertDialog;
import android.util.JsonReader;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import org.json.*;

/** Only snapshots the user explicitly saves live here. Android Auto Backup does not copy them. */
final class SampleLibrary {
    static File folder(Context c){return new File(c.getNoBackupFilesDir(),"rule-samples-v1");}
    static final int MAX_FILES=64, MAX_FILE_BYTES=4*1024*1024;
    static final long MAX_TOTAL_BYTES=32L*1024*1024;
    private static final long DAY=24L*60*60*1000;
    private static final String EXPIRY="-until-";

    static final class Inventory{
        final List<File> files=new ArrayList<>();
        long bytes;
        String usage(){
            String size=bytes<1024*1024 ? (bytes+1023)/1024+" KiB" :
                    String.format(Locale.ROOT,"%.1f MiB",bytes/(1024.0*1024));
            return files.size()+" / "+MAX_FILES+" 份 · "+size+" / 32 MiB";
        }
    }

    static long expiry(File file){
        String name=file.getName();
        int marker=name.lastIndexOf(EXPIRY);
        if(marker<0 || !name.endsWith(".json"))return 0;
        try{return Long.parseLong(name.substring(marker+EXPIRY.length(),name.length()-5));}
        catch(NumberFormatException ignored){return 0;}
    }

    static synchronized Inventory inventory(Context context,long now)throws IOException{
        Inventory result=new Inventory();
        File dir=folder(context);
        if(!dir.exists())return result;
        File[] files=dir.listFiles((d,name)->name.endsWith(".json"));
        if(files==null)throw new IOException("无法读取本机样本库");
        for(File file:files){
            long expires=expiry(file);
            if(expires>0 && now>=expires && file.delete())continue;
            result.files.add(file);
            result.bytes+=file.length();
        }
        result.files.sort(Comparator.comparing(File::getName).reversed());
        return result;
    }

    static File save(Context c,RuleSample sample)throws Exception{return save(c,sample,0);}

    static synchronized File save(Context c,RuleSample sample,int days)throws Exception{
        if(days!=0 && days!=7 && days!=30)throw new IOException("请选择样本保存期限");
        File dir=folder(c);
        if(!dir.exists() && !dir.mkdirs())throw new IOException("无法创建本机样本库");
        byte[] body=sample.export().toString().getBytes(StandardCharsets.UTF_8);
        if(body.length>MAX_FILE_BYTES)throw new IOException("单份样本最多 4 MiB");
        long now=System.currentTimeMillis();
        Inventory usage=inventory(c,now);
        if(usage.files.size()>=MAX_FILES || body.length>MAX_TOTAL_BYTES-usage.bytes)
            throw new IOException("样本库已满："+usage.usage()+"，请在样本库删除不需要的收藏");
        String until=days==0 ? "" : EXPIRY+(now+days*DAY);
        File dest=new File(dir,now+"-"+UUID.randomUUID()+until+".json");
        File tmp=File.createTempFile("writing-",".tmp",dir);
        try{
            try(FileOutputStream out=new FileOutputStream(tmp)){out.write(body);out.getFD().sync();}
            if(!tmp.renameTo(dest))throw new IOException("无法保存样本");
        }finally{if(tmp.exists())tmp.delete();}
        return dest;
    }

    static RuleSample read(File f)throws Exception{
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>MAX_FILE_BYTES)throw new IOException();out.write(b,0,n);}
            return RuleSample.read(out.toString("UTF-8"));
        }
    }
    // Sample exports put the package and page before the node array. Read only this header for listing.
    private static String[] header(File file)throws IOException{
        if(file.length()>MAX_FILE_BYTES)throw new IOException("样本过大");
        String pkg=null,page=null;
        try(JsonReader reader=new JsonReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){
            reader.beginObject();
            while(reader.hasNext()){
                String key=reader.nextName();
                if(key.equals("package"))pkg=reader.nextString();
                else if(key.equals("activity"))page=reader.nextString();
                else reader.skipValue();
                if(pkg!=null && page!=null)break;
            }
        }
        if(pkg==null)throw new IOException("缺少包名");
        return new String[]{pkg,page==null?"":page};
    }

    static void open(MainActivity a,java.util.function.Consumer<RuleSample> use,String onlyPkg){
        open(a,use,onlyPkg,onlyPkg==null?"本机样本库":"选择同一应用的普通页面");
    }
    static void open(MainActivity a,java.util.function.Consumer<RuleSample> use,String onlyPkg,String title){
        new Thread(()->{
            List<File> valid=new ArrayList<>();
            List<String> labels=new ArrayList<>();
            Inventory inventory;
            try{inventory=inventory(a,System.currentTimeMillis());}
            catch(IOException e){a.runOnUiThread(()->a.toast("无法读取本机样本库"));return;}
            java.text.DateFormat date=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT);
            for(File file:inventory.files)try{
                String[] info=header(file);
                if(onlyPkg!=null && !onlyPkg.equals(info[0]))continue;
                long expires=expiry(file);
                valid.add(file);
                labels.add(info[0]+" · "+date.format(file.lastModified())+"\n"+(info[1].isEmpty()?"未知页面":info[1])
                        +(expires>0?" · 保存至 "+date.format(expires):""));
            }catch(IOException|RuntimeException e){
                if(onlyPkg==null){valid.add(file);labels.add("无法读取的样本 · 可以删除");}
            }
            a.runOnUiThread(()->{
                if(a.isDestroyed())return;
                String caption=title+"\n"+inventory.usage();
                if(valid.isEmpty()){
                    new AlertDialog.Builder(a).setTitle(caption).setMessage(onlyPkg==null?"还没有保存样本":"这个应用还没有已保存的对照样本")
                            .setPositiveButton("关闭",null).show();
                    return;
                }
                AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle(caption)
                        .setItems(labels.toArray(new String[0]),(d,i)->new Thread(()->{
                            try{
                                File file=valid.get(i);
                                long expires=expiry(file);
                                if(expires>0 && System.currentTimeMillis()>=expires){
                                    inventory(a,System.currentTimeMillis());
                                    throw new IOException("样本已到期");
                                }
                                RuleSample sample=read(file);
                                a.runOnUiThread(()->{if(!a.isDestroyed())use.accept(sample);});
                            }catch(Exception e){a.runOnUiThread(()->a.toast("样本已删除、到期或无法读取"));}
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
