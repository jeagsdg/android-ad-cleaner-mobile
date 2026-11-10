package app.clearscreen;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** DNS-only stop-and-wait TCP endpoint. Bounded flows, framing, replay and retransmission. */
final class DnsTcp {
    interface Resolver { void resolve(byte[] query,Consumer<byte[]> result); }
    private final Map<Long,Flow> flows=new HashMap<>();
    private final Consumer<byte[]> output;private final Resolver resolver;
    private final LongSupplier clock;
    private final Random random=new java.security.SecureRandom();
    DnsTcp(Consumer<byte[]> output,Resolver resolver,LongSupplier clock){this.output=output;this.resolver=resolver;this.clock=clock;}
    static long add(long value,long count){return (value+count)&0xffffffffL;}
    static int distance(long a,long b){return (int)(a-b);}
    static long key(DnsWire.Packet p){return ((p.source&0xffffffffL)<<16)|p.port;}
    private final class Flow {
        final DnsWire.Packet peer;long next,receive,sentAt,touched;byte[] pending;int pendingCount,retries,window;
        boolean established,clientFin,serverFin,resolving,dead;byte[] incoming=new byte[0];
        final ArrayDeque<byte[]> outgoing=new ArrayDeque<>();int offset;
        Flow(DnsWire.Packet p,long now){peer=p;receive=add(p.sequence,1);next=random.nextInt()&0xffffffffL;window=p.window;touched=now;}
        void send(byte[] bytes,int flags,long now){int count=bytes.length+((flags&3)!=0?1:0);byte[] packet=DnsWire.reply(peer,bytes,next,receive,flags);
            if(count>0){pending=packet;pendingCount=count;sentAt=now;retries=0;}output.accept(packet);}
        void ack(){output.accept(DnsWire.reply(peer,new byte[0],next,receive,16));}
        void reset(){output.accept(DnsWire.reply(peer,new byte[0],next,receive,20));dead=true;}
        void pump(long now){
            if(dead||!established||pending!=null)return;
            if(!outgoing.isEmpty()&&window>0){byte[] bytes=outgoing.peek();int size=Math.min(Math.min(512,window),bytes.length-offset);send(Arrays.copyOfRange(bytes,offset,offset+size),24,now);offset+=size;if(offset==bytes.length){outgoing.remove();offset=0;}return;}
            if(clientFin&&!resolving&&incoming.length==0&&outgoing.isEmpty()&&!serverFin){serverFin=true;send(new byte[0],17,now);}
        }
        void parse(){
            if(dead||resolving||outgoing.size()>=4||incoming.length<2)return;
            int size=DnsWire.u16(incoming,0);if(size<12){reset();return;}if(incoming.length<size+2)return;
            byte[] query=Arrays.copyOfRange(incoming,2,size+2);incoming=Arrays.copyOfRange(incoming,size+2,incoming.length);resolving=true;
            resolver.resolve(query,answer->{synchronized(DnsTcp.this){if(dead)return;resolving=false;
                if(answer.length>65535){reset();return;}byte[] framed=new byte[answer.length+2];DnsWire.p16(framed,0,answer.length);System.arraycopy(answer,0,framed,2,answer.length);outgoing.add(framed);pump(clock.getAsLong());parse();}});
        }
    }
    synchronized void accept(DnsWire.Packet p){
        long now=clock.getAsLong();
        long key=key(p);Flow f=flows.get(key);
        if((p.flags&4)!=0){if(f!=null){f.dead=true;flows.remove(key);}return;}
        if((p.flags&2)!=0){
            if((p.flags&16)!=0)return;
            if(f!=null){if(!f.established&&f.receive==add(p.sequence,1)&&f.pending!=null)output.accept(f.pending);return;}
            if(flows.size()>=64)return;f=new Flow(p,now);flows.put(key,f);f.send(new byte[0],18,now);return;
        }
        if(f==null){output.accept(DnsWire.reply(p,new byte[0],p.ack,0,4));return;}
        if(f.dead){flows.remove(key);return;}
        if((p.flags&16)==0)return;
        boolean acknowledgesPending=f.pending!=null&&p.ack==add(f.next,f.pendingCount);
        if(!f.established&&(!acknowledgesPending||p.sequence!=f.receive))return;
        int skip=distance(f.receive,p.sequence);
        if(skip<0){f.ack();return;}
        if(acknowledgesPending){f.next=p.ack;f.pending=null;f.established=true;
            if(f.serverFin){f.dead=true;flows.remove(key);return;}}
        else if(distance(p.ack,f.next)>0)return;
        f.touched=now;
        f.window=p.window;
        if(p.data.length>skip&&!f.clientFin){int size=p.data.length-skip;if(f.incoming.length+size>65537){f.reset();return;}
            byte[] bytes=Arrays.copyOf(f.incoming,f.incoming.length+size);System.arraycopy(p.data,skip,bytes,f.incoming.length,size);f.incoming=bytes;f.receive=add(f.receive,size);f.ack();f.parse();
        }else if(p.data.length>0)f.ack();
        if((p.flags&1)!=0&&add(p.sequence,p.data.length)==f.receive){f.receive=add(f.receive,1);f.clientFin=true;f.ack();
            if(f.incoming.length>0&&!f.resolving&&(f.incoming.length<2||DnsWire.u16(f.incoming,0)+2>f.incoming.length)){f.reset();return;}}
        f.parse();f.pump(now);
    }
    synchronized void tick(){
        long now=clock.getAsLong();
        Iterator<Flow> it=flows.values().iterator();while(it.hasNext()){Flow f=it.next();
            if(f.dead||now-f.touched>30000){f.dead=true;it.remove();continue;}
            if(f.pending!=null&&now-f.sentAt>1000){if(++f.retries>5){f.reset();it.remove();}else{f.sentAt=now;output.accept(f.pending);}}
            else f.pump(now);
        }
    }
    synchronized void close(){for(Flow f:flows.values())f.dead=true;flows.clear();}
}
