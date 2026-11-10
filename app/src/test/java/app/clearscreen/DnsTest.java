package app.clearscreen;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class DnsTest {
    @Test public void namesAreExactAndAllowOverrides(){DomainRules rules=new DomainRules("ads.example.com","tracker.example.com");assertTrue(rules.blocked("ads.example.com",Collections.emptySet()));assertFalse(rules.blocked("notads.example.com",Collections.emptySet()));assertFalse(rules.blocked("a.ads.example.com",Collections.emptySet()));assertFalse(rules.blocked("ads.example.com",Collections.singleton("ads.example.com")));assertEquals("ads.example.com",DomainRules.normalize(" ADS.example.com. "));assertNull(DomainRules.normalize("https://ads.example.com"));assertNull(DomainRules.normalize("a..example.com"));}
    @Test public void questionsAndNegativeAnswersPreserveIdAndQuestion(){byte[] q=DnsWire.query("Ads.Example.com",1234);DnsWire.Question parsed=DnsWire.question(q);assertEquals("ads.example.com",parsed.name);assertEquals(512,parsed.udpSize);byte[] answer=DnsWire.error(q,3,false);assertTrue(DnsWire.answerMatches(q,answer));assertEquals(3,answer[3]&15);assertEquals(0,DnsWire.u16(answer,6));assertEquals(0,answer[3]&0x20);assertEquals(1,answer[2]&1);}
    @Test public void ednsSizeAndTruncationAreBounded(){byte[] q=DnsWire.query("example.com",1);byte[] edns=Arrays.copyOf(q,q.length+11);DnsWire.p16(edns,10,1);DnsWire.p16(edns,q.length+1,41);DnsWire.p16(edns,q.length+3,4096);assertEquals(1232,DnsWire.question(edns).udpSize);byte[] truncated=DnsWire.error(edns,0,true);assertEquals(q.length,truncated.length);assertEquals(2,truncated[2]&2);assertEquals(0,DnsWire.u16(truncated,10));assertTrue(DnsWire.answerMatches(edns,truncated));}
    @Test public void malformedQueriesNeverBecomeNetworkLookups(){assertNull(DnsWire.question(new byte[0]));byte[] q=DnsWire.query("example.com",5);q[12]=(byte)0xc0;assertNull(DnsWire.question(q));q=DnsWire.query("example.com",5);q[5]=2;assertNull(DnsWire.question(q));q=DnsWire.query("example.com",5);q[2]=(byte)0x81;assertNull(DnsWire.question(q));assertNull(DnsWire.question(Arrays.copyOf(q,15)));assertEquals(12,DnsWire.error(new byte[0],1,false).length);}
    @Test public void wrongTransactionOrQuestionCannotBeAccepted(){byte[] q=DnsWire.query("example.com",10),answer=DnsWire.error(q,0,false);answer[1]++;assertFalse(DnsWire.answerMatches(q,answer));answer=DnsWire.error(q,0,false);answer[13]='x';assertFalse(DnsWire.answerMatches(q,answer));}
    static DnsWire.Packet peer(int protocol){DnsWire.Packet p=new DnsWire.Packet();p.source=0xc6120001;p.port=34000;p.protocol=protocol;p.window=32768;p.data=new byte[0];return p;}
    static byte[] clientPacket(int protocol,byte[] payload){DnsWire.Packet p=peer(protocol);byte[] packet=DnsWire.reply(p,payload,100,0,16);DnsWire.p32(packet,12,p.source);DnsWire.p32(packet,16,DnsWire.SERVER);DnsWire.p16(packet,20,p.port);DnsWire.p16(packet,22,53);DnsWire.p16(packet,10,0);DnsWire.p16(packet,10,DnsWire.checksum(packet,0,20,0));DnsWire.p16(packet,protocol==17?26:36,0);DnsWire.p16(packet,protocol==17?26:36,DnsWire.checksum(packet,20,packet.length-20,DnsWire.pseudo(packet,protocol,packet.length-20)));return packet;}
    @Test public void packetRoundTripChecksLengthsAddressesAndChecksums(){byte[] q=DnsWire.query("example.com",2);for(int protocol:new int[]{6,17}){byte[] request=clientPacket(protocol,q);DnsWire.Packet p=DnsWire.packet(request,request.length);assertNotNull(p);assertArrayEquals(q,p.data);byte[] answer=DnsWire.reply(p,DnsWire.error(q,3,false),42,100,24);assertEquals(0,DnsWire.checksum(answer,0,20,0));assertEquals(0,DnsWire.checksum(answer,20,answer.length-20,DnsWire.pseudo(answer,protocol,answer.length-20)));request[request.length-1]^=1;assertNull(DnsWire.packet(request,request.length));request=clientPacket(protocol,q);request[6]=0x20;assertNull(DnsWire.packet(request,request.length));}}
    static final class Session {
        final List<byte[]> output=new ArrayList<>();int resolves;long client=1000,server,now;final DnsTcp tcp;
        Session(int answerSize){tcp=new DnsTcp(output::add,(q,cb)->{resolves++;byte[] a=Arrays.copyOf(DnsWire.error(q,0,false),answerSize);cb.accept(a);},()->now);}
        void advance(long millis){now+=millis;tcp.tick();}
        void send(int flags,byte[] data,long seq,long ack){DnsWire.Packet p=peer(6);p.flags=flags;p.data=data;p.sequence=seq;p.ack=ack;tcp.accept(p);}
        void connect(){send(2,new byte[0],client,0);byte[] syn=output.get(0);assertEquals(18,syn[33]);server=DnsTcp.add(DnsWire.u32(syn,24),1);client++;send(16,new byte[0],client,server);}
        byte[] frame(){byte[] q=DnsWire.query("example.com",9),frame=new byte[q.length+2];DnsWire.p16(frame,0,q.length);System.arraycopy(q,0,frame,2,q.length);return frame;}
    }
    @Test public void tcpHandlesFragmentedQueryLargeResponseAndDuplicatePackets(){Session s=new Session(1600);s.connect();byte[] query=s.frame();s.send(24,Arrays.copyOf(query,1),s.client,s.server);s.client++;assertEquals(0,s.resolves);long restStart=s.client;s.send(24,Arrays.copyOfRange(query,1,query.length),s.client,s.server);s.client+=query.length-1;assertEquals(1,s.resolves);s.send(24,Arrays.copyOfRange(query,1,query.length),restStart,s.server);assertEquals(1,s.resolves);
        java.io.ByteArrayOutputStream response=new java.io.ByteArrayOutputStream();int cursor=0;
        for(int i=0;i<6;i++){byte[] data=null;for(;cursor<s.output.size();cursor++){byte[] packet=s.output.get(cursor);if(packet.length>40){data=packet;cursor++;break;}}if(data==null)break;response.write(data,40,data.length-40);s.server=DnsTcp.add(s.server,data.length-40);s.send(16,new byte[0],s.client,s.server);}
        assertEquals(1602,response.size());assertEquals(1600,DnsWire.u16(response.toByteArray(),0));s.send(17,new byte[0],s.client,s.server);assertTrue(s.output.stream().anyMatch(p->(p[33]&1)!=0));}
    @Test public void tcpOutOfOrderPayloadIsNotResolvedAndSynRetransmits(){Session s=new Session(40);s.send(2,new byte[0],1000,0);byte[] first=s.output.get(0);s.send(2,new byte[0],1000,0);assertArrayEquals(first,s.output.get(1));s.advance(1100);assertArrayEquals(first,s.output.get(2));s.server=DnsTcp.add(DnsWire.u32(first,24),1);s.client=1001;s.send(16,new byte[0],s.client,s.server);s.send(24,s.frame(),2000,s.server);assertEquals(0,s.resolves);s.send(24,s.frame(),s.client,s.server);assertEquals(1,s.resolves);}
    @Test public void tcpDoesNotResolveUntilTheSynIsAcknowledgedInSequence(){
        Session s=new Session(40);
        s.send(2,new byte[0],s.client,0);
        long initialServerSequence=DnsWire.u32(s.output.get(0),24);
        s.client++;
        s.server=DnsTcp.add(initialServerSequence,1);
        s.send(24,s.frame(),s.client,initialServerSequence);
        s.send(24,s.frame(),s.client+1,s.server);
        assertEquals(0,s.resolves);
        s.send(24,s.frame(),s.client,s.server);
        assertEquals(1,s.resolves);
    }
    @Test public void tcpResponseRetransmitsOnScheduleDespiteAnOutOfOrderAck(){
        Session s=new Session(40);
        s.connect();
        byte[] query=s.frame();
        s.send(24,query,s.client,s.server);
        s.client+=query.length;
        byte[] response=s.output.get(s.output.size()-1);
        assertTrue(response.length>40);
        s.send(16,new byte[0],s.client+1,DnsTcp.add(s.server,response.length-40));
        int sent=s.output.size();
        s.advance(999);
        assertEquals(sent,s.output.size());
        s.advance(2);
        assertEquals(sent+1,s.output.size());
        assertArrayEquals(response,s.output.get(sent));
    }
    @Test public void randomMalformedPacketsStayBounded(){Random random=new Random(42);for(int i=0;i<5000;i++){byte[] bytes=new byte[random.nextInt(1600)];random.nextBytes(bytes);DnsWire.question(bytes);DnsWire.packet(bytes,bytes.length);DnsWire.error(bytes,1,false);}}
}
