package app.wraith.client;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;
public class ProbeTest {
 @Test public void probeHasNoTunOrLogsOrBackgroundTests()throws Exception{JSONObject p=ImportParser.parse("socks://example.com:1080").get(0);JSONObject c=ConfigBuilder.probeConfig(p,Json.obj("autoSelect",true,"debugLogs",true));assertEquals(0,c.getJSONArray("inbounds").length());assertFalse(c.has("experimental"));assertTrue(c.getJSONObject("log").getBoolean("disabled"));assertEquals(1,c.getJSONArray("outbounds").length());assertEquals("node-"+p.getString("id"),c.getJSONObject("route").getString("final"));}
 @Test public void tcpDoesNotMisdiagnoseUdpServers()throws Exception{JSONObject p=ImportParser.parse("hy2://password@example.com:443").get(0);assertEquals("proxyGet",PingInfo.effectiveType(p,Json.obj("pingType","tcp")));assertEquals("icmp",PingInfo.effectiveType(p,Json.obj("pingType","icmp")));}
 @Test public void realityAliasesAndDefaultFingerprint()throws Exception{JSONObject p=ImportParser.parse("vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&serverName=target.example&publicKey=test&shortId=aabb").get(0);JSONObject t=p.getJSONObject("outbound").getJSONObject("tls");assertEquals("target.example",t.getString("server_name"));assertEquals("test",t.getJSONObject("reality").getString("public_key"));assertEquals("aabb",t.getJSONObject("reality").getString("short_id"));assertEquals("chrome",t.getJSONObject("utls").getString("fingerprint"));assertFalse(t.optBoolean("insecure"));}
 @Test public void flagsNotDuplicated(){assertEquals("Germany",PingInfo.displayName("🇩🇪 Germany"));assertEquals("My server",PingInfo.displayName("My server"));}
 @Test public void allLeadingCountryFlagsBecomeIcons(){assertEquals("🇧🇪",PingInfo.leadingFlag("🇧🇪 ⚡️ Belgium"));assertEquals("🇳🇱",PingInfo.leadingFlag("🇳🇱 Netherlands"));assertEquals("",PingInfo.leadingFlag("Server 🇩🇪"));}
}
