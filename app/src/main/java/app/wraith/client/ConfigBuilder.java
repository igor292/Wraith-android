package app.wraith.client;

import org.json.*;
import java.net.URI;
import java.util.*;

public final class ConfigBuilder {
    private ConfigBuilder() {}
    /** Official sing-box rule-set mirrors, used to translate Xray geosite:/geoip: entries. */
    static final String GEOSITE="https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-";
    static final String GEOIP="https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-";
    /** Direct resolver used when the provider profile names none. */
    static final String DOMESTIC_DNS="77.88.8.8";
    /** Kinds sing-box ORs together inside one rule; everything else in a rule is ANDed. */
    static final Set<String> DOMAIN_FAMILY=Set.of("domain","domain_suffix","domain_keyword","domain_regex");
    static final Set<String> DOMAIN_KINDS=Set.of("domain","domain_suffix","domain_keyword","domain_regex","ip_cidr","package_name","process_name");

    /** Clash API port, chosen per session by the service (a fixed port fingerprinted the tunnel). */
    static volatile int apiPort=9097;
    public static JSONObject build(JSONObject state,String secret,String logPath) throws Exception {
        JSONObject settings=state.getJSONObject("settings");JSONObject chosen=null;
        List<JSONObject> profiles=Json.objects(state.getJSONArray("profiles"));
        for(JSONObject p:profiles)if(p.optString("id").equals(state.optString("selected")))chosen=p;
        if(chosen==null)throw new IllegalArgumentException(L.t("Сначала добавьте и выберите сервер.","Add and select a server first."));
        JSONObject config;
        if(chosen.optBoolean("full")) {
            config=Json.copy(chosen.getJSONObject("outbound"));
            // Network entrypoints belong to Android. The supplied routing and outbounds remain intact.
            config.put("inbounds",inbounds(settings));
            // Without interface auto-detection the core dials back into our own TUN and the tunnel dies.
            JSONObject route=config.optJSONObject("route");
            if(route==null)config.put("route",route=new JSONObject());
            if(!route.has("auto_detect_interface"))route.put("auto_detect_interface",true);
        } else {
            boolean autoSelect=settings.optBoolean("autoSelect");
            JSONArray outbounds=new JSONArray(),tags=new JSONArray();String selectedTag=selectorTag(chosen,autoSelect);
            for(JSONObject p:profiles) {
                if(p.optBoolean("full"))continue;
                JSONArray pool=p.optJSONArray("xrayCandidates");
                if(pool!=null&&pool.length()>1){
                    JSONArray poolTags=new JSONArray();
                    for(int i=0;i<pool.length();i++){
                        JSONObject candidate=pool.getJSONObject(i);String tag=candidateTag(p,i);
                        outbounds.put(configuredOutbound(candidate,p,settings,tag));poolTags.put(tag);
                    }
                    if(autoSelect){
                        String groupTag=poolTag(p);
                        outbounds.put(Json.obj("type","urltest","tag",groupTag,"outbounds",poolTags,"url",settings.optString("pingUrl","https://www.gstatic.com/generate_204"),"interval","3m","tolerance",50));
                        tags.put(groupTag);
                    }else{
                        // A JSON profile may contain several proxy outbounds. Keep the provider's
                        // order deterministic: without the explicit auto-select option, use its
                        // first supported host instead of silently probing and changing hosts.
                        tags.put(poolTags.getString(0));
                    }
                }else{
                    String tag=tagOf(p);outbounds.put(configuredOutbound(p.getJSONObject("outbound"),p,settings,tag));tags.put(tag);
                }
            }
            if(tags.length()==0)throw new IllegalArgumentException(L.t("Нет ни одного отдельного сервера для подключения.","There is no single server to connect to."));
            JSONArray selections=new JSONArray(tags.toString());
            // The urltest group probes every node on a timer, so it only exists when the user asked for it.
            if(autoSelect){
                outbounds.put(Json.obj("type","urltest","tag","auto","outbounds",tags,"url",settings.optString("pingUrl","https://www.gstatic.com/generate_204"),"interval","3m","tolerance",50));
                selections.put("auto");
            }
            outbounds.put(Json.obj("type","selector","tag","proxy","outbounds",selections,"default",settings.optBoolean("autoSelect")?"auto":selectedTag,"interrupt_exist_connections",true));
            outbounds.put(Json.obj("type","direct","tag","direct"));
            JSONArray rules=new JSONArray();
            if(settings.optBoolean("sniff",true))rules.put(Json.obj("action","sniff"));
            rules.put(Json.obj("protocol","dns","action","hijack-dns"));
            // "Обработка QUIC: reject": HTTP/3 is refused so browsers fall back to TCP, where
            // fragmentation and multiplexing apply and QUIC-blocking networks stop stalling pages.
            if(settings.optString("quic","allow").equals("reject"))rules.put(Json.obj("network",Json.arr("udp"),"port",Json.arr(443),"action","reject"));
            if(settings.optBoolean("bypassLan",true))rules.put(Json.obj("ip_is_private",true,"outbound","direct"));
            JSONArray ruleSets=new JSONArray();Map<String,String> ruleSetTags=new LinkedHashMap<>();String lastKey=null;
            String mode=settings.optString("mode","rules");
            if(mode.equals("rules"))for(JSONObject user:Json.objects(RoutingConfig.rulesFor(state,chosen))) {
                if(!user.optBoolean("enabled",true))continue;
                String kind=user.optString("kind","domain_suffix"),value=user.optString("value").trim();
                JSONObject r=new JSONObject();
                if(kind.equals("rule_set")) {
                    URI uri=URI.create(value);if(!"https".equals(uri.getScheme()))throw new IllegalArgumentException(L.t("Набор правил должен загружаться по HTTPS.","A rule set must be downloaded over HTTPS."));
                    String tag="rules-"+ruleSets.length();ruleSets.put(Json.obj("type","remote","tag",tag,"format",value.split("\\?")[0].endsWith(".json")?"source":"binary","url",value,"download_detour","direct","update_interval","24h"));r.put("rule_set",Json.arr(tag));
                } else if(kind.equals("geosite")||kind.equals("geoip")) {
                    // Xray geodata names are not sing-box rules; they map to the published .srs rule sets.
                    JSONArray used=new JSONArray();
                    for(String item:split(value,kind)) {
                        String geo=geoName(kind,item);
                        if(geo.isEmpty())continue;
                        String tag=kind+"-"+geo.replace('.','-').replace('@','-');
                        if(!ruleSetTags.containsKey(tag)){ruleSetTags.put(tag,geo);ruleSets.put(Json.obj("type","remote","tag",tag,"format","binary","url",(kind.equals("geosite")?GEOSITE:GEOIP)+geo+".srs","download_detour","direct","update_interval","24h"));}
                        used.put(tag);
                    }
                    if(used.length()==0)continue;
                    r.put("rule_set",used);
                } else if(kind.equals("ip_is_private")) {
                    r.put("ip_is_private",true);
                } else {
                    if(kind.startsWith("xray_"))continue; // legacy placeholder from earlier builds
                    if(!DOMAIN_KINDS.contains(kind))throw new IllegalArgumentException(L.t("Неизвестный тип правила: ","Unknown rule type: ")+kind);
                    JSONArray a=new JSONArray();for(String item:split(value,kind))a.put(item);
                    if(a.length()==0)throw new IllegalArgumentException(L.t("Пустое правило.","Empty rule."));r.put(kind,a);
                }
                ports(r,user.optString("port"));
                String network=user.optString("network").trim();
                if(Set.of("tcp","udp").contains(network))r.put("network",Json.arr(network));
                JSONArray protocol=user.optJSONArray("protocol");
                if(protocol!=null&&protocol.length()>0)r.put("protocol",new JSONArray(protocol.toString()));
                String action=user.optString("action","proxy");if(action.equals("block"))r.put("action","reject");else r.put("outbound",action.equals("direct")?"direct":"proxy");
                // One provider rule becomes one sing-box rule, like Happ's: the domain kinds are
                // ORed inside a rule, so consecutive domain-only rules with the same target merge.
                // Only the immediately previous rule is considered, so rule order is preserved.
                String key=mergeKey(r,user);
                if(key!=null&&key.equals(lastKey)&&rules.length()>0){
                    JSONObject previous=rules.getJSONObject(rules.length()-1);
                    for(String k:DOMAIN_FAMILY){
                        JSONArray add=r.optJSONArray(k);if(add==null)continue;
                        JSONArray into=previous.optJSONArray(k);
                        if(into==null)previous.put(k,add);else for(int i=0;i<add.length();i++)into.put(add.get(i));
                    }
                }else rules.put(r);
                lastKey=key;
            }
            // Happ's GlobalProxy=false means unmatched traffic stays direct; the app's own
            // "Напрямую" mode always wins over the provider profile.
            boolean globalProxy=mode.equals("global")||RoutingConfig.globalProxy(RoutingConfig.profileFor(state,chosen));
            JSONObject route=Json.obj("rules",rules,"final",mode.equals("direct")||!globalProxy?"direct":"proxy","auto_detect_interface",true,"default_domain_resolver","bootstrap");
            // Direct domain lists are needed twice: in the route (send direct) and in DNS (resolve
            // outside the tunnel). They live once, as inline rule sets both sections reference.
            JSONArray directSets=new JSONArray();
            for(int i=0;i<rules.length();i++){
                JSONObject r=rules.getJSONObject(i);
                if(!"direct".equals(r.optString("outbound"))||mergeKey(r,new JSONObject())==null)continue;
                JSONObject headless=new JSONObject();
                for(String k:DOMAIN_FAMILY)if(r.has(k))headless.put(k,r.get(k));
                if(headless.length()==0)continue;
                String tag="direct-domains"+(directSets.length()==0?"":"-"+directSets.length());
                ruleSets.put(Json.obj("type","inline","tag",tag,"rules",Json.arr(headless)));
                directSets.put(tag);
                rules.put(i,Json.obj("rule_set",Json.arr(tag),"outbound","direct"));
            }
            if(ruleSets.length()>0)route.put("rule_set",ruleSets);
            config=Json.obj("inbounds",inbounds(settings),"outbounds",outbounds,"route",route,"dns",dns(state,chosen,settings,directSets));
        }
        config.put("log",Json.obj("level",settings.optBoolean("debugLogs")?"debug":"info","timestamp",true,"output",logPath));
        config.put("experimental",Json.obj("clash_api",Json.obj("external_controller","127.0.0.1:"+apiPort,"secret",secret),"cache_file",Json.obj("enabled",true)));
        return config;
    }
    /**
     * Xray geodata name → published sing-box rule-set name, or "" when it cannot be translated.
     *
     * Happ ships V2Ray .dat databases, which sing-box removed in 1.12, so the only equivalent is
     * the .srs rule set published for the same list. A few names differ between the two sources,
     * and sing-geoip only publishes country codes, so anything else would download a 404 and
     * silently never match — it is skipped with a log entry instead.
     */
    static String geoName(String kind,String raw){
        String geo=raw.trim().toLowerCase(Locale.ROOT);
        if(geo.startsWith("!")){AppLog.add(L.t("Правило ","Rule ")+kind+":"+raw+L.t(" пропущено: отрицание geo-списков не поддерживается."," skipped: negated geo lists are not supported."));return "";}
        if(!geo.matches("[a-z0-9._-]+")){AppLog.add(L.t("Правило ","Rule ")+kind+":"+raw+L.t(" пропущено: недопустимое имя geo-списка."," skipped: invalid geo list name."));return "";}
        if(kind.equals("geosite")){
            if(geo.equals("ads")||geo.equals("ads-all")||geo.equals("category-ads"))return "category-ads-all";
            return geo;
        }
        // sing-geoip publishes country codes plus "private"; provider lists are not there.
        if(geo.matches("[a-z]{2}"))return geo;
        AppLog.add(L.t("Правило geoip:","Rule geoip:")+raw+L.t(" пропущено: наборы geoip существуют только для кодов стран."," skipped: geoip sets exist only for country codes."));
        return "";
    }
    /**
     * Identity of a rule that may absorb the next one: domain-family fields only, same target,
     * same source (provider list or one user rule), no port/network/protocol conditions.
     * Returns null for anything that must stay a separate rule.
     */
    static String mergeKey(JSONObject r,JSONObject source){
        for(Iterator<String> keys=r.keys();keys.hasNext();){
            String k=keys.next();
            if(!DOMAIN_FAMILY.contains(k)&&!k.equals("outbound")&&!k.equals("action"))return null;
        }
        return r.optString("outbound")+"|"+r.optString("action")+"|"+source.optString("name")+"|"+source.optBoolean("provider");
    }
    /** Outbound tag of a stored profile. */
    public static String tagOf(JSONObject profile)throws JSONException{return "node-"+profile.getString("id");}
    static String poolTag(JSONObject profile)throws JSONException{return "pool-"+profile.getString("id");}
    /** Candidate tags are local to an imported Xray document; scope them by profile before
     * combining several subscriptions into the shared sing-box configuration. */
    static String candidateTag(JSONObject profile,int index)throws JSONException{
        JSONArray candidates=profile.optJSONArray("xrayCandidates");
        String local=candidates!=null&&index>=0&&index<candidates.length()?candidates.getJSONObject(index).optString("tag"):"";
        if(local.isEmpty())local="xray-candidate-"+String.format(Locale.ROOT,"%02d",index+1);
        return "node-"+profile.getString("id")+"-"+local;
    }
    static String selectorTag(JSONObject profile)throws JSONException{return selectorTag(profile,false);}
    static String selectorTag(JSONObject profile,boolean autoSelect)throws JSONException{
        JSONArray candidates=profile.optJSONArray("xrayCandidates");
        return autoSelect&&candidates!=null&&candidates.length()>1?poolTag(profile):candidates!=null&&candidates.length()>0?candidateTag(profile,0):tagOf(profile);
    }

