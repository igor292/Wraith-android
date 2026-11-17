package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Xray 26 removed allowInsecure: such links go to sing-box, and Xray documents lose the field. */
public class InsecureTlsTest {
    @Test public void insecureLinkRunsOnSingBox()throws Exception{
        JSONObject p=ImportParser.parse("vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@example.com:443?type=ws&security=tls&sni=example.com&allowInsecure=1#v").get(0);
        assertEquals(CoreRouter.SING_BOX,CoreRouter.engine(p));
        JSONObject safe=ImportParser.parse("vless://00948d0e-1437-0005-abfe-bb90bf6bc89a@example.com:443?type=ws&security=tls&sni=example.com#v").get(0);
        assertEquals(CoreRouter.XRAY,CoreRouter.engine(safe));
    }
    @Test public void xrayDocumentDropsAllowInsecure()throws Exception{
        String src="{\"outbounds\":[{\"protocol\":\"trojan\",\"tag\":\"proxy\",\"settings\":{\"servers\":[{\"address\":\"example.com\",\"port\":443,\"password\":\"x\"}]},\"streamSettings\":{\"security\":\"tls\",\"tlsSettings\":{\"serverName\":\"example.com\",\"allowInsecure\":true}}}]}";
        JSONObject p=ImportParser.parse(src).get(0);
        JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",new JSONObject(),"rules",new JSONArray());
        JSONObject c=XrayRuntimeConfig.build(state,p,1,null);
        assertFalse(c.toString().contains("allowInsecure"));
    }
}
