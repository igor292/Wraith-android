package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Provider configs with several servers behind an automatic Xray balancer (leastPing / observatory). */
public class XrayBalancerConfigTest {
    static final String SOURCE="""
        {"remarks":"Auto","dns":{"servers":["1.1.1.1"]},
         "inbounds":[
           {"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":10808,"settings":{"udp":true}},
           {"tag":"http-in","protocol":"http","listen":"127.0.0.1","port":10809},
           {"tag":"dns-in","protocol":"dokodemo-door","listen":"127.0.0.1","port":5353,"settings":{"address":"1.1.1.1","port":53,"network":"udp"}}
         ],
         "outbounds":[
           {"tag":"proxy-nl","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
           {"tag":"proxy-de","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.2","port":8443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
           {"tag":"proxy-fi","protocol":"trojan","settings":{"servers":[{"address":"203.0.113.3","port":443,"password":"secret"}]},"streamSettings":{"security":"tls"}},
           {"tag":"direct","protocol":"freedom"},
           {"tag":"block","protocol":"blackhole"},
           {"tag":"dns-out","protocol":"dns"}
         ],
         "observatory":{"subjectSelector":["proxy-"],"probeUrl":"https://www.gstatic.com/generate_204","probeInterval":"1m"},
         "routing":{"domainStrategy":"IPIfNonMatch",
           "balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"leastPing"},"fallbackTag":"proxy-nl"}],
           "rules":[
             {"type":"field","inboundTag":["dns-in"],"outboundTag":"dns-out"},
             {"type":"field","domain":["domain:example.ru"],"outboundTag":"direct"},
             {"type":"field","inboundTag":["socks-in","http-in"],"balancerTag":"auto"}
           ]}}
        """;

    static JSONObject build(JSONObject settings)throws Exception{
        JSONObject profile=XrayConfig.profile(new JSONObject(SOURCE),SOURCE);
        profile.put("id","p1");
        JSONObject state=Json.obj("profiles",Json.arr(profile),"subscriptions",new JSONArray(),"rules",new JSONArray(),"settings",settings,"selected","p1");
        return XrayRuntimeConfig.build(state,profile,42,null);
    }
    static JSONObject ruleWith(JSONObject config,String key,String value)throws Exception{
        for(JSONObject rule:Json.objects(config.getJSONObject("routing").getJSONArray("rules")))if(value.equals(rule.optString(key)))return rule;
        return null;
    }

    @Test public void importKeepsAllServersAsOneProfile()throws Exception{
        JSONObject profile=XrayConfig.profile(new JSONObject(SOURCE),SOURCE);
        assertEquals(3,profile.getJSONArray("xrayCandidates").length());
    }

    @Test public void providerBalancerStaysAutomaticByDefault()throws Exception{
        JSONObject config=build(new JSONObject());
        // The balancer rule must not be rewritten into a fixed first server.
        JSONObject rule=ruleWith(config,"balancerTag","auto");
        assertNotNull("balancer rule was replaced by a fixed outbound",rule);
        assertFalse(rule.has("outboundTag"));
        int proxies=0;for(JSONObject o:Json.objects(config.getJSONArray("outbounds")))if(o.optString("tag").startsWith("proxy-"))proxies++;
        assertEquals(3,proxies);
        assertNotNull(config.optJSONObject("observatory"));
    }

    @Test public void rulesForProviderLocalInboundsAlsoMatchTheTunnel()throws Exception{
        JSONObject config=build(new JSONObject());
        JSONArray inbound=ruleWith(config,"balancerTag","auto").getJSONArray("inboundTag");
        assertTrue("tunnel traffic never reached the balancer",inbound.toString().contains("\"tun-in\""));
        // The provider's DNS inlet is not a proxy inlet: mapping it would send everything to dns-out.
        JSONArray dns=null;for(JSONObject rule:Json.objects(config.getJSONObject("routing").getJSONArray("rules")))if(rule.optJSONArray("inboundTag")!=null&&rule.getJSONArray("inboundTag").toString().contains("dns-in"))dns=rule.getJSONArray("inboundTag");assertNotNull(dns);
        assertFalse(dns.toString().contains("\"tun-in\""));
    }

