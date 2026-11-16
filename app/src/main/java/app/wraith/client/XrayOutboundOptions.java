package app.wraith.client;

import org.json.*;
import java.util.*;

/** App options expressed using the freedom/mux fields accepted by the bundled Xray core. */
final class XrayOutboundOptions {
    private XrayOutboundOptions(){}

    static void apply(JSONObject root,JSONObject profile,JSONObject settings,String primaryTag)throws Exception{
        if(settings==null)return;
        JSONArray outbounds=root.optJSONArray("outbounds");if(outbounds==null)return;
        JSONObject primary=find(outbounds,primaryTag);if(primary==null)return;
        String protocol=primary.optString("protocol").toLowerCase(Locale.ROOT);
        if(Set.of("freedom","direct","blackhole","block","dns","loopback").contains(protocol))return;

        boolean fragment=settings.optBoolean("fragment"),noise=settings.optBoolean("noise");
        if(fragment||noise){
            JSONObject stream=object(primary,"streamSettings"),sockopt=object(stream,"sockopt");
            String previous=sockopt.optString("dialerProxy");
            String tag=uniqueTag(outbounds,"wraith-dialer");
            JSONObject options=new JSONObject();
            if(fragment)options.put("fragment",fragment(settings));
            if(noise)options.put("noises",Json.arr(noise(settings)));
            JSONObject helper=Json.obj("tag",tag,"protocol","freedom","settings",options);
            // Keep the provider's existing dialer in the chain. Neither its tag, credentials,
            // redirect, transport nor other socket options are rewritten.
            if(!previous.isEmpty())helper.put("streamSettings",Json.obj("sockopt",Json.obj("dialerProxy",previous)));
            outbounds.put(helper);sockopt.put("dialerProxy",tag);
        }
        // Xray has no sing-box record_fragment, h2mux/smux/yamux or mux padding fields. A saved
        // recordFragment flag alone adds no native option and never changes engine selection.
        if(settings.optBoolean("mux")&&!vision(primary)&&Set.of("vless","vmess","trojan","shadowsocks").contains(protocol)){
            boolean streams=settings.optString("muxLimit","connections").equals("streams");
            int concurrency=settings.optInt(streams?"muxStreams":"muxConnections",streams?8:4);
            if(concurrency<1||concurrency>1024)throw new IllegalArgumentException("Ограничение мультиплексора: 1–1024");
            JSONObject mux=primary.optJSONObject("mux");if(mux==null)mux=new JSONObject();
            mux.put("enabled",true).put("concurrency",concurrency);primary.put("mux",mux);
        }
    }

    static JSONObject fragment(JSONObject settings)throws Exception{
        String packets=settings.optString("fragmentPackets","tlshello").trim().toLowerCase(Locale.ROOT);
        if(!packets.equals("tlshello")&&!packets.isEmpty())packets=range(packets,"Фрагментирование пакетов",1,100);
        String type=settings.optString("fragmentType","xray");
        String interval=type.equals("singbox")?String.valueOf(settings.optInt("fragmentDelay",10)):settings.optString("fragmentInterval","10-20");
        JSONObject options=Json.obj("packets",packets,"length",range(settings.optString("fragmentLength","50-100"),"Длина фрагмента",1,16384),"interval",range(interval,"Задержка фрагмента",0,1000));
        String split=settings.optString("fragmentMaxSplit","").trim();if(!split.isEmpty())options.put("maxSplit",range(split,"Максимальное разделение",1,10000));
        return options;
    }

    static JSONObject noise(JSONObject settings)throws Exception{
        String type=settings.optString("noiseType","array").trim().toLowerCase(Locale.ROOT);
        String packet=settings.optString("noisePacket","1A,2B,3C").trim();
        switch(type){
            case "array":{
                if(!packet.matches("(?i)(0x)?[0-9a-f]{1,2}(\\s*,\\s*(0x)?[0-9a-f]{1,2})*"))throw new IllegalArgumentException("Пакет array: байты в hex через запятую, например 1A,2B,3C");
                StringBuilder hex=new StringBuilder();for(String item:packet.split(","))hex.append(String.format(Locale.ROOT,"%02x",Integer.parseInt(item.trim().replaceFirst("(?i)^0x",""),16)));
                type="hex";packet=hex.toString();break;
            }
            case "rand":packet=range(settings.optString("noiseRand","1-8192"),"Размер шума",1,8192);break;
            case "str":if(packet.isEmpty())throw new IllegalArgumentException("Пакет шума пуст");break;
            case "hex":if(packet.isEmpty()||!packet.matches("(?i)([0-9a-f]{2})+"))throw new IllegalArgumentException("Пакет hex должен содержать целые байты");break;
            case "base64":{
                if(packet.isEmpty())throw new IllegalArgumentException("Пакет шума пуст");
                try{String normal=packet.replace('-','+').replace('_','/');byte[] bytes=Base64.getDecoder().decode(normal);if(bytes.length==0)throw new IllegalArgumentException();packet=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
                catch(IllegalArgumentException e){throw new IllegalArgumentException("Пакет шума не является корректным base64",e);}break;
            }
            default:throw new IllegalArgumentException("Тип шума: array, rand, str, hex или base64");
        }
        return Json.obj("type",type,"packet",packet,"delay",range(settings.optString("noiseDelay","10-16"),"Задержка шума",0,1000));
    }

    private static String range(String value,String name,long min,long max){return ConfigBuilder.range(value,name,min,max);}
    private static JSONObject object(JSONObject parent,String key)throws Exception{JSONObject result=parent.optJSONObject(key);if(result==null){result=new JSONObject();parent.put(key,result);}return result;}
    private static JSONObject find(JSONArray outbounds,String tag){if(tag==null||tag.isEmpty())return null;for(JSONObject outbound:Json.objects(outbounds))if(tag.equals(outbound.optString("tag")))return outbound;return null;}
    private static String uniqueTag(JSONArray outbounds,String base){String tag=base;for(int suffix=2;find(outbounds,tag)!=null;suffix++)tag=base+"-"+suffix;return tag;}
    private static boolean vision(JSONObject outbound){JSONObject settings=outbound.optJSONObject("settings");if(settings==null)return false;for(JSONObject peer:Json.objects(settings.optJSONArray("vnext")))for(JSONObject user:Json.objects(peer.optJSONArray("users")))if(user.optString("flow").startsWith("xtls-rprx-vision"))return true;return false;}
}
