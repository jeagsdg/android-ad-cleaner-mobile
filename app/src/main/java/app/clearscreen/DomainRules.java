package app.clearscreen;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

final class DomainRules {
    private static final Set<String> PROTECTED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "localhost", "www.google.com", "google.com", "www.baidu.com", "baidu.com", "qq.com",
            "weixin.qq.com", "login.live.com", "connectivitycheck.gstatic.com", "connectivitycheck.platform.hicloud.com",
            "connect.rom.miui.com", "conn1.oppomobile.com", "conn2.oppomobile.com")));
    private final String[] hosts;
    DomainRules(Context context)throws IOException{
        ArrayList<String> values=new ArrayList<>();
        // Do not name this .gz: aapt automatically inflates those assets and strips the suffix.
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(context.getAssets().open("dns-hosts.bin")),StandardCharsets.US_ASCII))){
            String line,previous="";while((line=reader.readLine())!=null){if(values.size()>=400000||line.length()>253||line.compareTo(previous)<=0)throw new IOException("广告规则校验失败");values.add(line);previous=line;}}
        hosts=values.toArray(new String[0]);if(hosts.length==0)throw new IOException("广告规则为空");
    }
    DomainRules(String... values){hosts=values.clone();Arrays.sort(hosts);}
    boolean blocked(String domain,Set<String> allowed){return !allowed.contains(domain)&&contains(domain);}
    boolean contains(String domain){return Arrays.binarySearch(hosts,domain)>=0;}
    static boolean canBlockHost(String domain){
        return domain != null && !PROTECTED_HOSTS.contains(domain) && !domain.matches("[0-9.]+");
    }
    int size(){return hosts.length;}
    static String normalize(String value){
        String domain=value.trim().toLowerCase(Locale.ROOT);if(domain.endsWith("."))domain=domain.substring(0,domain.length()-1);
        if(domain.length()>253||!domain.contains("."))return null;
        for(String part:domain.split("\\.",-1))if(!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return null;
        return domain;
    }
}
