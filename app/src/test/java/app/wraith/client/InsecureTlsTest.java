package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Xray 26 removed allowInsecure: Xray documents lose the field instead of failing to start. */
public class InsecureTlsTest {
    @Test public void xrayDocumentDropsAllowInsecure()throws Exception{
        String src="{\"outbounds\":[{\"protocol\":\"trojan\",\"tag\":\"proxy\",\"settings\":{\"servers\":[{\"address\":\"example.com\",\"port\":443,\"password\":\"x\"}]},\"streamSettings\":{\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"example.com\",\"allowInsecure\":true}}}]}";
        JSONObject p=ImportParser.parse(src).get(0);
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONObject c=XrayRuntimeConfig.build(state,p,1,null);
        assertFalse(c.toString().contains("allowInsecure"));
    }
}
