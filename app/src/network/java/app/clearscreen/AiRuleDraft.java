package app.clearscreen;

import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** User-initiated, text-only request. Credentials live only for this dialog. */
final class AiRuleDraft {
    static void open(MainActivity a, List<CustomSkipRule> rules) { open(a,rules,""); }
    static void open(MainActivity a, List<CustomSkipRule> rules,String initialPackage) {
        LinearLayout form = a.skipDialogContent();
        a.paragraph(form,"选一个应用，写下广告上能看见的按钮文字。只有包名和你填写的描述会发送给下方服务；API Key 仅用于本次请求。");
        EditText pkg = a.skipRuleField(form,"目标应用","应用包名",initialPackage);
        a.linkAction(form,"选择已安装应用",() -> a.chooseSkipRuleApp(pkg));
        EditText note = a.skipRuleField(form,"广告是什么样的？","例如：开屏右上角写着「跳过广告」","");
        android.content.SharedPreferences settings=a.getSharedPreferences("ai_rule_service",0);
        EditText api = a.skipRuleField(form,"服务地址 · Chat Completions","https://…/v1/chat/completions",settings.getString("endpoint",""));
        EditText model = a.skipRuleField(form,"模型名称","服务商提供的模型 ID",settings.getString("model",""));
        EditText key = a.skipRuleField(form,"API Key","仅本次使用","");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setSaveEnabled(false);
        TextView status=a.text("生成后可以检查、修改和试运行。",14,MainActivity.INK); form.addView(status);
        AlertDialog d=new AlertDialog.Builder(a).setTitle("用 AI 起草规则").setView(a.scrollDialogContent(form)).setNegativeButton("取消",null).setPositiveButton("生成草稿",null).create();
        d.show(); key.setOnFocusChangeListener((v,focused) -> { if(focused) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE); });
        d.getButton(-1).setOnClickListener(v -> {
            String app=pkg.getText().toString().trim(), description=note.getText().toString().trim(), endpoint=api.getText().toString().trim(), name=model.getText().toString().trim(), secret=key.getText().toString().trim();
            if (!app.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+") || CustomSkipRule.protectedPackage(app) || description.isEmpty() || name.isEmpty() || secret.isEmpty()) {status.setText("请选择可操作的应用，并填写描述、模型和密钥。");return;}
            URL url;
            try {url=new URL(endpoint); if (!url.getProtocol().equals("https") || url.getHost().isEmpty() || url.getUserInfo()!=null || url.getQuery()!=null || url.getRef()!=null) throw new Exception();}catch(Exception ex){status.setText("请填写完整的 HTTPS 接口地址。");return;}
            status.setText("正在向 "+url.getHost()+" 请求草稿…"); d.getButton(-1).setEnabled(false);
            new Thread(() -> {try {
                CustomSkipRule draft=request(url,secret,name,app,description);
                a.runOnUiThread(() -> {if(d.isShowing()&&!a.isDestroyed()){settings.edit().putString("endpoint",endpoint).putString("model",name).apply();d.dismiss();AdvancedRuleEditor.reviewDraft(a,rules,draft);}});
            }catch(Exception e){a.runOnUiThread(() -> {if(d.isShowing()){status.setText(e instanceof ServiceFailure ? e.getMessage() : "未获得有效规则。请核对接口、模型、描述及网络后重试。");d.getButton(-1).setEnabled(true);}});}},"rule-draft").start();
        });
    }
    private static final class ServiceFailure extends IOException { ServiceFailure(int code){super("服务返回 HTTP "+code+"。请检查密钥、模型权限或额度。");}}
    static CustomSkipRule parse(String pkg,String raw,String description) throws Exception {
        if(raw.length()>2048)throw new JSONException("草稿过长");
        JSONObject j=SubscriptionJson.read(raw);
        for(java.util.Iterator<String> keys=j.keys();keys.hasNext();)if(!keys.next().matches("mode|target|context"))
            throw new JSONException("草稿字段不受支持");
        if(!(j.opt("mode") instanceof String)||!(j.opt("target") instanceof String)
                ||j.has("context")&&!(j.opt("context") instanceof String))throw new JSONException("草稿字段类型无效");
        String mode=j.getString("mode"), target=j.getString("target"), context=j.optString("context","");
        if (!(mode.equals("splash")||mode.equals("popup")) || !literal(target,description) || (mode.equals("popup")&&!literal(context,description))) throw new JSONException("unsupported draft");
        return CustomSkipRule.advanced(pkg,"",mode,"="+target,mode.equals("popup")?"="+context:"",false,0,false,1,1,-1,null);
    }
    private static boolean literal(String s,String description){return !s.trim().isEmpty() && s.equals(s.trim()) && !s.matches("(?s).*[&=~+#\\r\\n].*") && description.contains(s);}
    static CustomSkipRule request(URL url,String key,String model,String pkg,String description) throws Exception {
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection();try {
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(35000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer "+key);
            JSONArray messages=new JSONArray().put(new JSONObject().put("role","system").put("content","只输出 JSON 对象，mode 为 splash 或 popup，target 为用户描述中逐字出现的广告跳过或关闭按钮文字。popup 必须另有 context 为逐字出现的广告提示。不能判断则返回 {}。不要生成代码、坐标或编造文字。")).put(new JSONObject().put("role","user").put("content",pkg+"\n"+description));
            byte[] data=new JSONObject().put("model",model).put("messages",messages).toString().getBytes(StandardCharsets.UTF_8);
            try(OutputStream out=c.getOutputStream()){out.write(data);} int code=c.getResponseCode();if(code!=200)throw new ServiceFailure(code);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(bytes.size()+n>131072)throw new IOException();bytes.write(buf,0,n);}String reply=SubscriptionJson.read(bytes.toString("UTF-8")).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim();return parse(pkg,reply.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", ""),description);}
        }finally{c.disconnect();}
    }
}
