package app.wraith.client;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import android.system.OsConstants;
import io.nekohasekai.libbox.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.net.Proxy;
import java.util.*;
import java.util.concurrent.*;

public final class WraithService extends VpnService implements PlatformInterface,CommandServerHandler {
    public static final int OFF=0,CONNECTING=1,CONNECTED=2,STOPPING=3;
    public static volatile int state=OFF;
    public static volatile String error="",apiSecret="",profileName="";
    public static volatile long since=0,rx=0,tx=0,rxRate=0,txRate=0;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService stats=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean wanted;
    private int failedHealthChecks;
    private CommandServer server;
    private static volatile WraithService active;
    private final java.util.concurrent.locks.ReentrantReadWriteLock probeLock=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    public static int probe(String tag,String url,String method,String mode,int timeout)throws Exception{WraithService s=active;if(s==null||state!=CONNECTED)throw new IOException("Подключите VPN для проверки через прокси");s.probeLock.readLock().lock();try{if(!s.wanted||s.server==null)throw new IOException("VPN отключён");return (int)s.server.probeOutbound(tag,url,method,mode,timeout);}finally{s.probeLock.readLock().unlock();}}
    public static boolean protectProbeFd(int fd){WraithService s=active;return s==null||s.protect(fd);}
    public static void protectProbe(Socket socket)throws IOException{WraithService s=active;if(s!=null&&state==CONNECTED&&!s.protect(socket))throw new IOException("Не удалось исключить пинг из VPN");}
    private ParcelFileDescriptor tun;
    private ConnectivityManager connectivity;
    private volatile InterfaceUpdateListener listener;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile Network underlying;
    private String ownInterface="";
    public static void start(Context c){CoreRouter.start(c);}
    public static void stop(Context c){CoreRouter.stop(c);}
    public static void restart(Context c){CoreRouter.restart(c);}
    @Override public void onCreate(){super.onCreate();active=this;connectivity=getSystemService(ConnectivityManager.class);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("connection","Подключение Wraith",NotificationManager.IMPORTANCE_LOW));stats.scheduleWithFixedDelay(this::readStats,1,1,TimeUnit.SECONDS);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&"stop".equals(intent.getAction())){wanted=false;worker.execute(()->{shutdownCore(false);CoreRouter.stopped(this);stopSelf();});return START_NOT_STICKY;}
        if(intent!=null&&"handoff".equals(intent.getAction())){wanted=false;String target=intent.getStringExtra("target");worker.execute(()->{shutdownCore(false);CoreRouter.stopped(this);stopSelf();if(target!=null&&!target.isEmpty())CoreRouter.handedOff(getApplicationContext(),target);});return START_NOT_STICKY;}
        boolean restart=intent!=null&&"restart".equals(intent.getAction());
        wanted=true;startForeground(7,notification(restart?"Переключение сервера…":"Подключение…"));
        worker.execute(()->{if(restart)shutdownCore(true);if(wanted&&state==OFF)connect();});
        return START_STICKY;
    }
    private android.app.Notification notification(String status){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WraithService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new android.app.Notification.Builder(this,"connection").setSmallIcon(R.drawable.ic_tile).setContentTitle("Wraith").setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(new android.app.Notification.Action.Builder(null,"Отключить",stop).build()).build();
    }
    private void connect(){
        long connectStarted=SystemClock.elapsedRealtime();JSONObject selected=null;
        state=CONNECTING;error="";rx=tx=rxRate=txRate=0;failedHealthChecks=0;QuickTile.sync(this);
        startForeground(7,notification("Подключение…"));
        try{
            if(!WraithApp.coreError.isEmpty())throw new IllegalStateException(WraithApp.coreError);
            if(VpnService.prepare(this)!=null)throw new IllegalStateException("Нужно разрешение Android на VPN-подключение.");
            Store store=Store.get(this);selected=store.selected();if(selected==null)throw new IllegalStateException("Сначала выберите сервер.");profileName=selected.optString("name");
            apiSecret=UUID.randomUUID().toString();File log=new File(getFilesDir(),"core/session.log");if(log.exists()&&log.length()>1024*1024){try(FileOutputStream clear=new FileOutputStream(log)){clear.write(new byte[0]);}}
            JSONObject snapshot=store.snapshot();JSONObject live=snapshot.optJSONObject("settings");if(live!=null)DnsChoice.inject(this,live);
            ConfigBuilder.apiPort=XrayRuntimeConfig.freshMetricsPort();JSONObject built=ConfigBuilder.build(snapshot,apiSecret,log.getAbsolutePath());
            String config=built.toString();
            // Routing is compiled into the running core; remember which one, so a later host switch
            // or subscription update with different rules restarts instead of silently keeping it.
            runningRouting=routingOf(snapshot,selected);
            String mode=store.settings().optString("mode","rules");
            JSONObject route=built.optJSONObject("route");
            String fin=route==null?"":route.optString("final");
            // Where unmatched traffic goes is the first thing to check when "everything is direct".
            routingSummary=(mode.equals("direct")?"⚠ режим «Напрямую»: VPN не используется":mode.equals("global")?"режим «Всё в прокси»":RoutingConfig.summary(snapshot,selected))
                +(fin.equals("direct")&&!mode.equals("direct")?"\n⚠ остальной трафик идёт напрямую":"");
            AppLog.add("Wraith "+WraithApp.VERSION+" · режим "+mode+" · final="+fin+" · "+RoutingConfig.summary(snapshot,selected));
            Libbox.checkConfig(config);
            server=new CommandServer(this,this);server.start();server.startOrReloadService(config,new OverrideOptions());
            if(!wanted){shutdownCore(false);CoreRouter.stopped(this);stopSelf();return;}
            since=SystemClock.elapsedRealtime();state=CONNECTED;
            // The selector restores its last choice from the cache file and only then honours
            // "default", so the picked server is applied explicitly after every start.
            // Re-apply the chosen outbound after core start so a selector cache cannot restore an
            // unrelated host from a previous session.
            if(!selected.optBoolean("full")&&!store.settings().optBoolean("autoSelect"))selectOutbound(ConfigBuilder.selectorTag(selected));
            QuickTile.sync(this);getSystemService(NotificationManager.class).notify(7,notification(profileName+" · подключено"));AppLog.add("Туннель запущен: "+profileName);
        }catch(Throwable e){error=Subscriptions.safeError(e);AppLog.add("Подключение: "+error);wanted=false;shutdownCore(false);CoreRouter.stopped(this);stopSelf();}
    }
    private void shutdownCore(boolean keepForeground){
        if(state!=OFF){state=STOPPING;QuickTile.sync(this);}
        probeLock.writeLock().lock();try{if(server!=null){try{server.closeService();}catch(Exception e){AppLog.add("Остановка: "+Subscriptions.safeError(e));}server.close();server=null;}}finally{probeLock.writeLock().unlock();}
        if(tun!=null){try{tun.close();}catch(IOException ignored){}tun=null;}
        closeDefaultInterfaceMonitor(null);state=OFF;rxRate=txRate=0;apiSecret="";failedHealthChecks=0;QuickTile.sync(this);
        if(!keepForeground)stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onRevoke(){wanted=false;state=OFF;QuickTile.sync(this);worker.execute(()->{shutdownCore(false);CoreRouter.stopped(this);stopSelf();});}
    @Override public void onDestroy(){wanted=false;state=OFF;QuickTile.sync(this);stats.shutdownNow();worker.execute(()->shutdownCore(false));worker.shutdown();active=null;super.onDestroy();}
    /**
     * Switches the running tunnel to another server through the selector, with no core restart and
     * no dropped connections. Returns false when the core cannot be reached, so the caller can
     * fall back to a restart.
     */
    /** Routing profile compiled into the running core ("" = none), and a one-line description. */
    public static volatile String runningRouting="",routingSummary="";
    static String routingOf(JSONObject state,JSONObject profile){
        JSONObject routing=RoutingConfig.profileFor(state,profile);
        return routing==null?"":routing.toString();
    }
    /** True when the running core was built with different routing than this profile needs. */
    public static boolean routingDiffers(android.content.Context c,JSONObject profile){
        try{ return !routingOf(Store.get(c).snapshot(),profile).equals(runningRouting); }
        catch(Exception e){ return true; }
    }
    public static boolean switchProfile(JSONObject profile){
        try{
            if(state!=CONNECTED||profile==null||profile.optBoolean("full"))return false;
            // The selector only changes the exit node. Rules come from the profile selected at
            // connect time, so a host with different routing needs a real restart.
            WraithService running=active;
            if(running==null||routingDiffers(running,profile)){AppLog.add("Другая маршрутизация у сервера — перезапуск ядра.");return false;}
            if(!selectOutbound(ConfigBuilder.selectorTag(profile,Store.get(running).settings().optBoolean("autoSelect"))))return false;
            profileName=profile.optString("name");
            WraithService s=active;
            if(s!=null)s.getSystemService(NotificationManager.class).notify(7,s.notification(profileName+" · подключено"));
            AppLog.add("Сервер переключён: "+profileName);
            return true;
        }catch(Exception e){AppLog.add("Смена сервера: "+Subscriptions.safeError(e));return false;}
    }
    static boolean selectOutbound(String tag){
        try{ request("PUT","/proxies/proxy",Json.obj("name",tag).toString()); return true; }
        catch(Exception e){ AppLog.add("Селектор: "+Subscriptions.safeError(e)); return false; }
    }
    public static JSONObject api(String path)throws Exception{return request("GET",path,null);}
    static JSONObject request(String method,String path,String body)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+ConfigBuilder.apiPort+path).openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(1500);c.setReadTimeout(2500);c.setRequestMethod(method);c.setRequestProperty("Authorization","Bearer "+apiSecret);
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");c.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        try{
            int code=c.getResponseCode();
            if(code<200||code>=300)throw new IOException("Ядро ответило HTTP "+code);
            if(code==204)return new JSONObject();
            return new JSONObject(new String(Subscriptions.bounded(c.getInputStream()),java.nio.charset.StandardCharsets.UTF_8));
        }finally{c.disconnect();}
    }
    private void readStats(){if(state!=CONNECTED||!wanted)return;try{JSONObject d=api("/connections");failedHealthChecks=0;long down=d.optLong("downloadTotal"),up=d.optLong("uploadTotal");rxRate=Math.max(0,down-rx);txRate=Math.max(0,up-tx);rx=down;tx=up;
        // "Отображение скорости": live rates in the notification, refreshed with the stats tick.
        if(Store.get(this).settings().optBoolean("notifySpeed"))getSystemService(NotificationManager.class).notify(7,notification(PingInfo.displayName(profileName)+"  ↓ "+MainActivity.bytes(rxRate)+"/с  ↑ "+MainActivity.bytes(txRate)+"/с"));
    }catch(Exception e){if(state==CONNECTED&&wanted&&++failedHealthChecks>=8){long connection=since;AppLog.add("Ядро sing-box перестало отвечать: "+Subscriptions.safeError(e));wanted=false;worker.execute(()->{if(state==CONNECTED&&since==connection){shutdownCore(false);CoreRouter.stopped(this);stopSelf();}});}}}
    @Override public int openTun(TunOptions options)throws Exception{
        Builder b=new Builder().setSession("Wraith").setMtu(options.getMTU()).setConfigureIntent(PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE));
        if(Build.VERSION.SDK_INT>=29)b.setMetered(false);
        RoutePrefixIterator v4=options.getInet4Address();while(v4.hasNext()){RoutePrefix p=v4.next();b.addAddress(p.address(),p.prefix());}
        RoutePrefixIterator v6=options.getInet6Address();while(v6.hasNext()){RoutePrefix p=v6.next();b.addAddress(p.address(),p.prefix());}
        if(options.getAutoRoute()){
            StringIterator dns=options.getDNSServerAddress();while(dns.hasNext())b.addDnsServer(dns.next());
            RoutePrefixIterator ranges=options.getInet4RouteRange();while(ranges.hasNext()){RoutePrefix p=ranges.next();b.addRoute(p.address(),p.prefix());}
            ranges=options.getInet6RouteRange();while(ranges.hasNext()){RoutePrefix p=ranges.next();b.addRoute(p.address(),p.prefix());}
        }
        JSONObject s=Store.get(this).settings();String appMode=s.optString("appMode","off");String apps=appMode.equals("off")?"":s.optString("apps").trim();boolean include=appMode.equals("include");
        if(!apps.isEmpty()) {int added=0;for(String app:apps.split("[\\n,]")){if(app.trim().isEmpty())continue;try{if(include)b.addAllowedApplication(app.trim());else b.addDisallowedApplication(app.trim());added++;}catch(android.content.pm.PackageManager.NameNotFoundException e){AppLog.add("Приложение удалено: "+app.trim());}}if(include&&added==0)throw new IllegalStateException("В списке VPN нет установленных приложений.");}
        if(underlying!=null)b.setUnderlyingNetworks(new Network[]{underlying});
        tun=b.establish();if(tun==null)throw new IOException("Android не создал VPN-интерфейс.");return tun.getFd();
    }
    @Override public boolean usePlatformAutoDetectInterfaceControl(){return true;}
    @Override public void autoDetectInterfaceControl(int fd)throws Exception{if(!protect(fd))throw new IOException("Не удалось защитить исходящий сокет от петли VPN.");}
    @Override public boolean useProcFS(){return Build.VERSION.SDK_INT<29;}
    @Override public ConnectionOwner findConnectionOwner(int protocol,String source,int sourcePort,String dest,int destPort)throws Exception{
        if(Build.VERSION.SDK_INT<29)throw new UnsupportedOperationException();int uid=connectivity.getConnectionOwnerUid(protocol,new InetSocketAddress(source,sourcePort),new InetSocketAddress(dest,destPort));if(uid<0)throw new IOException("Владелец соединения не найден");
        String[] packages=getPackageManager().getPackagesForUid(uid);ConnectionOwner owner=new ConnectionOwner();owner.setUserId(uid);owner.setUserName(packages==null?"":packages[0]);owner.setAndroidPackageNames(strings(packages==null?List.of():Arrays.asList(packages)));return owner;
    }
    @Override public void startDefaultInterfaceMonitor(InterfaceUpdateListener update){
        listener=update;networkCallback=new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){refreshNetwork();}@Override public void onLost(Network n){refreshNetwork();}@Override public void onCapabilitiesChanged(Network n,NetworkCapabilities c){refreshNetwork();}@Override public void onLinkPropertiesChanged(Network n,LinkProperties l){refreshNetwork();}};
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),networkCallback);refreshNetwork();
    }
    private synchronized void refreshNetwork(){
        InterfaceUpdateListener l=listener;if(l==null)return;
        Network best=null;int bestScore=-1;for(Network n:connectivity.getAllNetworks()){NetworkCapabilities cap=connectivity.getNetworkCapabilities(n);if(cap==null||!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)||!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;int score=(cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?4:0)+(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?2:1);if(score>bestScore){best=n;bestScore=score;}}
        underlying=best;
        try{if(best==null){l.updateDefaultInterface("",-1,false,false);return;}LinkProperties lp=connectivity.getLinkProperties(best);if(lp==null||lp.getInterfaceName()==null)return;java.net.NetworkInterface ni=java.net.NetworkInterface.getByName(lp.getInterfaceName());NetworkCapabilities cap=connectivity.getNetworkCapabilities(best);if(ni!=null){l.updateDefaultInterface(ni.getName(),ni.getIndex(),cap!=null&&!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),false);l.updateNetworkPath(ni.getName());setUnderlyingNetworks(new Network[]{best});}}catch(Exception e){AppLog.add("Смена сети: "+Subscriptions.safeError(e));}
    }
    @Override public synchronized void closeDefaultInterfaceMonitor(InterfaceUpdateListener ignored){listener=null;if(networkCallback!=null){try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception e){}networkCallback=null;}}
    @Override public NetworkInterfaceIterator getInterfaces()throws Exception{
        List<io.nekohasekai.libbox.NetworkInterface> result=new ArrayList<>();
        for(java.net.NetworkInterface ni:Collections.list(java.net.NetworkInterface.getNetworkInterfaces())){
            if(ni.getName().equals(ownInterface))continue;io.nekohasekai.libbox.NetworkInterface n=new io.nekohasekai.libbox.NetworkInterface();n.setName(ni.getName());n.setIndex(ni.getIndex());n.setMTU(Math.max(ni.getMTU(),1280));int flags=0;if(ni.isUp())flags|=OsConstants.IFF_UP|OsConstants.IFF_RUNNING;if(ni.isLoopback())flags|=OsConstants.IFF_LOOPBACK;if(ni.isPointToPoint())flags|=OsConstants.IFF_POINTOPOINT;if(ni.supportsMulticast())flags|=OsConstants.IFF_MULTICAST;n.setFlags(flags);
            List<String> addresses=new ArrayList<>();for(InterfaceAddress a:ni.getInterfaceAddresses())addresses.add(a.getAddress().getHostAddress().split("%")[0]+"/"+a.getNetworkPrefixLength());n.setAddresses(strings(addresses));n.setType(Libbox.InterfaceTypeOther);List<String> dns=new ArrayList<>();List<String> gateways=new ArrayList<>();
            for(Network network:connectivity.getAllNetworks()){LinkProperties lp=connectivity.getLinkProperties(network);if(lp==null||!ni.getName().equals(lp.getInterfaceName()))continue;for(InetAddress a:lp.getDnsServers())dns.add(a.getHostAddress());for(RouteInfo route:lp.getRoutes())if(route.getGateway()!=null)gateways.add(route.getGateway().getHostAddress());NetworkCapabilities cap=connectivity.getNetworkCapabilities(network);if(cap!=null){n.setType(cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)?Libbox.InterfaceTypeWIFI:cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)?Libbox.InterfaceTypeCellular:Libbox.InterfaceTypeEthernet);n.setMetered(!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED));}}
            n.setDNSServer(strings(dns));n.setGateway(strings(gateways));result.add(n);
        }
        Iterator<io.nekohasekai.libbox.NetworkInterface> it=result.iterator();return new NetworkInterfaceIterator(){public boolean hasNext(){return it.hasNext();}public io.nekohasekai.libbox.NetworkInterface next(){return it.next();}};
    }
    static StringIterator strings(List<String> list){Iterator<String> it=list.iterator();return new StringIterator(){public boolean hasNext(){return it.hasNext();}public String next(){return it.next();}public int len(){return list.size();}};}
    @Override public LocalDNSTransport localDNSTransport(){return null;}
    @Override public boolean underNetworkExtension(){return false;}
    @Override public boolean includeAllNetworks(){return false;}
    @Override public WIFIState readWIFIState(){return new WIFIState("","");}
    @Override public void clearDNSCache(){}
    @Override public void registerMyInterface(String name){ownInterface=name;}
    @Override public void sendNotification(io.nekohasekai.libbox.Notification n){AppLog.add(n.getTitle()+": "+n.getBody());}
    @Override public void cancelNotification(String id,int type){}
    @Override public void startNeighborMonitor(NeighborUpdateListener l){}
    @Override public void closeNeighborMonitor(NeighborUpdateListener l){}
    @Override public boolean usePlatformShell(){return false;}
    @Override public void checkPlatformShell(){throw new UnsupportedOperationException("Shell недоступен");}
    @Override public ShellSession openShellSession(PlatformUser u,String c,StringIterator e,String term,int r,int col){throw new UnsupportedOperationException();}
    @Override public PlatformUser lookupUser(String u){throw new UnsupportedOperationException();}
    @Override public String lookupSFTPServer(){throw new UnsupportedOperationException();}
    @Override public String readSystemSSHHostKey(){throw new UnsupportedOperationException();}
    @Override public String tailscaleHostname(){return "wraith";}
    @Override public boolean usePlatformBridge(){return false;}
    @Override public BridgeSession createBridge(BridgeOptions o){throw new UnsupportedOperationException();}
    @Override public void serviceStop(){wanted=false;worker.execute(()->{shutdownCore(false);CoreRouter.stopped(this);stopSelf();});}
    @Override public void serviceReload(){worker.execute(()->{shutdownCore(true);if(wanted)connect();});}
    @Override public SystemProxyStatus getSystemProxyStatus(){return new SystemProxyStatus();}
    @Override public void setSystemProxyEnabled(boolean e){throw new UnsupportedOperationException();}
    @Override public void triggerNativeCrash(){throw new UnsupportedOperationException();}
    @Override public void writeDebugMessage(String m){AppLog.add(m);}
    @Override public int connectSSHAgent(){throw new UnsupportedOperationException();}
}
