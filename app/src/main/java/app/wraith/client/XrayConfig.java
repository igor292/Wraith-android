package app.wraith.client;

import org.json.*;
import java.util.*;

/**
 * Converts Xray / V2Ray JSON into the sing-box equivalents used by the rest of the app.
 *
 * Happ hands out Xray configurations (see reference/happ-germany-xray.json), so an import that
 * only understands sing-box JSON rejects most of what a provider actually sends. A complete Xray
 * config becomes one Wraith profile. Provider candidates stay inside that profile as a sing-box
 * ordered host candidates, matching the single subscription entry shown by other clients. Wraith
 * uses the first host unless the user explicitly enables automatic host selection.
 */
public final class XrayConfig {
    private XrayConfig(){}
    /** Fingerprints accepted by sing-box uTLS. Anything else aborts core start, so it is mapped to chrome. */
    public static final Set<String> FINGERPRINTS=Set.of("chrome","firefox","edge","safari","360","qq","ios","android","random","randomized");
    private static final Set<String> LOCAL_OUTBOUNDS=Set.of("freedom","blackhole","dns","loopback");

    /** True when the document is an Xray/V2Ray config rather than a sing-box one. */
    public static boolean looksLike(JSONObject root){
        if(root.has("protocol")&&!root.has("type"))return true;
        for(JSONObject o:Json.objects(root.optJSONArray("outbounds"))){
            if(o.has("type"))return false;
            if(o.has("protocol")||o.has("streamSettings")||o.has("settings"))return true;
        }
        for(JSONObject o:Json.objects(root.optJSONArray("inbounds")))if(o.has("protocol"))return true;
        if(root.optJSONObject("routing")!=null||root.optJSONObject("policy")!=null||root.has("remarks"))return true;
        JSONObject log=root.optJSONObject("log");
        return log!=null&&log.has("loglevel");
    }

    /** Full Xray config → one profile, with provider candidates kept in an internal pool. */
    public static JSONObject profile(JSONObject root,String raw)throws Exception{
        List<JSONObject> outbounds=Json.objects(root.optJSONArray("outbounds"));
        if(outbounds.isEmpty()){
            if(root.has("protocol"))return ImportParser.profile(root.optString("tag","Xray"),outbound(root),raw,false);
            throw new IllegalArgumentException("В конфигурации Xray нет исходящих подключений.");
        }
        List<JSONObject> proxies=new ArrayList<>();
        for(JSONObject o:outbounds){
            if(LOCAL_OUTBOUNDS.contains(o.optString("protocol").toLowerCase(Locale.ROOT)))continue;
            proxies.add(o);
        }
        if(proxies.isEmpty())throw new IllegalArgumentException("В конфигурации Xray нет прокси-исходящего, только direct/block.");
        String baseName=str(root,"remarks","name");
        if(baseName.isEmpty())baseName="Xray";
        JSONObject routing=routing(root,baseName);
        JSONObject meta=root.optJSONObject("meta");
        String description=ImportParser.describe(meta==null?"":str(meta,"serverDescription"));
        List<JSONObject> converted=new ArrayList<>();List<String> tags=new ArrayList<>(),errors=new ArrayList<>();boolean nativeOnly=false;
        for(JSONObject proxy:proxies)for(JSONObject variant:variants(proxy)){
            try{
                JSONObject node=outbound(variant);
                String tag="xray-candidate-"+String.format(Locale.ROOT,"%02d",converted.size()+1);
                node.put("tag",tag);converted.add(node);tags.add(tag);
            }catch(Exception e){
                // The UI only needs the endpoint summary. Keep raw Xray-only TLS options (for
                // example pinnedPeerCertSha256), XHTTP, and TCP camouflage in source JSON and let
                // the native Xray runtime execute them instead of rejecting an otherwise valid node.
                JSONObject descriptor=descriptorOutbound(variant);
                if(descriptor==null){errors.add(Subscriptions.safeError(e));continue;}
                nativeOnly=true;
                String tag="xray-candidate-"+String.format(Locale.ROOT,"%02d",converted.size()+1);
                descriptor.put("tag",tag);converted.add(descriptor);tags.add(tag);
            }
        }
        if(converted.isEmpty())throw new IllegalArgumentException(errors.isEmpty()?"Нет поддерживаемых серверов в конфигурации Xray.":errors.get(0));
        String name=baseName;
        JSONObject profile=ImportParser.profile(name,converted.get(0),raw,false);
        // Keep individual host data internal: it feeds one automatic outbound and one UI row.
        if(converted.size()>1){
            JSONArray candidates=new JSONArray();for(JSONObject node:converted)candidates.put(node);
            profile.put("xrayCandidates",candidates);
        }
        if(nativeOnly)profile.put("xrayNativeOnly",true);
        if(routing.length()>1)profile.put("routing",routing);
        if(!description.isEmpty())profile.put("description",description);
        if(!errors.isEmpty())profile.put("xraySkipped",errors.size());
        return profile;
    }

