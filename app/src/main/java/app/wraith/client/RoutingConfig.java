package app.wraith.client;

import org.json.*;
import java.util.*;

/**
 * Resolves the routing profile attached to a server (an imported Xray configuration) or to its
 * subscription (a happ://routing/ link) into the intermediate rule list consumed by ConfigBuilder.
 *
 * Entries are grouped per match kind instead of one rule per domain: a provider list of 700 direct
 * domains becomes a handful of sing-box rules, exactly like the configuration Happ generates.
 */
public final class RoutingConfig {
    private RoutingConfig(){}

    /** Inline profile of an imported Xray config first, then the subscription's Happ profile. */
    public static JSONObject profileFor(JSONObject state,JSONObject profile){
        if(profile!=null){
            JSONObject inline=profile.optJSONObject("routing");
            if(inline!=null&&inline.length()>0)return inline;
        }
        String group=profile==null?"":profile.optString("group");
        if(group.isEmpty())return null;
        for(JSONObject sub:Json.objects(state.optJSONArray("subscriptions"))){
            if(!group.equals(sub.optString("id")))continue;
            // Happ "routing-enable: 0" — the provider switched routing off for this subscription.
            if(sub.has("routingEnabled")&&!sub.optBoolean("routingEnabled",true))return null;
            // Happ "restricted mode": for JSON subscriptions the routing profile sent alongside is not
            // applied as rules — each server's own JSON routing is. Applying it anyway put a
            // header profile's GlobalProxy=false on top and sent every connection direct.
            if(sub.optString("format").equals("json"))return null;
            String link=sub.optString("routing");
            if(link.isEmpty()||link.equals("happ://routing/off"))return null;
            // A broken provider profile must not make the whole tunnel unbuildable.
            try{ return decode(link); }
            catch(Exception e){ AppLog.add(L.t("Профиль маршрутизации подписки не разобран: ","Subscription routing profile could not be parsed: ")+Subscriptions.safeError(e)); return null; }
        }
        return null;
    }
    /**
     * Happ DNS address, assembled from the documented triple (DoH/DoT/DoQ/DoU + domain + ip).
     * Older profiles use the short RemoteDns/DomesticDns form, so both are accepted.
     * See https://docs.happ.info/main/dev-docs/routing — profile structure.
     */
    public static String dnsAddress(JSONObject routing,String prefix){
        if(routing==null)return "";
        String legacy=XrayConfig.str(routing,prefix+"Dns",prefix+"DNS");
        String kind=XrayConfig.str(routing,prefix+"DNSType",prefix+"DnsType").toUpperCase(Locale.ROOT);
        String domain=XrayConfig.str(routing,prefix+"DNSDomain",prefix+"DnsDomain");
        String ip=XrayConfig.str(routing,prefix+"DNSIP",prefix+"DnsIp",prefix+"DNSIp");
        if(domain.isEmpty()&&ip.isEmpty())return legacy;
        String scheme=kind.equals("DOH")||kind.equals("HTTPS")?"https":kind.equals("DOT")||kind.equals("TLS")?"tls":kind.equals("DOQ")||kind.equals("QUIC")?"quic":kind.equals("DOH3")||kind.equals("H3")?"h3":"";
        String host=domain.isEmpty()?ip:domain;
        if(host.contains("://"))return host;
        if(scheme.equals("https"))return "https://"+host+"/dns-query";
        if(!scheme.isEmpty())return scheme+"://"+host;
        return host;
    }
    /** IP the provider pinned for its DNS hostname, so no bootstrap lookup is needed. */
    public static String dnsPinnedIp(JSONObject routing,String prefix){
        if(routing==null)return "";
        String domain=XrayConfig.str(routing,prefix+"DNSDomain",prefix+"DnsDomain");
        if(domain.isEmpty())return "";
        String ip=XrayConfig.str(routing,prefix+"DNSIP",prefix+"DnsIp",prefix+"DNSIp");
        return ip.matches("[0-9a-fA-F:.]+")&&ip.matches(".*[.:].*")?ip:"";
    }
    /** Happ "GlobalProxy": false means unmatched traffic stays direct. */
    public static boolean globalProxy(JSONObject routing){
        if(routing==null)return true;
        Object value=routing.opt("GlobalProxy");
        if(value==null||value==JSONObject.NULL)return true;
        if(!String.valueOf(value).trim().equalsIgnoreCase("false"))return true;
        // GlobalProxy=false means "only ProxySites/ProxyIp use the tunnel". Without any such entry
        // it would silently turn the VPN off for everything, which is never what a provider means.
        JSONArray sites=routing.optJSONArray("ProxySites"),ips=routing.optJSONArray("ProxyIp");
        boolean hasProxyRules=(sites!=null&&sites.length()>0)||(ips!=null&&ips.length()>0);
        if(!hasProxyRules)AppLog.add(L.t("Профиль «","Profile «")+routing.optString("Name")+L.t("»: GlobalProxy=false без правил Proxy — игнорируется, неподошедший трафик идёт через VPN.","»: GlobalProxy=false without Proxy rules — ignored, unmatched traffic goes through the VPN."));
        return !hasProxyRules;
    }
    /** "Name · N записей" for the routing a profile gets, or a plain "no rules" statement. */
    public static String summary(JSONObject state,JSONObject profile){
        try{
            JSONObject routing=profileFor(state,profile);
            if(routing==null)return L.t("без правил провайдера","no provider rules");
            JSONArray rules=new JSONArray();append(rules,routing);
            int entries=0;
            for(JSONObject r:Json.objects(rules))entries+=ConfigBuilder.split(r.optString("value"),r.optString("kind")).size();
            return L.t("правила «","rules «")+routing.optString("Name",L.t("провайдер","provider"))+"» · "+entries+L.t(" записей"," entries");
        }catch(Exception e){return L.t("правила провайдера: ошибка","provider rules: error");}
    }
    public static JSONArray rulesFor(JSONObject state,JSONObject profile)throws Exception{
        JSONArray result=new JSONArray();
        JSONObject provider=profileFor(state,profile);
        if(provider!=null)append(result,provider);
        // User rules stay after provider rules: the provider decides first, the user refines.
        for(JSONObject rule:Json.objects(state.optJSONArray("rules")))result.put(Json.copy(rule));
        return result;
    }
    public static JSONObject decode(String link)throws Exception{
        if(!link.startsWith("happ://routing/add/")&&!link.startsWith("happ://routing/onadd/"))throw new IllegalArgumentException(L.t("Некорректный профиль маршрутизации","Invalid routing profile"));
        int slash=link.indexOf('/',"happ://routing/".length());
        return new JSONObject(ImportParser.base64(link.substring(slash+1).trim()));
    }
    static void append(JSONArray out,JSONObject json)throws Exception{
        String name=json.optString("Name",L.t("Профиль провайдера","Provider profile"));
        JSONArray ordered=json.optJSONArray("Rules");
        if(ordered!=null&&ordered.length()>0){
            for(JSONObject rule:Json.objects(ordered)){
                String action=rule.optString("action","proxy");
                emit(out,name,action,rule.optJSONArray("domain"),false,rule);
                emit(out,name,action,rule.optJSONArray("ip"),true,rule);
            }
            return;
        }
        for(String prefix:List.of("Block","Direct","Proxy"))for(String field:List.of("Sites","Ip")){
            JSONArray values=json.optJSONArray(prefix+field);
            if(values==null)continue;
            emit(out,name+" · "+prefix,prefix.equals("Block")?"block":prefix.equals("Direct")?"direct":"proxy",values,field.equals("Ip"),null);
        }
    }
    private static void emit(JSONArray out,String name,String action,JSONArray values,boolean addresses,JSONObject source)throws Exception{
        if(values==null||values.length()==0)return;
        Map<String,Set<String>> grouped=new LinkedHashMap<>();
        for(int i=0;i<values.length();i++){
            String value=String.valueOf(values.opt(i)).trim();
            if(value.isEmpty()||value.equals("null"))continue;
            if(value.startsWith("ext:")){AppLog.add(L.t("Правило пропущено: внешние geo-файлы Xray (","Rule skipped: external Xray geo files (")+value+L.t(") не поддерживаются.",") are not supported."));continue;}
            if(value.startsWith("geoip:")){String geo=value.substring(6).trim();if(geo.equalsIgnoreCase("private"))add(grouped,"ip_is_private","true");else add(grouped,"geoip",geo);continue;}
            if(value.startsWith("geosite:")){add(grouped,"geosite",value.substring(8).trim());continue;}
            if(addresses){add(grouped,"ip_cidr",value);continue;}
            if(value.startsWith("full:")){add(grouped,"domain",value.substring(5));continue;}
            if(value.startsWith("regexp:")){add(grouped,"domain_regex",value.substring(7));continue;}
            if(value.startsWith("keyword:")){add(grouped,"domain_keyword",value.substring(8));continue;}
            if(value.startsWith("domain:")){
                String domain=value.substring(7).trim();
                if(domain.isEmpty())continue;
                // Xray "domain:example.com" = example.com and its subdomains. sing-box domain_suffix
                // without a leading dot is stored with a root label and matches the domain itself or
                // at a dot boundary only (sing/common/domain/matcher.go): notexample.com does not
                // match. So one entry is exact — no second ".example.com" copy.
                add(grouped,"domain_suffix",domain);
                continue;
            }
            // A bare Xray domain entry is a substring match.
            add(grouped,"domain_keyword",value);
        }
        for(Map.Entry<String,Set<String>> entry:grouped.entrySet()){
            JSONObject rule=Json.obj("name",name,"kind",entry.getKey(),"value",String.join("\n",entry.getValue()),"action",action,"enabled",true,"provider",true);
            if(source!=null){
                String port=source.optString("port").trim();
                if(!port.isEmpty())rule.put("port",port);
                String network=source.optString("network").trim();
                if(!network.isEmpty())rule.put("network",network);
                JSONArray protocol=source.optJSONArray("protocol");
                if(protocol!=null&&protocol.length()>0)rule.put("protocol",new JSONArray(protocol.toString()));
            }
            out.put(rule);
        }
    }
    private static void add(Map<String,Set<String>> grouped,String kind,String value){
        String v=value.trim();
        if(v.isEmpty())return;
        if(kind.equals("domain")||kind.equals("domain_suffix")||kind.equals("domain_keyword"))v=asciiDomain(v);
        grouped.computeIfAbsent(kind,k->new LinkedHashSet<>()).add(v);
    }
    /**
     * Requests carry lower-case punycode names, so "sberId.ru" or "честныйзнак.рф" from a provider
     * list would never match as written. Xray normalises them; sing-box does not.
     */
    static String asciiDomain(String value){
        String lower=value.toLowerCase(Locale.ROOT);
        boolean dot=lower.startsWith(".");
        String body=dot?lower.substring(1):lower;
        if(!body.matches("[\\x00-\\x7F]*"))
            try{body=java.net.IDN.toASCII(body,java.net.IDN.ALLOW_UNASSIGNED);}catch(IllegalArgumentException keep){/* not a valid IDN: leave it */}
        return dot?"."+body:body;
    }
}
