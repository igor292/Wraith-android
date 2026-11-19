package app.wraith.client;
import org.junit.Test;
import org.json.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ImportParserTest {
    @Test public void profileSummaryShowsTransportSecurityAndJsonOnlyForJson()throws Exception{
        JSONObject link=ImportParser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&pbk=key&sid=ab&type=grpc").get(0);
        assertEquals("VLESS / GRPC / REALITY",MainActivity.profileSummary(link));
    }
    private final String uuid="11111111-1111-4111-8111-111111111111";
    private String b64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    @Test public void realityKeepsTlsAndTransport()throws Exception{
        JSONObject p=ImportParser.parse("vless://"+uuid+"@edge.example:443?security=reality&pbk=publickey&sid=abcd&sni=www.example.org&fp=chrome&flow=xtls-rprx-vision&type=tcp#%D0%A2%D0%B5%D1%81%D1%82").get(0);
        assertEquals("Тест",p.getString("name"));JSONObject o=p.getJSONObject("outbound");assertEquals("publickey",o.getJSONObject("tls").getJSONObject("reality").getString("public_key"));assertEquals("xtls-rprx-vision",o.getString("flow"));
    }
    @Test public void shadowsocksSip002PasswordColon()throws Exception{JSONObject o=ImportParser.parse("ss://"+b64("aes-256-gcm:pass:word")+"@example.com:8388#Test").get(0).getJSONObject("outbound");assertEquals("pass:word",o.getString("password"));assertEquals(8388,o.getInt("server_port"));}
    @Test public void shadowsocksLegacy()throws Exception{assertEquals("test",ImportParser.parse("ss://"+b64("aes-128-gcm:test@example.com:8388")).get(0).getJSONObject("outbound").getString("password"));}
    @Test public void trojanPreservesPlus()throws Exception{assertEquals("a+b",ImportParser.parse("trojan://a+b@example.com:443?sni=example.com").get(0).getJSONObject("outbound").getString("password"));}
    @Test public void ipv6Vless()throws Exception{assertEquals("2001:db8::1",ImportParser.parse("vless://"+uuid+"@[2001:db8::1]:443?security=none").get(0).getJSONObject("outbound").getString("server"));}
    @Test public void subscriptionBase64()throws Exception{assertEquals(2,ImportParser.parse(b64("#profile-title: test\nsocks://host.example:1080\ntrojan://pass@example.com:443")).size());}
    @Test public void vmessWebsocket()throws Exception{JSONObject p=ImportParser.parse("vmess://"+b64(Json.obj("v","2","add","edge.example","port","443","id",uuid,"net","ws","path","/ws","host","cdn.example","tls","tls","ps","Test").toString())).get(0);assertEquals("/ws",p.getJSONObject("outbound").getJSONObject("transport").getString("path"));}
    @Test(expected=IllegalArgumentException.class)public void hysteriaObfuscationIsRefused()throws Exception{ImportParser.parse("hy2://secret@example.com:443?obfs=salamander&obfs-password=mask");}
    @Test(expected=IllegalArgumentException.class)public void singBoxJsonIsRefused()throws Exception{ImportParser.parse("{\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}],\"route\":{\"final\":\"direct\"}}");}
    @Test public void happPlainUrl()throws Exception{assertEquals("https://example.com/sub?a=1&b=2",ImportParser.unwrap("happ://add/https://example.com/sub?a=1&b=2"));}
    @Test(expected=IllegalArgumentException.class)public void rejectsHappCrypto(){ImportParser.unwrap("happ://crypt5/secret");}
    @Test(expected=IllegalArgumentException.class)public void rejectsXhttpInsteadOfDowngrading()throws Exception{ImportParser.parse("vless://"+uuid+"@example.com:443?type=xhttp");}
    @Test public void malformedLineIsReportedNotFatal()throws Exception{List<String> skipped=new ArrayList<>();List<JSONObject> parsed=ImportParser.parse("socks://example.com:1080\nvless://broken",skipped);assertEquals(1,parsed.size());assertEquals(1,skipped.size());}
    @Test(expected=IllegalArgumentException.class)public void emptySubscriptionStillFails()throws Exception{ImportParser.parse("vless://broken\nvless://alsobroken");}
    @Test(expected=IllegalArgumentException.class)public void rejectsXrayJsonWithoutOutbounds()throws Exception{ImportParser.parse("{\"inbounds\":[],\"routing\":{}}");}
    @Test public void emojiNameDoesNotBreakTheLink()throws Exception{
        JSONObject p=ImportParser.parse("vless://"+uuid+"@example.com:443?security=reality&pbk=key&sid=ab#🇩🇪 Germany 1 Gbit/s").get(0);
        assertEquals("🇩🇪 Germany 1 Gbit/s",p.getString("name"));assertEquals("example.com",p.getJSONObject("outbound").getString("server"));
    }
    @Test public void unpaddedBase64Subscription()throws Exception{
        String body="socks://host.example:1080\ntrojan://pass@example.com:443";
        String encoded=Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)).replace("=","");
        assertEquals(2,ImportParser.parse(encoded).size());
    }
    @Test public void realityDropsAlpnAndFixesUnknownFingerprint()throws Exception{
        JSONObject o=ImportParser.parse("vless://"+uuid+"@example.com:443?security=reality&pbk=key&sid=ab&fp=chrome_100&alpn=h2,http/1.1&flow=xtls-rprx-vision").get(0).getJSONObject("outbound");
        JSONObject tls=o.getJSONObject("tls");
        assertFalse(tls.has("alpn"));assertEquals("chrome",tls.getJSONObject("utls").getString("fingerprint"));assertEquals("xudp",o.getString("packet_encoding"));
    }
    @Test public void xrayConfigBecomesSingBoxNodeWithRouting()throws Exception{
        String xray="{\"remarks\":\"🇩🇪 Germany\",\"dns\":{\"queryStrategy\":\"UseIP\",\"servers\":[\"https://8.8.8.8/dns-query\"]},"
            +"\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":\"192.0.2.10\",\"port\":8443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\",\"flow\":\"xtls-rprx-vision\"}]}]},"
            +"\"streamSettings\":{\"network\":\"tcp\",\"security\":\"reality\",\"realitySettings\":{\"fingerprint\":\"edge\",\"publicKey\":\"pk\",\"serverName\":\"mirror.example\",\"shortId\":\"0123456789abcdef\"},\"tcpSettings\":{}}},"
            +"{\"protocol\":\"freedom\",\"tag\":\"direct\"},{\"protocol\":\"blackhole\",\"tag\":\"block\"}],"
            +"\"routing\":{\"domainStrategy\":\"IPIfNonMatch\",\"rules\":[{\"type\":\"field\",\"outboundTag\":\"direct\",\"domain\":[\"max.ru\",\"domain:vk.com\",\"full:go.yandex\",\"geosite:category-ru\"]}]}}";
        JSONObject p=ImportParser.parse(xray).get(0);
        assertFalse(p.getBoolean("full"));
        JSONObject o=p.getJSONObject("outbound");
        assertEquals("vless",o.getString("type"));assertEquals(8443,o.getInt("server_port"));assertEquals("xtls-rprx-vision",o.getString("flow"));
        JSONObject tls=o.getJSONObject("tls");
        assertEquals("mirror.example",tls.getString("server_name"));
        assertEquals("edge",tls.getJSONObject("utls").getString("fingerprint"));
        assertEquals("0123456789abcdef",tls.getJSONObject("reality").getString("short_id"));
        assertFalse(tls.has("alpn"));
        assertFalse(o.has("streamSettings"));
        assertEquals("https://8.8.8.8/dns-query",p.getJSONObject("routing").getString("RemoteDns"));
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONObject config=ConfigBuilder.build(state,"s","/tmp/log");
        String route=config.getJSONObject("route").toString();
        assertTrue(route.contains("\"domain_keyword\""));
        assertTrue(route.contains("vk.com"));
        assertFalse(route.contains(".vk.com")); // one domain_suffix entry, no second copy
        assertTrue(route.contains("go.yandex"));
        assertTrue(config.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("url").endsWith("geosite-category-ru.srs"));
        assertEquals("direct",config.getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getString("download_detour"));
        state.getJSONObject("settings").put("geoRules",false);
        JSONObject withGeoDisabled=config=ConfigBuilder.build(state,"s","/tmp/log");
        assertTrue(withGeoDisabled.getJSONObject("route").getJSONArray("rule_set").toString().contains("geosite-category-ru"));
    }
    @Test public void xrayGrpcPreservesLongServiceNameAndTlsSni()throws Exception{
        String service="v1.segment.long-token.ts.dna=west-20";
        JSONObject xray=Json.obj("protocol","vless","settings",Json.obj("vnext",Json.arr(Json.obj("address","192.0.2.20","port",443,"users",Json.arr(Json.obj("id",uuid,"encryption","none"))))),
            "streamSettings",Json.obj("network","grpc","security","tls","grpcSettings",Json.obj("serviceName",service,"mode","gun"),"tlsSettings",Json.obj("serverName","xtgrdrot2w.j.cloudfront.hls.ttvnw.net","fingerprint","edge")));
        JSONObject outbound=XrayConfig.outbound(xray);
        assertEquals(service,outbound.getJSONObject("transport").getString("service_name"));
        assertEquals("xtgrdrot2w.j.cloudfront.hls.ttvnw.net",outbound.getJSONObject("tls").getString("server_name"));
        assertEquals("edge",outbound.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"));
    }
    @Test public void xrayCertificatePinKeepsFullConfigOnNativeXray()throws Exception{
        String xray="{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"example.com\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]},\"streamSettings\":{\"network\":\"grpc\",\"security\":\"tls\",\"grpcSettings\":{\"serviceName\":\"svc\"},\"tlsSettings\":{\"serverName\":\"example.com\",\"pinnedPeerCertSha256\":\"4D:89:46\"}}}]}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        assertTrue(profile.optBoolean("xrayNativeOnly"));
        assertEquals("example.com",profile.getJSONObject("outbound").getString("server"));
        assertEquals("grpc",profile.getJSONObject("outbound").getJSONObject("transport").getString("type"));
        assertEquals("example.com",profile.getJSONObject("outbound").getJSONObject("tls").getString("server_name"));
        assertEquals("VLESS / GRPC / TLS | JSON",MainActivity.profileSummary(profile));
        assertEquals(CoreRouter.XRAY,CoreRouter.engine(profile,Json.obj("fragment",true)));
        JSONObject probe=ConfigBuilder.probeConfig(profile,new JSONObject());
        assertEquals("grpc",probe.getJSONArray("outbounds").getJSONObject(0).getJSONObject("transport").getString("type"));
        JSONObject runtime=XrayRuntimeConfig.build(Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",new JSONObject(),"rules",new JSONArray()),profile,123,null);
        assertEquals("4D:89:46",runtime.getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("pinnedPeerCertSha256"));
    }
    @Test public void happRoutingProfileGroupsDomainsIntoFewRules()throws Exception{
        JSONObject routing=Json.obj("Name","Test","DirectSites",Json.arr("domain:vk.com","domain:ok.ru","full:go.yandex","max.ru"),"DirectIp",Json.arr("77.88.0.0/18"));
        JSONArray rules=new JSONArray();RoutingConfig.append(rules,routing);
        // domain + domain_suffix + domain (full) merged, one keyword rule, one ip rule.
        assertEquals(4,rules.length());
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);p.put("routing",routing);
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("sniff",false,"bypassLan",false),"rules",new JSONArray());
        JSONArray out=ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").getJSONArray("rules");
        assertEquals(3,out.length()); // hijack-dns + one merged domain rule + one ip rule
        JSONObject set=ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").getJSONArray("rule_set").getJSONObject(0).getJSONArray("rules").getJSONObject(0);
        assertTrue(set.has("domain")&&set.has("domain_suffix")&&set.has("domain_keyword"));assertEquals("direct-domains",out.getJSONObject(1).getJSONArray("rule_set").getString(0));
        assertEquals("direct",out.getJSONObject(1).getString("outbound"));
    }
    @Test public void urltestOnlyExistsWhenAutoSelectIsOn()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        assertFalse(ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds").toString().contains("urltest"));
        state.getJSONObject("settings").put("autoSelect",true);
        assertTrue(ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds").toString().contains("urltest"));
    }
    @Test public void selectorDefaultsToTheChosenNode()throws Exception{
        JSONObject a=ImportParser.parse("socks://a.example:1080").get(0),b=ImportParser.parse("socks://b.example:1080").get(0);
        JSONObject state=Json.obj("profiles",Json.arr(a,b),"selected",b.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONArray outbounds=ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds");
        JSONObject selector=null;for(JSONObject o:Json.objects(outbounds))if("selector".equals(o.optString("type")))selector=o;
        assertEquals("node-"+b.getString("id"),selector.getString("default"));
    }
    @Test public void xrayCandidatesStayOnTheFirstHostUnlessAutoSelectIsRequested()throws Exception{
        JSONObject profile=ImportParser.parse("{\"remarks\":\"Auto\",\"outbounds\":["
            +"{\"protocol\":\"vless\",\"tag\":\"one\",\"settings\":{\"vnext\":[{\"address\":\"a.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}},"
            +"{\"protocol\":\"vless\",\"tag\":\"two\",\"settings\":{\"vnext\":[{\"address\":\"b.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}}]}").get(0);
        assertNotNull(profile.optJSONArray("xrayCandidates"));
        String first=ConfigBuilder.candidateTag(profile,0);
        assertEquals(first,ConfigBuilder.selectorTag(profile));
        JSONObject state=Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONArray outbounds=ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds");
        JSONObject selector=null;for(JSONObject o:Json.objects(outbounds))if("selector".equals(o.optString("type")))selector=o;
        assertEquals(first,selector.getString("default"));
        assertFalse(outbounds.toString().contains("urltest"));
        state.getJSONObject("settings").put("autoSelect",true);
        outbounds=ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds");
        assertTrue(outbounds.toString().contains("urltest"));
        assertEquals("pool-"+profile.getString("id"),ConfigBuilder.selectorTag(profile,true));
    }
    @Test public void xrayRuntimeKeepsProviderBalancerAutomaticAndPreservesRules()throws Exception{
        String xray="{\"remarks\":\"Pool\",\"outbounds\":["
            +"{\"protocol\":\"vless\",\"tag\":\"de\",\"settings\":{\"vnext\":[{\"address\":\"first.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}},"
            +"{\"protocol\":\"vless\",\"tag\":\"nl\",\"settings\":{\"vnext\":[{\"address\":\"second.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}},"
            +"{\"protocol\":\"freedom\",\"tag\":\"direct\"}],"
            +"\"routing\":{\"balancers\":[{\"tag\":\"pool\",\"selector\":[\"de\",\"nl\"],\"strategy\":{\"type\":\"random\"}}],"
            +"\"rules\":[{\"type\":\"field\",\"domain\":[\"domain:one.example\"],\"balancerTag\":\"pool\"},{\"type\":\"field\",\"domain\":[\"domain:two.example\"],\"outboundTag\":\"nl\"}]}}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        JSONObject state=Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONObject runtime=XrayRuntimeConfig.build(state,profile,123,null);
        JSONObject firstOutbound=runtime.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("de",firstOutbound.getString("tag"));
        assertEquals("first.example",firstOutbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"));
        JSONObject routing=runtime.getJSONObject("routing");
        assertEquals("de",routing.getJSONArray("balancers").getJSONObject(0).getJSONArray("selector").getString(0));
        JSONArray rules=routing.getJSONArray("rules");
        assertEquals("wraith-dns",rules.getJSONObject(0).getString("outboundTag"));
        assertEquals("pool",rules.getJSONObject(1).getString("balancerTag"));assertFalse(rules.getJSONObject(1).has("outboundTag"));
        assertEquals("nl",rules.getJSONObject(2).getString("outboundTag"));
    }
    @Test public void xrayObservatoryBalancerRoutesResolverIpsDirectly()throws Exception{
        String xray="{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":\"node.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}},{\"protocol\":\"freedom\",\"tag\":\"direct\"}],"
            +"\"dns\":{\"servers\":[\"https://8.8.8.8/dns-query\",{\"address\":\"1.1.1.1\"},\"https://dns.google/dns-query\"]},"
            +"\"observatory\":{\"subjectSelector\":[\"proxy\"]},\"routing\":{\"balancers\":[{\"tag\":\"auto\",\"selector\":[\"proxy\"]}],\"rules\":[]}}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        JSONObject runtime=XrayRuntimeConfig.build(Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",Json.obj("dnsFromJson",true),"rules",new JSONArray()),profile,123,null);
        JSONArray rules=runtime.getJSONObject("routing").getJSONArray("rules");
        assertEquals("tun-in",rules.getJSONObject(0).getJSONArray("inboundTag").getString(0));
        assertEquals("direct",rules.getJSONObject(1).getString("outboundTag"));
        assertEquals(Json.arr("8.8.8.8","1.1.1.1").toString(),rules.getJSONObject(1).getJSONArray("ip").toString());
    }
    @Test public void xrayDoesNotAddResolverBypassWithoutObservatoryAndBalancer()throws Exception{
        String xray="{\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"node.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}}],\"dns\":{\"servers\":[\"8.8.8.8\"]}}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        JSONObject runtime=XrayRuntimeConfig.build(Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",Json.obj("dnsFromJson",true),"rules",new JSONArray()),profile,123,null);
        JSONArray rules=runtime.getJSONObject("routing").getJSONArray("rules");
        assertEquals(1,rules.length());
        assertEquals("wraith-dns",rules.getJSONObject(0).getString("outboundTag"));
    }

    @Test public void duplicateSubscriptionEndpointsReceiveUniqueStableIds()throws Exception{
        JSONObject outbound=Json.obj("type","vless","server","same.example","server_port",443,"uuid","11111111-1111-1111-1111-111111111111");
        JSONObject oldA=Json.obj("id","old-a","outbound",outbound,"favorite",true,"latency",42,"latencyType","proxyGet");
        JSONObject oldB=Json.obj("id","old-b","outbound",outbound,"favorite",false,"latency",51,"latencyType","proxyGet");
        JSONObject nextA=Json.obj("id","new-a","outbound",outbound),nextB=Json.obj("id","new-b","outbound",outbound),nextC=Json.obj("id","new-c","outbound",outbound);
        Store.reconcileSubscriptionProfiles(List.of(oldA,oldB),List.of(nextA,nextB,nextC),new HashSet<>(List.of("outside-id")),"sub");
        assertEquals("old-a",nextA.getString("id"));assertEquals("old-b",nextB.getString("id"));
        assertTrue(nextA.optBoolean("favorite"));assertFalse("old latency is dropped: provider order until the next ping",nextA.has("latency"));
        Set<String> ids=new HashSet<>(List.of(nextA.getString("id"),nextB.getString("id"),nextC.getString("id")));
        assertEquals(3,ids.size());assertFalse(ids.contains("outside-id"));
    }
    @Test public void xrayNativeTlsPinAndComplexGrpcConfigRemainImportableAndPinned()throws Exception{
        String xray="{\"remarks\":\"Pinned gRPC\",\"outbounds\":["
            +"{\"protocol\":\"vless\",\"tag\":\"first\",\"settings\":{\"vnext\":[{\"address\":\"first.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]},"
            +"\"streamSettings\":{\"network\":\"grpc\",\"security\":\"tls\",\"grpcSettings\":{\"serviceName\":\"long/service/name\",\"mode\":\"gun\"},\"tlsSettings\":{\"serverName\":\"edge.example\",\"fingerprint\":\"edge\",\"pinnedPeerCertSha256\":\"4D:89:46:70\"}}},"
            +"{\"protocol\":\"vless\",\"tag\":\"second\",\"settings\":{\"vnext\":[{\"address\":\"second.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}}],"
            +"\"routing\":{\"rules\":[{\"type\":\"field\",\"domain\":[\"domain:example.com\"],\"outboundTag\":\"direct\"}]}}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        assertTrue(profile.optBoolean("xrayNativeOnly"));
        assertEquals("first.example",profile.getJSONObject("outbound").getString("server"));
        assertEquals(CoreRouter.XRAY,CoreRouter.engine(profile,Json.obj("fragment",true)));
        JSONObject state=Json.obj("profiles",Json.arr(profile),"selected",profile.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONObject runtime=XrayRuntimeConfig.build(state,profile,123,null);
        JSONObject first=runtime.getJSONArray("outbounds").getJSONObject(0);
        assertEquals("first.example",first.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"));
        JSONObject stream=first.getJSONObject("streamSettings");
        assertEquals("long/service/name",stream.getJSONObject("grpcSettings").getString("serviceName"));
        assertEquals("4D:89:46:70",stream.getJSONObject("tlsSettings").getString("pinnedPeerCertSha256"));
        assertEquals("direct",runtime.getJSONObject("routing").getJSONArray("rules").getJSONObject(1).getString("outboundTag"));
    }
    @Test public void xrayJsonVnextPoolIsExpandedInProviderOrder()throws Exception{
        String xray="{\"remarks\":\"Pool\",\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":["
            +"{\"address\":\"first.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]},"
            +"{\"address\":\"second.example\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}}]}";
        JSONObject profile=ImportParser.parse(xray).get(0);
        JSONArray candidates=profile.getJSONArray("xrayCandidates");
        assertEquals(2,candidates.length());
        assertEquals("first.example",candidates.getJSONObject(0).getString("server"));
        assertEquals("second.example",candidates.getJSONObject(1).getString("server"));
    }
    @Test public void xrayCandidateTagsAreScopedAcrossImportedJsonProfiles()throws Exception{
        String nodeTemplate="{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"HOST\",\"port\":443,\"users\":[{\"id\":\""+uuid+"\",\"encryption\":\"none\"}]}]}}";
        String aJson="{\"outbounds\":["+nodeTemplate.replace("HOST","a.example-1")+","+nodeTemplate.replace("HOST","a.example-2")+",{\"protocol\":\"freedom\"},{\"protocol\":\"blackhole\"}]}";
        String bJson="{\"outbounds\":["+nodeTemplate.replace("HOST","b.example-1")+","+nodeTemplate.replace("HOST","b.example-2")+",{\"protocol\":\"freedom\"},{\"protocol\":\"blackhole\"}]}";
        JSONObject a=ImportParser.parse(aJson).get(0);
        JSONObject b=ImportParser.parse(bJson).get(0);
        JSONObject state=Json.obj("profiles",Json.arr(a,b),"selected",a.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONArray outbounds=ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds");
        Set<String> tags=new HashSet<>();
        for(JSONObject outbound:Json.objects(outbounds))assertTrue("duplicate tag: "+outbound.optString("tag"),tags.add(outbound.optString("tag")));
        assertEquals(ConfigBuilder.candidateTag(a,0),ConfigBuilder.selectorTag(a));
        assertEquals(ConfigBuilder.candidateTag(b,0),ConfigBuilder.selectorTag(b));
        assertNotEquals(ConfigBuilder.candidateTag(a,0),ConfigBuilder.candidateTag(b,0));
    }
    @Test public void dnsHostsAndDomesticDnsAreTranslated()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        p.put("routing",Json.obj("Name","T","RemoteDns","https://1.1.1.1/dns-query","DomesticDns","77.88.8.8","DnsHosts",Json.obj("blocked.example","127.0.0.1"),"DirectSites",Json.arr("domain:vk.com")));
        JSONObject dns=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("dnsFromJson",true),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns");
        assertEquals("1.1.1.1",dns.getJSONArray("servers").getJSONObject(1).getString("server"));
        assertTrue(dns.getJSONArray("servers").toString().contains("hosts"));
        assertTrue(dns.getJSONArray("rules").toString().contains("local-dns"));
    }
    @Test(expected=IllegalArgumentException.class)public void muxRejectsXudp()throws Exception{ConfigBuilder.multiplex(Json.obj("muxProtocol","xudp"));}

    // --- Happ compatibility (https://docs.happ.info/main/dev-docs) ---

    @Test public void routingLinkInThePayloadIsBoundToItsHosts()throws Exception{
        String routing=Base64.getEncoder().encodeToString(Json.obj("Name","Ru","DirectSites",Json.arr("domain:vk.com")).toString().getBytes(StandardCharsets.UTF_8));
        List<JSONObject> parsed=ImportParser.parse("happ://routing/onadd/"+routing+"\nsocks://a.example:1080\nsocks://b.example:1080");
        assertEquals(2,parsed.size());
        assertEquals("Ru",parsed.get(0).getJSONObject("routing").getString("Name"));
        JSONObject state=Json.obj("profiles",Json.arr(parsed.get(0)),"selected",parsed.get(0).getString("id"),"settings",Json.obj("sniff",false,"bypassLan",false),"rules",new JSONArray());
        assertTrue(ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").toString().contains("vk.com"));
    }
    @Test public void routingOffClearsTheProfile()throws Exception{
        List<JSONObject> parsed=ImportParser.parse("happ://routing/off\nsocks://a.example:1080");
        assertFalse(parsed.get(0).has("routing"));
    }
    @Test public void happDnsTripleIsUsed()throws Exception{
        JSONObject routing=Json.obj("Name","T","RemoteDNSType","DoH","RemoteDNSDomain","https://cloudflare-dns.com/dns-query","RemoteDNSIP","1.1.1.1","DomesticDNSType","DoU","DomesticDNSIP","8.8.8.8");
        assertEquals("https://cloudflare-dns.com/dns-query",RoutingConfig.dnsAddress(routing,"Remote"));
        assertEquals("8.8.8.8",RoutingConfig.dnsAddress(routing,"Domestic"));
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);p.put("routing",routing);
        JSONObject dns=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("dnsFromJson",true),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns");
        JSONObject remote=dns.getJSONArray("servers").getJSONObject(1);
        // The pinned IP is dialled and the hostname only names the certificate, like Happ's DnsHosts pair.
        assertEquals("1.1.1.1",remote.getString("server"));
        assertEquals("cloudflare-dns.com",remote.getJSONObject("tls").getString("server_name"));
        assertTrue(dns.getJSONArray("servers").toString().contains("8.8.8.8"));
    }
    @Test public void legacyShortDnsFieldStillWorks()throws Exception{
        assertEquals("https://8.8.8.8/dns-query",RoutingConfig.dnsAddress(Json.obj("RemoteDns","https://8.8.8.8/dns-query"),"Remote"));
    }
    @Test public void globalProxyFalseKeepsUnmatchedTrafficDirect()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        p.put("routing",Json.obj("Name","T","GlobalProxy","false","ProxySites",Json.arr("domain:youtube.com")));
        JSONObject route=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("route");
        assertEquals("direct",route.getString("final"));
    }
    @Test public void routingDisabledByProviderIsIgnored()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);p.put("group","sub1");
        JSONObject sub=Json.obj("id","sub1","routing","happ://routing/add/"+Base64.getEncoder().encodeToString(Json.obj("Name","X","DirectSites",Json.arr("domain:vk.com")).toString().getBytes(StandardCharsets.UTF_8)),"routingEnabled",false);
        assertNull(RoutingConfig.profileFor(Json.obj("subscriptions",Json.arr(sub)),p));
    }
    @Test public void serverDescriptionReplacesTheProtocolLabel()throws Exception{
        String description=Base64.getEncoder().encodeToString("Happ the best".getBytes(StandardCharsets.UTF_8));
        JSONObject p=ImportParser.parse("vless://"+uuid+"@example.com:443?security=none#title?serverDescription="+description).get(0);
        assertEquals("title",p.getString("name"));
        assertEquals("Happ the best",p.getString("description"));
    }
    @Test public void plainServerDescriptionIsNotMangled(){assertEquals("Premium RU",ImportParser.describe("Premium RU"));}
    @Test public void hysteriaPortHopping()throws Exception{
        JSONObject o=ImportParser.parse("hy2://secret@example.com:5000-6000?sni=example.com&mportHopInt=15").get(0).getJSONObject("outbound");
        assertEquals("5000:6000",o.getJSONArray("server_ports").getString(0));
        assertEquals("15s",o.getString("hop_interval"));
        assertEquals(5000,o.getInt("server_port"));
        JSONArray ports=ImportParser.parse("hy2://secret@example.com:443?mport=1234,5000-6000,7044").get(0).getJSONObject("outbound").getJSONArray("server_ports");
        assertEquals(3,ports.length());assertEquals("1234:1234",ports.getString(0));assertEquals("5000:6000",ports.getString(1));
    }
    @Test public void socksAcceptsHappBase64Forms()throws Exception{
        assertEquals("pass",ImportParser.parse("socks://"+b64("user:pass")+"@1.2.3.4:443#Name").get(0).getJSONObject("outbound").getString("password"));
        JSONObject full=ImportParser.parse("socks://"+b64("user:pass@1.2.3.4:443")).get(0).getJSONObject("outbound");
        assertEquals("1.2.3.4",full.getString("server"));assertEquals(443,full.getInt("server_port"));assertEquals("user",full.getString("username"));
    }
    /** The owner's real subscription shape: a JSON array of full Xray configs, one per server. */
    @Test public void happJsonSubscriptionWithXrayHysteria()throws Exception{
        String finland="{\"dns\":{\"queryStrategy\":\"UseIP\",\"servers\":[\"https://8.8.8.8/dns-query\"]},\"meta\":null,"
            +"\"outbounds\":[{\"protocol\":\"hysteria\",\"settings\":{\"address\":\"192.0.2.30\",\"port\":33367,\"version\":2},"
            +"\"streamSettings\":{\"hysteriaSettings\":{\"auth\":\"secret\",\"version\":2},\"network\":\"hysteria\",\"security\":\"tls\","
            +"\"tlsSettings\":{\"fingerprint\":\"firefox\",\"serverName\":\"cloud.example\"}},\"tag\":\"proxy\"},"
            +"{\"protocol\":\"freedom\",\"tag\":\"direct\"}],\"remarks\":\"Finland\","
            +"\"routing\":{\"rules\":[{\"domain\":[\"domain:vk.com\"],\"outboundTag\":\"direct\",\"type\":\"field\"}]}}";
        List<JSONObject> parsed=ImportParser.parse("["+finland+"]");
        JSONObject o=parsed.get(0).getJSONObject("outbound");
        assertEquals("hysteria2",o.getString("type"));assertEquals("secret",o.getString("password"));assertEquals(33367,o.getInt("server_port"));
        assertEquals("cloud.example",o.getJSONObject("tls").getString("server_name"));
        assertFalse(o.getJSONObject("tls").has("utls"));
        JSONObject state=Json.obj("profiles",Json.arr(parsed.get(0)),"selected",parsed.get(0).getString("id"),"settings",Json.obj("sniff",false,"bypassLan",false),"rules",new JSONArray());
        assertTrue(ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").toString().contains("vk.com"));
        assertEquals("HYSTERIA",MainActivity.protocolLabel("hysteria2"));
    }
    /** Direct domains must not be resolved through the proxy: foreign GeoDNS answers get reset. */
    @Test public void directDomainsResolveOutsideTheTunnel()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        p.put("routing",Json.obj("Name","T","RemoteDns","https://8.8.8.8/dns-query","Rules",Json.arr(Json.obj("action","direct","domain",Json.arr("domain:vk.com","max.ru")))));
        JSONObject dns=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("ipPreference","auto"),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns");
        JSONObject local=null;for(JSONObject server:Json.objects(dns.getJSONArray("servers")))if(server.optString("tag").equals("local-dns"))local=server;
        assertEquals(ConfigBuilder.DOMESTIC_DNS,local.getString("server"));assertFalse(local.has("detour"));
        JSONObject rule=dns.getJSONArray("rules").getJSONObject(1); // index 0 is the AAAA block
        assertEquals("local-dns",rule.getString("server"));
        assertEquals("direct-domains",rule.getJSONArray("rule_set").getString(0));
    }
    /** Owner's log: yandex.ru → AAAA → direct dial over IPv6-less Wi-Fi → "network is unreachable" → reset. */
    @Test public void directDomainsGetNoIpv6Answers()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        p.put("routing",Json.obj("Name","T","Rules",Json.arr(Json.obj("action","direct","domain",Json.arr("domain:yandex.ru")))));
        JSONArray rules=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("ipPreference","auto"),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns").getJSONArray("rules");
        JSONObject block=rules.getJSONObject(rules.length()-2),route=rules.getJSONObject(rules.length()-1);
        assertEquals("predefined",block.getString("action"));
        assertTrue(block.getJSONArray("query_type").toString().contains("AAAA"));
        assertEquals("local-dns",route.getString("server")); // after the AAAA rule, so A still resolves locally
        JSONArray ipv4=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("ipPreference","ipv4"),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns").getJSONArray("rules");
        assertFalse(ipv4.getJSONObject(0).has("rule_set")); // global AAAA block in explicit IPv4 mode
    }
    /** Owner's log: everything, Telegram included, went direct — a header profile applied to a JSON subscription. */
    @Test public void jsonSubscriptionIgnoresHeaderRoutingProfile()throws Exception{
        String header="happ://routing/onadd/"+b64(Json.obj("Name","H","GlobalProxy","false","ProxySites",Json.arr("domain:youtube.com")).toString());
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);p.put("group","s");
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray(),
            "subscriptions",Json.arr(Json.obj("id","s","routing",header,"format","json")));
        assertNull(RoutingConfig.profileFor(state,p));
        assertEquals("proxy",ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").getString("final"));
        state.getJSONArray("subscriptions").getJSONObject(0).put("format","links"); // link subscriptions still honour it
        assertEquals("direct",ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").getString("final"));
    }
    @Test public void globalProxyFalseWithoutProxyRulesIsIgnored()throws Exception{
        assertTrue(RoutingConfig.globalProxy(Json.obj("Name","X","GlobalProxy","false")));
        assertFalse(RoutingConfig.globalProxy(Json.obj("Name","X","GlobalProxy","false","ProxyIp",Json.arr("8.8.8.8/32"))));
    }
    /** Auto is the default: prefer IPv4 while retaining IPv6 answers for dual-stack networks. */
    @Test public void automaticIpPreferenceIsTheDefault()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        JSONObject dns=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns");
        assertEquals("prefer_ipv4",dns.getString("strategy"));
        assertEquals(0,dns.getJSONArray("rules").length());
    }
    @Test public void settingsReachTheConfig()throws Exception{
        JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);
        JSONObject c=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("quic","reject","proxyPort",10808,"excludedRoutes","10.0.0.0/8\n192.168.0.0/16","dnsFromJson",false,"customDns",true,"localProxy",true,"tunnelDns","https://1.1.1.1/dns-query"),"rules",new JSONArray()),"s","/tmp/log");
        assertTrue(c.getJSONObject("route").getJSONArray("rules").toString().contains("\"port\":[443]"));
        assertEquals(10808,c.getJSONArray("inbounds").getJSONObject(1).getInt("listen_port"));
        assertEquals(2,c.getJSONArray("inbounds").getJSONObject(0).getJSONArray("route_exclude_address").length());
        assertEquals("1.1.1.1",c.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("server"));
    }
    /** Happ's Xray fragmentation and noises go to the patched dialer; Vision nodes get them too. */
    @Test public void xrayFragmentAndNoiseReachTheOutbound()throws Exception{
        JSONObject vless=ImportParser.parse("vless://"+uuid+"@example.com:443?security=reality&pbk=key&sid=ab&flow=xtls-rprx-vision").get(0);
        JSONObject hy=ImportParser.parse("hy2://secret@example.com:443").get(0);
        JSONObject s=Json.obj("fragment",true,"fragmentPackets","tlshello","fragmentLength","50-100","fragmentInterval","10-20","fragmentMaxSplit","100-200","noise",true,"noiseType","array","noisePacket","1A,2B,3C","noiseDelay","10-16");
        JSONObject v=ConfigBuilder.build(Json.obj("profiles",Json.arr(vless),"selected",vless.getString("id"),"settings",s,"rules",new JSONArray()),"s","/tmp/log").getJSONArray("outbounds").getJSONObject(0);
        assertEquals("50-100",v.getJSONObject("wraith_fragment").getString("length"));
        assertFalse(v.getJSONObject("tls").has("fragment")); // sing-box's own fragmentation stays off
        JSONObject h=ConfigBuilder.build(Json.obj("profiles",Json.arr(hy),"selected",hy.getString("id"),"settings",s,"rules",new JSONArray()),"s","/tmp/log").getJSONArray("outbounds").getJSONObject(0);
        assertFalse(h.has("wraith_fragment")); // UDP protocol: no TCP fragmentation
        assertEquals("1A,2B,3C",h.getJSONArray("wraith_noise").getJSONObject(0).getString("packet"));
    }
    @Test(expected=IllegalArgumentException.class)public void badNoisePacketIsRejectedEarly()throws Exception{ConfigBuilder.wraithNoise(Json.obj("noiseType","array","noisePacket","ZZ,1"));}
    @Test(expected=IllegalArgumentException.class)public void badFragmentRangeIsRejectedEarly()throws Exception{ConfigBuilder.wraithFragment(Json.obj("fragmentLength","100-50"));}
    @Test public void domainsAreLowercasedAndPunycoded(){
        assertEquals("sberid.ru",RoutingConfig.asciiDomain("sberId.ru"));
        assertEquals("xn--80ajghhoc2aj1c8b.xn--p1ai",RoutingConfig.asciiDomain("честныйзнак.рф"));
        assertEquals(".xn--80ajghhoc2aj1c8b.xn--p1ai",RoutingConfig.asciiDomain(".честныйзнак.рф"));
    }
    @Test public void geoNamesAreTranslatedOrSkipped(){
        assertEquals("category-ads-all",ConfigBuilder.geoName("geosite","ads"));
        assertEquals("cn",ConfigBuilder.geoName("geoip","CN"));
        assertEquals("",ConfigBuilder.geoName("geoip","amazon"));
        assertEquals("",ConfigBuilder.geoName("geosite","!cn"));
    }
    @Test public void generatedConfigUsesNewDnsSchema()throws Exception{JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("ipPreference","ipv4"),"rules",new JSONArray());JSONObject c=ConfigBuilder.build(state,"secret","/tmp/core.log");assertEquals("https",c.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).getString("type"));assertEquals(2,c.getJSONArray("inbounds").getJSONObject(0).getJSONArray("address").length());assertTrue(c.getJSONObject("dns").getJSONArray("rules").getJSONObject(0).toString().contains("AAAA"));}
    @Test public void directDnsHasNoRedundantDirectDetour()throws Exception{JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("mode","direct"),"rules",new JSONArray());JSONArray servers=ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("dns").getJSONArray("servers");assertFalse(servers.getJSONObject(0).has("detour"));assertFalse(servers.getJSONObject(1).has("detour"));}
    @Test(expected=IllegalArgumentException.class)public void lanNeedsPassword()throws Exception{ConfigBuilder.inbounds(Json.obj("localProxy",true,"allowLan",true));}
    @Test public void ruleOrderAndBlockAction()throws Exception{JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("sniff",false,"bypassLan",false),"rules",Json.arr(Json.obj("kind","domain_suffix","value","ads.example","action","block"),Json.obj("kind","ip_cidr","value","203.0.113.0/24","action","direct")));JSONArray r=ConfigBuilder.build(state,"s","/tmp/log").getJSONObject("route").getJSONArray("rules");assertEquals("reject",r.getJSONObject(1).getString("action"));assertEquals("direct",r.getJSONObject(2).getString("outbound"));}
    @Test public void multiplexLimitsAreMutuallyExclusive()throws Exception{JSONObject connections=ConfigBuilder.multiplex(Json.obj("muxConnections",8));assertEquals(8,connections.getInt("max_connections"));assertFalse(connections.has("max_streams"));JSONObject streams=ConfigBuilder.multiplex(Json.obj("muxLimit","streams","muxStreams",16));assertEquals(16,streams.getInt("max_streams"));assertFalse(streams.has("max_connections"));}
    @Test(expected=IllegalArgumentException.class)public void rejectsInvalidMuxLimit()throws Exception{ConfigBuilder.multiplex(Json.obj("muxConnections",-1));}
    @Test public void dpiSettingsPreserveTlsCredentials()throws Exception{JSONObject p=ImportParser.parse("trojan://secret@example.com:443?sni=example.com").get(0);JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj("fragment",true,"fragmentType","singbox","recordFragment",true,"fragmentDelay",20,"mux",true),"rules",new JSONArray());JSONObject out=ConfigBuilder.build(state,"s","/tmp/log").getJSONArray("outbounds").getJSONObject(0);assertEquals("secret",out.getString("password"));assertTrue(out.getJSONObject("tls").getBoolean("fragment"));assertTrue(out.getJSONObject("tls").getBoolean("record_fragment"));assertEquals("20ms",out.getJSONObject("tls").getString("fragment_fallback_delay"));assertEquals("h2mux",out.getJSONObject("multiplex").getString("protocol"));}
}
