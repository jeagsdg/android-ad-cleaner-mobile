package app.clearscreen;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small, bounded DNS/IP codec. No browsing payload is routed into this codec. */
final class DnsWire {
    static final int SERVER=0xc6120002; // 198.18.0.2, only this /32 is routed.
    static int u16(byte[] b,int i){return ((b[i]&255)<<8)|(b[i+1]&255);}
    static long u32(byte[] b,int i){return ((long)u16(b,i)<<16)|u16(b,i+2);}
    static void p16(byte[] b,int i,int v){b[i]=(byte)(v>>>8);b[i+1]=(byte)v;}
    static void p32(byte[] b,int i,long v){p16(b,i,(int)(v>>>16));p16(b,i+2,(int)v);}
    static final class Question {
        final String name; final int end,type,udpSize;
        Question(String n,int e,int t,int s){name=n;end=e;type=t;udpSize=s;}
    }
    static Question question(byte[] q){
        if(q.length<17||q.length>65535||(q[2]&0xf8)!=0||u16(q,4)!=1||u16(q,6)!=0||u16(q,8)!=0)return null;
        // Queries with compressed names are rejected, avoiding pointer cycles and copied dangling pointers.
        int p=12;StringBuilder name=new StringBuilder();
        while(p<q.length){int len=q[p++]&255;if(len==0)break;if(len>63||p+len>=q.length)return null;
            if(name.length()>0)name.append('.');
            for(int i=0;i<len;i++){int c=q[p++]&255;if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='-'||c=='_'))return null;name.append((char)c);}
            if(name.length()>253)return null;
        }
        if(p+4>q.length||u16(q,p+2)!=1)return null;
        int type=u16(q,p),end=p+4,size=512;p=end;
        int extra=u16(q,10);if(extra>1)return null;
        if(extra==1){if(p+11>q.length||q[p]!=0||u16(q,p+1)!=41)return null;
            size=Math.max(512,Math.min(1232,u16(q,p+3)));int len=u16(q,p+9);if(p+11+len!=q.length)return null;
        }else if(p!=q.length)return null;
        return new Question(name.toString().toLowerCase(java.util.Locale.ROOT),end,type,size);
    }
    static byte[] error(byte[] q,int rcode,boolean truncated){
        Question question=question(q);int end=question==null?12:question.end;
        byte[] out=Arrays.copyOf(q,Math.max(12,end));out[2]=(byte)(0x80|(q.length>2?q[2]&1:0)|(truncated?2:0));out[3]=(byte)(0x80|rcode);
        p16(out,4,question==null?0:1);p16(out,6,0);p16(out,8,0);p16(out,10,0);return out;
    }
    static boolean answerMatches(byte[] q,byte[] a){
        Question question=question(q);if(question==null||a.length<question.end||a.length>65535||(a[2]&0x80)==0||u16(q,0)!=u16(a,0)||u16(a,4)!=1)return false;
        for(int i=12;i<question.end;i++)if(q[i]!=a[i])return false;return true;
    }
    static byte[] query(String domain,int id){
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] h=new byte[12];p16(h,0,id);h[2]=1;h[5]=1;out.write(h,0,12);
        for(String part:domain.split("\\.")){byte[] s=part.getBytes(StandardCharsets.US_ASCII);out.write(s.length);out.write(s,0,s.length);}out.write(0);out.write(0);out.write(1);out.write(0);out.write(1);return out.toByteArray();
    }
    static final class Packet {
        int source,port,protocol,flags,window;long sequence,ack;byte[] data;
    }
    static Packet packet(byte[] b,int n){
        if(n<28||(b[0]&255)!=0x45||u16(b,2)!=n||(u16(b,6)&0x3fff)!=0||(int)u32(b,16)!=SERVER||checksum(b,0,20,0)!=0)return null;
        Packet p=new Packet();p.source=(int)u32(b,12);p.port=u16(b,20);p.protocol=b[9]&255;
        if(u16(b,22)!=53)return null;
        if(p.protocol==17){if(u16(b,24)!=n-20||n<28)return null;if(u16(b,26)!=0&&checksum(b,20,n-20,pseudo(b,17,n-20))!=0)return null;p.data=Arrays.copyOfRange(b,28,n);}
        else if(p.protocol==6){if(n<40)return null;int h=(b[32]>>>4&15)*4;if(h<20||20+h>n||checksum(b,20,n-20,pseudo(b,6,n-20))!=0)return null;
            p.sequence=u32(b,24);p.ack=u32(b,28);p.flags=b[33]&255;p.window=u16(b,34);p.data=Arrays.copyOfRange(b,20+h,n);
        }else return null;
        return p;
    }
    static byte[] reply(Packet p,byte[] data,long seq,long ack,int flags){
        int transport=p.protocol==17?8:20;byte[] b=new byte[20+transport+data.length];b[0]=0x45;p16(b,2,b.length);b[8]=64;b[9]=(byte)p.protocol;p32(b,12,SERVER);p32(b,16,p.source);
        p16(b,20,53);p16(b,22,p.port);
        if(p.protocol==17)p16(b,24,8+data.length);
        else{p32(b,24,seq);p32(b,28,ack);b[32]=0x50;b[33]=(byte)flags;p16(b,34,32768);}
        System.arraycopy(data,0,b,20+transport,data.length);p16(b,10,checksum(b,0,20,0));int check=checksum(b,20,b.length-20,pseudo(b,p.protocol,b.length-20));p16(b,p.protocol==17?26:36,check==0?65535:check);return b;
    }
    static int pseudo(byte[] b,int protocol,int length){return u16(b,12)+u16(b,14)+u16(b,16)+u16(b,18)+protocol+length;}
    static int checksum(byte[] b,int start,int length,int sum){int end=start+length;for(int i=start;i<end;i+=2)sum+=((b[i]&255)<<8)|(i+1<end?b[i+1]&255:0);while((sum>>>16)!=0)sum=(sum&65535)+(sum>>>16);return (~sum)&65535;}
}