    private static JSONObject configuredOutbound(JSONObject source,JSONObject profile,JSONObject settings,String tag)throws Exception{
        JSONObject o=Json.copy(source);ImportParser.repairReality(o,profile.optString("source"));o.put("tag",tag);
        boolean udpProtocol=Set.of("hysteria","hysteria2","tuic","wireguard").contains(o.optString("type"));
        boolean tcpTls=o.optJSONObject("tls")!=null&&!udpProtocol;
        boolean fragmentable=tcpTls&&o.optString("flow").isEmpty();
        boolean xrayFragment=settings.optString("fragmentType","xray").equals("xray");
        if(settings.optBoolean("fragment")&&xrayFragment&&!udpProtocol)o.put("wraith_fragment",wraithFragment(settings));
        if(settings.optBoolean("noise"))o.put("wraith_noise",Json.arr(wraithNoise(settings)));
        if(settings.optBoolean("fragment")&&!xrayFragment&&fragmentable){
            JSONObject tls=o.getJSONObject("tls");tls.put("fragment",true);
            int delay=settings.optInt("fragmentDelay",10);if(delay<0||delay>1000)throw new IllegalArgumentException(L.t("Задержка фрагментации должна быть от 0 до 1000 мс.","Fragment delay must be from 0 to 1000 ms."));
            tls.put("fragment_fallback_delay",delay+"ms");
        }
        if(settings.optBoolean("recordFragment")&&fragmentable)o.getJSONObject("tls").put("record_fragment",true);
        if(settings.optBoolean("mux")&&o.optString("flow").isEmpty()&&Set.of("vmess","vless","trojan","shadowsocks").contains(o.optString("type")))o.put("multiplex",multiplex(settings));
        return o;
    }
    /** Splits a stored rule value. Regular expressions may legitimately contain commas. */
    static List<String> split(String value,String kind){
        List<String> out=new ArrayList<>();
        for(String item:value.split(kind.equals("domain_regex")?"\\n":"[\\n,]")){String v=item.trim();if(!v.isEmpty())out.add(v);}
        return out;
    }
    /** Xray port expressions ("443", "80,443", "1000-2000") → sing-box port / port_range. */
    static void ports(JSONObject rule,String value)throws Exception{
        if(value==null||value.trim().isEmpty())return;
        JSONArray single=new JSONArray(),ranges=new JSONArray();
        for(String item:value.split("[,\\s]+")){
            String v=item.trim();if(v.isEmpty())continue;
            if(v.contains("-")){String[] pair=v.split("-",2);if(pair[0].trim().isEmpty()||pair[1].trim().isEmpty())continue;ranges.put(pair[0].trim()+":"+pair[1].trim());continue;}
            try{int port=Integer.parseInt(v);if(port>0&&port<65536)single.put(port);}catch(NumberFormatException ignored){}
        }
        if(single.length()>0)rule.put("port",single);
        if(ranges.length()>0)rule.put("port_range",ranges);
    }
    public static JSONObject probeConfig(JSONObject profile,JSONObject settings)throws Exception{
        if(profile.optBoolean("full"))throw new IllegalArgumentException(L.t("Для проверки выберите отдельный сервер","Select a single server to test"));
        JSONObject state=Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",settings,"rules",new JSONArray());
        JSONObject config=build(state,"","");String tag=selectorTag(profile);
        if(profile.optJSONArray("xrayCandidates")==null){
            JSONObject outbound=config.getJSONArray("outbounds").getJSONObject(0);
            config.put("outbounds",Json.arr(outbound));
        }
        config.put("inbounds",new JSONArray());config.remove("experimental");
        config.put("log",Json.obj("disabled",true));config.put("route",Json.obj("final",tag,"default_domain_resolver","bootstrap","auto_detect_interface",true));
        config.put("dns",Json.obj("servers",Json.arr(Json.obj("type","udp","tag","bootstrap","server","1.1.1.1")),"final","bootstrap"));
        return config;
    }
    /** "a-b" or "a" with non-negative integers, as Happ writes its ranges. */
    static String range(String value,String name,long min,long max){
        String v=value==null?"":value.trim().replace(" ","");
        if(!v.matches("[0-9]+(-[0-9]+)?"))throw new IllegalArgumentException(name+L.t(": укажите число или диапазон вида 10-20",": enter a number or a range like 10-20"));
        String[] p=v.split("-");long a=Long.parseLong(p[0]),b=p.length>1?Long.parseLong(p[1]):a;
        if(a<min||b>max||b<a)throw new IllegalArgumentException(name+L.t(": допустимо ",": allowed ")+min+"–"+max);
        return v;
    }
    /** Happ's Xray fragmentation block → wraith_fragment dialer option. */
    static JSONObject wraithFragment(JSONObject s)throws Exception{
        String packets=s.optString("fragmentPackets","tlshello");
        if(!packets.equals("tlshello"))range(packets,L.t("Фрагментирование пакетов","Packet fragmentation"),1,100);
        JSONObject f=Json.obj("packets",packets,"length",range(s.optString("fragmentLength","50-100"),L.t("Длина фрагмента","Fragment length"),1,16384),"interval",range(s.optString("fragmentInterval","10-20"),L.t("Задержка фрагмента","Fragment delay"),0,1000));
        String split=s.optString("fragmentMaxSplit","").trim();
        if(!split.isEmpty())f.put("max_split",range(split,L.t("Максимальное разделение","Maximum split"),1,10000));
        return f;
    }
    /** Happ's noise block → one wraith_noise entry (UDP only: Hysteria, TUIC, QUIC). */
    static JSONObject wraithNoise(JSONObject s)throws Exception{
        String type=s.optString("noiseType","array");
        if(!Set.of("array","rand","str","base64").contains(type))throw new IllegalArgumentException(L.t("Тип шума: array, rand, str или base64","Noise type: array, rand, str or base64"));
        JSONObject n=Json.obj("type",type,"delay",range(s.optString("noiseDelay","10-16"),L.t("Задержка шума","Noise delay"),0,1000));
        if(type.equals("rand"))n.put("rand",range(s.optString("noiseRand","1-8192"),"Rand",1,8192)).put("rand_range",range(s.optString("noiseRange","0-255"),"RandRange",0,255));
        else{
            String packet=s.optString("noisePacket","1A,2B,3C").trim();
            if(packet.isEmpty())throw new IllegalArgumentException(L.t("Пакет шума пуст","Noise packet is empty"));
            if(type.equals("array")&&!packet.matches("(?i)\\s*(0x)?[0-9a-f]{1,2}(\\s*,\\s*(0x)?[0-9a-f]{1,2})*\\s*"))throw new IllegalArgumentException(L.t("Пакет array: байты в hex через запятую, например 1A,2B,3C","array packet: hex bytes separated by commas, e.g. 1A,2B,3C"));
            n.put("packet",packet);
        }
        return n;
    }
    static JSONObject multiplex(JSONObject settings)throws Exception{
        String protocol=settings.optString("muxProtocol","h2mux");if(!Set.of("h2mux","smux","yamux").contains(protocol))throw new IllegalArgumentException(L.t("Протокол мультиплексора должен быть h2mux, smux или yamux. XUDP из Xray не поддерживается.","Multiplexer protocol must be h2mux, smux or yamux. Xray XUDP is not supported."));
        boolean streams=settings.optString("muxLimit","connections").equals("streams");int value=settings.optInt(streams?"muxStreams":"muxConnections",streams?8:4);if(value<1||value>1024)throw new IllegalArgumentException(L.t("Ограничение мультиплексора: 1–1024","Multiplexer limit: 1–1024"));
        return Json.obj("enabled",true,"protocol",protocol,streams?"max_streams":"max_connections",value,"padding",settings.optBoolean("muxPadding",true));
    }
    static JSONArray inbounds(JSONObject s)throws Exception{
        int mtu=s.optInt("mtu",1500);if(mtu<1280||mtu>9000)throw new IllegalArgumentException(L.t("MTU должен быть от 1280 до 9000.","MTU must be between 1280 and 9000."));
        JSONArray addresses=Json.arr("172.19.0.1/30");
        // Always capture IPv6. When disabled it is rejected, preventing bypass via IPv6.
        addresses.put("fdfe:dcba:9876::1/126");
        String stack=s.optString("tunStack","mixed");if(!Set.of("mixed","system","gvisor").contains(stack))throw new IllegalArgumentException(L.t("Неизвестный стек TUN: ","Unknown TUN stack: ")+stack);
        JSONObject tun=Json.obj("type","tun","tag","tun-in","address",addresses,"mtu",mtu,"auto_route",true,"strict_route",true,"stack",stack,"dns_mode","hijack");
        // "Исключённые маршруты": subnets left out of the TUN entirely, like Happ's Exclude Routes.
        JSONArray excluded=new JSONArray();for(String line:s.optString("excludedRoutes").split("\n")){String c=line.trim();if(!c.isEmpty())excluded.put(c);}
        if(excluded.length()>0)tun.put("route_exclude_address",excluded);
        JSONArray in=Json.arr(tun);
        if(s.optBoolean("localProxy",false)) {
            JSONObject mixed=Json.obj("type","mixed","tag","mixed-in","listen",s.optBoolean("allowLan")?"0.0.0.0":"127.0.0.1","listen_port",s.optInt("proxyPort",2081));
            if(s.optBoolean("allowLan")) {
                String password=s.optString("proxyPassword");if(password.length()<8)throw new IllegalArgumentException(L.t("Для доступа из LAN задайте пароль прокси не короче 8 символов.","For LAN access set a proxy password of at least 8 characters."));
                mixed.put("users",Json.arr(Json.obj("username","wraith","password",password)));
            }
            in.put(mixed);
        }
        return in;
    }
    /**
     * DNS comes from the imported configuration or routing profile, never from app settings.
     * RemoteDns, DomesticDns, DnsHosts and the domain strategy of the provider profile are honoured.
     */
    static JSONObject dns(JSONObject state,JSONObject profile,JSONObject s,JSONArray directSets)throws Exception{
        JSONObject routing=RoutingConfig.profileFor(state,profile);
        // "Использовать DNS из JSON" off, or nothing from the provider: the user's tunnel DNS,
        // then the default DoH.
        // Provider DNS (switch «DNS из JSON»), then the user's own DNS (switch «Свой DNS»), then the
        // phone's own resolver captured at connect time, which is reached directly like the phone would.
        boolean fromJson=s.optBoolean("dnsFromJson",false);
        String provided=fromJson?RoutingConfig.dnsAddress(routing,"Remote"):"";
        String chosen=provided.isEmpty()?DnsChoice.custom(s):provided;
        String phone=chosen.isEmpty()?DnsChoice.firstSystem(s):"";
        JSONObject remote=server("remote-dns",chosen.isEmpty()?phone:chosen,DnsChoice.DEFAULT_CUSTOM,provided.isEmpty()?"":RoutingConfig.dnsPinnedIp(routing,"Remote"));
        if(phone.isEmpty()&&!s.optString("mode").equals("direct"))remote.put("detour","proxy");
        // A direct DNS server with an explicit direct detour is rejected by sing-box 1.14:
        // "detour to an empty direct outbound makes no sense". No detour already means direct.
        JSONArray servers=Json.arr(Json.obj("type","udp","tag","bootstrap","server","1.1.1.1"),remote);
        JSONArray rules=new JSONArray();
        // The single IP-family selector is authoritative. Empty answers keep applications from
        // attempting a family the user did not select.
        String ip=s.optString("ipPreference","auto");
        if(ip.equals("ipv4"))rules.put(Json.obj("query_type",Json.arr("AAAA"),"action","predefined"));
        else if(ip.equals("ipv6"))rules.put(Json.obj("query_type",Json.arr("A"),"action","predefined"));
        JSONObject hosts=routing==null?null:routing.optJSONObject("DnsHosts");
        if(hosts!=null&&hosts.length()>0){
            JSONObject predefined=new JSONObject();JSONArray domains=new JSONArray();
            for(Iterator<String> keys=hosts.keys();keys.hasNext();){
                String domain=keys.next();JSONArray values=new JSONArray();
                Object value=hosts.opt(domain);
                if(value instanceof JSONArray)for(int i=0;i<((JSONArray)value).length();i++)values.put(String.valueOf(((JSONArray)value).opt(i)).trim());
                else if(value!=null)values.put(String.valueOf(value).trim());
                JSONArray addresses=new JSONArray();
                for(int i=0;i<values.length();i++){String address=values.getString(i);if(address.matches("[0-9a-fA-F:.]+")&&address.matches(".*[.:].*"))addresses.put(address);}
                if(addresses.length()==0)continue;
                predefined.put(domain,addresses);domains.put(domain);
            }
            if(domains.length()>0){
                servers.put(Json.obj("type","hosts","tag","hosts-dns","predefined",predefined));
                rules.put(Json.obj("domain",domains,"action","route","server","hosts-dns"));
            }
        }
        // Domains that leave the tunnel must also be resolved outside it. Resolving them through the
        // proxy returns the exit country's GeoDNS answer (a foreign edge of a Russian service), and
        // a direct connection from a Russian address to that edge gets reset. Xray avoids this by
        // overriding the destination with the sniffed domain and letting freedom resolve it locally;
        // here the same effect comes from a direct domestic resolver for every direct domain.
        // The domains themselves are the inline rule sets the route section already defines.
        String domestic=RoutingConfig.dnsAddress(routing,"Domestic");
        if(!domestic.isEmpty()||directSets.length()>0){
            // No detour: resolved outside the tunnel. Without a provider value, Yandex DNS answers
            // with domestic addresses for exactly the domains a Russian provider routes direct.
            servers.put(server("local-dns",domestic,DOMESTIC_DNS,RoutingConfig.dnsPinnedIp(routing,"Domestic")));
            if(directSets.length()>0){
                // Direct traffic can only use what the physical network offers, and many Wi-Fi
                // networks have no IPv6. The TUN does accept IPv6, so a browser that got an AAAA
                // record connects, the direct dial fails with "network is unreachable", and the
                // browser sees a reset instead of a connect failure — no IPv4 fallback. Answering
                // AAAA (and HTTPS records, which carry ipv6hint) empty keeps direct sites on IPv4.
                rules.put(Json.obj("rule_set",new JSONArray(directSets.toString()),"query_type",Json.arr("AAAA","HTTPS"),"action","predefined"));
                rules.put(Json.obj("rule_set",new JSONArray(directSets.toString()),"action","route","server","local-dns"));
            }
        }
        return Json.obj("servers",servers,"rules",rules,"final","remote-dns","strategy",s.optString("ipPreference","auto").equals("ipv4")?"ipv4_only":s.optString("ipPreference").equals("ipv6")?"ipv6_only":strategy(routing),"reverse_mapping",true);
    }
    static JSONObject server(String tag,String address,String fallback)throws Exception{return server(tag,address,fallback,"");}
    /**
     * Provider DNS address (DoH/DoT/QUIC URL or bare IP) → sing-box DNS server object.
     * When the provider also pinned an IP for its DNS hostname (Happ ships that pair as
     * RemoteDNSDomain + RemoteDNSIP plus a DnsHosts entry), the IP is dialled directly and the
     * hostname is only used as the TLS name — no bootstrap lookup, exactly like Happ.
     */
    static JSONObject server(String tag,String address,String fallback,String pinnedIp)throws Exception{
        String value=address==null?"":address.trim();
        if(value.isEmpty())value=fallback;
        if(value.isEmpty())throw new IllegalArgumentException(L.t("Не указан адрес DNS-сервера.","DNS server address is missing."));
        if(value.startsWith("https://")||value.startsWith("h3://")||value.startsWith("tls://")||value.startsWith("quic://")||value.startsWith("tcp://")||value.startsWith("udp://")){
            URI u=new URI(value);
            if(u.getHost()==null)throw new IllegalArgumentException(L.t("Некорректный адрес DNS: ","Invalid DNS address: ")+value);
            String scheme=u.getScheme();
            String type=scheme.equals("https")?"https":scheme.equals("h3")?"h3":scheme.equals("tls")?"tls":scheme.equals("quic")?"quic":scheme;
            String host=u.getHost();
            boolean pin=pinnedIp!=null&&!pinnedIp.isEmpty()&&!host.matches("[0-9a-fA-F:.]+");
            JSONObject server=Json.obj("type",type,"tag",tag,"server",pin?pinnedIp:host);
            if(pin&&!type.equals("udp")&&!type.equals("tcp"))server.put("tls",Json.obj("enabled",true,"server_name",host));
            else if(!host.matches("[0-9a-fA-F:.]+"))server.put("domain_resolver","bootstrap");
            if(u.getPort()>0)server.put("server_port",u.getPort());
            if(type.equals("https")||type.equals("h3")){String path=u.getRawPath();server.put("path",path==null||path.isEmpty()?"/dns-query":path);}
            return server;
        }
        if(value.equalsIgnoreCase("localhost")||value.equals("local"))return Json.obj("type","local","tag",tag);
        if(value.matches("[0-9a-fA-F:.]+")&&value.matches(".*[.:].*"))return Json.obj("type","udp","tag",tag,"server",value);
        if(value.matches("[A-Za-z0-9.-]+"))return Json.obj("type","udp","tag",tag,"server",value,"domain_resolver","bootstrap");
        throw new IllegalArgumentException(L.t("DNS: используйте URL (https/tls/quic), имя хоста или IP-адрес. Получено: ","DNS: use a URL (https/tls/quic), a host name or an IP address. Got: ")+value);
    }
    /** Xray queryStrategy / domainStrategy → sing-box DNS strategy. */
    static String strategy(JSONObject routing){
        String value=routing==null?"":routing.optString("QueryStrategy",routing.optString("DomainStrategy",""));
        if(value.equalsIgnoreCase("UseIPv4"))return "ipv4_only";
        if(value.equalsIgnoreCase("UseIPv6"))return "ipv6_only";
        return "prefer_ipv4";
    }
}
