package app.wraith.client;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.net.Socket;

/**
 * Tunnel state seen by the app process. The tunnel itself is XrayVpnService in the :xray
 * process; it reports its state through CoreRouter broadcasts into these fields.
 */
public final class WraithService {
    public static final int OFF=0,CONNECTING=1,CONNECTED=2,STOPPING=3;
    public static volatile int state=OFF;
    public static volatile String error="",profileName="";
    public static volatile long since=0,rx=0,tx=0,rxRate=0,txRate=0;
    /** Routing profile compiled into the running core ("" = none), and a one-line description. */
    public static volatile String runningRouting="",routingSummary="";
    private WraithService(){}

    public static void start(Context c){CoreRouter.start(c);}
    public static void stop(Context c){CoreRouter.stop(c);}
    public static void restart(Context c){CoreRouter.restart(c);}

    static String routingOf(JSONObject state,JSONObject profile){
        JSONObject routing=RoutingConfig.profileFor(state,profile);
        return routing==null?"":routing.toString();
    }
    /** True when the running core was built with different routing than this profile needs. */
    public static boolean routingDiffers(Context c,JSONObject profile){
        try{ return !routingOf(Store.get(c).snapshot(),profile).equals(runningRouting); }
        catch(Exception e){ return true; }
    }
    /** Xray switches servers by restarting the core. */
    public static boolean switchProfile(JSONObject profile){return false;}
    /** Latency sockets of the app process need no protection: the Xray VPN excludes this app. */
    public static boolean protectProbeFd(int fd){return true;}
    public static void protectProbe(Socket socket)throws IOException{}
}
