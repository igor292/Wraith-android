package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Default DNS is the phone's; provider and custom DNS apply only when switched on. */
public class DnsChoiceTest {
    static JSONObject xrayDns(JSONObject settings)throws Exception{
        String source="{\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":\"node.example\",\"port\":443,\"users\":[{\"id\":\"00948d0e-1437-0005-abfe-bb90bf6bc89a\",\"encryption\":\"none\"}]}]}}],\"dns\":{\"servers\":[\"9.9.9.9\"]}}";
        JSONObject p=ImportParser.parse(source).get(0);
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",settings,"rules",new JSONArray());
        return XrayRuntimeConfig.build(state,p,1,null);
    }
    @Test public void phoneDnsByDefaultAndQueriedDirectly()throws Exception{
        JSONObject c=xrayDns(Json.obj(DnsChoice.SYSTEM_KEY,Json.arr("192.168.1.1")));
        assertEquals("[\"192.168.1.1\"]",c.getJSONObject("dns").getJSONArray("servers").toString());
        boolean direct=false;for(JSONObject r:Json.objects(c.getJSONObject("routing").getJSONArray("rules")))if(r.optJSONArray("ip")!=null&&r.getJSONArray("ip").toString().contains("192.168.1.1")&&r.optString("outboundTag").equals("direct"))direct=true;
        assertTrue(direct);
        // Local SOCKS proxy is off unless the user turns it on.
        assertEquals(1,c.getJSONArray("inbounds").length());
    }
    @Test public void providerDnsOnlyWithSwitch()throws Exception{
        assertEquals("[\"9.9.9.9\"]",xrayDns(Json.obj("dnsFromJson",true,DnsChoice.SYSTEM_KEY,Json.arr("192.168.1.1"))).getJSONObject("dns").getJSONArray("servers").toString());
    }
    @Test public void customDnsWins()throws Exception{
        JSONObject s=Json.obj("customDns",true,"tunnelDns","tls://1.1.1.1",DnsChoice.SYSTEM_KEY,Json.arr("192.168.1.1"));
        assertEquals("[\"tls://1.1.1.1\"]",xrayDns(s).getJSONObject("dns").getJSONArray("servers").toString());
        assertEquals(DnsChoice.DEFAULT_CUSTOM,DnsChoice.custom(Json.obj("customDns",true)));
        assertEquals("",DnsChoice.custom(Json.obj("tunnelDns","1.1.1.1")));
    }
    @Test public void singBoxUsesPhoneDnsWithoutProxyDetour()throws Exception{
        JSONObject p=ImportParser.parse("trojan://secret@example.com:443?sni=example.com").get(0);
        JSONObject dns=ConfigBuilder.build(Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",Json.obj(DnsChoice.SYSTEM_KEY,Json.arr("10.0.0.1")),"rules",new JSONArray()),"s","/tmp/log").getJSONObject("dns");
        JSONObject remote=null;for(JSONObject s:Json.objects(dns.getJSONArray("servers")))if(s.optString("tag").equals("remote-dns"))remote=s;
        assertNotNull(remote);assertEquals("10.0.0.1",remote.getString("server"));assertFalse(remote.has("detour"));
    }
}
