package app.wraith.client;

import org.json.*;
import java.net.URI;
import java.util.*;

/** Builds native Xray JSON while preserving provider-supplied Xray configuration verbatim. */
final class XrayRuntimeConfig {
    static final String DNS_IP="1.1.1.1";
    private XrayRuntimeConfig(){}

    /** Fresh per session: a fixed localhost port let any app on the phone detect the running tunnel. */
    static volatile int metricsPort=freshMetricsPort();
    static int freshMetricsPort(){try(java.net.ServerSocket s=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){return s.getLocalPort();}catch(Exception e){return 20000+new java.security.SecureRandom().nextInt(40000);}}
    static JSONObject build(JSONObject state,JSONObject profile,int tunFd,IsolatedXray xray)throws Exception{
        metricsPort=freshMetricsPort();
        JSONObject settings=state.optJSONObject("settings");if(settings==null)settings=new JSONObject();
        String source=profile.optString("source","").trim();JSONObject root=prepare(profile,settings,source(profile,xray));

        // A provider "api" block opens its own gRPC listener (api.listen), possibly on the LAN, that can
        // add or remove outbounds. Android owns every entrypoint, so it never reaches the core.
        root.remove("api");root.remove("remarks");root.remove("name");root.remove("meta");root.remove("version");
        if(root.optJSONObject("dns")==null||!settings.optBoolean("dnsFromJson",false))root.put("dns",dns(profile,settings));
        JSONObject env=root.optJSONObject("env");if(env==null){env=new JSONObject();root.put("env",env);}env.put("xray.tun.fd",String.valueOf(tunFd));
        // The provider's local inlets (socks/http on 10808/10809 for desktop clients) are replaced
        // by the TUN. Rules that send "traffic from those inlets" to a balancer must now see the
        // TUN as well, otherwise nothing ever reached the provider's automatic server choice.
        Set<String> clientInlets=clientInboundTags(root.optJSONArray("inbounds"));
        root.put("inbounds",inbounds(settings));
        if(!clientInlets.isEmpty())remapInboundRules(root,clientInlets);
        String[] appTags=ensureAppOutbounds(root,profile);
        String dnsTag=ensureDnsOutbound(root);
        JSONObject routing=root.optJSONObject("routing");if(routing==null){routing=new JSONObject();root.put("routing",routing);}
        if(!routing.has("domainStrategy"))routing.put("domainStrategy","IPIfNonMatch");
        JSONArray rules=routing.optJSONArray("rules");if(rules==null){rules=new JSONArray();routing.put("rules",rules);}
        // Observatory/balancers need DNS to resolve their candidates independently of the
        // balancer they are measuring. INCY applies this same narrow exception to full configs.
        // Without it a resolver endpoint can itself be sent through the unresolved selector,
        // leaving the tunnel up while every connection waits on DNS/health checks.
        JSONArray resolverIps=observatoryDnsIps(root);
        if(resolverIps.length()>0)rules=insertRule(rules,0,Json.obj("type","field","ip",resolverIps,"outboundTag",appTags[1]));
        // The phone's own resolvers live in the local or operator network and are queried directly, as without VPN.
        if(!settings.optBoolean("dnsFromJson",false)&&DnsChoice.custom(settings).isEmpty()&&!DnsChoice.system(settings).isEmpty())rules=insertRule(rules,0,Json.obj("type","field","ip",new JSONArray(DnsChoice.system(settings)),"port","53","outboundTag",appTags[1]));
        rules=insertRule(rules,0,Json.obj("type","field","inboundTag",Json.arr("tun-in"),"network","tcp,udp","port","53","outboundTag",dnsTag));routing.put("rules",rules);
        if(source.startsWith("{")){appendUserRules(rules,state,appTags);appendAutoCatchAll(rules,root);}else appendRules(rules,state,profile,settings,appTags);
        String mode=settings.optString("mode","rules");
        if(mode.equals("direct")||mode.equals("global")){
            JSONObject all=Json.obj("type","field","inboundTag",Json.arr("tun-in"));
            // "Всё в прокси" with a provider balancer goes to the balancer, not to one fixed server.
            String balancer=!mode.equals("global")?"":hasBalancer(root,AUTO_BALANCER)?AUTO_BALANCER:providerBalancing(root)?mainBalancer(root):"";
            if(!balancer.isEmpty())all.put("balancerTag",balancer);else all.put("outboundTag",mode.equals("direct")?appTags[1]:appTags[0]);
            rules=insertRule(rules,1,all);routing.put("rules",rules);
        }
        JSONObject log=root.optJSONObject("log");if(log==null){log=new JSONObject();root.put("log",log);}
        // Imported log paths could overwrite the app's own files (same Linux user); logs go to logcat/AppLog only.
        log.remove("access");log.remove("error");if(!log.has("loglevel"))log.put("loglevel",settings.optBoolean("debugLogs")?"debug":"warning");
        JSONObject policy=root.optJSONObject("policy");if(policy==null){policy=new JSONObject();root.put("policy",policy);}
        JSONObject system=policy.optJSONObject("system");if(system==null){system=new JSONObject();policy.put("system",system);}
        system.put("statsInboundDownlink",true).put("statsInboundUplink",true).put("statsOutboundDownlink",true).put("statsOutboundUplink",true);
        root.put("stats",root.optJSONObject("stats")==null?new JSONObject():root.getJSONObject("stats"));
        root.put("metrics",Json.obj("listen","127.0.0.1:"+metricsPort));
        return root;
    }

