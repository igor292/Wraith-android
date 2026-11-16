package app.wraith.client;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.json.JSONObject;

/** One Quick Settings tile that toggles the currently selected server. */
public final class QuickTile extends TileService {
    static void sync(Context context) {
        // The tile may not be added, and some vendor ROMs throw here. A tile refresh must never
        // break the caller: this runs from Store.select and from the connection service.
        try { TileService.requestListeningState(context,new ComponentName(context,QuickTile.class)); }
        catch (Exception ignored) {}
    }

    @Override public void onStartListening() {
        super.onStartListening();
        CoreRouter.verifyStatus(this);
        Tile tile=getQsTile();if(tile==null)return;
        JSONObject selected=null;try{selected=Store.get(this).selected();}catch(Exception ignored){}
        String selectedServer=selected==null?"Сервер не выбран":PingInfo.displayName(selected.optString("name","Выбранный сервер"));
        int state=WraithService.state;
        String activeServer=WraithService.profileName==null||WraithService.profileName.isBlank()?selectedServer:PingInfo.displayName(WraithService.profileName);
        tile.setLabel(state==WraithService.CONNECTED?activeServer:"Wraith");
        if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(state==WraithService.CONNECTED?"Подключено · "+activeServer:state==WraithService.CONNECTING?"Подключение · "+activeServer:selectedServer);
        tile.setContentDescription(state==WraithService.CONNECTED?"Wraith подключён к "+activeServer:"Wraith · "+selectedServer);
        tile.setState(selected==null?Tile.STATE_UNAVAILABLE:state==WraithService.CONNECTED||state==WraithService.CONNECTING?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        if(Build.VERSION.SDK_INT>=30)tile.setStateDescription(state==WraithService.CONNECTED?"Подключено":state==WraithService.CONNECTING?"Подключение":state==WraithService.STOPPING?"Отключение":"Выключено");
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        CoreRouter.verifyStatus(this);
        if(WraithService.state!=WraithService.OFF){WraithService.stop(this);sync(this);return;}
        JSONObject selected=null;try{selected=Store.get(this).selected();}catch(Exception ignored){}
        if(selected!=null&&VpnService.prepare(this)==null){WraithService.start(this);sync(this);return;}
        openApp();
    }

    private void openApp(){
        Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending=PendingIntent.getActivity(this,0,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(pending);else try{pending.send();}catch(PendingIntent.CanceledException ignored){}
    }
}