    /** Minimal endpoint metadata for an Xray-native feature that the sing-box preview cannot map. */
    private static JSONObject descriptorOutbound(JSONObject x)throws Exception{
        String protocol=x.optString("protocol").toLowerCase(Locale.ROOT);
        if(!Set.of("vless","vmess","trojan","shadowsocks","socks","http").contains(protocol))return null;
        JSONObject settings=x.optJSONObject("settings");if(settings==null)return null;
        JSONObject peer=Set.of("vless","vmess").contains(protocol)?firstOf(settings,"vnext"):firstOf(settings,"servers");
        if(peer==null&&settings.has("address"))peer=settings;
        if(peer==null)return null;
        JSONObject node=Json.obj("type",protocol,"server",host(peer),"server_port",port(peer));
        if(protocol.equals("vless")||protocol.equals("vmess")){
            JSONObject user=firstOf(peer,"users");if(user==null)return null;
            node.put("uuid",str(user,"id","uuid","password"));
        }else if(protocol.equals("trojan")||protocol.equals("shadowsocks")){
            node.put("password",str(peer,"password"));
            if(protocol.equals("shadowsocks"))node.put("method",str(peer,"method"));
        }else{
            JSONObject user=firstOf(peer,"users");if(user!=null){String username=str(user,"user","username");if(!username.isEmpty())node.put("username",username).put("password",str(user,"pass","password"));}
        }
        // Keep transport/TLS details in the lightweight preview outbound. This is used for the
        // displayed host type and latency probe only; actual connections use the untouched source
        // JSON in native Xray, where certificate pins and native transports remain enforced.
        JSONObject stream=x.optJSONObject("streamSettings");
        try{streamPreview(node,stream);}catch(Exception unsupportedPreview){
            // Keep native-only transports importable even if the latency-preview core cannot model them.
            String network=str(stream,"network","type").toLowerCase(Locale.ROOT);
            if(!network.isEmpty()&&!Set.of("tcp","raw").contains(network))node.put("transport",Json.obj("type",network));
        }
        return node;
    }

    /** Parser collection API remains a singleton for a full config, even if it contains a pool. */
    public static List<JSONObject> profiles(JSONObject root,String raw,List<String> skipped)throws Exception{
        try{return List.of(profile(root,raw));}
        catch(Exception e){skipped.add(Subscriptions.safeError(e));return List.of();}
    }

    /** Xray can put several hosts in one protocol outbound (vnext / servers). Treat them as
     * ordered candidates too, so Wraith's single visible profile still maps to one precise host. */
    private static List<JSONObject> variants(JSONObject outbound)throws Exception{
        String protocol=outbound.optString("protocol").toLowerCase(Locale.ROOT);
        String key=Set.of("vless","vmess").contains(protocol)?"vnext":Set.of("trojan","shadowsocks","socks","http").contains(protocol)?"servers":"";
        JSONObject settings=outbound.optJSONObject("settings");JSONArray peers=settings==null||key.isEmpty()?null:settings.optJSONArray(key);
        if(peers==null||peers.length()<2)return List.of(outbound);
        List<JSONObject> result=new ArrayList<>();
        for(int i=0;i<peers.length();i++){
            JSONObject variant=Json.copy(outbound),variantSettings=variant.getJSONObject("settings");
            variantSettings.put(key,Json.arr(Json.copy(peers.getJSONObject(i))));result.add(variant);
        }
        return result;
    }

