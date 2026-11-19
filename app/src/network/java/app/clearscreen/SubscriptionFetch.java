package app.clearscreen;
import java.net.*;
import java.io.*;
import javax.net.ssl.HttpsURLConnection;
final class SubscriptionFetch {
    static String get(String url) throws IOException{
        URL location=new URL(url);
        for(int i=0;i<5;i++){
            if(!"https".equals(location.getProtocol())||location.getUserInfo()!=null||location.getRef()!=null)throw new IOException("需要 HTTPS 公开订阅地址");
            HttpsURLConnection c=(HttpsURLConnection)location.openConnection();
            try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(15000);
                int code=c.getResponseCode();if(code>=300&&code<400&&c.getHeaderField("Location")!=null){location=new URL(location,c.getHeaderField("Location"));continue;}
                if(code!=200)throw new IOException("订阅返回 HTTP "+code);
                try(InputStream in=c.getInputStream();ByteArrayOutputStream b=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(b.size()+n>4*1024*1024)throw new IOException("文件超过 4 MB");b.write(buf,0,n);}return b.toString("UTF-8");
                }
            }finally{c.disconnect();}
        }throw new IOException("订阅重定向次数过多");
    }
}
