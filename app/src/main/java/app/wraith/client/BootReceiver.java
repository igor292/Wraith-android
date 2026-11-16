package app.wraith.client;
import android.content.*;
import android.net.VpnService;
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){UpdateJob.schedule(c);try{if(Store.get(c).settings().optBoolean("autoConnect")&&Store.get(c).selected()!=null&&VpnService.prepare(c)==null)WraithService.start(c);}catch(Exception e){AppLog.add("Автозапуск: "+Subscriptions.safeError(e));}}
}