    /** Single Xray outbound → sing-box outbound. */
    public static JSONObject outbound(JSONObject x)throws Exception{
        String protocol=x.optString("protocol").toLowerCase(Locale.ROOT);
        JSONObject s=x.optJSONObject("settings")==null?new JSONObject():x.getJSONObject("settings");
        JSONObject o=new JSONObject();
        switch(protocol){
            case "vless": case "vmess": {
                JSONObject peer=firstOf(s,"vnext","servers");
                if(peer==null)throw new IllegalArgumentException("В Xray-исходящем "+protocol+" нет блока vnext.");
                JSONObject user=firstOf(peer,"users");
                if(user==null)throw new IllegalArgumentException("В Xray-исходящем "+protocol+" нет пользователя.");
                o.put("type",protocol).put("server",host(peer)).put("server_port",port(peer)).put("uuid",str(user,"id","uuid","password"));
                if(protocol.equals("vless")){
                    String encryption=str(user,"encryption");
                    if(!encryption.isEmpty()&&!encryption.equals("none"))throw new IllegalArgumentException("VLESS encryption из Xray не поддерживается sing-box.");
                    String flow=str(user,"flow");
                    if(!flow.isEmpty()){
                        if(!flow.equals("xtls-rprx-vision"))throw new IllegalArgumentException("Поток VLESS "+flow+" не поддерживается sing-box.");
                        o.put("flow",flow).put("packet_encoding","xudp");
                    }
                }else{
                    String security=str(user,"security");
                    o.put("security",security.isEmpty()?"auto":security);
                    o.put("alter_id",user.optInt("alterId",user.optInt("alter_id",0)));
                }
                break;
            }
            case "trojan": {
                JSONObject peer=firstOf(s,"servers");
                if(peer==null)throw new IllegalArgumentException("В Xray-исходящем trojan нет серверов.");
                o.put("type","trojan").put("server",host(peer)).put("server_port",port(peer)).put("password",str(peer,"password"));
                break;
            }
            case "shadowsocks": {
                JSONObject peer=firstOf(s,"servers");
                if(peer==null)throw new IllegalArgumentException("В Xray-исходящем shadowsocks нет серверов.");
                o.put("type","shadowsocks").put("server",host(peer)).put("server_port",port(peer)).put("method",str(peer,"method")).put("password",str(peer,"password"));
                break;
            }
            case "socks": case "http": {
                JSONObject peer=firstOf(s,"servers");
                if(peer==null)throw new IllegalArgumentException("В Xray-исходящем "+protocol+" нет серверов.");
                o.put("type",protocol).put("server",host(peer)).put("server_port",port(peer));
                JSONObject user=firstOf(peer,"users");
                if(user!=null){
                    String login=str(user,"user","username");
                    if(!login.isEmpty())o.put("username",login).put("password",str(user,"pass","password"));
                }
                if(protocol.equals("socks")){String version=str(peer,"version");o.put("version",version.isEmpty()?"5":version);}
                break;
            }
            case "hysteria": case "hysteria2": return hysteria(s,x.optJSONObject("streamSettings"));
            case "freedom": return Json.obj("type","direct");
            case "blackhole": return Json.obj("type","block");
            case "": throw new IllegalArgumentException("В исходящем Xray не указан protocol.");
            default: throw new IllegalArgumentException("Протокол Xray "+protocol+" не поддерживается sing-box.");
        }
        stream(o,x.optJSONObject("streamSettings"));
        return o;
    }

