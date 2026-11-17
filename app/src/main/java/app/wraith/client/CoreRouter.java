package app.wraith.client;

import android.content.Context;
import android.content.Intent;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;

/** Starts, stops and restarts the Xray tunnel (XrayVpnService in the :xray process). */
public final class CoreRouter {
    public static final String XRAY="xray";
    private static final String PREFS="wraith_core", ACTIVE="active_engine", ROUTING="running_routing";
    private static volatile long lastStatusWrite;
    private static volatile long lastXraySignal;
    private CoreRouter(){}

    public static String engine(Context context){return XRAY;}
    static String engine(JSONObject profile){return XRAY;}
    static String engine(JSONObject profile,JSONObject settings){return XRAY;}
    private static String active(Context c){try{android.util.AtomicFile file=new android.util.AtomicFile(new File(c.getFilesDir(),"active-engine"));if(file.getBaseFile().exists())return new String(file.readFully(),java.nio.charset.StandardCharsets.UTF_8);}catch(IOException ignored){}return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(ACTIVE,"");}
    private static void active(Context c,String value){try{android.util.AtomicFile file=new android.util.AtomicFile(new File(c.getFilesDir(),"active-engine"));FileOutputStream out=file.startWrite();try{out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(out);}catch(IOException e){file.failWrite(out);throw e;}}catch(IOException e){AppLog.add(L.t("Не удалось сохранить состояние ядра: ","Could not save core state: ")+e.getMessage());}c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(ACTIVE,value).apply();}
    public static void start(Context context){Context c=context.getApplicationContext();launch(c,engine(c));}
    /** Engine that currently owns the VPN ("" when nothing runs). */
    static String activeEngine(Context context){return active(context.getApplicationContext());}
    /**
     * Remembers the routing compiled into the core being started. The Xray tunnel runs in another
     * process, so without this the app process never knew its routing and every subscription
     * update (including the hourly background job) restarted a perfectly healthy tunnel.
     */
    private static void rememberRouting(Context c){
        String routing="";
        try{Store s=Store.get(c);JSONObject selected=s.selected();if(selected!=null)routing=WraithService.routingOf(s.snapshot(),selected);}catch(Exception ignored){}
        WraithService.runningRouting=routing;
        c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(ROUTING,routing).apply();
    }
    static void launch(Context c,String target){
        active(c,target);rememberRouting(c);
        AppLog.add(L.t("Запуск подключения · ядро ","Starting connection · core ")+target+" · Wraith "+WraithApp.VERSION);
        Intent i=new Intent(c,XrayVpnService.class);
        c.startForegroundService(i);
    }
    public static void stop(Context context){Context c=context.getApplicationContext();String old=active(c);if(old.isEmpty())old=engine(c);c.startService(new Intent(c,XrayVpnService.class).setAction("stop"));}
    /** A handoff lets the old service close its TUN before the new engine claims Android's VPN. */
    public static void restart(Context context){
        Context c=context.getApplicationContext();String target=engine(c),old=active(c);
        if(old.isEmpty()){launch(c,target);return;}
        if(old.equals(target)){
            rememberRouting(c);
            c.startForegroundService(new Intent(c,XrayVpnService.class).setAction("restart"));return;
        }
        c.startService(new Intent(c,XrayVpnService.class).setAction("handoff").putExtra("target",target));
    }
    static void handedOff(Context c,String target){launch(c,target);}
    static void stopped(Context c){active(c,"");}
    static void remoteSignal(){lastXraySignal=android.os.SystemClock.elapsedRealtime();}
    static void verifyStatus(Context c){
        int state=WraithService.state;if(state!=WraithService.CONNECTED&&state!=WraithService.CONNECTING)return;
        long last=lastXraySignal,limit=state==WraithService.CONNECTED?10000:45000;
        if(last==0||android.os.SystemClock.elapsedRealtime()-last<limit||!XRAY.equals(active(c)))return;
        AppLog.add(L.t("Процесс Xray перестал сообщать состояние; туннель сброшен.","The Xray process stopped reporting its state; the tunnel was reset."));
        WraithService.state=WraithService.OFF;WraithService.error="";WraithService.rxRate=WraithService.txRate=0;
        stopped(c);QuickTile.sync(c);
    }
    public static void publish(Context c,int state,String error,String name,long since,long rx,long tx,long rxRate,long txRate){
        long now=android.os.SystemClock.elapsedRealtime();if(state!=WraithService.CONNECTED||now-lastStatusWrite>5000){saveStatus(c,state,error,name,since,rx,tx,rxRate,txRate);lastStatusWrite=now;}
        Intent i=new Intent(WraithApp.ACTION_CORE_STATE).setPackage(c.getPackageName());
        i.putExtra("state",state).putExtra("error",error==null?"":error).putExtra("name",name==null?"":name)
            .putExtra("since",since).putExtra("rx",rx).putExtra("tx",tx).putExtra("rxRate",rxRate).putExtra("txRate",txRate);
        c.sendBroadcast(i);
    }
    static void restoreStatus(Context c){
        // A saved CONNECTED state only proves that the core once ran. If its process was killed,
        // restoring that snapshot leaves the power button and tile permanently stuck on.
        // A live Xray service broadcasts fresh state on its next stats tick.
        WraithService.state=WraithService.OFF;WraithService.error="";WraithService.profileName="";
        WraithService.since=0;WraithService.rx=WraithService.tx=WraithService.rxRate=WraithService.txRate=0;
        // A tunnel in the :xray process survives an app-process restart; keep knowing its routing.
        if(!active(c).isEmpty())WraithService.runningRouting=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(ROUTING,"");
    }
    private static void saveStatus(Context c,int state,String error,String name,long since,long rx,long tx,long rxRate,long txRate){try{JSONObject j=Json.obj("state",state,"error",error==null?"":error,"name",name==null?"":name,"since",since,"rx",rx,"tx",tx,"rxRate",rxRate,"txRate",txRate);AtomicFile f=new AtomicFile(new File(c.getFilesDir(),"core-status.json"));FileOutputStream out=f.startWrite();try{out.write(j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));f.finishWrite(out);}catch(Exception e){f.failWrite(out);}}catch(Exception ignored){}}
}
