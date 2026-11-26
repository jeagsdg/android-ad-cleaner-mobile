package app.clearscreen;

import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.util.*;

/** Real sockets through the local TUN. Requires prior visible VPN consent, never shell grants. */
final class NetworkChecks {
    static byte[] udp(byte[] query)throws Exception{try(DatagramSocket socket=new DatagramSocket()){socket.setSoTimeout(8000);socket.send(new DatagramPacket(query,query.length,InetAddress.getByName("198.18.0.2"),53));byte[] buffer=new byte[4096];DatagramPacket answer=new DatagramPacket(buffer,buffer.length);socket.receive(answer);return Arrays.copyOf(buffer,answer.getLength());}catch(Exception e){throw new IOException("DNS socket failed; "+AdFilterService.health(),e);}}
    static void livePolicy(DeviceChecks test)throws Exception{
        Context c=test.getTargetContext();
        test.require(VpnService.prepare(c)==null,"VPN consent present");
        test.require(AdFilterService.active==null,"dedicated VPN policy fixture starts with filter stopped");
        FilterStore settings=new FilterStore(c);
        Set<String> old=settings.bypassedApps();
        String domain="policy-"+Long.toHexString(SystemClock.elapsedRealtime())+".invalid";
        byte[] query=DnsWire.query(domain,841);
        try{
            settings.setBypassedApps(Collections.emptySet());
            c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(600);
            Intent start=new Intent(c,AdFilterService.class).setAction("start");
            if(Build.VERSION.SDK_INT>=26)c.startForegroundService(start);else c.startService(start);
            long end=SystemClock.elapsedRealtime()+15000;
            while(!AdFilterService.running()&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            test.require(AdFilterService.running(),"local VPN ready");
            AdFilterService service=AdFilterService.active;
            test.require(settings.blockHost(domain,false),"temporary domain block saved");
            test.require(locallyBlocked(c,query,1500),"new block took effect on current interface");
            settings.allow(domain,true);
            test.require(!locallyBlocked(c,query,1500),"exact allow applied on next query");
            settings.allow(domain,false);
            test.require(locallyBlocked(c,query,1500),"removing allow restored block");
            Set<String> outside=Collections.singleton(c.getPackageName());
            test.require(changeBypass(c,outside).contains("已生效"),"OS established new app bypass policy");
            test.require(awaitPolicy(c,query,false),"bypassed UID no longer traverses local TUN");
            test.require(changeBypass(c,Collections.emptySet()).contains("已生效"),"OS removed bypass policy");
            test.require(awaitPolicy(c,query,true),"restored UID traverses TUN again");
            test.require(service==AdFilterService.active&&AdFilterService.running(),"same filter service remained active");
        }finally{
            if(AdFilterService.active!=null)c.startService(new Intent(c,AdFilterService.class).setAction(AdFilterService.STOP));
            long end=SystemClock.elapsedRealtime()+6000;
            while(AdFilterService.active!=null&&SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            settings.removeCustomHost(domain);
            settings.allow(domain,false);
            settings.setBypassedApps(old);
        }
    }

    private static String changeBypass(Context c,Set<String> apps)throws Exception{
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> result=new java.util.concurrent.atomic.AtomicReference<>("");
        AdFilterService.saveBypassedApps(c,apps,text->{result.set(text);done.countDown();});
        if(!done.await(8,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("VPN policy callback timed out");
        return result.get();
    }

    private static boolean awaitPolicy(Context c,byte[] query,boolean expected)throws Exception{
        long end=SystemClock.elapsedRealtime()+6000;
        do{
            if(locallyBlocked(c,query,700)==expected)return true;
            Thread.sleep(150);
        }while(SystemClock.elapsedRealtime()<end);
        return false;
    }

    private static boolean locallyBlocked(Context c,byte[] query,int timeout)throws Exception{
        long before=AdFilterService.blocked(c);
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setSoTimeout(timeout);
            socket.send(new DatagramPacket(query,query.length,InetAddress.getByName("198.18.0.2"),53));
            DatagramPacket result=new DatagramPacket(new byte[4096],4096);
            socket.receive(result);
            byte[] answer=Arrays.copyOf(result.getData(),result.getLength());
            return DnsWire.answerMatches(query,answer)&&(answer[3]&15)==3&&AdFilterService.blocked(c)>before;
        }catch(SocketTimeoutException|PortUnreachableException|NoRouteToHostException noLocalRoute){
            return false;
        }
    }
    static void run(DeviceChecks test)throws Exception{
        Context context=test.getTargetContext();test.require(VpnService.prepare(context)==null,"visible VPN authorization must already exist");
        context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1000);
        Intent start=new Intent(context,AdFilterService.class).setAction("start");
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(start);else context.startService(start);
        long deadline=SystemClock.elapsedRealtime()+12000;while(!AdFilterService.running()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        test.require(AdFilterService.running(),"filter established: "+new FilterStore(context).message());
        byte[] blocked=DnsWire.query("doubleclick.net",703),normal=DnsWire.query("example.com",704);FilterStore store=new FilterStore(context);store.allow("doubleclick.net",false);
        long before=AdFilterService.blocked(context);byte[] answer=udp(blocked);test.require(DnsWire.answerMatches(blocked,answer)&&(answer[3]&15)==3,"UDP blocked name NXDOMAIN");test.require(AdFilterService.blocked(context)==before+1,"actual blocked query counted");
        answer=udp(normal);test.require(DnsWire.answerMatches(normal,answer)&&(answer[3]&15)==0&&DnsWire.u16(answer,6)>0,"normal UDP DNS resolves over physical network");
        store.allow("doubleclick.net",true);long allowedBefore=AdFilterService.blocked(context);answer=udp(blocked);test.require(DnsWire.answerMatches(blocked,answer)&&(answer[3]&15)==0,"allowlist takes effect without restart");test.require(AdFilterService.blocked(context)==allowedBefore,"allowed query not counted as blocked");store.allow("doubleclick.net",false);
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("198.18.0.2",53),5000);socket.setSoTimeout(8000);DataOutputStream out=new DataOutputStream(socket.getOutputStream());DataInputStream in=new DataInputStream(socket.getInputStream());
            for(byte[] query:new byte[][]{blocked,normal}){out.writeShort(query.length);out.write(query,0,3);out.flush();Thread.sleep(50);out.write(query,3,query.length-3);out.flush();int size=in.readUnsignedShort();byte[] data=new byte[size];in.readFully(data);test.require(DnsWire.answerMatches(query,data),"TCP split-frame query and reusable stream resolved");test.require((data[3]&15)==(query==blocked?3:0),"TCP policy matches UDP");}}
        test.require(InetAddress.getAllByName("example.org").length>0,"Android platform DNS works while filtering");
        try{java.net.HttpURLConnection connection=(java.net.HttpURLConnection)new URL("https://example.com/").openConnection();connection.setConnectTimeout(8000);connection.setReadTimeout(8000);test.require(connection.getResponseCode()==200,"normal HTTPS bypass remains usable");connection.disconnect();}finally{context.startService(new Intent(context,AdFilterService.class).setAction(AdFilterService.STOP));}
        deadline=SystemClock.elapsedRealtime()+6000;while(AdFilterService.active!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);test.require(!AdFilterService.running(),"filter stops and releases TUN");test.require(InetAddress.getAllByName("example.net").length>0,"system DNS works after stop");
        test.require(!new Store(context).exportText().contains("doubleclick.net"),"DNS history excluded from diagnostic export");
    }
}