    /** Source loading is shared by the live core and its native probe, without sing-box conversion. */
    static JSONObject source(JSONObject profile,IsolatedXray xray)throws Exception{
        String source=profile.optString("source","").trim();
        if(source.startsWith("{")){
            JSONObject root=new JSONObject(source);if(!XrayConfig.looksLike(root))throw new IllegalArgumentException(L.t("Это не конфигурация Xray.","This is not an Xray configuration."));
            if(root.optJSONArray("outbounds")==null&&root.has("protocol")){
                JSONObject outbound=Json.copy(root);for(String field:List.of("dns","routing","log","remarks","name","meta","env"))outbound.remove(field);
                if(outbound.optString("tag").isEmpty())outbound.put("tag","proxy");
                JSONObject result=Json.obj("outbounds",Json.arr(outbound));
                for(String field:List.of("dns","routing","log","env"))if(root.has(field))result.put(field,root.get(field));return result;
            }
            return root;
        }
        if(source.isEmpty())throw new IllegalArgumentException(L.t("У профиля нет исходной ссылки для Xray.","The profile has no source link for Xray."));
        JSONObject local=XrayLinks.outbound(source);if(local!=null)return Json.obj("outbounds",Json.arr(local));
        JSONObject response=new JSONObject(xray.invoke(Json.obj("apiVersion",3,"method","convertShareLinksToXrayJson","payload",Json.obj("text",source)).toString()));
        if(!response.optBoolean("success"))throw new IllegalArgumentException(response.optString("error",L.t("Не удалось преобразовать ссылку в Xray.","Could not convert the link to Xray.")));
        JSONObject data=response.optJSONObject("data");JSONArray outbounds=data==null?null:data.optJSONArray("outbounds");
        if(outbounds==null||outbounds.length()==0)throw new IllegalArgumentException(L.t("Ссылка не содержит Xray-серверов.","The link contains no Xray servers."));return Json.obj("outbounds",new JSONArray(outbounds.toString()));
    }

