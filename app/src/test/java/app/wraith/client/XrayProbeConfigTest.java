package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Via-proxy latency checks for every server of an automatic (balancer) config. */
public class XrayProbeConfigTest {
    /** Typical panel export: untagged direct, DNS bound to tags, geosite hosts, fakedns, balancer. */
    static final String AUTO="""
        {"remarks":"Auto","log":{"loglevel":"warning"},
         "dns":{"tag":"dns-query","servers":["fakedns",{"address":"https://1.1.1.1/dns-query","domains":["geosite:google"],"skipFallback":true},"8.8.8.8","localhost"],
                "hosts":{"geosite:category-ads":"127.0.0.1","dns.google":"8.8.8.8"},"queryStrategy":"UseIPv4"},
         "fakedns":[{"ipPool":"198.18.0.0/15","poolSize":65535}],
         "inbounds":[{"tag":"socks","protocol":"socks","listen":"127.0.0.1","port":10808,"settings":{"udp":true}}],
         "outbounds":[
           {"tag":"proxy-nl","protocol":"vless","settings":{"vnext":[{"address":"192.0.2.1","port":443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]},"streamSettings":{"sockopt":{"dialerProxy":"frag"}}},
           {"tag":"proxy-de","protocol":"vless","settings":{"vnext":[{"address":"198.51.100.2","port":8443,"users":[{"id":"00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption":"none"}]}]}},
           {"tag":"proxy-fi","protocol":"trojan","settings":{"servers":[{"address":"203.0.113.3","port":443,"password":"secret"}]},"streamSettings":{"security":"tls"}},
           {"protocol":"freedom"},
           {"tag":"frag","protocol":"freedom","settings":{"fragment":{"packets":"tlshello","length":"10-20","interval":"10-20"}}},
           {"tag":"block","protocol":"blackhole"}
         ],
         "observatory":{"subjectSelector":["proxy-"],"probeUrl":"https://www.gstatic.com/generate_204","probeInterval":"1m"},
         "routing":{"domainStrategy":"IPIfNonMatch",
           "balancers":[{"tag":"auto","selector":["proxy-"],"strategy":{"type":"leastPing"},"fallbackTag":"proxy-nl"}],
           "rules":[{"type":"field","inboundTag":["dns-query"],"balancerTag":"auto"},{"type":"field","inboundTag":["socks"],"balancerTag":"auto"}]}}
        """;

    /** Builds the probe runtime exactly as the UI does: one job per server of the pool. */
    static JSONObject runtime(String source)throws Exception{
        JSONObject profile=XrayConfig.profile(new JSONObject(source),source);
        JSONArray pool=profile.getJSONArray("xrayCandidates");
        JSONArray in=new JSONArray(),out=new JSONArray(),rules=new JSONArray();JSONObject dns=null;
        for(int i=0;i<pool.length();i++){
            JSONObject one=Json.copy(profile);one.put("outbound",pool.getJSONObject(i));one.remove("xrayCandidates");
            JSONObject d=XrayProbeService.appendProbe(one,Json.obj("autoSelect",false),XrayRuntimeConfig.source(one,null),i,20000+i,out,in,rules);
            if(dns==null)dns=d;
        }
        return XrayProbeService.assembleProbe(in,out,rules,dns,"assets");
    }

    @Test public void everyServerOfAnAutoConfigGetsItsOwnProbe()throws Exception{
        JSONObject r=runtime(AUTO);
        assertEquals(3,r.getJSONArray("inbounds").length());
        String outs=r.getJSONArray("outbounds").toString();
        assertTrue(outs.contains("192.0.2.1"));assertTrue(outs.contains("198.51.100.2"));assertTrue(outs.contains("203.0.113.3"));
        // The fragment dialer travels with the server that uses it, under its renamed tag.
        assertTrue(outs.contains("\"dialerProxy\":\"wraith-probe-0-0\""));
        // Unmatched traffic (and Xray's own DNS) leaves directly.
        assertEquals("wraith-probe-direct",r.getJSONArray("outbounds").getJSONObject(0).getString("tag"));
        for(JSONObject rule:Json.objects(r.getJSONObject("routing").getJSONArray("rules")))assertFalse(rule.has("balancerTag"));
        // Only plain resolvers survive from the provider DNS.
        JSONObject dns=r.getJSONObject("dns");
        assertEquals("[\"https://1.1.1.1/dns-query\",\"8.8.8.8\"]",dns.getJSONArray("servers").toString());
        assertFalse(dns.has("tag"));assertFalse(dns.getJSONObject("hosts").has("geosite:category-ads"));
        File dir=new File("build/probe-configs");dir.mkdirs();Files.write(new File(dir,"auto.json").toPath(),r.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
