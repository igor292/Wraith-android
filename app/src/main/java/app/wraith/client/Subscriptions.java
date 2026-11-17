package app.wraith.client;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

public final class Subscriptions {
    public static final int MAX_BYTES=8*1024*1024;
    /**
     * Bumped whenever the parser or the routing converter changes what a subscription yields.
     * Subscriptions stored by an older build are refetched once on the next launch, otherwise an
     * app update would leave the user with hosts and routing produced by the previous parser.
     */
    public static final int PARSE_VERSION=11;
    /** Remnawave returns per-host Xray JSON for this Happ Android signature. */
    public static final String USER_AGENT="Happ/4.4.1/Android";
    private static final class CompactResponse extends IOException{}
    private Subscriptions(){}
    public static byte[] bounded(InputStream input)throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException(L.t("Размер файла превышает 8 МБ.","The file is larger than 8 MB."));out.write(buf,0,n);}return out.toByteArray();
        }
    }
    public static JSONObject create(Context c,String url)throws Exception{
        URI uri=new URI(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IllegalArgumentException(L.t("Подписка должна быть обычной HTTPS-ссылкой без логина в адресе.","The subscription must be a plain HTTPS link without a login in the address."));
        JSONObject sub=Json.obj("id",UUID.randomUUID().toString(),"url",url,"name",uri.getHost(),"interval",24,"updated",0);
        update(c,sub);return sub;
    }
    public static void update(Context c,JSONObject original)throws Exception{
        JSONObject sub=Json.copy(original);
        try{ fetchAutomatic(c,sub,sub.getString("url")); }
        catch(Exception primary){
            // Documented Happ behaviour: an unavailable main URL falls back to the backup one.
            String fallback=sub.optString("fallback-url");
            if(fallback.isEmpty()||fallback.equals(sub.optString("url")))throw primary;
            AppLog.add(L.t("Основной адрес подписки недоступен, пробуем резервный: ","The main subscription address is unavailable, trying the fallback: ")+safeError(primary));
            fetchAutomatic(c,sub,fallback);
        }
    }
    private static void fetchAutomatic(Context c,JSONObject sub,String url)throws Exception{
        Store store=Store.get(c);String custom=store.settings().optString("userAgent").trim();
        if(!custom.isEmpty()){fetch(c,sub,url,custom,true);return;}
        String id=clientNumericId(store);
        try{fetch(c,sub,url,USER_AGENT+"/"+id,false);}
        catch(CompactResponse compact){
            // Some panels have not registered Wraith yet and only expose their complete per-host
            // Xray document to this established Android signature. Retry transparently.
            fetch(c,sub,url,compatibleUserAgent(id),true);
        }
    }
    static String clientNumericId(Store store)throws Exception{
        String id=store.settings().optString("clientNumericId");
        if(!id.matches("[0-9]{19,20}")){
            long value=new java.security.SecureRandom().nextLong();
            id=Long.toUnsignedString(value);
            // Keep the shape used by the Android client signature even for a short unsigned value.
            while(id.length()<19)id="0"+id;
            store.setting("clientNumericId",id);
        }
        return id;
    }
    static String compatibleUserAgent(String id){return "Happ/4.4.1/Android/"+id;}
    private static void fetch(Context c,JSONObject sub,String startUrl,String userAgent,boolean acceptCompact)throws Exception{
        Store store=Store.get(c);String url=startUrl;
        HttpURLConnection connection=null;
        try{
            for(int redirects=0;redirects<6;redirects++){
                URL u=new URL(url);if(!u.getProtocol().equals("https"))throw new IOException(L.t("Небезопасное перенаправление подписки на HTTP отклонено.","Insecure redirect of the subscription to HTTP was refused."));
                Proxy proxy=Proxy.NO_PROXY;
                if(WraithService.state==WraithService.CONNECTED&&store.settings().optBoolean("localProxy",false)&&!store.settings().optBoolean("allowLan"))proxy=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",store.settings().optInt("proxyPort",2081)));
                connection=(HttpURLConnection)u.openConnection(proxy);int timeout=Math.max(5,Math.min(15,store.settings().optInt("subTimeout",10)));connection.setConnectTimeout(timeout*1000);connection.setReadTimeout(timeout*2000);connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent",userAgent);connection.setRequestProperty("X-Wraith-Version",WraithApp.VERSION);
                if(store.settings().optBoolean("sendHwid",true)) {
                    String id=store.settings().optString("deviceId");if(id.isEmpty()){id=UUID.randomUUID().toString();store.setting("deviceId",id);}
                    connection.setRequestProperty("X-Hwid",id);connection.setRequestProperty("X-Device-Os","Android");connection.setRequestProperty("X-Ver-Os",android.os.Build.VERSION.RELEASE);connection.setRequestProperty("X-Device-Model",android.os.Build.MODEL);
                }
                int status=connection.getResponseCode();
                if(status>=300&&status<400){String target=connection.getHeaderField("Location");if(target==null)throw new IOException(L.t("Пустое перенаправление.","Empty redirect."));url=new URL(u,target).toString();connection.disconnect();connection=null;continue;}
                if(status!=200)throw new IOException(L.t("Сервер подписки вернул HTTP ","The subscription server returned HTTP ")+status+".");
                String body=new String(bounded(connection.getInputStream()),StandardCharsets.UTF_8);
                String encrypt=connection.getHeaderField("encrypt-tag");
                if(encrypt!=null){
                    String keyId=ImportParser.query(u.getQuery()).getOrDefault("key","");
                    if(!keyId.equals("key02"))throw new IOException(L.t("Неизвестный ключ шифрования. Нужна обычная подписка.","Unknown encryption key. A plain subscription is required."));
                    byte[] data=Base64.getDecoder().decode(body.trim()),tag=Base64.getDecoder().decode(encrypt);byte[] combined=Arrays.copyOf(data,data.length+tag.length);System.arraycopy(tag,0,combined,data.length,tag.length);
                    Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec("key02:+]%4ij#P\"/".getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,"kkkkkkkkkkkk".getBytes(StandardCharsets.UTF_8)));body=new String(aes.doFinal(combined),StandardCharsets.UTF_8);
                }
                if(!body.contains("://")&&!body.trim().startsWith("{")&&!body.trim().startsWith("["))try{body=ImportParser.base64(body);}catch(Exception ignored){}
                String response=body.trim();boolean compact=!response.startsWith("{")&&!response.startsWith("[")&&(response.contains("vless://")||response.contains("vmess://")||response.contains("trojan://")||response.contains("hy2://")||response.contains("hysteria2://"));
                if(compact&&!acceptCompact)throw new CompactResponse();
                // The routing profile is stored even when some nodes fail to parse: it carries the
                // provider's whole direct/proxy/block list and the DNS the generated config needs.
                for(String line:body.split("\\r?\\n")){line=line.trim();if(line.startsWith("happ://routing/")){sub.put("routing",line);break;}}
                Map<String,String> meta=new HashMap<>();for(String line:body.split("\\r?\\n"))if(line.startsWith("#")&&line.contains(":")){int colon=line.indexOf(':');meta.put(line.substring(1,colon).trim().toLowerCase(Locale.ROOT),line.substring(colon+1).trim());}
                List<String> skipped=new ArrayList<>();
                List<JSONObject> parsed=ImportParser.parse(body,skipped);
                // Remembered so the UI can say why a subscription carries no routing rules.
                String trimmed=body.trim();
                sub.put("format",trimmed.startsWith("[")||trimmed.startsWith("{")?"json":"links");
                if(skipped.isEmpty())sub.remove("skipped");
                else{sub.put("skipped",L.t("Пропущено серверов: ","Servers skipped: ")+skipped.size()+". "+skipped.get(0));AppLog.add(L.t("Подписка «","Subscription «")+sub.optString("name")+L.t("»: пропущено ","»: skipped ")+skipped.size()+L.t(" строк. "," lines. ")+skipped.get(0));}
                // Documented Happ subscription parameters, header or "#key: value" body line.
                // https://docs.happ.info/main/dev-docs/app-management
                for(String key:List.of("profile-title","subscription-userinfo","profile-update-interval","support-url","profile-web-page-url","announce","routing",
                        "routing-enable","fallback-url","new-url","new-domain","sub-info-text","sub-info-color","sub-info-button-text","sub-info-button-link","sub-expire","sub-expire-button-link")){
                    String value=connection.getHeaderField(key);if(value==null)value=meta.get(key);if(value==null)continue;
                    if(value.startsWith("base64:"))value=ImportParser.base64(value.substring(7));
                    switch(key){
                        case "profile-title":sub.put("name",value.length()>25?value.substring(0,25):value);break;
                        case "profile-update-interval":try{sub.put("interval",Math.max(1,Math.min(168,Integer.parseInt(value.trim()))));}catch(NumberFormatException ignored){}break;
                        case "routing-enable":sub.put("routingEnabled",enabled(value));break;
                        case "new-url":case "fallback-url":{String target=value.trim();if(target.startsWith("https://"))sub.put(key,target);break;}
                        case "new-domain":{String host=value.trim();if(host.matches("[A-Za-z0-9.-]+"))sub.put(key,host);break;}
                        case "sub-info-text":sub.put(key,value.length()>200?value.substring(0,200):value);break;
                        case "sub-expire":sub.put(key,enabled(value));break;
                        default:sub.put(key,value);
                    }
                }
                // The provider can move its own subscription to another address.
                String moved=sub.optString("new-url");
                if(!moved.isEmpty()&&!moved.equals(sub.optString("url"))){sub.put("url",moved);sub.remove("new-url");AppLog.add(L.t("Провайдер сменил адрес подписки «","The provider changed the address of subscription «")+sub.optString("name")+"».");}
                String newDomain=sub.optString("new-domain");
                if(!newDomain.isEmpty()){
                    URL current=new URL(sub.getString("url"));
                    if(!newDomain.equals(current.getHost())){sub.put("url",new URL(current.getProtocol(),newDomain,current.getPort(),current.getFile()).toString());AppLog.add(L.t("Провайдер сменил домен подписки «","The provider changed the domain of subscription «")+sub.optString("name")+"».");}
                    sub.remove("new-domain");
                }
                sub.put("updated",System.currentTimeMillis());sub.put("parsed",PARSE_VERSION);sub.remove("error");store.replaceSubscription(sub,parsed);UpdateJob.schedule(c);
                // A running tunnel keeps the rules it was started with. If this update changed the
                // routing of the connected server, restart so the new rules actually apply.
                JSONObject selected=store.selected();
                if(WraithService.state==WraithService.CONNECTED&&selected!=null&&WraithService.routingDiffers(c,selected)){
                    AppLog.add(L.t("Правила подписки «","Rules of subscription «")+sub.optString("name")+L.t("» изменились — перезапуск туннеля.","» changed — restarting the tunnel."));
                    WraithService.restart(c);
                }
                return;
            }
            throw new IOException(L.t("Слишком много перенаправлений подписки.","Too many subscription redirects."));
        }finally{if(connection!=null)connection.disconnect();}
    }
    public static void updateAll(Context c,boolean onlyDue){
        Store store=Store.get(c);for(JSONObject sub:store.subscriptions()){
            // The interval decides, except that a subscription parsed by an older build is always
            // due. A stray has("routing") check used to refetch every subscription without a
            // routing profile on every app launch.
            boolean stale=sub.optInt("parsed",0)<PARSE_VERSION;
            if(onlyDue&&!stale&&sub.optLong("updated")>0&&System.currentTimeMillis()-sub.optLong("updated")<Math.max(1,Store.get(c).settings().optInt("updateInterval",0)>0?Store.get(c).settings().optInt("updateInterval"):sub.optLong("interval",24))*3600000L)continue;
            try{update(c,sub);}catch(Exception e){try{sub.put("error",safeError(e));store.save();}catch(Exception ignored){}AppLog.add(L.t("Обновление подписки: ","Subscription update: ")+safeError(e));}
        }
    }
    /** Happ convention: "true" or "1" enables, any other non-empty value disables. */
    static boolean enabled(String value){String v=value==null?"":value.trim().toLowerCase(Locale.ROOT);return v.isEmpty()||v.equals("true")||v.equals("1");}
    public static String safeError(Throwable e){String msg=e.getMessage();if(msg==null)return e.getClass().getSimpleName();return msg.replaceAll("[a-zA-Z][a-zA-Z0-9+.-]*://[^\\s]+",L.t("[адрес скрыт]","[address hidden]")).replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}","[id]");}
}