    /** Keep provider tags and dependent dialers intact while selecting a deterministic default. */
    static JSONObject prepare(JSONObject profile,JSONObject settings,JSONObject source)throws Exception{
        JSONObject root=Json.copy(source);String tag=proxyTag(root,profile);JSONArray outbounds=root.getJSONArray("outbounds");
        // A provider balancer (leastPing/random/roundRobin with observatory) is the automatic server
        // choice the user asked for. Pinning it to the first server meant one dead node took the whole
        // profile down. Latency probes still pin a single candidate ("pinCandidate").
        boolean pin=settings.optBoolean("pinCandidate")||(!settings.optBoolean("autoSelect",true)&&!providerBalancing(root));
        if(pin){
            JSONObject chosen=null;JSONArray ordered=new JSONArray();for(JSONObject outbound:Json.objects(outbounds))if(tag.equals(outbound.optString("tag"))){chosen=outbound;break;}
            if(chosen==null)throw new IllegalArgumentException(L.t("Не найден выбранный Xray outbound.","The selected Xray outbound was not found."));
            JSONObject expected=profile.optJSONObject("outbound"),peers=chosen.optJSONObject("settings");
            if(expected!=null&&peers!=null)for(String key:List.of("vnext","servers")){
                JSONArray list=peers.optJSONArray(key);if(list==null||list.length()<2)continue;
                for(JSONObject peer:Json.objects(list))if(XrayConfig.host(peer).equalsIgnoreCase(expected.optString("server"))&&port(peer)==expected.optInt("server_port")){peers.put(key,Json.arr(peer));break;}
            }
            ordered.put(chosen);for(JSONObject outbound:Json.objects(outbounds))if(outbound!=chosen)ordered.put(outbound);root.put("outbounds",ordered);
            JSONObject routing=root.optJSONObject("routing");JSONArray balancers=routing==null?null:routing.optJSONArray("balancers"),rules=routing==null?null:routing.optJSONArray("rules");
            if(balancers!=null&&rules!=null)for(JSONObject balancer:Json.objects(balancers)){
                boolean selected=false;JSONArray selectors=balancer.optJSONArray("selector");if(selectors!=null)for(int i=0;i<selectors.length();i++)if(tag.startsWith(selectors.optString(i))){selected=true;break;}
                if(selected)for(JSONObject rule:Json.objects(rules))if(balancer.optString("tag").equals(rule.optString("balancerTag"))){rule.remove("balancerTag");rule.put("outboundTag",tag);}
            }
        }
        for(JSONObject outbound:Json.objects(root.optJSONArray("outbounds"))){
            JSONObject legacy=outbound.optJSONObject("proxySettings");if(legacy==null)continue;String dependency=legacy.optString("tag");
            if(dependency.isEmpty())outbound.remove("proxySettings");
            else if(legacy.optBoolean("transportLayer")){
                JSONObject stream=outbound.optJSONObject("streamSettings");if(stream==null){stream=new JSONObject();outbound.put("streamSettings",stream);}JSONObject sockopt=stream.optJSONObject("sockopt");if(sockopt==null){sockopt=new JSONObject();stream.put("sockopt",sockopt);}
                if(sockopt.has("dialerProxy")&&!dependency.equals(sockopt.optString("dialerProxy")))throw new IllegalArgumentException(L.t("В JSON конфликтуют proxySettings и dialerProxy.","proxySettings and dialerProxy conflict in the JSON."));sockopt.put("dialerProxy",dependency);outbound.remove("proxySettings");
            }
        }
        // Xray 26 refuses to start on the removed "allowInsecure"; drop it so the rest of the document still runs.
        for(JSONObject outbound:Json.objects(root.optJSONArray("outbounds"))){JSONObject stream=outbound.optJSONObject("streamSettings"),tls=stream==null?null:stream.optJSONObject("tlsSettings");if(tls!=null&&tls.remove("allowInsecure")!=null)AppLog.add(L.t("allowInsecure удалён: Xray 26 его не поддерживает, сертификат проверяется.","allowInsecure removed: Xray 26 does not support it, the certificate is verified."));}
        // DPI options apply to every server the core may pick, not only to the first one.
        List<String> candidates=proxyTags(root);
        if(pin)XrayOutboundOptions.apply(root,profile,settings,tag);
        else for(String candidate:candidates)XrayOutboundOptions.apply(root,profile,settings,candidate);
        if(!pin&&!providerBalancing(root)&&candidates.size()>1)addAutoBalancer(root,candidates,tag,settings);
        return root;
    }

    static final String AUTO_BALANCER="wraith-auto";
    /**
     * "Быстрый сервер автоматически" for a multi-server Xray document that has no balancer of its
     * own: the servers are measured by an observatory and traffic that went to the default server
     * goes to the fastest live one instead. Without this the switch did nothing on Xray.
     */
    private static void addAutoBalancer(JSONObject root,List<String> candidates,String fallback,JSONObject settings)throws Exception{
        JSONObject routing=root.optJSONObject("routing");if(routing==null){routing=new JSONObject();root.put("routing",routing);}
        JSONArray balancers=routing.optJSONArray("balancers");if(balancers==null){balancers=new JSONArray();routing.put("balancers",balancers);}
        balancers.put(Json.obj("tag",AUTO_BALANCER,"selector",new JSONArray(candidates),"strategy",Json.obj("type","leastPing"),"fallbackTag",fallback));
        if(root.optJSONObject("observatory")==null)root.put("observatory",Json.obj("subjectSelector",new JSONArray(candidates),"probeUrl",settings.optString("pingUrl","https://www.gstatic.com/generate_204"),"probeInterval","3m","enableConcurrency",true));
        JSONArray rules=routing.optJSONArray("rules");if(rules==null){rules=new JSONArray();routing.put("rules",rules);}
        // Rules that named the default server now name the automatic choice.
        for(JSONObject rule:Json.objects(rules))if(fallback.equals(rule.optString("outboundTag"))){rule.remove("outboundTag");rule.put("balancerTag",AUTO_BALANCER);}
    }
    /** Unmatched tunnel traffic for an auto-selected document, appended after every other rule. */
    private static void appendAutoCatchAll(JSONArray rules,JSONObject root)throws Exception{
        JSONObject routing=root.optJSONObject("routing");
        for(JSONObject b:Json.objects(routing==null?null:routing.optJSONArray("balancers")))
            if(AUTO_BALANCER.equals(b.optString("tag"))){rules.put(Json.obj("type","field","inboundTag",Json.arr("tun-in","socks","http"),"balancerTag",AUTO_BALANCER));return;}
    }

