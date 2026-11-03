package app.clearscreen;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import android.system.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Split-route local DNS filter. Other traffic is never proxied or decrypted. */
public final class AdFilterService extends VpnService {
    static final String STOP="app.clearscreen.FILTER_STOP";
    static volatile AdFilterService active;
    private volatile boolean stopping;
    private volatile ParcelFileDescriptor tunnel;
    private volatile boolean ready;
    private final AtomicLong packetsRead=new AtomicLong(),packetsAccepted=new AtomicLong(),packetsWritten=new AtomicLong();
    private volatile String lastPacket="none";
    private final Object writeLock=new Object(),historyLock=new Object();
    private final LinkedHashSet<String> recent=new LinkedHashSet<>();
    private final AtomicLong queries=new AtomicLong(),blocks=new AtomicLong();
    private final AtomicInteger failures=new AtomicInteger();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(128));
    private final ConcurrentHashMap<Network,NetworkCapabilities> networks=new ConcurrentHashMap<>();
    private volatile Network underlying;
    private ConnectivityManager connectivity;private FilterStore store;private Thread loop;private DomainRules rules;private DnsTcp tcp;
    private long savedAt;
    private final ConnectivityManager.NetworkCallback networkCallback=new ConnectivityManager.NetworkCallback(){
        @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){networks.put(n,c);selectNetwork();}
        @Override public void onLost(Network n){networks.remove(n);selectNetwork();}
        @Override public void onLinkPropertiesChanged(Network n,LinkProperties p){selectNetwork();}
    };
    static boolean running(){AdFilterService s=active;return s!=null&&s.tunnel!=null&&s.ready&&!s.stopping;}
    static String health(){AdFilterService s=active;return s==null?"no active service":"read="+s.packetsRead.get()+" accepted="+s.packetsAccepted.get()+" written="+s.packetsWritten.get()+" ready="+s.ready+" last="+s.lastPacket;}
    static long blocked(Context context){AdFilterService s=active;return s==null?new FilterStore(context).blocked():s.blocks.get();}
    static void clearRecent(){AdFilterService s=active;if(s!=null)synchronized(s.historyLock){s.recent.clear();}}
    @Override public void onCreate(){super.onCreate();store=new FilterStore(this);connectivity=getSystemService(ConnectivityManager.class);queries.set(store.queried());blocks.set(store.blocked());recent.addAll(store.recent());}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||STOP.equals(intent.getAction())){stop("网络过滤已关闭");return START_NOT_STICKY;}
        if(loop!=null)return START_NOT_STICKY;
        try{
            if(VpnService.prepare(this)!=null)throw new IOException("请先确认系统 VPN 授权");
            NotificationManager manager=getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("network-filter","网络过滤运行状态",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,31,new Intent(this,MainActivity.class).putExtra("tab",6),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop=PendingIntent.getService(this,32,new Intent(this,AdFilterService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            Notification notification=new Notification.Builder(this,"network-filter").setSmallIcon(app.clearscreen.R.drawable.ic_app).setContentTitle("净屏 · 网络过滤")
                    .setContentText("过滤已知广告域名 · 点此管理").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止过滤",stop).build()).build();
            if(Build.VERSION.SDK_INT>=34)startForeground(30,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);else startForeground(30,notification);
            active=this;store.enabled(true);store.message("正在准备本地规则与网络…");
            NetworkRequest request=new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
            connectivity.registerNetworkCallback(request,networkCallback);
            for(Network n:connectivity.getAllNetworks()){NetworkCapabilities c=connectivity.getNetworkCapabilities(n);if(c!=null&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)&&c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))networks.put(n,c);}
            selectNetwork();loop=new Thread(this::run,"clearscreen-dns");loop.start();
        }catch(Exception e){stop("无法启动网络过滤："+readable(e));}
        return START_NOT_STICKY; // A killed process releases its VPN; never leaves a pretend-enabled filter.
    }
    private synchronized void selectNetwork(){
        Network selected=connectivity.getActiveNetwork();if(selected==null||!networks.containsKey(selected)){
            selected=null;for(Map.Entry<Network,NetworkCapabilities> entry:networks.entrySet())if(selected==null||entry.getValue().hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))selected=entry.getKey();
        }
        underlying=selected;if(tunnel!=null)setUnderlyingNetworks(selected==null?null:new Network[]{selected});
    }
    private void run(){
        try{
            rules=new DomainRules(this);if(stopping)return;
            if(underlying==null)throw new IOException("当前没有可用网络，请连接网络后再开启");
            checkLegacyPrivateDns(underlying);
            Builder builder=new Builder().setSession("净屏 · DNS 广告过滤").setMtu(1500).addAddress("198.18.0.1",32).addRoute("198.18.0.2",32).addDnsServer("198.18.0.2")
                    .allowFamily(OsConstants.AF_INET6).setBlocking(false).setUnderlyingNetworks(new Network[]{underlying});
            // Keep Android's conservative metered default; do not mask mobile-data accounting.
            ParcelFileDescriptor created=builder.establish();if(created==null)throw new IOException("系统未建立 VPN，请重新授权");
            synchronized(writeLock){if(stopping){created.close();return;}tunnel=created;}
            tcp=new DnsTcp(this::write,this::resolve);store.message("等待系统网络路由就绪…");long establishedAt=SystemClock.elapsedRealtime();
            byte[] buffer=new byte[32767];StructPollfd poll=new StructPollfd();poll.fd=created.getFileDescriptor();poll.events=(short)OsConstants.POLLIN;long noNetworkSince=0;
            while(!stopping){
                if(!ready){for(Network network:connectivity.getAllNetworks()){NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);LinkProperties link=connectivity.getLinkProperties(network);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)&&link!=null&&link.getDnsServers().contains(InetAddress.getByName("198.18.0.2"))){ready=true;store.message("正在过滤已知广告域名；其他流量由原网络传输");break;}}
                    if(!ready&&SystemClock.elapsedRealtime()-establishedAt>8000)throw new IOException("系统未完成 VPN 路由配置");}
                long now=SystemClock.elapsedRealtime();if(underlying==null){if(noNetworkSince==0)noNetworkSince=now;if(now-noNetworkSince>6000)throw new IOException("网络已断开，已退出过滤；联网后可重新开启");}else noNetworkSince=0;
                int ready=Os.poll(new StructPollfd[]{poll},500);if(ready>0&&(poll.revents&OsConstants.POLLIN)!=0){
                    int n;try{n=Os.read(created.getFileDescriptor(),buffer,0,buffer.length);}catch(ErrnoException e){if(e.errno==OsConstants.EAGAIN)continue;throw e;}
                    packetsRead.incrementAndGet();lastPacket="bytes="+n;if(n>=28){int protocol=buffer[9]&255;lastPacket+=" versionHeader="+(buffer[0]&255)+" total="+DnsWire.u16(buffer,2)+" protocol="+protocol+" destinationMatch="+((int)DnsWire.u32(buffer,16)==DnsWire.SERVER)+" ipChecksum="+DnsWire.checksum(buffer,0,20,0)+" transportChecksum="+DnsWire.checksum(buffer,20,n-20,DnsWire.pseudo(buffer,protocol,n-20));}
                    DnsWire.Packet packet=DnsWire.packet(buffer,n);if(packet!=null){packetsAccepted.incrementAndGet();if(packet.protocol==17)resolve(packet.data,answer->{DnsWire.Question question=DnsWire.question(packet.data);if(question!=null&&answer.length>question.udpSize)answer=DnsWire.error(packet.data,0,true);write(DnsWire.reply(packet,answer,0,0,0));});else tcp.accept(packet,now);}
                }
                tcp.tick(now);if(now-savedAt>5000){persist();savedAt=now;}
            }
        }catch(Exception e){if(!stopping)stop("已停止过滤并交还系统网络："+readable(e));}
        finally{if(!stopping)stop("网络过滤已结束");}
    }
    private void resolve(byte[] query,Consumer<byte[]> callback){
        if(stopping)return;DnsWire.Question question=DnsWire.question(query);
        if(question==null){callback.accept(DnsWire.error(query,1,false));return;}
        queries.incrementAndGet();
        if(rules.blocked(question.name,store.allowed())){blocks.incrementAndGet();synchronized(historyLock){recent.remove(question.name);recent.add(question.name);while(recent.size()>30)recent.remove(recent.iterator().next());}callback.accept(DnsWire.error(query,3,false));return;}
        try{workers.execute(()->{
            byte[] response;
            try{Network network=underlying;if(network==null)throw new IOException("网络暂时断开");response=upstream(network,query);if(!DnsWire.answerMatches(query,response))throw new IOException("DNS 响应不匹配");failures.set(0);}
            catch(Exception e){response=DnsWire.error(query,2,false);if(failures.incrementAndGet()>=8&&!stopping)stop("DNS 连续请求失败，已退出过滤以恢复系统网络");}
            if(!stopping)callback.accept(response);
        });}catch(RejectedExecutionException e){if(!stopping)callback.accept(DnsWire.error(query,2,false));}
    }
    private void checkLegacyPrivateDns(Network network)throws IOException{
        if(Build.VERSION.SDK_INT==28){LinkProperties p=connectivity.getLinkProperties(network);if(p!=null&&p.isPrivateDnsActive())throw new IOException("Android 9 的加密 DNS 暂不支持叠加本过滤，已保留原网络设置");}
    }
    private byte[] upstream(Network network,byte[] query)throws Exception{
        if(Build.VERSION.SDK_INT>=29){
            CompletableFuture<byte[]> result=new CompletableFuture<>();CancellationSignal cancellation=new CancellationSignal();
            DnsResolver.getInstance().rawQuery(network,query,DnsResolver.FLAG_NO_RETRY,Runnable::run,cancellation,new DnsResolver.Callback<byte[]>(){
                public void onAnswer(byte[] answer,int rcode){result.complete(answer);}
                public void onError(DnsResolver.DnsException error){result.completeExceptionally(error);}
            });
            try{return result.get(5,TimeUnit.SECONDS);}finally{cancellation.cancel();}
        }
        checkLegacyPrivateDns(network);LinkProperties link=connectivity.getLinkProperties(network);if(link==null||link.getDnsServers().isEmpty())throw new IOException("系统没有提供 DNS 服务器");
        IOException last=null;int tried=0;
        for(InetAddress address:link.getDnsServers()){
            if(++tried>2)break;
            try(DatagramSocket socket=new DatagramSocket()){
                if(!protect(socket))throw new IOException("无法保护 DNS 连接");network.bindSocket(socket);socket.connect(address,53);socket.setSoTimeout(2000);socket.send(new DatagramPacket(query,query.length));
                byte[] bytes=new byte[65535];DatagramPacket packet=new DatagramPacket(bytes,bytes.length);socket.receive(packet);byte[] answer=Arrays.copyOf(bytes,packet.getLength());
                if(!DnsWire.answerMatches(query,answer))throw new IOException("DNS 响应校验失败");
                if((answer[2]&2)==0)return answer;
                try(Socket tcpSocket=new Socket()){
                    if(!protect(tcpSocket))throw new IOException("无法保护 DNS 连接");network.bindSocket(tcpSocket);tcpSocket.connect(new InetSocketAddress(address,53),2000);tcpSocket.setSoTimeout(2500);
                    DataOutputStream output=new DataOutputStream(tcpSocket.getOutputStream());output.writeShort(query.length);output.write(query);output.flush();DataInputStream input=new DataInputStream(tcpSocket.getInputStream());int size=input.readUnsignedShort();if(size<12)throw new IOException("DNS 响应过短");byte[] full=new byte[size];input.readFully(full);return full;
                }
            }catch(IOException e){last=e;}
        }
        throw last==null?new IOException("DNS 不可用"):last;
    }
    private void write(byte[] bytes){
        boolean failed=false;
        synchronized(writeLock){ParcelFileDescriptor fd=tunnel;if(stopping||fd==null)return;
            try{Os.write(fd.getFileDescriptor(),bytes,0,bytes.length);packetsWritten.incrementAndGet();}catch(Exception e){failed=!stopping;}}
        if(failed)stop("网络接口写入失败，已退出过滤");
    }
    private void persist(){if(store==null)return;synchronized(historyLock){store.counters(queries.get(),blocks.get(),recent);}}
    private static String readable(Exception e){String message=e.getMessage();return message==null?e.getClass().getSimpleName():message;}
    private void stop(String message){
        stopping=true;if(store!=null){store.enabled(false);store.message(message);}closeTunnel();if(tcp!=null)tcp.close();workers.shutdownNow();persist();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();if(active==this)active=null;
    }
    private void closeTunnel(){synchronized(writeLock){ParcelFileDescriptor fd=tunnel;tunnel=null;if(fd!=null)try{fd.close();}catch(IOException ignored){}}}
    @Override public void onRevoke(){stop("系统已撤销 VPN 授权或已切换到其他 VPN");super.onRevoke();}
    @Override public void onDestroy(){stopping=true;closeTunnel();if(tcp!=null)tcp.close();workers.shutdownNow();persist();try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}if(active==this)active=null;super.onDestroy();}
}
