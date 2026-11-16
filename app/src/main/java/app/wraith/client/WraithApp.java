package app.wraith.client;

import android.app.Application;
import io.nekohasekai.libbox.*;
import java.io.File;
import java.util.concurrent.*;
import android.content.*;
import android.app.ActivityManager;
import android.os.Build;
import androidx.core.content.ContextCompat;

public final class WraithApp extends Application {
    public static final ExecutorService IO=Executors.newFixedThreadPool(4);
    public static volatile String coreError="";
    public static final String ACTION_CORE_STATE="app.wraith.client.CORE_STATE";
    /** Single source of truth for the version shown in the UI, the core and the subscription User-Agent. */
    public static final String VERSION=BuildConfig.VERSION_NAME;
    @Override public void onCreate(){
        super.onCreate();
        // libbox and libXray each bundle an independent Go runtime. They must never be loaded
        // into the same OS process, so the Xray VPN service skips all libbox setup here.
        String process=processName();
        if((getPackageName()+":xray").equals(process)||(getPackageName()+":xray_probe").equals(process))return;
        CoreRouter.restoreStatus(this);
        // Material You: the wallpaper palette on Android 12+, unless the user switched it off.
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this,new com.google.android.material.color.DynamicColorsOptions.Builder().setPrecondition((activity,theme)->{try{return Store.get(activity).settings().optBoolean("dynamicColor",true);}catch(Exception e){return true;}}).build());
        ContextCompat.registerReceiver(this,new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
            CoreRouter.remoteSignal();
            WraithService.state=intent.getIntExtra("state",WraithService.OFF);
            WraithService.error=intent.getStringExtra("error");if(WraithService.error==null)WraithService.error="";
            WraithService.profileName=intent.getStringExtra("name");if(WraithService.profileName==null)WraithService.profileName="";
            WraithService.since=intent.getLongExtra("since",0);WraithService.rx=intent.getLongExtra("rx",0);WraithService.tx=intent.getLongExtra("tx",0);
            WraithService.rxRate=intent.getLongExtra("rxRate",0);WraithService.txRate=intent.getLongExtra("txRate",0);QuickTile.sync(context);
        }},new IntentFilter(ACTION_CORE_STATE),ContextCompat.RECEIVER_NOT_EXPORTED);
        try{
            go.Seq.setContext(this);
            File working=new File(getFilesDir(),"core");if(!working.exists()&&!working.mkdirs())throw new IllegalStateException("Нет доступа к каталогу ядра");
            SetupOptions options=new SetupOptions();options.setBasePath(getFilesDir().getAbsolutePath());options.setWorkingPath(working.getAbsolutePath());options.setTempPath(getCacheDir().getAbsolutePath());options.setFixAndroidStack(true);options.setLogMaxLines(200);options.setAppVersion(VERSION);options.setAppMarketingVersion("Wraith");options.setOomKillerDisabled(true);
            Libbox.setup(options);AppLog.add("Wraith "+VERSION+" · Xray основной, sing-box по необходимости · sing-box "+Libbox.version());UpdateJob.schedule(this);XrayAssets.scheduleUpdate(this);
        }catch(Throwable e){coreError=Subscriptions.safeError(e);AppLog.add("Ошибка инициализации ядра: "+coreError);}
    }
    private String processName(){if(Build.VERSION.SDK_INT>=28)return Application.getProcessName();ActivityManager am=getSystemService(ActivityManager.class);if(am!=null)for(ActivityManager.RunningAppProcessInfo p:am.getRunningAppProcesses())if(android.os.Process.myPid()==p.pid)return p.processName;return getPackageName();}
}