    /** True when routing rules send traffic to a balancer defined by the provider. */
    static boolean providerBalancing(JSONObject root){
        JSONObject routing=root.optJSONObject("routing");if(routing==null)return false;
        Set<String> balancers=new HashSet<>();for(JSONObject b:Json.objects(routing.optJSONArray("balancers")))if(!b.optString("tag").isEmpty())balancers.add(b.optString("tag"));
        if(balancers.isEmpty())return false;
        for(JSONObject rule:Json.objects(routing.optJSONArray("rules")))if(balancers.contains(rule.optString("balancerTag")))return true;
        return false;
    }
    static boolean hasBalancer(JSONObject root,String tag){
        JSONObject routing=root.optJSONObject("routing");
        for(JSONObject b:Json.objects(routing==null?null:routing.optJSONArray("balancers")))if(tag.equals(b.optString("tag")))return true;
        return false;
    }
    /** Balancer used by the provider's catch-all (or last) balancer rule. */
    static String mainBalancer(JSONObject root){
        JSONObject routing=root.optJSONObject("routing");String found="";
        for(JSONObject rule:Json.objects(routing==null?null:routing.optJSONArray("rules"))){
            String b=rule.optString("balancerTag");if(b.isEmpty())continue;found=b;
            boolean catchAll=!rule.has("domain")&&!rule.has("ip")&&!rule.has("port")&&!rule.has("protocol");
            if(catchAll)return b;
        }
        return found;
    }
    /** Real proxy outbounds in provider order, excluding local helpers and hops used only as another server's dialer. */
    static List<String> proxyTags(JSONObject root){
        List<JSONObject> outbounds=Json.objects(root.optJSONArray("outbounds"));Set<String> dialers=new HashSet<>();
        for(JSONObject o:outbounds){JSONObject stream=o.optJSONObject("streamSettings"),sockopt=stream==null?null:stream.optJSONObject("sockopt");if(sockopt!=null&&!sockopt.optString("dialerProxy").isEmpty())dialers.add(sockopt.optString("dialerProxy"));}
        List<String> tags=new ArrayList<>();
        for(JSONObject o:outbounds){
            String protocol=o.optString("protocol").toLowerCase(Locale.ROOT),tag=o.optString("tag");
            if(tag.isEmpty()||dialers.contains(tag)||Set.of("freedom","blackhole","dns","loopback").contains(protocol))continue;
            tags.add(tag);
        }
        return tags;
    }
    /** Provider inlets that carry client traffic (not DNS forwarders). */
    static Set<String> clientInboundTags(JSONArray inbounds){
        Set<String> tags=new HashSet<>();
        for(JSONObject in:Json.objects(inbounds)){
            String protocol=in.optString("protocol").toLowerCase(Locale.ROOT),tag=in.optString("tag");if(tag.isEmpty())continue;
            JSONObject s=in.optJSONObject("settings");
            boolean transparent=protocol.equals("dokodemo-door")&&s!=null&&s.optBoolean("followRedirect");
            if(Set.of("socks","http","mixed","tun").contains(protocol)||transparent)tags.add(tag);
        }
        return tags;
    }
    private static void remapInboundRules(JSONObject root,Set<String> clientInlets)throws Exception{
        JSONObject routing=root.optJSONObject("routing");if(routing==null)return;
        for(JSONObject rule:Json.objects(routing.optJSONArray("rules"))){
            JSONArray tags=rule.optJSONArray("inboundTag");if(tags==null)continue;
            boolean client=false,hasTun=false;
            for(int i=0;i<tags.length();i++){String t=tags.optString(i);if(clientInlets.contains(t))client=true;if(t.equals("tun-in"))hasTun=true;}
            if(client&&!hasTun)tags.put("tun-in");
        }
    }

