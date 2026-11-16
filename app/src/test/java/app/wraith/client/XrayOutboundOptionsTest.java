package app.wraith.client;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class XrayOutboundOptionsTest {
    private static JSONObject node(String tag,String flow)throws Exception{
        return Json.obj("tag",tag,"protocol","vless","settings",Json.obj("vnext",Json.arr(Json.obj("address","192.0.2.1","port",443,"users",Json.arr(Json.obj("id","00948d0e-1437-0005-abfe-bb90bf6bc89a","encryption","none","flow",flow))))),"streamSettings",Json.obj("network","tcp","security","reality","realitySettings",Json.obj("serverName","example.com","publicKey","provider-key","shortId","abcd","fingerprint","qq")));
    }

    @Test public void nativeSourceKeepsEngineWithEveryAppDpiFlag()throws Exception{
        JSONObject outbound=node("provider", "xtls-rprx-vision"),profile=Json.obj("source",Json.obj("outbounds",Json.arr(outbound)).toString(),"outbound",Json.obj("type","vless"),"jsonSource",true);
        for(String flag:new String[]{"fragment","recordFragment","noise","mux"})assertEquals(CoreRouter.XRAY,CoreRouter.engine(profile,Json.obj(flag,true)));
        assertEquals(CoreRouter.XRAY,CoreRouter.engine(profile,Json.obj("fragment",true,"recordFragment",true,"noise",true,"mux",true)));
    }

    @Test public void supportedShareProtocolsKeepNativeEngineWithOptions()throws Exception{
        for(String type:new String[]{"vless","trojan","shadowsocks","socks"}){
            JSONObject profile=Json.obj("source",type+"://example.com","outbound",Json.obj("type",type));
            assertEquals(CoreRouter.XRAY,CoreRouter.engine(profile,Json.obj("fragment",true,"noise",true,"mux",true)));
        }
    }

    @Test public void fullSingboxAndUnsupportedShareProtocolsKeepSingbox()throws Exception{
        JSONObject profile=Json.obj("source","{\"outbounds\":[{\"type\":\"vless\"}]}","full",true,"outbound",Json.obj("type","vless"));
        assertEquals(CoreRouter.SING_BOX,CoreRouter.engine(profile,Json.obj("fragment",true)));
        for(String type:new String[]{"hysteria2","tuic","anytls","wireguard","ssh"})assertEquals(CoreRouter.SING_BOX,CoreRouter.engine(Json.obj("source",type+"://example.com","outbound",Json.obj("type",type)),Json.obj("noise",true)));
    }

    @Test public void appHelperChainsExistingDialerWithoutLosingProviderOptions()throws Exception{
        JSONObject primary=node("provider", "xtls-rprx-vision");
        primary.getJSONObject("streamSettings").put("sockopt",Json.obj("dialerProxy","provider-fragment","tcpKeepAliveIdle",60));
        JSONObject providerHelper=Json.obj("tag","provider-fragment","protocol","freedom","settings",Json.obj("redirect","127.0.0.1:8443","fragment",Json.obj("packets","tlshello","length","10-20","interval","0-1")));
        JSONObject collision=Json.obj("tag","wraith-dialer","protocol","freedom");
        JSONObject originalHelper=Json.copy(providerHelper),originalTls=Json.copy(primary.getJSONObject("streamSettings").getJSONObject("realitySettings"));
        JSONObject root=Json.obj("outbounds",Json.arr(primary,providerHelper,collision));
        XrayOutboundOptions.apply(root,new JSONObject(),Json.obj("fragment",true,"fragmentMaxSplit","100-200","noise",true,"noiseType","array","noisePacket","1A,2,0xFF"),"provider");
        assertEquals(4,root.getJSONArray("outbounds").length());
        assertEquals(originalHelper.toString(),providerHelper.toString());
        assertEquals(originalTls.toString(),primary.getJSONObject("streamSettings").getJSONObject("realitySettings").toString());
        assertEquals(60,primary.getJSONObject("streamSettings").getJSONObject("sockopt").getInt("tcpKeepAliveIdle"));
        assertEquals("wraith-dialer-2",primary.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"));
        JSONObject app=root.getJSONArray("outbounds").getJSONObject(3);
        assertEquals("provider-fragment",app.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"));
        JSONObject options=app.getJSONObject("settings");
        assertEquals("100-200",options.getJSONObject("fragment").getString("maxSplit"));
        assertFalse(options.getJSONObject("fragment").has("max_split"));
        JSONObject noise=options.getJSONArray("noises").getJSONObject(0);
        assertEquals("hex",noise.getString("type"));assertEquals("1a02ff",noise.getString("packet"));
    }

    @Test public void disabledAppOptionsDoNotChangeProviderFragmentOrMux()throws Exception{
        JSONObject primary=node("provider", ""),root=Json.obj("outbounds",Json.arr(primary));
        primary.put("mux",Json.obj("enabled",true,"concurrency",16,"xudpProxyUDP443","skip"));
        primary.getJSONObject("streamSettings").put("sockopt",Json.obj("dialerProxy","native-dialer"));
        JSONObject original=Json.copy(root);
        XrayOutboundOptions.apply(root,new JSONObject(),Json.obj("fragment",false,"noise",false,"mux",false,"recordFragment",true),"provider");
        assertEquals(original.toString(),root.toString());
    }

    @Test public void muxUsesNativeConcurrencyAndExcludesVision()throws Exception{
        JSONObject plain=node("plain", ""),vision=node("vision", "xtls-rprx-vision");
        plain.put("mux",Json.obj("xudpProxyUDP443","allow","xudpConcurrency",4));
        JSONObject root=Json.obj("outbounds",Json.arr(plain,vision)),settings=Json.obj("mux",true,"muxLimit","streams","muxStreams",12);
        XrayOutboundOptions.apply(root,new JSONObject(),settings,"plain");XrayOutboundOptions.apply(root,new JSONObject(),settings,"vision");
        JSONObject mux=plain.getJSONObject("mux");assertTrue(mux.getBoolean("enabled"));assertEquals(12,mux.getInt("concurrency"));assertEquals("allow",mux.getString("xudpProxyUDP443"));assertFalse(mux.has("protocol"));assertFalse(vision.has("mux"));
    }

    @Test public void nativeNoiseNormalizesBase64AndRandRanges()throws Exception{
        JSONObject base64=XrayOutboundOptions.noise(Json.obj("noiseType","base64","noisePacket","+/8="));assertEquals("-_8",base64.getString("packet"));
        JSONObject random=XrayOutboundOptions.noise(Json.obj("noiseType","rand","noiseRand","50-100"));assertEquals("50-100",random.getString("packet"));assertFalse(random.has("rand"));
    }
    @Test(expected=IllegalArgumentException.class)public void invalidBase64IsRejected()throws Exception{XrayOutboundOptions.noise(Json.obj("noiseType","base64","noisePacket","%invalid"));}
    @Test(expected=IllegalArgumentException.class)public void invalidFragmentRangeIsRejected()throws Exception{XrayOutboundOptions.fragment(Json.obj("fragmentLength","100-50"));}
}
