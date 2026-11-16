package app.wraith.client;

import android.app.Instrumentation;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.nekohasekai.libbox.Libbox;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Controlled network tests opt in through configPath; ordinary test runs skip them. */
@RunWith(AndroidJUnit4.class)
public final class XrayLiveTest {
    private static final String ORIGINAL="integration-state-before.json";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private final Bundle arguments=InstrumentationRegistry.getArguments();

    @Test public void nativeProbe()throws Exception {
        JSONObject profile=fixture(),settings=settings();
        Bundle result=probe(profile,settings,UUID.randomUUID().toString());
        assertEquals(result.getString("error"),"",result.getString("error",""));
        assertTrue("Native probe did not return a successful latency",result.getInt("latency",-1)>0);
        report("NATIVE_PROBE_OK latencyMs="+result.getInt("latency")+" name="+profile.optString("name"));
    }

    @Test public void nativeProbeModes()throws Exception {
        JSONObject profile=fixture(),settings=settings();
        for(String method:new String[]{"GET","HEAD"})for(String mode:new String[]{"keepalive","double"}){
            Bundle result=probe(profile,settings,UUID.randomUUID().toString(),method,mode);
            assertEquals(method+"/"+mode+" failed: "+result.getString("error"),"",result.getString("error",""));
            assertTrue(method+"/"+mode+" returned no latency",result.getInt("latency",-1)>0);
            report("NATIVE_PROBE_MODE_OK method="+method+" mode="+mode+" latencyMs="+result.getInt("latency"));
        }
    }

    /** The controlled server rejects libbox's older REALITY client-version bytes. */
    @Test public void legacySingboxProbeRejected()throws Exception {
        JSONObject profile=fixture(),settings=settings();
        assertEquals("Run the legacy comparison with the VPN stopped",WraithService.OFF,WraithService.state);
        settings.put("fragment",false).put("recordFragment",false).put("noise",false).put("mux",false);
        String config=ConfigBuilder.probeConfig(profile,settings).toString();
        Throwable failure=null;
        try {
            Libbox.probeConfig(config,"node-"+profile.getString("id"),arguments.getString("probeUrl","https://example.com"),"GET","keepalive",
                Integer.parseInt(arguments.getString("probeTimeout","10000")),WraithService::protectProbeFd);
        } catch(Throwable e) {failure=e;}
        assertNotNull("Legacy sing-box unexpectedly passed the version-restricted REALITY endpoint",failure);
        String message=Subscriptions.safeError(failure);
        assertTrue("Failure must identify REALITY, rather than a generic timeout/configuration error: "+message,message.toLowerCase(Locale.ROOT).contains("reality"));
        report("LEGACY_REALITY_REJECTED "+message);
    }

    @Test public void nativeProbeBatch()throws Exception {
        JSONObject profile=fixture(),settings=settings();
        CountDownLatch completed=new CountDownLatch(2);
        List<Bundle> results=Collections.synchronizedList(new ArrayList<>());
        String session=UUID.randomUUID().toString();
        try {
            for(int i=0;i<2;i++)sendProbe(profile,settings,session,new Messenger(new Handler(Looper.getMainLooper(),message->{if(message.what==XrayProbeService.RESULT){results.add(message.getData());completed.countDown();}return true;})));
            assertTrue("Two native probes did not both finish",completed.await(40,TimeUnit.SECONDS));
            assertEquals(2,results.size());
            for(Bundle result:results){assertEquals(result.getString("error"),"",result.getString("error",""));assertTrue(result.getInt("latency",-1)>0);}
            report("NATIVE_BATCH_OK count="+results.size());
        } finally {cancelProbe(session);}
    }

    @Test public void startTunnel()throws Exception {runTunnel(false);}
    @Test public void restartTunnel()throws Exception {runTunnel(true);}

