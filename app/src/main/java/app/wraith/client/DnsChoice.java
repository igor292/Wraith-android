package app.wraith.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Which DNS the tunnel uses, in order: the provider's DNS (only with «DNS из JSON»), the user's own
 * address (only with «Свой DNS»), otherwise the DNS of the phone's current network. The phone's
 * servers are read by the service at connect time and passed to the builders as "_systemDns".
 */
final class DnsChoice {
    static final String DEFAULT_CUSTOM="https://8.8.8.8/dns-query";
    static final String SYSTEM_KEY="_systemDns";
    private DnsChoice(){}

    /** The user's own DNS, or "" when the switch is off. */
    static String custom(JSONObject s){
        if(s==null||!s.optBoolean("customDns"))return "";
        String v=s.optString("tunnelDns").trim();return v.isEmpty()?DEFAULT_CUSTOM:v;
    }
    /** IP addresses of the phone's DNS servers captured at connect time. */
    static List<String> system(JSONObject s){
        List<String> out=new ArrayList<>();JSONArray a=s==null?null:s.optJSONArray(SYSTEM_KEY);
        if(a!=null)for(int i=0;i<a.length();i++){String ip=a.optString(i).trim();if(!ip.isEmpty())out.add(ip);}
        return out;
    }
    static String firstSystem(JSONObject s){List<String> l=system(s);return l.isEmpty()?"":l.get(0);}

    /** Stores the DNS servers of the best non-VPN network into settings[_systemDns]. */
    static void inject(Context context,JSONObject settings){
        if(settings==null)return;
        try{settings.put(SYSTEM_KEY,new JSONArray(read(context)));}catch(Exception e){AppLog.add("DNS телефона: "+Subscriptions.safeError(e));}
    }
    static List<String> read(Context context){
        List<String> out=new ArrayList<>();
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);if(cm==null)return out;
        Network active=cm.getActiveNetwork(),best=null;int bestScore=-1;
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities cap=cm.getNetworkCapabilities(n);
            if(cap==null||cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
            int score=(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?4:0)+(n.equals(active)?2:0)+(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?1:0);
            if(score>bestScore){best=n;bestScore=score;}
        }
        LinkProperties lp=best==null?null:cm.getLinkProperties(best);
        if(lp!=null)for(InetAddress a:lp.getDnsServers()){
            // Link-local IPv6 resolvers need a scope id the cores cannot express; skip them.
            if(a.isLinkLocalAddress()||a.isLoopbackAddress()||a.isAnyLocalAddress())continue;
            String ip=a.getHostAddress();if(ip==null)continue;int scope=ip.indexOf('%');if(scope>=0)ip=ip.substring(0,scope);
            if(!out.contains(ip))out.add(ip);
            if(out.size()>=3)break;
        }
        // Prefer IPv4 first: it works on every network the phone may roam to.
        out.sort((x,y)->Boolean.compare(x.contains(":"),y.contains(":")));
        return out;
    }
}