    /**
     * Xray's Hysteria outbound (settings.address/port/version + streamSettings.hysteriaSettings,
     * network "hysteria") → sing-box hysteria2. Hysteria runs over QUIC, so a uTLS fingerprint
     * from tlsSettings does not apply and is dropped.
     */
    static JSONObject hysteria(JSONObject s,JSONObject st)throws Exception{
        JSONObject hs=st==null?null:st.optJSONObject("hysteriaSettings");
        if(hs==null)hs=new JSONObject();
        int version=s.optInt("version",hs.optInt("version",2));
        if(version!=2)throw new IllegalArgumentException("Hysteria v1 из Xray не поддерживается, нужен Hysteria 2.");
        JSONObject peer=s.has("address")?s:firstOf(s,"servers","vnext");
        if(peer==null)throw new IllegalArgumentException("В Xray-исходящем hysteria нет адреса сервера.");
        String password=str(hs,"auth","password");
        if(password.isEmpty())password=str(s,"auth","password");
        JSONObject o=Json.obj("type","hysteria2","server",host(peer),"server_port",port(peer),"password",password);
        int up=mbps(str(hs,"up","up_mbps")),down=mbps(str(hs,"down","down_mbps"));
        if(up>0)o.put("up_mbps",up);
        if(down>0)o.put("down_mbps",down);
        JSONObject hop=hs.optJSONObject("udphop");
        if(hop!=null)ImportParser.hop(o,str(hop,"port","ports").replace(':','-'),str(hop,"interval"));
        JSONObject t=st==null?null:st.optJSONObject("tlsSettings");
        if(t==null)t=new JSONObject();
        String sni=str(t,"serverName","server_name","sni");
        JSONObject tls=Json.obj("enabled",true,"server_name",sni.isEmpty()?o.optString("server"):sni);
        if(t.optBoolean("allowInsecure")||t.optBoolean("insecure"))tls.put("insecure",true);
        JSONArray alpn=t.optJSONArray("alpn");
        if(alpn!=null&&alpn.length()>0)tls.put("alpn",new JSONArray(alpn.toString()));
        o.put("tls",tls);
        return o;
    }
    private static int mbps(String value){
        String digits=value.replaceAll("[^0-9]","");
        if(digits.isEmpty())return 0;
        try{return Integer.parseInt(digits.length()>6?digits.substring(0,6):digits);}catch(NumberFormatException e){return 0;}
    }
    /** streamSettings → sing-box tls/transport. */
    static void stream(JSONObject o,JSONObject st)throws Exception{
        stream(o,st,false);
    }
    private static void streamPreview(JSONObject o,JSONObject st)throws Exception{
        stream(o,st,true);
    }
    private static void stream(JSONObject o,JSONObject st,boolean omitNativeCertificatePin)throws Exception{
        if(st==null)return;
        String security=st.optString("security","none").toLowerCase(Locale.ROOT);
        if(security.equals("reality")){
            JSONObject r=st.optJSONObject("realitySettings");
            if(r==null)r=st.optJSONObject("realitySettings".toLowerCase(Locale.ROOT));
            if(r==null)r=new JSONObject();
            String sni=str(r,"serverName","server_name","sni","dest","target");
            JSONObject tls=Json.obj("enabled",true,"server_name",sni.isEmpty()?o.optString("server"):sni);
            tls.put("utls",Json.obj("enabled",true,"fingerprint",fingerprint(str(r,"fingerprint","fp"))));
            // Xray never sends ALPN for Reality, and an extra ALPN extension breaks the handshake.
            tls.put("reality",Json.obj("enabled",true,"public_key",str(r,"publicKey","public_key","pbk"),"short_id",str(r,"shortId","shortID","short_id","sid")));
            o.put("tls",tls);
        }else if(security.equals("tls")||security.equals("xtls")||security.equals("utls")){
            JSONObject t=st.optJSONObject("tlsSettings");
            if(t==null)t=st.optJSONObject("xtlsSettings");
            if(t==null)t=new JSONObject();
            String sni=str(t,"serverName","server_name","sni");
            JSONObject tls=Json.obj("enabled",true,"server_name",sni.isEmpty()?o.optString("server"):sni);
            if(t.optBoolean("allowInsecure")||t.optBoolean("insecure"))tls.put("insecure",true);
            // sing-box 1.14.1 bundled in this app supports SPKI pins, while Xray's
            // pinnedPeerCertSha256 is a hash of the complete DER certificate. They are
            // different values; never copy one into the other or silently weaken TLS.
            JSONArray certPins=t.optJSONArray("pinnedPeerCertSha256");
            if(!omitNativeCertificatePin&&certPins!=null&&certPins.length()>0)throw new IllegalArgumentException("Этот сервер требует pinnedPeerCertSha256 (SHA-256 сертификата). Встроенное ядро sing-box 1.14.1 не поддерживает такой pin; подключение остановлено, чтобы не отключать проверку TLS.");
            String certPin=str(t,"pinnedPeerCertSha256");
            if(!omitNativeCertificatePin&&!certPin.isEmpty())throw new IllegalArgumentException("Этот сервер требует pinnedPeerCertSha256 (SHA-256 сертификата). Встроенное ядро sing-box 1.14.1 не поддерживает такой pin; подключение остановлено, чтобы не отключать проверку TLS.");
            JSONArray alpn=t.optJSONArray("alpn");
            if(alpn!=null&&alpn.length()>0)tls.put("alpn",new JSONArray(alpn.toString()));
            String fp=str(t,"fingerprint","fp");
            if(!fp.isEmpty())tls.put("utls",Json.obj("enabled",true,"fingerprint",fingerprint(fp)));
            o.put("tls",tls);
        }
        String network=st.optString("network",st.optString("type","tcp")).toLowerCase(Locale.ROOT);
        switch(network){
            case "": case "tcp": case "raw": {
                JSONObject raw=st.optJSONObject("tcpSettings")==null?st.optJSONObject("rawSettings"):st.optJSONObject("tcpSettings");
                JSONObject header=raw==null?null:raw.optJSONObject("header");
                if(header!=null&&!header.optString("type","none").equals("none"))throw new IllegalArgumentException("Маскировка TCP HTTP из Xray не поддерживается sing-box.");
                return;
            }
            case "ws": {
                JSONObject w=st.optJSONObject("wsSettings")==null?new JSONObject():st.getJSONObject("wsSettings");
                String path=str(w,"path");
                JSONObject t=Json.obj("type","ws","path",path.isEmpty()?"/":path);
                JSONObject headers=w.optJSONObject("headers");
                String hostHeader=str(w,"host");
                if(hostHeader.isEmpty()&&headers!=null)hostHeader=str(headers,"Host","host");
                if(!hostHeader.isEmpty())t.put("headers",Json.obj("Host",hostHeader));
                int early=w.optInt("maxEarlyData",0);
                String earlyHeader=str(w,"earlyDataHeaderName");
                int question=path.indexOf("?ed=");
                if(question>=0){
                    try{early=Integer.parseInt(path.substring(question+4).split("[&#]")[0]);}catch(NumberFormatException ignored){}
                    t.put("path",path.substring(0,question).isEmpty()?"/":path.substring(0,question));
                    if(earlyHeader.isEmpty())earlyHeader="Sec-WebSocket-Protocol";
                }
                if(early>0)t.put("max_early_data",early).put("early_data_header_name",earlyHeader.isEmpty()?"Sec-WebSocket-Protocol":earlyHeader);
                o.put("transport",t);
                return;
            }
            case "grpc": {
                JSONObject g=st.optJSONObject("grpcSettings")==null?new JSONObject():st.getJSONObject("grpcSettings");
                JSONObject grpc=Json.obj("type","grpc","service_name",str(g,"serviceName","service_name"));
                String mode=str(g,"mode");
                if(mode.equalsIgnoreCase("multi"))grpc.put("idle_timeout","15s");
                o.put("transport",grpc);
                return;
            }
            case "h2": case "http": {
                JSONObject h=st.optJSONObject("httpSettings")==null?new JSONObject():st.getJSONObject("httpSettings");
                String path=str(h,"path");
                JSONObject t=Json.obj("type","http","path",path.isEmpty()?"/":path);
                JSONArray hosts=h.optJSONArray("host");
                if(hosts!=null&&hosts.length()>0)t.put("host",new JSONArray(hosts.toString()));
                else if(!str(h,"host").isEmpty())t.put("host",Json.arr(str(h,"host")));
                o.put("transport",t);
                return;
            }
            case "httpupgrade": {
                JSONObject h=st.optJSONObject("httpupgradeSettings")==null?new JSONObject():st.getJSONObject("httpupgradeSettings");
                String path=str(h,"path");
                JSONObject t=Json.obj("type","httpupgrade","path",path.isEmpty()?"/":path);
                if(!str(h,"host").isEmpty())t.put("host",str(h,"host"));
                o.put("transport",t);
                return;
            }
            default: throw new IllegalArgumentException("Транспорт Xray "+network+" не поддерживается sing-box. Запросите TCP, WS, gRPC, HTTP/2 или HTTPUpgrade.");
        }
    }

