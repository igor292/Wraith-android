package app.wraith.client;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Xray runs in :xray so its Go runtime can coexist with libbox in the app process. */
public final class XrayVpnService extends VpnService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService stats=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean wanted;private volatile long commandId;private volatile int currentState=WraithService.OFF;private ParcelFileDescriptor tun;private String profileName="",proxyTag="proxy";private volatile java.util.List<String> statTags=java.util.List.of();private volatile JSONObject sessionSettings=new JSONObject();private long since,rx,tx;private IsolatedXray xray;private int failedHealthChecks;
    public static final String ACTION="app.wraith.client.XRAY_COMMAND";
    private static final int NOTIFICATION=7;
    @Override public void onCreate(){super.onCreate();AppLog.mirrorTo(new File(getFilesDir(),"core/xray-service.log"));NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("connection","Подключение Wraith",NotificationManager.IMPORTANCE_LOW));stats.scheduleWithFixedDelay(this::readStats,1,1,TimeUnit.SECONDS);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"":intent.getAction();long command=++commandId;
        if("stop".equals(action)){wanted=false;worker.execute(()->{stopCore();if(command==commandId){CoreRouter.stopped(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}});return START_NOT_STICKY;}
        if("handoff".equals(action)){wanted=false;String target=intent.getStringExtra("target");worker.execute(()->{stopCore();if(command==commandId){CoreRouter.stopped(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);if(target!=null)CoreRouter.handedOff(getApplicationContext(),target);}});return START_NOT_STICKY;}
        boolean restart="restart".equals(action);wanted=true;startForeground(NOTIFICATION,notification(restart?"Переключение сервера…":"Подключение…"));
        worker.execute(()->{if(command!=commandId)return;if(restart||xray!=null||tun!=null)stopCore();if(wanted)connect(command,startId);});return START_STICKY;
    }
    private Notification notification(String text){PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,XrayVpnService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);return new Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_tile).setContentTitle("Wraith · Xray").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,"Отключить",stop).build()).build();}
    private void connect(long command,int startId){
        JSONObject selected=null;long started=SystemClock.elapsedRealtime();
        try{
            if(VpnService.prepare(this)!=null)throw new IllegalStateException("Нужно разрешение Android на VPN-подключение.");
            JSONObject snapshot=Store.readOnlySnapshot(this);for(JSONObject p:Json.objects(snapshot.optJSONArray("profiles")))if(p.optString("id").equals(snapshot.optString("selected"))){selected=p;break;}
            if(selected==null)throw new IllegalStateException("Сначала выберите сервер.");profileName=selected.optString("name");
            setState(WraithService.CONNECTING,"");startForeground(NOTIFICATION,notification("Подключение…"));
            JSONObject settings=snapshot.optJSONObject("settings");if(settings==null){settings=new JSONObject();snapshot.put("settings",settings);}DnsChoice.inject(this,settings);sessionSettings=Json.copy(settings);String dns=XrayRuntimeConfig.androidDns(snapshot,selected,settings);
            File geodata=XrayAssets.ensure(this);
            tun=establishTun(settings,dns);if(tun==null)throw new IOException("Android не создал VPN-интерфейс.");
            xray=new IsolatedXray(this);xray.setDNS(XrayRuntimeConfig.dnsEndpoint(dns));
            JSONObject config=XrayRuntimeConfig.build(snapshot,selected,tun.getFd(),xray);
            config.getJSONObject("env").put("xray.location.asset",geodata.getAbsolutePath());proxyTag=XrayRuntimeConfig.proxyTag(config,selected);statTags=XrayRuntimeConfig.proxyTags(config);
            configureLogs(config);
            JSONObject request=Json.obj("apiVersion",3,"method","runXray","payload",Json.obj("xrayJson",config.toString()));
            JSONObject result=new JSONObject(xray.invoke(request.toString()));if(!result.optBoolean("success"))throw new IllegalStateException(result.optString("error","Xray не запустился."));
            if(!wanted||command!=commandId){stopCore();return;}
            since=SystemClock.elapsedRealtime();rx=tx=0;failedHealthChecks=0;setState(WraithService.CONNECTED,"");getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(profileName+" · подключено"));AppLog.add("Туннель запущен: "+profileName+" · Xray");
        }catch(Throwable e){String message=Subscriptions.safeError(e);AppLog.add("Xray: "+message);stopCore();if(command==commandId){wanted=false;setState(WraithService.OFF,message);CoreRouter.stopped(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);}}
    }
    private void configureLogs(JSONObject config)throws Exception{
        File dir=new File(getFilesDir(),"core");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не удалось создать папку логов Xray.");
        File error=new File(dir,"xray-error.log"),access=new File(dir,"xray-access.log");
        for(File f:new File[]{error,access})if(f.length()>1024*1024)try(FileOutputStream out=new FileOutputStream(f,false)){out.write(new byte[0]);}
        JSONObject log=config.optJSONObject("log");if(log==null){log=new JSONObject();config.put("log",log);}
        log.put("error",error.getAbsolutePath()).put("access",access.getAbsolutePath());
    }
    private ParcelFileDescriptor establishTun(JSONObject s,String dns)throws Exception{
        int mtu=s.optInt("mtu",1500);if(mtu<1280||mtu>9000)throw new IllegalArgumentException("MTU должен быть от 1280 до 9000.");
        Builder b=new Builder().setSession("Wraith").setMtu(mtu).setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE));if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
        b.addAddress("172.19.0.1",30);b.addAddress("fdfe:dcba:9876::1",126);b.addRoute("0.0.0.0",0);b.addRoute("::",0);b.addDnsServer(dns);
        String excluded=s.optString("excludedRoutes","");if(Build.VERSION.SDK_INT>=33)for(String row:excluded.split("\\n")){String cidr=row.trim();if(cidr.isEmpty())continue;try{String[] parts=cidr.split("/",2);if(parts.length!=2)throw new IllegalArgumentException();b.excludeRoute(new IpPrefix(java.net.InetAddress.getByName(parts[0]),Integer.parseInt(parts[1])));}catch(Exception e){AppLog.add("Пропущен маршрут исключения: "+cidr);}}
        String appMode=s.optString("appMode","off"),apps=appMode.equals("off")?"":s.optString("apps","").trim();boolean include=appMode.equals("include");int added=0;
        if(!include)b.addDisallowedApplication(getPackageName());
        if(!apps.isEmpty())for(String app:apps.split("[\\n,]")){String pkg=app.trim();if(pkg.isEmpty()||pkg.equals(getPackageName()))continue;try{if(include)b.addAllowedApplication(pkg);else b.addDisallowedApplication(pkg);added++;}catch(android.content.pm.PackageManager.NameNotFoundException e){AppLog.add("Приложение удалено: "+pkg);}}
        if(include&&added==0)throw new IllegalStateException("В списке VPN нет установленных приложений.");
        return b.establish();
    }
    private void stopCore(){try{if(xray!=null)xray.invoke(Json.obj("apiVersion",3,"method","stopXray","payload",new JSONObject()).toString());}catch(Throwable ignored){}if(xray!=null)xray.resetDNS();xray=null;if(tun!=null){try{tun.close();}catch(IOException ignored){}tun=null;}failedHealthChecks=0;if(currentState!=WraithService.OFF)setState(WraithService.OFF,"");}
    private void setState(int value,String error){currentState=value;CoreRouter.publish(this,value,error,profileName,since,rx,tx,0,0);}
    private void readStats(){if(currentState!=WraithService.CONNECTED||!wanted)return;try{java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL("http://127.0.0.1:"+XrayRuntimeConfig.metricsPort+"/debug/vars").openConnection(java.net.Proxy.NO_PROXY);c.setConnectTimeout(300);c.setReadTimeout(500);JSONObject vars;try{vars=new JSONObject(new String(Subscriptions.bounded(c.getInputStream()),java.nio.charset.StandardCharsets.UTF_8));}finally{c.disconnect();}failedHealthChecks=0;long down=0,up=0;JSONObject stats=vars.optJSONObject("stats");JSONObject outbounds=stats==null?null:stats.optJSONObject("outbound");if(outbounds!=null)for(String tag:statTags.isEmpty()?java.util.List.of(proxyTag):statTags){JSONObject counted=outbounds.optJSONObject(tag);if(counted!=null){down+=counted.optLong("downlink");up+=counted.optLong("uplink");}}long oldRx=rx,oldTx=tx;rx=down;tx=up;CoreRouter.publish(this,WraithService.CONNECTED,"",profileName,since,rx,tx,Math.max(0,rx-oldRx),Math.max(0,tx-oldTx));if(sessionSettings.optBoolean("notifySpeed"))getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(PingInfo.displayName(profileName)+"  ↓ "+MainActivity.bytes(Math.max(0,rx-oldRx))+"/с  ↑ "+MainActivity.bytes(Math.max(0,tx-oldTx))+"/с"));}catch(Exception e){if(currentState==WraithService.CONNECTED&&wanted&&++failedHealthChecks>=8){long connection=since;AppLog.add("Ядро Xray перестало отвечать: "+Subscriptions.safeError(e));wanted=false;worker.execute(()->{if(currentState==WraithService.CONNECTED&&since==connection){stopCore();CoreRouter.stopped(this);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}});}}}
    @Override public void onRevoke(){wanted=false;setState(WraithService.OFF,"");worker.execute(()->{stopCore();CoreRouter.stopped(this);stopSelf();});}
    @Override public void onDestroy(){wanted=false;setState(WraithService.OFF,"");stats.shutdownNow();worker.execute(this::stopCore);worker.shutdown();super.onDestroy();}
}