    @Test public void globalModeUsesTheBalancerNotOneServer()throws Exception{
        JSONObject config=build(Json.obj("mode","global"));
        JSONArray rules=config.getJSONObject("routing").getJSONArray("rules");
        JSONObject global=rules.getJSONObject(1);
        assertEquals("tun-in",global.getJSONArray("inboundTag").getString(0));
        assertEquals("auto",global.optString("balancerTag"));
    }

    static final String PLAIN="""
        {"remarks":"Pool","outbounds":[
          {"tag":"a","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
          {"tag":"b","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.2","port":443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]},"streamSettings":{"sockopt":{"dialerProxy":"hop"}}},
          {"tag":"hop","protocol":"socks","settings":{"servers":[{"address":"203.0.113.9","port":1080}]}},
          {"tag":"direct","protocol":"freedom"}],
         "routing":{"rules":[{"type":"field","domain":["domain:ru"],"outboundTag":"direct"},{"type":"field","domain":["domain:example.com"],"outboundTag":"a"}]}}
        """;
    static JSONObject buildPlain(JSONObject settings)throws Exception{
        JSONObject profile=XrayConfig.profile(new JSONObject(PLAIN),PLAIN);profile.put("id","p2");
        JSONObject state=Json.obj("profiles",Json.arr(profile),"subscriptions",new JSONArray(),"rules",new JSONArray(),"settings",settings,"selected","p2");
        return XrayRuntimeConfig.build(state,profile,42,null);
    }

    @Test public void autoSelectBuildsBalancerForPlainMultiServerJson()throws Exception{
        JSONObject config=buildPlain(Json.obj("autoSelect",true));
        assertTrue(XrayRuntimeConfig.hasBalancer(config,XrayRuntimeConfig.AUTO_BALANCER));
        JSONArray selector=null;for(JSONObject b:Json.objects(config.getJSONObject("routing").getJSONArray("balancers")))if(b.optString("tag").equals(XrayRuntimeConfig.AUTO_BALANCER))selector=b.getJSONArray("selector");
        // The dialer hop "hop" is not a server of its own.
        assertEquals("[\"a\",\"b\"]",selector.toString());
        assertNotNull(config.optJSONObject("observatory"));
        assertNotNull(ruleWith(config,"balancerTag",XrayRuntimeConfig.AUTO_BALANCER));
        assertNotNull(ruleWith(config,"outboundTag","direct"));
    }

    @Test public void autoSelectIsEnabledByDefaultForPlainMultiServerJson()throws Exception{
        JSONObject config=buildPlain(new JSONObject());
        assertTrue(XrayRuntimeConfig.hasBalancer(config,XrayRuntimeConfig.AUTO_BALANCER));
        assertNotNull(config.optJSONObject("observatory"));
    }

    @Test public void explicitAutoSelectOffKeepsChosenServer()throws Exception{
        JSONObject config=buildPlain(Json.obj("autoSelect",false));
        assertFalse(XrayRuntimeConfig.hasBalancer(config,XrayRuntimeConfig.AUTO_BALANCER));
        assertEquals("a",config.getJSONArray("outbounds").getJSONObject(0).getString("tag"));
    }

    @Test public void probeOfOneCandidateIsStillPinned()throws Exception{
        JSONObject profile=XrayConfig.profile(new JSONObject(SOURCE),SOURCE);
        JSONObject second=Json.copy(profile);second.put("outbound",profile.getJSONArray("xrayCandidates").getJSONObject(1));second.remove("xrayCandidates");
        JSONObject probe=XrayProbeService.probeConfig(second,Json.obj("autoSelect",false),null);
        assertEquals("proxy-de",probe.getJSONArray("outbounds").getJSONObject(0).getString("tag"));
    }
}