    static String proxyTag(JSONObject root,JSONObject profile)throws Exception{
        JSONArray outbounds=root.optJSONArray("outbounds");if(outbounds==null)throw new IllegalArgumentException(L.t("В конфигурации Xray нет outbounds.","The Xray configuration has no outbounds."));
        JSONObject expected=profile==null?null:profile.optJSONObject("outbound"),chosen=null,first=null;
        for(JSONObject outbound:Json.objects(outbounds)){
            String protocol=outbound.optString("protocol").toLowerCase(Locale.ROOT);if(Set.of("freedom","blackhole","dns","loopback").contains(protocol))continue;
            if(first==null)first=outbound;
            if(expected!=null&&protocol.equalsIgnoreCase(expected.optString("type"))&&containsEndpoint(outbound,expected.optString("server"),expected.optInt("server_port"))){chosen=outbound;break;}
        }
        if(chosen==null)chosen=first;if(chosen==null)throw new IllegalArgumentException(L.t("В конфигурации Xray нет прокси-outbound.","The Xray configuration has no proxy outbound."));
        String tag=chosen.optString("tag");if(tag.isEmpty()){tag=uniqueTag(outbounds,"proxy");chosen.put("tag",tag);}return tag;
    }

    /** Resolver IPs for Xray observatory/balancer configs, preserving provider server order. */
    static JSONArray observatoryDnsIps(JSONObject root){
        JSONObject routing=root.optJSONObject("routing");JSONArray balancers=routing==null?null:routing.optJSONArray("balancers");
        if((root.optJSONObject("observatory")==null&&root.optJSONObject("burstObservatory")==null)||balancers==null||balancers.length()==0)return new JSONArray();
        JSONObject dns=root.optJSONObject("dns");JSONArray servers=dns==null?null:dns.optJSONArray("servers");if(servers==null)return new JSONArray();
        JSONArray ips=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<servers.length();i++){
            Object server=servers.opt(i);String address="";
            if(server instanceof JSONObject){JSONObject item=(JSONObject)server;address=item.optString("address",item.optString("server"));}
            else if(server instanceof String)address=(String)server;
            String host=dnsHost(address);if(isIp(host)&&seen.add(host))ips.put(host);
        }
        return ips;
    }

    private static boolean containsEndpoint(JSONObject outbound,String host,int port){
        JSONObject settings=outbound.optJSONObject("settings");if(settings==null)return false;
        for(String key:List.of("vnext","servers"))for(JSONObject peer:Json.objects(settings.optJSONArray(key)))
            if(XrayConfig.host(peer).equalsIgnoreCase(host)&&port(peer)==port)return true;
        return settings.optString("address").equalsIgnoreCase(host)&&settings.optInt("port")==port;
    }
    private static int port(JSONObject peer){try{return XrayConfig.port(peer);}catch(Exception ignored){return 0;}}
    private static JSONArray insertRule(JSONArray rules,int index,JSONObject rule)throws Exception{
        JSONArray next=new JSONArray();int at=Math.max(0,Math.min(index,rules.length()));
        for(int i=0;i<=rules.length();i++){
            if(i==at)next.put(rule);
            if(i<rules.length())next.put(rules.get(i));
        }
        return next;
    }

    static JSONArray inbounds(JSONObject s)throws Exception{
        int mtu=s.optInt("mtu",1500);if(mtu<1280||mtu>9000)throw new IllegalArgumentException(L.t("MTU должен быть от 1280 до 9000.","MTU must be between 1280 and 9000."));
        JSONArray a=Json.arr(Json.obj("tag","tun-in","port",0,"protocol","tun","settings",Json.obj("name","wraith","desc","Wraith","mtu",mtu),"sniffing",Json.obj("enabled",true,"routeOnly",true,"destOverride",Json.arr("http","tls","quic"))));
        if(s.optBoolean("localProxy",false)){
            boolean lan=s.optBoolean("allowLan");int port=s.optInt("proxyPort",2081);String listen=lan?"0.0.0.0":"127.0.0.1";
            if(!lan)a.put(Json.obj("tag","socks","listen",listen,"port",port,"protocol","socks","settings",Json.obj("auth","noauth","udp",true)));
            else{
                // A LAN-reachable proxy without credentials is an open relay for anyone on the
                // same Wi-Fi. The sing-box builder already required the password; Xray did not.
                String password=s.optString("proxyPassword");if(password.length()<8)throw new IllegalArgumentException(L.t("Для доступа из LAN задайте пароль прокси не короче 8 символов.","For LAN access set a proxy password of at least 8 characters."));
                a.put(Json.obj("tag","socks","listen",listen,"port",port,"protocol","socks","settings",Json.obj("auth","password","accounts",Json.arr(Json.obj("user","wraith","pass",password)),"udp",true)));
                a.put(Json.obj("tag","http","listen",listen,"port",s.optInt("httpProxyPort",2082),"protocol","http","settings",Json.obj("accounts",Json.arr(Json.obj("user","wraith","pass",password)))));
            }
        }
        return a;
    }
    private static String ensureDnsOutbound(JSONObject root)throws Exception{
        JSONArray outbounds=root.optJSONArray("outbounds");if(outbounds==null)throw new IllegalArgumentException(L.t("В конфигурации Xray нет outbounds.","The Xray configuration has no outbounds."));
        for(int i=0;i<outbounds.length();i++){JSONObject outbound=outbounds.optJSONObject(i);if(outbound!=null&&outbound.optString("protocol").equals("dns")){String tag=outbound.optString("tag");if(!tag.isEmpty())return tag;tag="wraith-dns";outbound.put("tag",tag);return tag;}}
        String tag=uniqueTag(outbounds,"wraith-dns");
        outbounds.put(Json.obj("protocol","dns","tag",tag,"settings",Json.obj("nonIPQuery","skip")));return tag;
    }
    private static String[] ensureAppOutbounds(JSONObject root,JSONObject profile)throws Exception{
        JSONArray outbounds=root.optJSONArray("outbounds");if(outbounds==null)throw new IllegalArgumentException(L.t("В конфигурации Xray нет outbounds.","The Xray configuration has no outbounds."));
        String proxy=proxyTag(root,profile),direct="",block="";
        for(int i=0;i<outbounds.length();i++){
            JSONObject o=outbounds.optJSONObject(i);if(o==null)continue;String protocol=o.optString("protocol"),tag=o.optString("tag");
            if(protocol.equals("freedom")&&direct.isEmpty()&&!tag.isEmpty())direct=tag;
            if(protocol.equals("blackhole")&&block.isEmpty()&&!tag.isEmpty())block=tag;
        }
        if(proxy.isEmpty())throw new IllegalArgumentException(L.t("В конфигурации Xray нет прокси-outbound.","The Xray configuration has no proxy outbound."));
        if(direct.isEmpty()){direct=uniqueTag(outbounds,"direct");outbounds.put(Json.obj("protocol","freedom","tag",direct,"settings",Json.obj("domainStrategy","UseIPv4")));}
        if(block.isEmpty()){block=uniqueTag(outbounds,"block");outbounds.put(Json.obj("protocol","blackhole","tag",block,"settings",new JSONObject()));}
        return new String[]{proxy,direct,block};
    }
    private static String uniqueTag(JSONArray outbounds,String base){String candidate=base;for(int suffix=2;hasTag(outbounds,candidate);suffix++)candidate=base+"-"+suffix;return candidate;}
    private static boolean hasTag(JSONArray outbounds,String tag){for(int i=0;i<outbounds.length();i++){JSONObject o=outbounds.optJSONObject(i);if(o!=null&&tag.equals(o.optString("tag")))return true;}return false;}
    /** The resolver address announced to Android for the TUN; all port-53 traffic is hijacked to Xray's DNS anyway. */
    static String androidDns(JSONObject state,JSONObject profile,JSONObject settings){
        if(settings.optBoolean("dnsFromJson",false)){
            String pinned=RoutingConfig.dnsPinnedIp(RoutingConfig.profileFor(state,profile),"Remote");if(!pinned.isEmpty())return pinned;
            String source=profile.optString("source","");if(source.startsWith("{"))try{JSONObject root=new JSONObject(source),dns=root.optJSONObject("dns");JSONArray servers=dns==null?null:dns.optJSONArray("servers");if(servers!=null&&servers.length()>0){String host=dnsHost(servers.opt(0));if(isIp(host))return host;}}catch(Exception ignored){}
            String host=dnsHost(RoutingConfig.dnsAddress(RoutingConfig.profileFor(state,profile),"Remote"));if(isIp(host))return host;
        }
        String custom=dnsHost(DnsChoice.custom(settings));if(isIp(custom))return custom;
        String phone=DnsChoice.firstSystem(settings);if(isIp(phone)&&!phone.contains(":"))return phone;
        return DNS_IP;
    }

    static String dnsEndpoint(String address){String value=address==null?"":address.trim();if(value.isEmpty())value=DNS_IP;if(value.indexOf(':')>=0&&!value.startsWith("["))value="["+value+"]";return value+":53";}
    private static String dnsHost(Object value){
        try{if(value instanceof JSONObject){JSONObject o=(JSONObject)value;return dnsHost(o.optString("address",o.optString("server")));}String s=String.valueOf(value).trim();if(isIp(s))return s;if(s.contains("://"))return URI.create(s).getHost();return s;}catch(Exception ignored){return "";}
    }
    private static boolean isIp(String value){return value!=null&&value.matches("[0-9a-fA-F:.]+")&&value.matches(".*[.:].*");}
    /** Provider DNS (with «DNS из JSON»), else the user's own DNS (with «Свой DNS»), else the phone's resolvers. */
    private static JSONObject dns(JSONObject p,JSONObject s)throws Exception{
        String value=s.optBoolean("dnsFromJson",false)?RoutingConfig.dnsAddress(p.optJSONObject("routing"),"Remote"):"";
        if(value.isEmpty())value=DnsChoice.custom(s);
        JSONArray servers=new JSONArray();
        if(!value.isEmpty())servers.put(value);else for(String ip:DnsChoice.system(s))servers.put(ip);
        if(servers.length()==0)servers.put(DnsChoice.DEFAULT_CUSTOM);
        return Json.obj("servers",servers,"queryStrategy","UseIP");
    }

    private static void appendRules(JSONArray out,JSONObject state,JSONObject profile,JSONObject settings,String[] tags)throws Exception{
        JSONObject routing=RoutingConfig.profileFor(state,profile);boolean global=settings.optString("mode","rules").equals("global")||RoutingConfig.globalProxy(routing);
        if(!settings.optString("mode","rules").equals("direct"))for(JSONObject rule:Json.objects(RoutingConfig.rulesFor(state,profile)))appendRule(out,rule,tags);
        if(!global&&routing!=null)out.put(Json.obj("type","field","inboundTag",Json.arr("tun-in"),"outboundTag",tags[1]));
    }
    private static void appendUserRules(JSONArray out,JSONObject state,String[] tags)throws Exception{for(JSONObject rule:Json.objects(state.optJSONArray("rules")))appendRule(out,rule,tags);}
    private static void appendRule(JSONArray out,JSONObject rule,String[] tags)throws Exception{
        if(!rule.optBoolean("enabled",true))return;JSONObject r=Json.obj("type","field");JSONArray domains=new JSONArray(),ips=new JSONArray();String kind=rule.optString("kind"),value=rule.optString("value");
        for(String item:ConfigBuilder.split(value,kind))switch(kind){case "domain":domains.put("full:"+item);break;case "domain_suffix":domains.put("domain:"+item);break;case "domain_keyword":domains.put("keyword:"+item);break;case "domain_regex":domains.put("regexp:"+item);break;case "geosite":domains.put("geosite:"+item);break;case "ip_cidr":ips.put(item);break;case "ip_is_private":ips.put("geoip:private");break;case "geoip":ips.put("geoip:"+item);break;default:break;}
        if(domains.length()>0)r.put("domain",domains);if(ips.length()>0)r.put("ip",ips);
        String port=rule.optString("port");if(!port.isEmpty())r.put("port",port);String network=rule.optString("network");if(!network.isEmpty())r.put("network",network);
        JSONArray protocols=rule.optJSONArray("protocol");if(protocols!=null&&protocols.length()>0)r.put("protocol",protocols);
        String action=rule.optString("action","proxy");r.put("outboundTag",action.equals("direct")?tags[1]:action.equals("block")?tags[2]:tags[0]);if(r.length()>2)out.put(r);
    }
}
