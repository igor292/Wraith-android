package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Map;

/** Share links that libXray's converter may not know, turned into Xray outbounds locally. */
final class XrayLinks {
    private XrayLinks(){}

    /** null when the link is not one of these. */
    static JSONObject outbound(String link)throws Exception{
        String l=link.trim().toLowerCase(java.util.Locale.ROOT);
        if(l.startsWith("hy2://")||l.startsWith("hysteria2://"))return hysteria2(link);
        if(l.startsWith("wireguard://")||l.startsWith("wg://"))return wireguard(link);
        return null;
    }

    private static String q(Map<String,String> q,String fallback,String... keys){
        for(String k:keys){String v=q.get(k);if(v!=null&&!v.trim().isEmpty())return v.trim();}
        return fallback;
    }

    static JSONObject hysteria2(String link)throws Exception{
        ImportParser.Link l=ImportParser.split(link);Map<String,String> qm=l.query;
        if(!q(qm,"","obfs").isEmpty())throw new IllegalArgumentException(L.t("Hysteria2 с obfs не поддерживается Xray.","Hysteria2 with obfs is not supported by Xray."));
        int port=l.port>0?l.port:443;
        JSONObject hy=Json.obj("version",2,"auth",ImportParser.decode(l.userInfo));
        String hops=q(qm,l.ports,"mport","ports");
        if(!hops.isEmpty()){JSONObject hop=Json.obj("port",hops.replace(':','-'));String interval=q(qm,"","mportHopInt","hopInterval","hop_interval");if(!interval.isEmpty())hop.put("interval",interval.replaceAll("s$",""));hy.put("udphop",hop);}
        JSONObject tls=Json.obj("serverName",q(qm,l.host,"sni","peer","serverName"));
        String alpn=q(qm,"","alpn");tls.put("alpn",alpn.isEmpty()?Json.arr("h3"):new JSONArray(alpn.split(",")));
        // Xray 26 removed allowInsecure: the certificate is verified (pinned when the link has a pin).
        String pin=q(qm,"","pinSHA256","pinnedPeerCertSha256","pcs").replace(":","").toLowerCase(java.util.Locale.ROOT);
        if(!pin.isEmpty())tls.put("pinnedPeerCertSha256",pin);
        return Json.obj("protocol","hysteria","tag","proxy","settings",Json.obj("version",2,"address",l.host,"port",port),
            "streamSettings",Json.obj("network","hysteria","hysteriaSettings",hy,"security","tls","tlsSettings",tls));
    }

    static JSONObject wireguard(String link)throws Exception{
        ImportParser.Link l=ImportParser.split(link);Map<String,String> qm=l.query;
        String key=ImportParser.decode(l.userInfo);if(key.isEmpty())throw new IllegalArgumentException(L.t("В ссылке WireGuard нет приватного ключа.","The WireGuard link has no private key."));
        String pub=q(qm,"","publickey","publicKey","public_key");if(pub.isEmpty())throw new IllegalArgumentException(L.t("В ссылке WireGuard нет публичного ключа сервера.","The WireGuard link has no server public key."));
        int port=l.port>0?l.port:51820;String host=l.host.contains(":")?"["+l.host+"]":l.host;
        JSONObject peer=Json.obj("publicKey",pub,"endpoint",host+":"+port,"allowedIPs",Json.arr("0.0.0.0/0","::/0"));
        String psk=q(qm,"","presharedkey","psk");if(!psk.isEmpty())peer.put("preSharedKey",psk);
        JSONObject settings=Json.obj("secretKey",key,"peers",Json.arr(peer));
        JSONArray address=new JSONArray();for(String a:q(qm,"","address","ip").split(",")){a=a.trim();if(a.isEmpty())continue;address.put(a.contains("/")?a:a.contains(":")?a+"/128":a+"/32");}
        if(address.length()>0)settings.put("address",address);
        JSONArray reserved=new JSONArray();for(String r:q(qm,"","reserved").split(",")){try{reserved.put(Integer.parseInt(r.trim()));}catch(NumberFormatException ignored){}}
        if(reserved.length()>0)settings.put("reserved",reserved);
        try{settings.put("mtu",Integer.parseInt(q(qm,"","mtu")));}catch(NumberFormatException ignored){}
        return Json.obj("protocol","wireguard","tag","proxy","settings",settings);
    }
}
