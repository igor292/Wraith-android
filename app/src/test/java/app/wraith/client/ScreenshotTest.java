package app.wraith.client;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import androidx.test.core.app.ApplicationProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Looper;

/** Renders the main screens to PNG files (build/screens) so the UI can be reviewed without a device. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=34,qualifiers="w400dp-h860dp-xhdpi")
public class ScreenshotTest {
    static JSONObject state(boolean withServers,String theme)throws Exception{
        JSONArray profiles=new JSONArray(),subs=new JSONArray();
        if(withServers){
            subs.put(Json.obj("id","sub1","name","My VPN Provider","url","https://example.com/sub","interval",24,"updated",System.currentTimeMillis()-3600_000L,
                "subscription-userinfo","upload=1073741824; download=16106127360; total=107374182400; expire="+(System.currentTimeMillis()/1000+30*86400L),
                "sub-info-text","Новые серверы в Финляндии уже доступны","sub-info-color","green"));
            String[][] nodes={{"🇩🇪 Germany · Frankfurt","de.example.com","42"},{"🇳🇱 Netherlands","nl.example.com","58"},{"🇫🇮 Finland · Helsinki","fi.example.com","-1"},{"🇺🇸 USA · New York","us.example.com","131"}};
            for(int i=0;i<nodes.length;i++){
                JSONObject out=Json.obj("type","vless","server",nodes[i][1],"server_port",443,"uuid","00000000-0000-0000-0000-00000000000"+i,"tls",Json.obj("enabled",true,"reality",Json.obj("enabled",true)));
                JSONObject p=Json.obj("id","p"+i,"name",nodes[i][0],"outbound",out,"source","","group","sub1","favorite",i==1);
                int ms=Integer.parseInt(nodes[i][2]);if(ms>=0)p.put("latency",ms);else p.put("pingError","timeout");
                profiles.put(p);
            }
            profiles.put(Json.obj("id","p9","name","Мой сервер","outbound",Json.obj("type","hysteria2","server","my.example.org","server_port",8443),"source","","group",""));
        }
        return Json.obj("profiles",profiles,"subscriptions",subs,"rules",new JSONArray().put(Json.obj("name","Банки напрямую","kind","domain_suffix","value","sberbank.ru\ntinkoff.ru","action","direct","enabled",true)),
            "settings",Json.obj("theme",theme,"anonymousStatsConsent","declined","dynamicColor",false),"selected",withServers?"p0":"");
    }

    static void shoot(String name,int tab,String page,boolean withServers,String theme,int state)throws Exception{
        Store.useInMemory(ApplicationProvider.getApplicationContext(),state(withServers,theme));
        WraithService.state=state;WraithService.since=android.os.SystemClock.elapsedRealtime()-754_000;WraithService.rxRate=2_450_000;WraithService.txRate=180_000;WraithService.profileName=withServers?"🇩🇪 Germany · Frankfurt":"";
        Bundle saved=new Bundle();saved.putInt("tab",tab);saved.putString("settingsPage",page);
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(900));
        MainActivity activity=controller.get();
        View decor=activity.getWindow().getDecorView();
        save(decor,decor.getWidth(),decor.getHeight(),name);
        // The whole scrolling page, so content below the fold is reviewed too.
        ScrollView scroll=find(decor,ScrollView.class);
        if(scroll!=null){View content=scroll.getChildAt(0);int w=decor.getWidth();content.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));content.layout(0,0,w,content.getMeasuredHeight());
            Bitmap full=Bitmap.createBitmap(w,content.getMeasuredHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(full);c.drawColor(Ui.BG);content.draw(c);write(full,name+"-full");}
        controller.pause().stop().destroy();
    }
    static <T> T find(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T t=find(((ViewGroup)v).getChildAt(i),type);if(t!=null)return t;}return null;}
    static void save(View v,int w,int h,String name)throws Exception{Bitmap b=Bitmap.createBitmap(Math.max(1,w),Math.max(1,h),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));write(b,name);}
    static void write(Bitmap b,String name)throws Exception{File dir=new File(System.getProperty("wraith.screens","build/screens"));dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}

    @Test public void screens()throws Exception{
        if(System.getProperty("wraith.screens.enabled")==null&&System.getenv("WRAITH_SCREENS")==null)return;
        shoot("home-dark-connected",MainActivity.HOME,"",true,"dark",WraithService.CONNECTED);
        shoot("home-light-off",MainActivity.HOME,"",true,"light",WraithService.OFF);
        shoot("home-dark-empty",MainActivity.HOME,"",false,"dark",WraithService.OFF);
        shoot("settings-dark",MainActivity.SETTINGS,"",true,"dark",WraithService.OFF);
        shoot("settings-light",MainActivity.SETTINGS,"",true,"light",WraithService.OFF);
        shoot("tunnel-dark",MainActivity.SETTINGS,"tunnel",true,"dark",WraithService.OFF);
        shoot("routes-light",MainActivity.SETTINGS,"routes",true,"light",WraithService.OFF);
        shoot("logs-dark",MainActivity.LOGS,"",true,"dark",WraithService.OFF);
        shoot("stats-dark",MainActivity.SETTINGS,"stats",true,"dark",WraithService.CONNECTED);
        shoot("ping-dark",MainActivity.SETTINGS,"ping",true,"dark",WraithService.OFF);
        WraithService.state=WraithService.OFF;
    }
}