    private void runTunnel(boolean restart)throws Exception {
        JSONObject profile=fixture(),settings=settings();
        boolean leave=Boolean.parseBoolean(arguments.getString("leaveRunning","false"));
        Store store=Store.get(context);
        File backup=new File(context.getCacheDir(),ORIGINAL);
        if(!backup.exists())write(backup,store.backup());
        boolean successful=false;
        StateReceiver status=new StateReceiver(context);
        ContextCompat.registerReceiver(context,status,new IntentFilter(WraithApp.ACTION_CORE_STATE),ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            assertNull("Grant VPN app-op on the test emulator before running",VpnService.prepare(context));
            if(!restart&&WraithService.state!=WraithService.OFF){CoreRouter.stop(context);status.await(WraithService.OFF,10000);}
            JSONObject state=Json.obj("format","wraith-backup-v1","profiles",Json.arr(profile),"subscriptions",new JSONArray(),"rules",new JSONArray(),"settings",settings,"selected",profile.getString("id"));
            store.restore(state.toString());
            assertEquals("Fixture must exercise native Xray",CoreRouter.XRAY,CoreRouter.engine(profile,settings));
            status.reset();
            if(restart)CoreRouter.restart(context);else CoreRouter.start(context);
            status.await(WraithService.CONNECTED,30000);
            assertEquals(profile.optString("name"),status.name);
            assertEquals(CoreRouter.XRAY,context.getSharedPreferences("wraith_core",Context.MODE_PRIVATE).getString("active_engine",""));
            report("XRAY_LIVE_READY name="+status.name+" holdSeconds="+arguments.getString("holdSeconds","20"));
            long hold=Math.max(0,Long.parseLong(arguments.getString("holdSeconds","20")))*1000;
            long until=SystemClock.elapsedRealtime()+hold;
            while(SystemClock.elapsedRealtime()<until){Thread.sleep(Math.min(500,Math.max(1,until-SystemClock.elapsedRealtime())));assertEquals("Tunnel stopped during traffic test: "+status.error,WraithService.CONNECTED,status.state);}
            JSONObject metrics=metrics();
            JSONObject counters=metrics.optJSONObject("stats");
            report("XRAY_LIVE_COUNTERS "+(counters==null?metrics:counters).toString());
            if(Boolean.parseBoolean(arguments.getString("requireTraffic","false"))){
                assertNotNull("Xray metrics did not expose stats",counters);
                JSONObject inbounds=counters.optJSONObject("inbound");JSONObject tun=inbounds==null?null:inbounds.optJSONObject("tun-in");
                assertNotNull("Xray did not report tun-in counters",tun);
                assertTrue("No TCP/UDP bytes returned through TUN",tun.optLong("downlink")>0);
                assertTrue("No TCP/UDP bytes entered TUN",tun.optLong("uplink")>0);
            }
            successful=true;
        } finally {
            if(!leave||!successful){
                CoreRouter.stop(context);
                try{status.await(WraithService.OFF,10000);}catch(Exception ignored){}finally{if(backup.exists()){store.restore(read(backup));assertTrue("Unable to remove integration state backup",backup.delete());}}
            }
            context.unregisterReceiver(status);
        }
    }

    @Test public void stopTunnel()throws Exception {
        Assume.assumeTrue("Live stop requires configPath or stopLive=true",!arguments.getString("configPath","").isEmpty()||Boolean.parseBoolean(arguments.getString("stopLive","false")));
        StateReceiver status=new StateReceiver(context);
        ContextCompat.registerReceiver(context,status,new IntentFilter(WraithApp.ACTION_CORE_STATE),ContextCompat.RECEIVER_NOT_EXPORTED);
        try {
            CoreRouter.stop(context);status.await(WraithService.OFF,10000);
            File backup=new File(context.getCacheDir(),ORIGINAL);
            if(backup.exists()){Store.get(context).restore(read(backup));assertTrue(backup.delete());}
            report("XRAY_LIVE_STOPPED");
        } finally {context.unregisterReceiver(status);}
    }