    /**
     * routing + dns of an Xray config → routing profile in the same shape Happ's happ://routing/ uses,
     * extended with an ordered "Rules" array so provider rule order is preserved.
     */
    static JSONObject routing(JSONObject root,String name)throws Exception{
        JSONObject out=Json.obj("Name",name);
        JSONArray rules=new JSONArray();
        JSONObject r=root.optJSONObject("routing");
        if(r!=null){
            String strategy=str(r,"domainStrategy");
            if(!strategy.isEmpty())out.put("DomainStrategy",strategy);
            for(JSONObject rule:Json.objects(r.optJSONArray("rules"))){
                String tag=str(rule,"outboundTag","outbound").toLowerCase(Locale.ROOT);
                if(tag.equals("dns-out")||tag.equals("dns"))continue;
                JSONObject converted=Json.obj("action",tag.contains("block")||tag.contains("blackhole")?"block":tag.contains("direct")||tag.contains("freedom")?"direct":"proxy");
                boolean matches=false;
                JSONArray domains=list(rule,"domain","domains");
                if(domains.length()>0){converted.put("domain",domains);matches=true;}
                JSONArray ips=list(rule,"ip","ips");
                if(ips.length()>0){converted.put("ip",ips);matches=true;}
                String port=str(rule,"port");
                if(!port.isEmpty()){converted.put("port",port);matches=true;}
                JSONArray protocols=list(rule,"protocol");
                if(protocols.length()>0){converted.put("protocol",protocols);matches=true;}
                String network=str(rule,"network");
                if(!network.isEmpty())converted.put("network",network);
                if(matches)rules.put(converted);
            }
        }
        if(rules.length()>0)out.put("Rules",rules);
        JSONObject dns=root.optJSONObject("dns");
        if(dns!=null){
            String remote=dnsServer(dns.optJSONArray("servers"));
            if(!remote.isEmpty())out.put("RemoteDns",remote);
            String query=str(dns,"queryStrategy");
            if(!query.isEmpty())out.put("QueryStrategy",query);
            JSONObject hosts=dns.optJSONObject("hosts");
            if(hosts!=null&&hosts.length()>0)out.put("DnsHosts",hosts);
        }
        return out;
    }

