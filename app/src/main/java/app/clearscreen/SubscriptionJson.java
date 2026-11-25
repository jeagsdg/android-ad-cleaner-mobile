package app.clearscreen;

import org.json.*;

/** Normalise the JSON5 syntax commonly used in subscription files before Android's JSON parser.
 * Scans strings separately: URLs, selector operators and comment-like text are opaque. */
final class SubscriptionJson {
    private SubscriptionJson(){}
    static JSONObject read(String source)throws JSONException{
        if(source==null||source.length()>4*1024*1024)throw new JSONException("订阅过大");
        StringBuilder out=new StringBuilder(source.length());int i=0,last=-1;
        while(i<source.length()){
            char c=source.charAt(i++);
            if(Character.isWhitespace(c)||c=='\ufeff'){out.append(' ');continue;}
            if(c=='/'&&i<source.length()){
                char next=source.charAt(i);
                if(next=='/'){while(i<source.length()&&source.charAt(i)!='\r'&&source.charAt(i)!='\n')i++;out.append(' ');continue;}
                if(next=='*'){int end=source.indexOf("*/",i+1);if(end<0)throw new JSONException("注释缺少 */");i=end+2;out.append(' ');continue;}
            }
            if(c=='\''||c=='"'){
                char quote=c;StringBuilder value=new StringBuilder();boolean closed=false;
                while(i<source.length()){
                    c=source.charAt(i++);if(c==quote){closed=true;break;}
                    if(c!='\\'){value.append(c);continue;}
                    if(i==source.length())throw new JSONException("字符串未结束");
                    char e=source.charAt(i++);switch(e){
                    case '\r':if(i<source.length()&&source.charAt(i)=='\n')i++;break;
                    case '\n':break;case 'n':value.append('\n');break;case 'r':value.append('\r');break;
                    case 't':value.append('\t');break;case 'b':value.append('\b');break;case 'f':value.append('\f');break;
                    case 'v':value.append((char)11);break;case '0':value.append((char)0);break;
                    case 'x':case 'u':int end=i+(e=='u'?4:2);
                        try{value.append((char)Integer.parseInt(source.substring(i,end),16));i=end;}
                        catch(IndexOutOfBoundsException|NumberFormatException bad){throw new JSONException("字符串转义无效");}break;
                    default:value.append(e);
                    }
                }
                if(!closed)throw new JSONException("字符串未结束");
                out.append(JSONObject.quote(value.toString()));last=out.length()-1;continue;
            }
            if((c=='}'||c==']')&&last>=0&&out.charAt(last)==',')out.setCharAt(last,' ');
            out.append(c);last=out.length()-1;
        }
        JSONTokener tok=new JSONTokener(out.toString());Object root=tok.nextValue();
        if(!(root instanceof JSONObject)||tok.nextClean()!=0)throw new JSONException("需要一份完整的订阅对象");
        return (JSONObject)root;
    }
}
