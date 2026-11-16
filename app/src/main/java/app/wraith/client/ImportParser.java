package app.wraith.client;

import org.json.*;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Parses public share formats locally; no third-party subscription converter. */
public final class ImportParser {
    public static final Set<String> TYPES=Set.of("vless","vmess","trojan","shadowsocks","socks","http","hysteria2","hysteria","tuic","anytls","ssh","wireguard");
    private ImportParser() {}
    public static String decode(String s) {
        try { return URLDecoder.decode(s.replace("+","%2B"), "UTF-8"); }
        catch (UnsupportedEncodingException impossible) { return s; }
        catch (IllegalArgumentException malformed) { return s; }
    }
    /**
     * Base64 for subscription bodies. Real providers send unpadded data, mix the URL-safe alphabet
     * and wrap lines, and the strict JDK decoders reject all of that, so normalise before decoding.
     */
    public static String base64(String s) {
        String v=s.replaceAll("\\s","").replace('-','+').replace('_','/');
        int cut=v.indexOf('=');
        if(cut>=0)v=v.substring(0,cut);
        if(v.isEmpty())throw new IllegalArgumentException("Пустые данные Base64.");
        StringBuilder padded=new StringBuilder(v);
        while(padded.length()%4!=0)padded.append('=');
        return new String(Base64.getMimeDecoder().decode(padded.toString()),StandardCharsets.UTF_8);
    }
    public static String unwrap(String input) {
        String s=input.trim();
        if(s.startsWith("happ://crypt")||s.startsWith("happ://limited")||s.startsWith("happ://hwid"))
            throw new IllegalArgumentException("Эта ссылка зашифрована сторонним клиентом. Нужна обычная ссылка подписки от провайдера.");
        for(String prefix:List.of("happ://add/","wraith://add/","wraith://import/"))
            if(s.startsWith(prefix)) { s=s.substring(prefix.length()); if(!s.startsWith("https://")&&!s.startsWith("http://")) s=decode(s); break; }
        if(s.startsWith("clash://")||s.startsWith("sing-box://import-remote-profile?")) {
            int question=s.indexOf('?');
            s=question<0?"":query(s.substring(question+1)).getOrDefault("url","");
        }
        return s;
    }
    public static Map<String,String> query(String raw) {
        Map<String,String> out=new LinkedHashMap<>();
        if(raw!=null) for(String p:raw.split("[&;]")) { if(p.isEmpty())continue;String[] kv=p.split("=",2);out.put(decode(kv[0]),kv.length==2?decode(kv[1]):""); }
        return out;
    }
    public static List<JSONObject> parse(String raw) throws Exception { return parse(raw,new ArrayList<>()); }
    /**
     * Parses one link, a subscription body, or a JSON configuration.
     *
     * Lines that cannot be represented in sing-box are collected in {@code skipped} instead of
     * cancelling the whole import: a single unsupported node must not cost the user the other
     * fifty. Nothing is lost silently — the caller reports every skipped line.
     */
    public static List<JSONObject> parse(String raw,List<String> skipped) throws Exception {
        String s=raw.trim().replace("\uFEFF","");
        if(s.isEmpty()) throw new IllegalArgumentException("Вставьте ссылку или конфигурацию.");
        List<JSONObject> results=new ArrayList<>();
        if(s.startsWith("{")) {
            JSONObject o=new JSONObject(s);
            // One provider JSON document is one visible profile. Its proxy candidates stay internal.
            if(XrayConfig.looksLike(o)) { JSONObject p=XrayConfig.profile(o,s);p.put("jsonSource",true);results.add(p);return results; }
            if(o.has("outbounds")) {
                // A complete sing-box config remains intact; it is validated by the core before start.
                JSONObject p=profile(str(o,"name","tag","remarks","JSON-конфигурация"),o,"",true);
                String description=describe(XrayConfig.str(o.optJSONObject("meta")==null?new JSONObject():o.getJSONObject("meta"),"serverDescription"));
                if(!description.isEmpty())p.put("description",description);
                p.put("jsonSource",true);
                results.add(p); return results;
            }
            if(TYPES.contains(o.optString("type"))) { JSONObject p=profile(str(o,"tag","name","Сервер"),o,"",false);p.put("jsonSource",true);results.add(p);return results; }
            throw new IllegalArgumentException("Ожидается конфигурация sing-box или Xray с исходящими подключениями.");
        }
        if(s.startsWith("[")) {
            JSONArray a=new JSONArray(s);
            for(JSONObject o:Json.objects(a)) {
                try {
                    if(XrayConfig.looksLike(o)) { JSONObject p=XrayConfig.profile(o,o.toString());p.put("jsonSource",true);results.add(p);continue; }
                    boolean full=o.has("outbounds");
                    if(!full&&!TYPES.contains(o.optString("type")))throw new IllegalArgumentException("Неизвестный протокол: "+o.optString("type","(нет)"));
                    JSONObject p=profile(str(o,"tag","name","remarks","Сервер"),o,"",full);p.put("jsonSource",true);results.add(p);
                } catch(Exception e) { skipped.add(Subscriptions.safeError(e)); }
            }
        } else {
            if(!s.contains("://")) {
                try { s=base64(s); } catch(Exception e) { throw new IllegalArgumentException("Не удалось распознать ссылку или Base64-подписку."); }
                if(s.trim().startsWith("{")||s.trim().startsWith("[")) return parse(s,skipped);
            }
            String routingLink="";
            for(String line:s.split("\\r?\\n")) {
                line=line.trim(); if(line.isEmpty()||line.startsWith("#")) continue;
                // Happ ships the routing profile inside the payload, next to the hosts — through the
                // clipboard, a deeplink, a QR code or the subscription body. Dropping this line was
                // exactly why imported hosts arrived without the provider's rules.
                if(line.startsWith("happ://routing/")) { if(routingLink.isEmpty())routingLink=line; continue; }
                if(!line.contains("://")) continue;
                try { results.add(parseLink(line)); } catch(Exception e) { skipped.add(Subscriptions.safeError(e)); }
            }
            attachRouting(results,routingLink);
        }
        if(results.isEmpty()) {
            if(!skipped.isEmpty())throw new IllegalArgumentException("Ни одна строка не поддерживается ("+skipped.size()+"). "+skipped.get(0));
            throw new IllegalArgumentException("В подписке не найдено серверов поддерживаемого формата.");
        }
        return results;
    }
    /** Binds a happ://routing/ profile found in the payload to every host from that payload. */
    static void attachRouting(List<JSONObject> results,String link) {
        if(link==null||link.isEmpty()||results.isEmpty())return;
        if(link.equals("happ://routing/off")||link.startsWith("happ://routing/off")){
            for(JSONObject p:results)p.remove("routing");
            return;
        }
        try {
            JSONObject routing=RoutingConfig.decode(link);
            for(JSONObject p:results)if(!p.optBoolean("full"))p.put("routing",routing);
        } catch(Exception e) { AppLog.add("Профиль маршрутизации из импорта не разобран: "+Subscriptions.safeError(e)); }
    }
    static JSONObject profile(String name,JSONObject outbound,String raw,boolean full) throws JSONException {
        return Json.obj("id",UUID.randomUUID().toString(),"name",name.isEmpty()?"Сервер":name,"outbound",outbound,"source",raw,"full",full,"group","","latency",-1,"favorite",false);
    }
    /**
     * Provider caption shown under the server name instead of the protocol.
     * Happ passes it as base64 (plain text is accepted too), max 30 characters.
     */
    static String describe(String value) {
        if(value==null||value.trim().isEmpty())return "";
        String text=value.trim();
        // Plain text is also valid here, and plain ASCII can decode as base64 into noise,
        // so the decoded form is only used when it comes out as readable text.
        if(text.matches("[A-Za-z0-9+/=_-]{4,}"))
            try { String decoded=base64(text); if(readable(decoded))text=decoded; } catch(Exception plain) { /* already plain text */ }
        text=text.replaceAll("[\\r\\n\\t]"," ").trim();
        return text.length()>30?text.substring(0,30):text;
    }
    /** True when a decoded string is text a person could have written, not binary noise. */
    private static boolean readable(String s) {
        if(s==null||s.trim().isEmpty())return false;
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='�')return false;
            if(c<0x20&&c!='\n'&&c!='\r'&&c!='\t')return false;
        }
        return true;
    }

    /**
     * Share link split without java.net.URI. Provider links routinely carry raw spaces, emoji flags
     * and unescaped characters in the fragment, all of which make java.net.URI throw and silently
     * cost the user the node — usually every node, since names come from the same generator.
     */
    static final class Link {
        String scheme="",userInfo="",host="",path="",fragment="",description="",ports="";
        int port=-1;
        Map<String,String> query=new LinkedHashMap<>();
    }
    static Link split(String text) {
        Link l=new Link();
        int mark=text.indexOf("://");
        if(mark<0)throw new IllegalArgumentException("Это не ссылка подключения.");
        l.scheme=text.substring(0,mark).toLowerCase(Locale.ROOT);
        String rest=text.substring(mark+3);
        int hash=rest.indexOf('#');
        if(hash>=0){
            String fragment=rest.substring(hash+1);
            // Happ metadata after the title: "#title?serverDescription=<base64>".
            int params=fragment.indexOf('?');
            if(params>=0&&fragment.indexOf("serverDescription=",params)>0){
                l.description=describe(query(fragment.substring(params+1)).get("serverDescription"));
                fragment=fragment.substring(0,params);
            }
            l.fragment=decode(fragment);
            rest=rest.substring(0,hash);
        }
        int question=rest.indexOf('?');
        if(question>=0){l.query=query(rest.substring(question+1));rest=rest.substring(0,question);}
        int slash=rest.indexOf('/');
        if(slash>=0){l.path=rest.substring(slash);rest=rest.substring(0,slash);}
        int at=rest.lastIndexOf('@');
        if(at>=0){l.userInfo=rest.substring(0,at);rest=rest.substring(at+1);}
        if(rest.startsWith("[")){
            int close=rest.indexOf(']');
            if(close<0)throw new IllegalArgumentException("Незакрытый IPv6-адрес в ссылке.");
            l.host=rest.substring(1,close);
            String tail=rest.substring(close+1);
            if(tail.startsWith(":"))l.port=number(tail.substring(1));
        } else {
            int colon=rest.lastIndexOf(':');
            if(colon>=0){
                l.host=rest.substring(0,colon);
                String tail=rest.substring(colon+1).trim();
                // Hysteria port hopping writes a range list in the port position: host:5000-6000,7044
                if(tail.matches("[0-9]+([,-][0-9]+)+")){l.ports=tail;l.port=number(tail.split("[,-]")[0]);}
                else l.port=number(tail);
            }
            else l.host=rest;
        }
        if(l.host.trim().isEmpty())throw new IllegalArgumentException("В ссылке отсутствует адрес сервера.");
        l.host=l.host.trim();
        return l;
    }
    private static int number(String value){
        try{ return Integer.parseInt(value.trim()); }
        catch(NumberFormatException e){ throw new IllegalArgumentException("Некорректный порт в ссылке: "+value); }
    }

    /** Happ also passes socks/http credentials base64-encoded instead of as user:pass. */
    private static String credentials(String auth) {
        if(auth.isEmpty()||auth.contains(":"))return auth;
        if(!auth.matches("[A-Za-z0-9+/=_-]{4,}"))return auth;
        try { String decoded=base64(auth); return decoded.contains(":")&&readable(decoded)?decoded:auth; }
        catch(Exception plain) { return auth; }
    }
    static JSONObject parseLink(String text) throws Exception {
        String scheme=text.substring(0,text.indexOf("://")).toLowerCase(Locale.ROOT);
        if(scheme.equals("vmess")) return vmess(text);
        if(scheme.equals("ss")) return shadowsocks(text);
        // socks://<base64 of user:pass@host:port> — the whole address encoded in one blob.
        if(Set.of("socks","socks5","socks4","http").contains(scheme)) {
            String body=text.substring(text.indexOf("://")+3).split("[#?]")[0];
            if(!body.contains("@")&&!body.contains(":")&&body.matches("[A-Za-z0-9+/=_-]{8,}"))
                try { String decoded=base64(body); if(readable(decoded)&&decoded.contains(":"))text=scheme+"://"+decoded.trim()+text.substring(text.indexOf("://")+3+body.length()); }
                catch(Exception plain) { /* not a base64 payload */ }
        }
        Link l=split(text);
        scheme=l.scheme;
        if(scheme.equals("hy2")) scheme="hysteria2";
        if(scheme.equals("socks5")||scheme.equals("socks4")) scheme="socks";
        if(!TYPES.contains(scheme)) throw new IllegalArgumentException("Неизвестный протокол: "+scheme);
        Map<String,String> q=l.query;
        if(l.port<1) l.port=defaultPort(scheme,q);
        if(l.port<1||l.port>65535) throw new IllegalArgumentException("В ссылке отсутствует или некорректен порт сервера.");
        String auth=decode(l.userInfo);
        JSONObject o=Json.obj("type",scheme,"server",l.host,"server_port",l.port);
        switch(scheme) {
            case "vless": {
                if(auth.isEmpty())throw new IllegalArgumentException("В ссылке VLESS нет UUID.");
                o.put("uuid",auth);
                String encryption=first(q,"none","encryption");
                if(!encryption.equals("none")) throw new IllegalArgumentException("VLESS encryption из Xray не поддерживается этой сборкой.");
                String flow=first(q,"","flow");
                if(!flow.isEmpty()){
                    if(!flow.equals("xtls-rprx-vision")) throw new IllegalArgumentException("Поток VLESS "+flow+" не поддерживается sing-box.");
                    o.put("flow",flow).put("packet_encoding","xudp");
                }
                break;
            }
            case "trojan": case "anytls": case "hysteria2": o.put("password",auth); break;
            case "socks": case "http": { String credentials=credentials(auth);String[] pair=credentials.split(":",2);if(!credentials.isEmpty()){o.put("username",pair[0]);o.put("password",pair.length>1?pair[1]:"");} if(scheme.equals("socks")) o.put("version",text.startsWith("socks4://")?"4":"5"); break; }
            case "tuic": { String[] pair=auth.split(":",2);if(pair.length!=2) throw new IllegalArgumentException("TUIC требует UUID и пароль.");o.put("uuid",pair[0]);o.put("password",pair[1]);o.put("congestion_control",first(q,"bbr","congestion_control"));break; }
            default: throw new IllegalArgumentException("Для "+scheme+" используйте JSON sing-box.");
        }
        boolean secure=Set.of("trojan","anytls","hysteria2","tuic").contains(scheme)||Set.of("tls","reality","xtls","utls").contains(first(q,"","security"));
        if(secure) o.put("tls",tls(q,l.host));
        if(q.containsKey("type")) transport(o,q.get("type"),q);
        if(scheme.equals("hysteria2")&&q.containsKey("obfs")&&!q.get("obfs").isEmpty()) o.put("obfs",Json.obj("type",q.get("obfs"),"password",first(q,"","obfs-password","obfs_password")));
        if(Set.of("hysteria2","hysteria").contains(scheme)) hop(o,first(q,l.ports,"mport","ports"),first(q,"","mportHopInt","hopInterval","hop_interval"));
        JSONObject p=profile(l.fragment.isEmpty()?l.host:l.fragment,o,text,false);
        if(!l.description.isEmpty())p.put("description",l.description);
        return p;
    }
    /**
     * Hysteria port hopping. Happ writes the range list in the port position or in mport
     * ("1234,5000-6000,7044") and the switch interval in mportHopInt seconds; sing-box wants
     * server_ports as "start:end" entries plus hop_interval.
     */
    static void hop(JSONObject o,String list,String interval) throws JSONException {
        if(list==null||list.trim().isEmpty())return;
        JSONArray ranges=new JSONArray();
        for(String item:list.split(",")){
            String v=item.trim();
            if(v.isEmpty())continue;
            if(v.contains("-")){String[] pair=v.split("-",2);if(port(pair[0])>0&&port(pair[1])>0)ranges.put(port(pair[0])+":"+port(pair[1]));}
            else if(port(v)>0)ranges.put(port(v)+":"+port(v));
        }
        if(ranges.length()==0)return;
        o.put("server_ports",ranges);
        int seconds=port(interval);
        o.put("hop_interval",(seconds>0?seconds:30)+"s");
    }
    private static int port(String value){try{int n=Integer.parseInt(value.trim());return n>0&&n<65536?n:-1;}catch(Exception e){return -1;}}
    private static int defaultPort(String scheme,Map<String,String> q){
        if(Set.of("trojan","anytls","hysteria2","hysteria","tuic").contains(scheme))return 443;
        if(Set.of("tls","reality").contains(q.getOrDefault("security","")))return 443;
        return -1;
    }
    static JSONObject tls(Map<String,String> q,String host) throws JSONException {
        boolean reality=Set.of("reality").contains(q.getOrDefault("security",""))||!first(q,"","pbk","publicKey","public_key").isEmpty();
        JSONObject t=Json.obj("enabled",true,"server_name",first(q,host,"sni","serverName","server_name","peer","host"));
        if(q.getOrDefault("allowInsecure",q.getOrDefault("insecure","0")).matches("1|true")) t.put("insecure",true);
        String fingerprint=first(q,"","fp","fingerprint");
        if(reality){
            // Xray sends no ALPN extension for Reality; adding one breaks the handshake, so it is dropped.
            t.put("reality",Json.obj("enabled",true,"public_key",first(q,"","pbk","publicKey","public_key"),"short_id",first(q,"","sid","shortId","short_id")));
            t.put("utls",Json.obj("enabled",true,"fingerprint",XrayConfig.fingerprint(fingerprint)));
            return t;
        }
        if(q.containsKey("alpn")&&!q.get("alpn").isEmpty()) t.put("alpn",new JSONArray(Arrays.asList(q.get("alpn").split(","))));
        if(!fingerprint.isEmpty())t.put("utls",Json.obj("enabled",true,"fingerprint",XrayConfig.fingerprint(fingerprint)));
        return t;
    }
    private static String first(Map<String,String> q,String fallback,String... keys){for(String key:keys){String value=q.get(key);if(value!=null&&!value.trim().isEmpty())return value.trim();}return fallback;}
    private static String str(JSONObject o,String... keys){
        String fallback=keys[keys.length-1];
        for(int i=0;i<keys.length-1;i++){String value=XrayConfig.str(o,keys[i]);if(!value.isEmpty())return value;}
        return fallback;
    }

    /**
     * Normalises a stored Reality outbound before it reaches the core: strips Xray-only fields,
     * repairs a fingerprint the core would reject, and recovers SNI / public key / short id from
     * the original share link when an older build stored them incompletely.
     */
    static void repairReality(JSONObject outbound,String source)throws Exception{
        JSONObject tls=outbound.optJSONObject("tls");if(tls==null)return;
        JSONObject reality=tls.optJSONObject("reality");
        if(reality==null||!reality.optBoolean("enabled")){
            JSONObject utls=tls.optJSONObject("utls");
            if(utls!=null&&utls.optBoolean("enabled"))utls.put("fingerprint",XrayConfig.fingerprint(utls.optString("fingerprint")));
            return;
        }
        // Keep only fields understood by sing-box. Xray-only share parameters
        // such as spx/pqv must not leak into the sing-box Reality object.
        for(String key:List.of("spider_x","spiderX","spx","mldsa65_verify","mldsa65Verify","pqv","fingerprint","server_name","alpn"))reality.remove(key);
        // Reality always runs over uTLS, and an ALPN extension is never sent by Xray for Reality.
        tls.remove("alpn");
        tls.remove("insecure");
        JSONObject utls=tls.optJSONObject("utls");
        tls.put("utls",Json.obj("enabled",true,"fingerprint",XrayConfig.fingerprint(utls==null?"":utls.optString("fingerprint"))));
        if(!source.startsWith("vless://"))return;
        Map<String,String> q=split(source).query;
        if(reality.optString("public_key").isEmpty())reality.put("public_key",first(q,"","pbk","publicKey","public_key"));
        if(reality.optString("short_id").isEmpty())reality.put("short_id",first(q,"","sid","shortId","short_id"));
        if(tls.optString("server_name").isEmpty()||tls.optString("server_name").equals(outbound.optString("server")))tls.put("server_name",first(q,outbound.optString("server"),"sni","serverName","server_name","peer"));
    }
    static void transport(JSONObject o,String type,Map<String,String> q) throws JSONException {
        type=type==null?"":type.trim().toLowerCase(Locale.ROOT);
        if(type.isEmpty()||type.equals("tcp")||type.equals("raw")||type.equals("none")) {
            if(!q.getOrDefault("headerType","none").equals("none")) throw new IllegalArgumentException("TCP HTTP-заголовки Xray не поддерживаются sing-box.");
            return;
        }
        JSONObject t=Json.obj("type",type);
        switch(type) {
            case "ws": {
                String path=q.getOrDefault("path","/");
                int early=q.containsKey("ed")?number(q.get("ed")):0;
                int question=path.indexOf("?ed=");
                if(question>=0){try{early=Integer.parseInt(path.substring(question+4).split("[&#]")[0]);}catch(NumberFormatException ignored){}path=path.substring(0,question);}
                t.put("path",path.isEmpty()?"/":path);
                if(q.containsKey("host")&&!q.get("host").isEmpty()) t.put("headers",Json.obj("Host",q.get("host")));
                if(early>0){t.put("max_early_data",early);t.put("early_data_header_name",q.getOrDefault("eh","Sec-WebSocket-Protocol"));}
                break;
            }
            case "grpc": t.put("service_name",q.getOrDefault("serviceName",q.getOrDefault("path","")));break;
            case "http": case "h2": t.put("type","http");t.put("path",q.getOrDefault("path","/"));if(q.containsKey("host")&&!q.get("host").isEmpty())t.put("host",Json.arr(q.get("host")));break;
            case "httpupgrade":t.put("path",q.getOrDefault("path","/"));if(q.containsKey("host")&&!q.get("host").isEmpty())t.put("host",q.get("host"));break;
            default:throw new IllegalArgumentException("Транспорт "+type+" не поддерживается. Запросите у провайдера TCP, WS, gRPC или HTTPUpgrade.");
        }
        o.put("transport",t);
    }
    static JSONObject vmess(String s) throws Exception {
        String payload=s.substring(8);
        int hash=payload.indexOf('#');
        String label=hash>=0?decode(payload.substring(hash+1)):"";
        if(hash>=0)payload=payload.substring(0,hash);
        JSONObject v;
        try{ v=new JSONObject(base64(payload)); }
        catch(Exception e){ return parseVmessUri(s); }
        String address=XrayConfig.str(v,"add","address","host");
        if(address.isEmpty())throw new IllegalArgumentException("В ссылке VMess нет адреса сервера.");
        String id=XrayConfig.str(v,"id","uuid");
        if(id.isEmpty())throw new IllegalArgumentException("В ссылке VMess нет UUID.");
        int port;
        try{ port=Integer.parseInt(XrayConfig.str(v,"port")); }
        catch(NumberFormatException e){ throw new IllegalArgumentException("Некорректный порт в ссылке VMess."); }
        String security=XrayConfig.str(v,"scy","security");
        JSONObject o=Json.obj("type","vmess","server",address.replace("[","").replace("]",""),"server_port",port,"uuid",id,"security",security.isEmpty()?"auto":security,"alter_id",v.optInt("aid",v.optInt("alterId",0)));
        Map<String,String> q=new LinkedHashMap<>();
        for(String k:List.of("path","host","sni","fp","alpn","serviceName")){String value=XrayConfig.str(v,k);if(!value.isEmpty())q.put(k,value);}
        String tlsMode=XrayConfig.str(v,"tls");
        if(tlsMode.equals("tls")||tlsMode.equals("reality")){q.put("security",tlsMode);o.put("tls",tls(q,address));}
        transport(o,XrayConfig.str(v,"net"),q);
        String name=!label.isEmpty()?label:XrayConfig.str(v,"ps","remarks","name");
        JSONObject p=profile(name.isEmpty()?address:name,o,s,false);
        String description=describe(XrayConfig.str(v,"serverDescription"));
        if(!description.isEmpty())p.put("description",description);
        return p;
    }
    /** Some panels emit vmess:// in plain URI form instead of Base64 JSON. */
    private static JSONObject parseVmessUri(String s) throws Exception {
        Link l=split(s);
        if(l.port<1)throw new IllegalArgumentException("Не удалось разобрать ссылку VMess.");
        String auth=decode(l.userInfo);
        String[] pair=auth.split(":",2);
        JSONObject o=Json.obj("type","vmess","server",l.host,"server_port",l.port,"uuid",pair.length>1?pair[1]:auth,"security",first(l.query,"auto","encryption"),"alter_id",0);
        if(Set.of("tls","reality").contains(first(l.query,"","security")))o.put("tls",tls(l.query,l.host));
        if(l.query.containsKey("type"))transport(o,l.query.get("type"),l.query);
        return profile(l.fragment.isEmpty()?l.host:l.fragment,o,s,false);
    }
    static JSONObject shadowsocks(String s) throws Exception {
        String data=s.substring(5);String name="Shadowsocks",description="";
        int hash=data.indexOf('#');
        if(hash>=0){
            String fragment=data.substring(hash+1);
            int params=fragment.indexOf('?');
            if(params>=0&&fragment.indexOf("serverDescription=",params)>0){description=describe(query(fragment.substring(params+1)).get("serverDescription"));fragment=fragment.substring(0,params);}
            name=decode(fragment);data=data.substring(0,hash);
        }
        int question=data.indexOf('?');if(question>=0){Map<String,String> q=query(data.substring(question+1));if(q.containsKey("plugin")&&!q.get("plugin").isEmpty())throw new IllegalArgumentException("Shadowsocks plugin: используйте полный JSON sing-box.");data=data.substring(0,question);}
        if(!data.contains("@")) data=base64(data);
        int at=data.lastIndexOf('@');if(at<0)throw new IllegalArgumentException("Некорректная Shadowsocks-ссылка.");
        String user=data.substring(0,at);if(!user.contains(":")) user=base64(user);else user=decode(user);
        String[] auth=user.split(":",2);if(auth.length!=2)throw new IllegalArgumentException("Нет пароля Shadowsocks.");
        Link l=split("ss://"+data.substring(at+1));
        if(l.port<1)throw new IllegalArgumentException("Некорректный адрес Shadowsocks.");
        JSONObject p=profile(name,Json.obj("type","shadowsocks","server",l.host,"server_port",l.port,"method",auth[0],"password",auth[1]),s,false);
        if(!description.isEmpty())p.put("description",description);
        return p;
    }
}