    private JSONObject fixture()throws Exception {
        String encoded=arguments.getString("configBase64","");
        if(!encoded.isEmpty())return XrayConfig.profile(new JSONObject(new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8)),new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8));
        String path=arguments.getString("configPath","");
        Assume.assumeTrue("Live tests require a controlled synthetic configPath",!path.isEmpty());
        JSONObject root=new JSONObject(shellRead(path));
        JSONObject profile=XrayConfig.profile(root,root.toString());
        String name=arguments.getString("profileName",profile.optString("name","Xray Integration"));
        profile.put("name",name);return profile;
    }

    private JSONObject settings()throws Exception {
        JSONObject settings=Json.obj("localProxy",true,"proxyPort",2081,"debugLogs",true,"dnsFromJson",true,"appMode","off","mode","rules","fragment",false,"recordFragment",false,"noise",false,"mux",false,"autoSelect",false,"autoUpdate",false);
        String path=arguments.getString("settingsPath","");
        String inline=arguments.getString("settingsJson","");
        JSONObject overrides=!path.isEmpty()?new JSONObject(shellRead(path)):inline.isEmpty()?new JSONObject():new JSONObject(inline);
        Iterator<String> keys=overrides.keys();while(keys.hasNext()){String key=keys.next();settings.put(key,overrides.get(key));}
        return settings;
    }

    private Bundle probe(JSONObject profile,JSONObject settings,String session)throws Exception {
        return probe(profile,settings,session,"GET","keepalive");
    }
    private Bundle probe(JSONObject profile,JSONObject settings,String session,String method,String mode)throws Exception {
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<Bundle> result=new AtomicReference<>();
        try {
            sendProbe(profile,settings,session,new Messenger(new Handler(Looper.getMainLooper(),message->{if(message.what==XrayProbeService.RESULT){result.set(message.getData());done.countDown();}return true;})),method,mode);
            assertTrue("Native Xray probe timed out",done.await(40,TimeUnit.SECONDS));
            assertNotNull("Native Xray probe returned no bundle",result.get());return result.get();
        } finally {cancelProbe(session);}
    }
    private void sendProbe(JSONObject profile,JSONObject settings,String session,Messenger receiver){
        sendProbe(profile,settings,session,receiver,"GET","keepalive");
    }
    private void sendProbe(JSONObject profile,JSONObject settings,String session,Messenger receiver,String method,String mode){
        Intent request=new Intent(context,XrayProbeService.class).setAction(XrayProbeService.ACTION)
            .putExtra(XrayProbeService.EXTRA_PROFILE,profile.toString()).putExtra(XrayProbeService.EXTRA_SETTINGS,settings.toString())
            .putExtra(XrayProbeService.EXTRA_URL,arguments.getString("probeUrl","https://example.com"))
            .putExtra(XrayProbeService.EXTRA_TIMEOUT,Integer.parseInt(arguments.getString("probeTimeout","10000")))
            .putExtra(XrayProbeService.EXTRA_METHOD,method).putExtra(XrayProbeService.EXTRA_MODE,mode)
            .putExtra(XrayProbeService.EXTRA_SESSION,session).putExtra(XrayProbeService.EXTRA_MESSENGER,receiver);
        context.startService(request);
    }
    private void cancelProbe(String session){context.startService(new Intent(context,XrayProbeService.class).setAction(XrayProbeService.ACTION_CANCEL).putExtra(XrayProbeService.EXTRA_SESSION,session));}
    private JSONObject metrics()throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+XrayRuntimeConfig.metricsPort+"/debug/vars").openConnection(Proxy.NO_PROXY);
        try{connection.setConnectTimeout(3000);connection.setReadTimeout(3000);return new JSONObject(new String(Subscriptions.bounded(connection.getInputStream()),StandardCharsets.UTF_8));}finally{connection.disconnect();}
    }
    private void report(String text){Bundle status=new Bundle();status.putString("stream","\n"+text+"\n");instrumentation.sendStatus(1,status);}
    private String shellRead(String path)throws Exception {
        if(!path.startsWith("/data/local/tmp/")||path.indexOf('\n')>=0||path.indexOf('\r')>=0)throw new IllegalArgumentException("Fixture paths must be under /data/local/tmp/");
        try(ParcelFileDescriptor pipe=instrumentation.getUiAutomation().executeShellCommand("cat '"+path.replace("'","'\\''")+"'")){
            return new String(Subscriptions.bounded(new FileInputStream(pipe.getFileDescriptor())),StandardCharsets.UTF_8);
        }
    }
    private static String read(File file)throws Exception {return new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static void write(File file,String value)throws Exception {try(FileOutputStream out=new FileOutputStream(file)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private static final class StateReceiver extends BroadcastReceiver {
        final Context context;volatile int state=-1;volatile String name="",error="";
        StateReceiver(Context context){this.context=context;}
        @Override public void onReceive(Context context,Intent intent){state=intent.getIntExtra("state",-1);name=intent.getStringExtra("name");error=intent.getStringExtra("error");}
        void reset(){state=-1;name=error="";}
        void await(int expected,long timeout)throws Exception {
            long deadline=SystemClock.elapsedRealtime()+timeout;
            while(state!=expected&&SystemClock.elapsedRealtime()<deadline){readStatus();if(expected==WraithService.CONNECTED&&state==WraithService.OFF&&error!=null&&!error.isEmpty())fail("Xray startup failed: "+error);Thread.sleep(50);}
            assertEquals("Core state: "+state+" error="+error,expected,state);
        }
        private void readStatus(){try(FileInputStream input=context.openFileInput("core-status.json")){byte[] bytes=new byte[input.available()];int count=input.read(bytes);if(count<=0)return;JSONObject status=new JSONObject(new String(bytes,0,count,StandardCharsets.UTF_8));state=status.optInt("state",-1);name=status.optString("name","");error=status.optString("error","");}catch(Exception ignored){}}
    }
}