    /** First usable DNS address in an Xray dns.servers list (entries may be strings or objects). */
    static String dnsServer(JSONArray servers)throws Exception{
        if(servers==null)return "";
        String fallback="";
        for(int i=0;i<servers.length();i++){
            Object item=servers.opt(i);
            String address=item instanceof JSONObject?str((JSONObject)item,"address","server"):String.valueOf(item);
            address=address.trim();
            if(address.isEmpty()||address.equals("null"))continue;
            if(address.equalsIgnoreCase("localhost")||address.startsWith("fakedns"))continue;
            if(address.startsWith("https://")||address.startsWith("tls://")||address.startsWith("quic://")||address.startsWith("h3://"))return address;
            if(fallback.isEmpty())fallback=address;
        }
        return fallback;
    }

    public static String fingerprint(String value){
        String v=value==null?"":value.trim().toLowerCase(Locale.ROOT);
        if(v.isEmpty())return "chrome";
        return FINGERPRINTS.contains(v)?v:"chrome";
    }
    static String host(JSONObject peer){return str(peer,"address","server","host").replace("[","").replace("]","");}
    static int port(JSONObject peer)throws Exception{
        Object value=peer.opt("port");
        int port=value instanceof Number?((Number)value).intValue():value==null?0:Integer.parseInt(String.valueOf(value).trim());
        if(port<1||port>65535)throw new IllegalArgumentException("Некорректный порт в конфигурации Xray.");
        return port;
    }
    static JSONObject firstOf(JSONObject holder,String... keys){
        for(String key:keys){
            JSONArray a=holder.optJSONArray(key);
            if(a!=null)for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null)return o;}
        }
        return null;
    }
    static String str(JSONObject o,String... keys){
        for(String key:keys){
            Object value=o==null?null:o.opt(key);
            if(value!=null&&value!=JSONObject.NULL&&!String.valueOf(value).trim().isEmpty())return String.valueOf(value).trim();
        }
        return "";
    }
    static JSONArray list(JSONObject o,String... keys)throws Exception{
        JSONArray out=new JSONArray();
        for(String key:keys){
            Object value=o.opt(key);
            if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++){String item=String.valueOf(a.opt(i)).trim();if(!item.isEmpty())out.put(item);}}
            else if(value!=null&&value!=JSONObject.NULL&&!String.valueOf(value).trim().isEmpty())out.put(String.valueOf(value).trim());
        }
        return out;
    }
}
