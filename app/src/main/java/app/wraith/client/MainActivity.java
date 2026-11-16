package app.wraith.client;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.content.pm.*;
import android.graphics.*;
import app.wraith.client.Ui.Icon;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.integration.android.*;
import androidx.core.content.FileProvider;
import io.nekohasekai.libbox.Libbox;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import app.wraith.client.Ui.Glyph;

import static app.wraith.client.Ui.*;

public final class MainActivity extends androidx.appcompat.app.AppCompatActivity {
    /** Home and the server list are one screen (SERVERS is kept as an alias of HOME). */
    static final int HOME=0,SERVERS=0,SETTINGS=1,LOGS=2;
    private Store store;private LinearLayout root,body;private BottomNavigationView nav;private ExtendedFloatingActionButton fab;private ScrollView scroll;private int tab=0,lastState=-1;private String search="",groupFilter="";
    private final Set<String> collapsedGroups=new HashSet<>();private final Set<String> pingingIds=ConcurrentHashMap.newKeySet();private final Object pingLock=new Object();private int pendingPingTasks; private String settingsPage="";
    private final Map<String,TextView> profileMetaViews=new HashMap<>();
    private final Map<String,LinearLayout> profileLists=new LinkedHashMap<>();private final Map<String,View> profileRows=new LinkedHashMap<>();
    private View notice;private int noticeSerial;
    private TextView stateLabel,stateHint,sessionInfo,connectionCaption;private View checkConnectionButton;private Orb orb;
    /** Last state seen by the tick, used to notice "was connecting/connected, now off with an error". */
    private int watchedState=-1;private String shownError="";
    /** Live values of the open statistics page; refreshed by the tick instead of rebuilding the page. */
    private Runnable statsUpdater;
    private final Handler handler=new Handler(Looper.getMainLooper());private boolean busy;private String exportData="";private String exportName="";
    private LinearLayout serverResults;private MaterialButton pingButton;private volatile boolean pingRunning;private volatile AtomicBoolean pingCancelled;private volatile ExecutorService pingPool;private long pingRunId;private String pingSession="",justSelected="";private int tabFrom=-1,settingsRootScroll=0,restoreScroll=-1;
    private ValueAnimator pingButtonAnimator;
    private UpdateChecker.Result pendingUpdate;
    // Only the live parts of the page are redrawn on a state change. Rebuilding the whole body
    // made the screen blink on every connect step, ping result and server selection.
    private final Runnable tick=new Runnable(){public void run(){CoreRouter.verifyStatus(MainActivity.this);int state=WraithService.state;
        // A failed connection used to fall back to "Готов к подключению" with no explanation;
        // the reason only existed in the logs.
        if((watchedState==WraithService.CONNECTING||watchedState==WraithService.CONNECTED)&&state==WraithService.OFF){String failure=WraithService.error==null?"":WraithService.error;if(!failure.isEmpty()&&!failure.equals(shownError)){shownError=failure;error(new IllegalStateException("Не удалось подключиться: "+failure));}}
        watchedState=state;
        if(lastState!=state){lastState=state;refresh();}updateHome();if(statsUpdater!=null&&tab==SETTINGS&&settingsPage.equals("stats"))statsUpdater.run();handler.postDelayed(this,1000);}};
    /** Redraws the server list in place, keeping scroll position, search text and keyboard state. */
    private void refreshServers(){if(tab==SERVERS&&serverResults!=null)drawServerResults(serverResults,false);}
    /** Cheap refresh of the current page; falls back to a full rebuild only when needed. */
    private void refresh(){if(isFinishing())return;if((tab==SERVERS&&serverResults!=null)||(tab==HOME&&stateLabel!=null)){refreshServers();updateHome();}else render();}
    @Override public void onCreate(Bundle b){
        String theme="system";try{store=Store.get(this);theme=store.settings().optString("theme","system");}catch(Exception ignored){}
        // Night mode must be known before the first frame, otherwise the M3 palette flashes.
        getDelegate().setLocalNightMode(Ui.nightMode(theme));
        super.onCreate(b);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        if(store==null){Ui.applyTheme(this,1f);new InlinePanel.Builder(this).setTitle("Хранилище недоступно").setMessage("Не удалось открыть зашифрованное хранилище. Данные сохранены; не очищайте приложение.").setPositiveButton("Закрыть",(d,w)->finish()).show();return;}
        try{if(b==null)store.clearPingResults();}catch(Exception ignored){}
        if(b!=null){tab=Math.max(HOME,Math.min(LOGS,b.getInt("tab",HOME)));settingsPage=b.getString("settingsPage","");}
        applyTheme();buildRoot();render();handleIntent(getIntent());if(b==null)onOpen();else requestNotificationPermission();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);b.putString("settingsPage",settingsPage);}
    @Override protected void onResume(){super.onResume();if(store!=null)handler.post(tick);if(pendingUpdate!=null&&(Build.VERSION.SDK_INT<26||getPackageManager().canRequestPackageInstalls())){UpdateChecker.Result update=pendingUpdate;pendingUpdate=null;handler.post(()->downloadUpdate(update));}}
    @Override protected void onPause(){handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    private void handleIntent(Intent i){if(i==null)return;String input=i.getDataString();if(Intent.ACTION_SEND.equals(i.getAction()))input=i.getStringExtra(Intent.EXTRA_TEXT);if(input!=null&&urlCommand(input)){setIntent(new Intent());return;}if(input!=null){final String text=input;handler.post(()->importDialog(text));setIntent(new Intent());}}
    /**
     * Happ-compatible control links: wraith:// or happ:// connect, disconnect, toggle, open, close,
     * each with a _without_ui variant that does the job and leaves the screen.
     */
    private boolean urlCommand(String link){
        String l=link.trim();
        String cmd=l.startsWith("wraith://")?l.substring(9):l.startsWith("happ://")?l.substring(7):null;
        if(cmd==null)return false;
        while(cmd.endsWith("/"))cmd=cmd.substring(0,cmd.length()-1);
        boolean quiet=cmd.endsWith("_without_ui");
        String action=quiet?cmd.substring(0,cmd.length()-"_without_ui".length()):cmd;
        if(!Set.of("connect","open","disconnect","close","toggle").contains(action))return false;
        boolean on=WraithService.state!=WraithService.OFF;
        boolean start=action.equals("connect")||action.equals("open")||(action.equals("toggle")&&!on);
        if(start&&!on){
            if(store.selected()==null){toast("Сначала добавьте сервер");return true;}
            // Starting needs the Android VPN permission once; that dialog needs this screen.
            if(android.net.VpnService.prepare(this)!=null){toggleConnection();return true;}
            WraithService.start(this);
        }else if(!start&&on){
            // Any web page can open a wraith:// link. Silently dropping the tunnel from a page would
            // expose the user's real traffic, so turning it off by link always asks first.
            handler.post(()->confirm("Отключить VPN?","Другое приложение или сайт просит отключить VPN.",()->WraithService.stop(this)));
            return true;
        }
        if(quiet)handler.postDelayed(this::finish,300);
        return true;
    }
    /** Palette, font and system bar colours from the settings; called before any view is built. */
    /** M3 tokens from the (possibly dynamic) theme, font scale from the settings, edge-to-edge bars. */
    private void applyTheme(){
        JSONObject s=store.settings();String font=s.optString("fontSize","normal");
        Ui.applyTheme(this,font.equals("large")?1.15f:font.equals("small")?0.9f:1f);
        getWindow().getDecorView().setBackgroundColor(BG);
        androidx.core.view.WindowInsetsControllerCompat bars=androidx.core.view.WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!DARK);bars.setAppearanceLightNavigationBars(!DARK);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
    }
    /** Launch-time actions from Настройки → Подписки: update, ping, connect. */
    private void onOpen(){
        JSONObject s=store.settings();
        runOnOpenActions(s);
    }
    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);}
    private void runOnOpenActions(JSONObject s){
        requestNotificationPermission();
        boolean update=s.optBoolean("updateOnOpen",false),ping=s.optBoolean("pingOnOpen"),connect=s.optBoolean("connectOnOpen");
        checkUpdatesOnOpen();
        if(connect&&WraithService.state==WraithService.OFF&&store.selected()!=null)handler.post(this::toggleConnection);
        if(!update&&!ping)return;
        WraithApp.IO.execute(()->{
            if(update)Subscriptions.updateAll(this,true);
            runOnUiThread(()->{if(isFinishing())return;if(tab==HOME||tab==SERVERS)refresh();if(ping)pingAll();});
        });
    }
    /** Startup update checks stay silent unless a newer signed APK is available. */
    private void checkUpdatesOnOpen(){
        WraithApp.IO.execute(()->{try{
            UpdateChecker.Result result=UpdateChecker.check(this);
            runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(result.available)showUpdateDialog(result);});
        }catch(Exception ignored){}});
    }
    private int d(float v){return dp(this,v);}
    private boolean syncingNav;
    /** Edge-to-edge page: scrolling content, M3 navigation bar, extended FAB on the Servers tab. */
    private void buildRoot(){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(BG);
        root=column(this);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        body=column(this);body.setFocusableInTouchMode(true);body.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);pad(body,16,0,16,104);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav=new BottomNavigationView(this);nav.setLabelVisibilityMode(NavigationBarView.LABEL_VISIBILITY_LABELED);nav.setBackgroundColor(CARD);
        String[] labels={"Главная","Настройки","Журнал"},icons={"home","settings","logs"};
        for(int i=0;i<labels.length;i++)nav.getMenu().add(Menu.NONE,i,i,labels[i]).setIcon(new Glyph(this,icons[i]));
        nav.setOnItemSelectedListener(item->{if(syncingNav)return true;tabFrom=tab;tab=item.getItemId();settingsPage="";search="";scroll.scrollTo(0,0);render();return true;});
        nav.setOnItemReselectedListener(item->{if(syncingNav)return;if(tab==SETTINGS&&!settingsPage.isEmpty()){settingsPage="";restoreScroll=settingsRootScroll;render();}else scroll.smoothScrollTo(0,0);});
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));
        frame.addView(root,new FrameLayout.LayoutParams(-1,-1));
        fab=new ExtendedFloatingActionButton(this);fab.setText("Добавить");fab.setIcon(new Glyph(this,"plus"));fab.setContentDescription("Добавить сервер или подписку");fab.setOnClickListener(v->importDialog(""));
        FrameLayout.LayoutParams fl=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);fl.setMargins(d(16),d(16),d(16),d(96));frame.addView(fab,fl);
        nav.addOnLayoutChangeListener((v,l,t,r,bottom,ol,ot,or,ob)->{FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)fab.getLayoutParams();int want=(bottom-t)+d(16);if(p.bottomMargin!=want){p.bottomMargin=want;fab.setLayoutParams(p);}});
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()|androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            androidx.core.graphics.Insets ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime());
            root.setPadding(bars.left,bars.top,bars.right,0);
            // The keyboard covers the navigation bar; lift the page so the focused field stays visible.
            scroll.setPadding(0,0,0,Math.max(0,ime.bottom-nav.getHeight()));
            return insets;
        });
        setContentView(frame);
    }
    private void render(){if(store==null)return;int savedScroll=restoreScroll>=0?restoreScroll:scroll.getScrollY();restoreScroll=-1;body.removeAllViews();body.requestFocus();group=null;stateLabel=null;stateHint=null;sessionInfo=null;serverCardName=null;statsUpdater=null;orb=null;serverResults=null;pingButton=null;checkConnectionButton=null;connectionCaption=null;
        syncingNav=true;if(nav.getSelectedItemId()!=tab)nav.setSelectedItemId(tab);syncingNav=false;
        if(tab==SERVERS)fab.show();else fab.hide();
        switch(tab){case HOME:home();break;case SETTINGS:switch(settingsPage){case "ping":pingSettings();break;case "tunnel":tunnelSettings();break;case "apps":appsPage();break;case "routes":routes();break;case "subscriptions":subscriptionSettings();break;case "stats":statsPage();break;case "schemes":schemesPage();break;default:settings();}break;default:logsPage();}
        closeGroup();lastState=WraithService.state;
        final int direction=tabFrom<0?0:tab>tabFrom?1:tab<tabFrom?-1:0;tabFrom=-1;
        scroll.post(()->{scroll.scrollTo(0,savedScroll);
            // M3 shared-axis feel: the new page enters from the side the user moved towards.
            int shift=direction!=0?direction*d(28):tab==SETTINGS&&!settingsPage.isEmpty()?d(28):0;
            if(shift!=0){body.setAlpha(0f);body.setTranslationX(shift);body.animate().alpha(1f).translationX(0).setDuration(Anim.SLOW).setInterpolator(Anim.out()).start();}});}
    private void space(int h){closeGroup();body.addView(new View(this),new LinearLayout.LayoutParams(1,d(h)));}
    private TextView label(String s,int size,int color){closeGroup();TextView t=text(this,s,size,color);body.addView(t);return t;}
    /** Large M3 page title with optional trailing actions; inner settings pages get a back arrow and a smaller title. */
    private LinearLayout heading(String title,String sub,View... actions){
        closeGroup();boolean inner=tab==SETTINGS&&!settingsPage.isEmpty();
        LinearLayout bar=row(this);pad(bar,inner?0:4,inner?8:28,0,4);
        if(inner)bar.addView(iconAction("back","Назад",()->{settingsPage="";restoreScroll=settingsRootScroll;render();}));
        TextView t=title(this,title,inner?22:32,TEXT);t.setMaxLines(2);if(inner)pad(t,4,0,0,0);bar.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        for(View a:actions)bar.addView(a);
        body.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        if(sub!=null&&!sub.isEmpty()){TextView s=text(this,sub,14,MUTED);pad(s,inner?52:4,2,4,0);body.addView(s);}
        space(inner?16:20);return bar;
    }
    /** M3 filled card. */
    private LinearLayout card(){LinearLayout c=column(this);pad(c,16,16,16,16);c.setBackground(bg(CARD,d(24)));return c;}
    private void addCard(View v){closeGroup();body.addView(v,new LinearLayout.LayoutParams(-1,-2));space(12);}
    /** M3 filled (primary) or tonal button with an optional leading icon. */
    private MaterialButton button(String title,String icon,boolean primary,Runnable action){
        MaterialButton b=new MaterialButton(this);b.setText(title);b.setAllCaps(false);b.setMinHeight(d(52));b.setMinimumHeight(d(52));b.setTextSize(15*FONT);b.setInsetTop(0);b.setInsetBottom(0);b.setCornerRadius(d(26));
        int fg=primary?ON_PRIMARY:ON_SECONDARY_CONTAINER;
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?MINT:SECONDARY_CONTAINER));b.setTextColor(fg);
        if(icon!=null){b.setIcon(new Glyph(this,icon));b.setIconTint(android.content.res.ColorStateList.valueOf(fg));b.setIconSize(d(18));b.setIconPadding(d(8));b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);}
        b.setRippleColor(android.content.res.ColorStateList.valueOf(withAlpha(fg,.16f)));b.setContentDescription(title);b.setOnClickListener(v->action.run());return b;}
    /** M3 segmented button: one choice of several; the chosen segment gets a check and the secondary container colour. */
    private View segmented(String[] ids,String[] names,String current,Change pick){
        LinearLayout r=row(this);r.setBackground(border(this,0x00000000,24,OUTLINE));r.setPadding(d(1),d(1),d(1),d(1));
        for(int i=0;i<ids.length;i++){String id=ids[i];boolean on=id.equals(current);
            if(i>0){View div=new View(this);div.setBackgroundColor(OUTLINE);r.addView(div,new LinearLayout.LayoutParams(d(1),-1));}
            TextView t=title(this,(on?"✓  ":"")+names[i],14,on?ON_SECONDARY_CONTAINER:TEXT);t.setGravity(Gravity.CENTER);t.setMinHeight(d(46));t.setMaxLines(1);t.setEllipsize(TextUtils.TruncateAt.END);pad(t,6,0,6,0);
            float left=i==0?d(23):0,right=i==ids.length-1?d(23):0;android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(on?SECONDARY_CONTAINER:0x00000000);shape.setCornerRadii(new float[]{left,left,right,right,right,right,left,left});t.setBackground(ripple(shape,d(23)));
            t.setOnClickListener(v->{if(on)return;try{pick.run(id);}catch(Exception e){error(e);}});
            r.addView(t,new LinearLayout.LayoutParams(0,-2,1));}
        return r;}
    private void pair(View a,View b){closeGroup();LinearLayout r=row(this);r.addView(a,new LinearLayout.LayoutParams(0,-2,1));r.addView(new View(this),new LinearLayout.LayoutParams(d(8),1));r.addView(b,new LinearLayout.LayoutParams(0,-2,1));body.addView(r);space(12);}
    private TextView serverCardName,serverCardMeta,serverCardGlyph;
    /** Home: the connection itself. Big power button, state, live speed, current server and routing mode. */
    /** One screen: the connection on top, every server right below it. */
    private void home(){
        heading("Wraith","",iconAction("refresh","Обновить все подписки",()->task("Обновляем подписки…",()->{Subscriptions.updateAll(this,false);int failed=0;for(JSONObject sub:store.subscriptions())if(sub.has("error"))failed++;return failed==0?"Подписки обновлены":"Не обновлено подписок: "+failed;})));
        LinearLayout hero=column(this);hero.setGravity(Gravity.CENTER_HORIZONTAL);pad(hero,20,24,20,20);hero.setBackground(bg(CARD,d(32)));
        orb=new Orb(this);orb.state=WraithService.state;orb.setOnClickListener(v->toggleConnection());LinearLayout.LayoutParams op=new LinearLayout.LayoutParams(d(152),d(152));op.gravity=Gravity.CENTER_HORIZONTAL;hero.addView(orb,op);
        stateLabel=title(this,"",24,TEXT);stateLabel.setGravity(Gravity.CENTER);pad(stateLabel,0,14,0,0);hero.addView(stateLabel,new LinearLayout.LayoutParams(-1,-2));
        stateHint=text(this,"",14,MUTED);stateHint.setGravity(Gravity.CENTER);stateHint.setMaxLines(3);pad(stateHint,8,6,8,0);hero.addView(stateHint,new LinearLayout.LayoutParams(-1,-2));
        sessionInfo=title(this,"",14,ON_SECONDARY_CONTAINER);sessionInfo.setFontFeatureSettings("tnum");sessionInfo.setMaxLines(1);sessionInfo.setBackground(bg(SECONDARY_CONTAINER,d(16)));pad(sessionInfo,14,8,14,8);sessionInfo.setVisibility(View.GONE);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-2,-2);sl.gravity=Gravity.CENTER_HORIZONTAL;sl.topMargin=d(14);hero.addView(sessionInfo,sl);
        body.addView(hero,new LinearLayout.LayoutParams(-1,-2));space(16);
        serverList();
        updateHome();
    }
    private TextView avatar(String glyph,boolean selected){TextView a=text(this,glyph,18,selected?ON_PRIMARY:ON_SECONDARY_CONTAINER);a.setGravity(Gravity.CENTER);a.setIncludeFontPadding(false);a.setBackground(bg(selected?MINT:SECONDARY_CONTAINER,d(20)));return a;}
    private String serverGlyph(String name){String flag=PingInfo.leadingFlag(name);if(!flag.isEmpty())return flag;String n=name.toLowerCase(Locale.ROOT);if(n.contains("german")||n.contains("de "))return "🇩🇪";if(n.contains("finland")||n.contains("fi "))return "🇫🇮";if(n.contains("nether")||n.contains("nl "))return "🇳🇱";if(n.contains("usa")||n.contains("united states"))return "🇺🇸";return "◆";}
    /** Servers: search, subscriptions as cards, every server with its own actions. */
    /** Search, "ping all" and the subscriptions with their servers. */
    private void serverList(){
        int count=store.profiles().size();
        if(count>0){
            EditText box=edit("Поиск среди "+count+" "+plural(count,"сервера","серверов","серверов"),search,false);box.setBackground(bg(SURFACE_HIGHEST,d(28)));pad(box,20,0,20,0);Glyph lens=new Glyph(this,"search").tinted(MUTED);lens.setBounds(0,0,d(22),d(22));box.setCompoundDrawablesRelative(lens,null,null,null);box.setCompoundDrawablePadding(d(14));
            box.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){search=s.toString().trim();refreshServers();}public void afterTextChanged(Editable e){}});
            body.addView(box,new LinearLayout.LayoutParams(-1,d(56)));space(12);
            pingButton=button("Пинг всех","pulse",false,()->{if(pingRunning)cancelPing();else pingAll();});setPingUi(pingRunning);
            body.addView(pingButton,new LinearLayout.LayoutParams(-1,-2));space(16);
        }
        LinearLayout results=column(this);body.addView(results,new LinearLayout.LayoutParams(-1,-2));serverResults=results;drawServerResults(results,true);
    }
    static String plural(int n,String one,String few,String many){int m=n%100;if(m>=11&&m<=14)return many;switch(n%10){case 1:return one;case 2:case 3:case 4:return few;default:return many;}}
    private void pingAll(){pingGroup(null);}
    private void pingGroup(String group){List<JSONObject> targets=new ArrayList<>();for(JSONObject p:store.profiles())if(group==null||p.optString("group").equals(group))targets.add(p);startPing(targets);}
    private void pingProfile(JSONObject profile){startPing(new ArrayList<>(Collections.singletonList(profile)));}
    private void startPing(List<JSONObject> targets){
        if(targets.isEmpty())return;boolean changed=false;long run;String session;AtomicBoolean cancelled;ExecutorService pool;
        synchronized(pingLock){
            if(!pingRunning){if(busy)return;busy=true;pingRunning=true;pingRunId++;pingSession=UUID.randomUUID().toString();pingCancelled=new AtomicBoolean();pingPool=Executors.newFixedThreadPool(5);pendingPingTasks=0;}
            run=pingRunId;session=pingSession;cancelled=pingCancelled;pool=pingPool;
            for(JSONObject p:targets){String id=p.optString("id");if(id.isEmpty()||!pingingIds.add(id))continue;pendingPingTasks++;changed=true;
                try{pool.submit(()->{
                    try{
                        if(cancelled.get()||Thread.currentThread().isInterrupted())return;
                        int ms=ping(p,session,cancelled);synchronized(pingLock){if(cancelled.get()||run!=pingRunId)return;p.put("latency",ms);p.put("latencyType",PingInfo.effectiveType(p,store.settings()));p.remove("pingError");}
                    }catch(Exception e){synchronized(pingLock){if(!cancelled.get()&&run==pingRunId)try{p.remove("latency");p.put("pingError",Subscriptions.safeError(e));}catch(JSONException ignored){}}}
                    finally{
                        final boolean reorderAfterPing;
                        synchronized(pingLock){
                            // A cancelled run may still be unwinding when the same row starts a
                            // new probe. Only its own generation may remove the row's spinner.
                            if(run!=pingRunId)return;pingingIds.remove(id);
                            reorderAfterPing=--pendingPingTasks==0;
                            if(reorderAfterPing){try{store.save();}catch(Exception ignored){}pingRunning=false;busy=false;pingCancelled=null;ExecutorService finished=pingPool;pingPool=null;if(finished!=null)finished.shutdown();pingingIds.clear();}
                        }
                        runOnUiThread(()->{synchronized(pingLock){if(run!=pingRunId)return;}updatePingRow(p);if(reorderAfterPing&&store.settings().optString("sort","none").equals("ping")&&serverResults!=null)reorderVisibleProfiles();setPingUi(pingRunning);updateHome();});
                    }
                });}catch(RejectedExecutionException e){pingingIds.remove(id);pendingPingTasks--;}
            }
            if(pingRunning&&pendingPingTasks==0){pingRunning=false;busy=false;pingCancelled=null;ExecutorService finished=pingPool;pingPool=null;if(finished!=null)finished.shutdown();}
        }
        if(changed||pingRunning){setPingUi(pingRunning);updatePingRows();updateHome();}
    }
    private void cancelPing(){String session;synchronized(pingLock){if(!pingRunning)return;session=pingSession;pingRunId++;AtomicBoolean cancelled=pingCancelled;if(cancelled!=null)cancelled.set(true);ExecutorService pool=pingPool;if(pool!=null)pool.shutdownNow();pingRunning=false;busy=false;pendingPingTasks=0;pingCancelled=null;pingPool=null;pingSession="";pingingIds.clear();}try{startService(new Intent(this,XrayProbeService.class).setAction(XrayProbeService.ACTION_CANCEL).putExtra(XrayProbeService.EXTRA_SESSION,session));}catch(Exception ignored){}setPingUi(false);updatePingRows();updateHome();}
    private void setPingUi(boolean active){if(pingButton==null)return;String label=active?"Остановить проверку":"Пинг всех";if(!label.contentEquals(pingButton.getText())){pingButton.setText(label);pingButton.setIcon(new Glyph(this,active?"close":"pulse"));}}
    private void updateHome(){if(tab!=HOME||stateLabel==null)return;int state=WraithService.state;if(orb!=null){orb.state=state;orb.pinging=pingRunning;orb.invalidate();}
        Anim.swap(stateLabel,pingRunning?"Проверка задержки":state==WraithService.CONNECTED?"Подключено":state==WraithService.CONNECTING?"Подключение…":state==WraithService.STOPPING?"Отключение…":"Не подключено");
        stateLabel.setTextColor(state==WraithService.CONNECTED?MINT:TEXT);
        String failure=WraithService.error==null?"":WraithService.error;boolean failed=state==WraithService.OFF&&!pingRunning&&!failure.isEmpty();
        JSONObject chosen=store.selected();
        Anim.swap(stateHint,pingRunning?"Нажмите кнопку, чтобы остановить проверку":failed?"Ошибка: "+(failure.length()>90?failure.substring(0,90)+"…":failure):chosen==null?"Добавьте сервер или подписку, чтобы начать":PingInfo.displayName(state==WraithService.CONNECTED&&WraithService.profileName!=null&&!WraithService.profileName.isEmpty()?WraithService.profileName:chosen.optString("name")));
        stateHint.setTextColor(failed?RED:MUTED);
        if(sessionInfo!=null){
            if(state==WraithService.CONNECTED&&!pingRunning){
                long secs=Math.max(0,(SystemClock.elapsedRealtime()-WraithService.since)/1000);
                String live=String.format(Locale.ROOT,"%02d:%02d:%02d",secs/3600,(secs/60)%60,secs%60)+"   ↓ "+bytes(WraithService.rxRate)+"/с   ↑ "+bytes(WraithService.txRate)+"/с";
                if(!live.contentEquals(sessionInfo.getText()))sessionInfo.setText(live);sessionInfo.setVisibility(View.VISIBLE);
            }else sessionInfo.setVisibility(View.GONE);
        }
        if(serverCardName!=null){
            String running=WraithService.profileName==null?"":WraithService.profileName;String name=state==WraithService.CONNECTED&&!running.isEmpty()?running:chosen==null?"":chosen.optString("name");
            serverCardName.setText(chosen==null?"Сервер не выбран":PingInfo.displayName(name));
            if(chosen!=null){int ms=chosen.optInt("latency",-1);serverCardMeta.setText(profileSummary(chosen)+(ms>=0?" · "+ms+" мс":""));}else serverCardMeta.setText("Нажмите, чтобы добавить");
            serverCardGlyph.setText(chosen==null?"+":chosen.optBoolean("favorite")?"★":serverGlyph(chosen.optString("name")));
        }
    }
    public static String bytes(long b){if(b<1024)return b+" Б";if(b<1024*1024)return String.format(Locale.ROOT,"%.1f КБ",b/1024.0);if(b<1024L*1024*1024)return String.format(Locale.ROOT,"%.1f МБ",b/1048576.0);return String.format(Locale.ROOT,"%.2f ГБ",b/1073741824.0);}
    private void toggleConnection(){if(pingRunning){cancelPing();return;}if(WraithService.state!=WraithService.OFF){WraithService.stop(this);return;}if(store.selected()==null){importDialog("");return;}WraithService.error="";shownError="";Intent request=android.net.VpnService.prepare(this);if(request!=null)startActivityForResult(request,10);else WraithService.start(this);}
    private void drawServerResults(LinearLayout results,boolean animate){
        results.removeAllViews();profileMetaViews.clear();profileLists.clear();profileRows.clear();Set<String> known=new HashSet<>();for(JSONObject sub:store.subscriptions()){known.add(sub.optString("id"));drawGroup(results,sub,sub.optString("id"),sub.optString("name","Подписка"),known);}
        boolean manual=false;for(JSONObject p:store.profiles())if(!known.contains(p.optString("group")))manual=true;
        if(manual)drawGroup(results,null,"","Мои серверы",known);
        if(store.profiles().isEmpty()){
            LinearLayout empty=column(this);empty.setGravity(Gravity.CENTER_HORIZONTAL);pad(empty,24,32,24,24);empty.setBackground(bg(CARD,d(28)));
            TextView icon=text(this,"+",28,ON_PRIMARY_CONTAINER);icon.setGravity(Gravity.CENTER);icon.setBackground(bg(PRIMARY_CONTAINER,d(28)));empty.addView(icon,new LinearLayout.LayoutParams(d(56),d(56)));
            TextView t=title(this,"Пока нет серверов",20,TEXT);t.setGravity(Gravity.CENTER);pad(t,0,16,0,8);empty.addView(t,new LinearLayout.LayoutParams(-1,-2));
            TextView hint=text(this,"Добавьте ссылку на сервер, HTTPS-подписку или JSON-конфигурацию. Можно вставить из буфера обмена или отсканировать QR-код.",14,MUTED);hint.setGravity(Gravity.CENTER);empty.addView(hint,new LinearLayout.LayoutParams(-1,-2));
            LinearLayout actions=row(this);pad(actions,0,20,0,0);actions.addView(button("Из буфера","copy",true,this::importClipboard),new LinearLayout.LayoutParams(0,-2,1));actions.addView(new View(this),new LinearLayout.LayoutParams(d(8),1));actions.addView(button("QR-код","qr",false,this::scanQr),new LinearLayout.LayoutParams(0,-2,1));empty.addView(actions,new LinearLayout.LayoutParams(-1,-2));
            results.addView(empty,new LinearLayout.LayoutParams(-1,-2));
        }else if(results.getChildCount()==0&&!search.isEmpty()){TextView none=text(this,"Ничего не найдено по запросу «"+search+"»",14,MUTED);none.setGravity(Gravity.CENTER);pad(none,16,24,16,24);results.addView(none,new LinearLayout.LayoutParams(-1,-2));}
        if(animate){Anim.stagger(results);animateVisibleProfileRows();}
    }
    /** Move the existing row views into their new order; building hundreds of swipe cards on each ping stalls the UI. */
    private void reorderVisibleProfiles(){
        Comparator<JSONObject> order=serverOrder();Set<String> subscriptionIds=new HashSet<>();for(JSONObject sub:store.subscriptions())subscriptionIds.add(sub.optString("id"));
        for(Map.Entry<String,LinearLayout> entry:profileLists.entrySet()){
            String group=entry.getKey();LinearLayout list=entry.getValue();List<JSONObject> members=new ArrayList<>();
            for(JSONObject p:store.profiles()){String profileGroup=p.optString("group");boolean inGroup=group.isEmpty()?!subscriptionIds.contains(profileGroup):profileGroup.equals(group);if(inGroup&&searchable(p).contains(search.toLowerCase(Locale.ROOT))&&profileRows.containsKey(p.optString("id")))members.add(p);}
            members.sort(order);boolean changed=false;for(int i=0;i<members.size();i++){View row=profileRows.get(members.get(i).optString("id"));if(i>=list.getChildCount()||list.getChildAt(i)!=row){changed=true;break;}}
            if(!changed)continue;
            Map<String,Integer> oldY=new HashMap<>();int[] location=new int[2];for(int i=0;i<list.getChildCount();i++){View row=list.getChildAt(i);row.animate().cancel();row.setTranslationY(0);row.getLocationOnScreen(location);oldY.put(String.valueOf(row.getTag()),location[1]);}
            list.removeAllViews();List<View> orderedRows=new ArrayList<>();
            for(JSONObject p:members){View row=profileRows.get(p.optString("id"));if(row==null)continue;LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=d(8);list.addView(row,lp);orderedRows.add(row);}
            list.post(()->{for(int i=0;i<orderedRows.size();i++){View row=orderedRows.get(i);Integer previous=oldY.get(String.valueOf(row.getTag()));if(previous==null||!row.getGlobalVisibleRect(new Rect()))continue;row.getLocationOnScreen(location);float offset=previous-location[1];if(Math.abs(offset)<d(2))continue;row.setTranslationY(offset);row.animate().translationY(0).setStartDelay(Math.min(i*12L,96L)).setDuration(330).setInterpolator(Anim.out()).start();}});
        }
    }
    private void animateVisibleProfileRows(){
        if(profileRows.isEmpty())return;scroll.post(()->{Rect visible=new Rect();if(!scroll.getGlobalVisibleRect(visible))return;int index=0;for(View row:profileRows.values()){if(!row.getGlobalVisibleRect(new Rect()))continue;row.setAlpha(0f);row.setTranslationY(d(7));row.animate().alpha(1f).translationY(0).setStartDelay(Math.min(index++*22L,110L)).setDuration(260).setInterpolator(Anim.out()).start();}});
    }
    /** Update only the small status labels; rebuilding every subscription card per probe caused visible UI stalls. */
    private void updatePingRows(){if(tab!=SERVERS||serverResults==null)return;for(JSONObject p:store.profiles())updatePingRow(p);}
    private void updatePingRow(JSONObject p){if(tab!=SERVERS||serverResults==null)return;TextView meta=profileMetaViews.get(p.optString("id"));if(meta==null)return;boolean checking=pingingIds.contains(p.optString("id"));String delay=checking?"Проверяем…":p.optInt("latency",-1)>=0?p.optInt("latency")+" мс":p.has("pingError")?PingInfo.shortError(p.optString("pingError")):"";String details=profileSummary(p)+(delay.isEmpty()?"":" · "+delay);if(!details.contentEquals(meta.getText()))meta.setText(details);boolean selected=p.optString("id").equals(store.selectedId());meta.setTextColor(p.has("pingError")&&!checking?RED:selected?ON_SECONDARY_CONTAINER:MUTED);}
    private void drawGroup(LinearLayout results,JSONObject sub,String group,String title,Set<String> known){
        List<JSONObject> members=new ArrayList<>();for(JSONObject p:store.profiles()){boolean match=sub!=null?p.optString("group").equals(group):!known.contains(p.optString("group"));if(match&&searchable(p).contains(search.toLowerCase(Locale.ROOT)))members.add(p);}
        if(!search.isEmpty()&&members.isEmpty())return;
        boolean collapsed=collapsedGroups.contains(group)&&search.isEmpty();
        Runnable flip=()->{if(!collapsedGroups.add(group))collapsedGroups.remove(group);drawServerResults(results,true);};
        LinearLayout box=column(this);pad(box,8,8,8,8);box.setBackground(bg(SURFACE_LOW,d(28)));
        LinearLayout head=row(this);pad(head,12,4,0,4);
        LinearLayout titles=column(this);TextView name=title(this,title,16,TEXT);name.setMaxLines(2);titles.addView(name);
        String count=members.size()+" "+plural(members.size(),"сервер","сервера","серверов");
        String status=sub==null?count:sub.has("error")?sub.optString("error"):(sub.optLong("updated")>0?"Обновлено "+android.text.format.DateFormat.format("dd.MM, HH:mm",sub.optLong("updated")):"Не обновлялась")+" · "+count;
        TextView meta=text(this,status,12,sub!=null&&sub.has("error")?RED:MUTED);meta.setMaxLines(3);pad(meta,0,4,0,0);titles.addView(meta);
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));titles.setOnClickListener(v->flip.run());
        head.addView(iconAction(collapsed?"expand":"collapse",collapsed?"Развернуть":"Свернуть",flip));
        head.addView(iconAction("pulse","Пинг серверов группы",()->pingGroup(group)));
        if(sub!=null)head.addView(iconAction("more","Действия с подпиской",()->subscriptionMenu(sub)));
        box.addView(head);
        if(sub!=null){
            String info=sub.optString("subscription-userinfo");
            if(!info.isEmpty()){LinearLayout usage=column(this);pad(usage,12,8,12,4);usage.addView(text(this,formatSubscription(info),12,MUTED));LinearProgressIndicator bar=new LinearProgressIndicator(this);bar.setMax(1000);bar.setTrackCornerRadius(d(3));bar.setTrackThickness(d(6));bar.setProgressCompat(subscriptionProgress(info),true);LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-1,-2);bl.topMargin=d(8);usage.addView(bar,bl);box.addView(usage);}
            String skipped=sub.optString("skipped");if(!skipped.isEmpty()){TextView warn=text(this,skipped,12,LILAC);warn.setMaxLines(3);pad(warn,12,6,12,0);box.addView(warn);}
            View announcement=announcement(sub);if(announcement!=null)box.addView(announcement);
        }
        if(!collapsed){members.sort(serverOrder());LinearLayout list=column(this);pad(list,0,4,0,0);profileLists.put(group,list);for(JSONObject p:members)drawProfile(list,p);box.addView(list);}
        results.addView(box,new LinearLayout.LayoutParams(-1,-2));gap(results,12);
    }
    /**
     * Happ's prominent subscription announcement (sub-info-*) and the expiry notice it shows three
     * days before the subscription runs out, derived from subscription-userinfo expire.
     */
    /**
     * Happ's prominent subscription announcement (sub-info-*) and the expiry notice it shows three
     * days before the subscription runs out, as an M3 container-coloured banner.
     */
    private View announcement(JSONObject sub){
        String message=sub.optString("sub-info-text").trim();
        String buttonText=sub.optString("sub-info-button-text").trim(),buttonLink=sub.optString("sub-info-button-link").trim();
        String color=sub.optString("sub-info-color");
        int fill=color.equalsIgnoreCase("red")?ERROR_CONTAINER:color.equalsIgnoreCase("green")?PRIMARY_CONTAINER:TERTIARY_CONTAINER;
        int ink=color.equalsIgnoreCase("red")?ON_ERROR_CONTAINER:color.equalsIgnoreCase("green")?ON_PRIMARY_CONTAINER:ON_TERTIARY_CONTAINER;
        long expire=0;
        for(String part:sub.optString("subscription-userinfo").split(";")){String[] kv=part.trim().split("=",2);if(kv.length==2&&kv[0].equals("expire"))try{expire=Long.parseLong(kv[1]);}catch(Exception ignored){}}
        long days=expire>0?(expire*1000-System.currentTimeMillis())/86400000L:Long.MAX_VALUE;
        if(sub.optBoolean("sub-expire",true)&&expire>0&&days<=3){
            message=days<0?"Подписка истекла":days==0?"Подписка истекает сегодня":"Подписка истекает через "+days+" дн.";
            fill=ERROR_CONTAINER;ink=ON_ERROR_CONTAINER;
            if(buttonLink.isEmpty())buttonLink=sub.optString("sub-expire-button-link");
            if(!buttonLink.isEmpty())buttonText="Продлить";
        }
        if(message.isEmpty()||message.equals("0"))return null;
        LinearLayout banner=row(this);pad(banner,16,12,8,12);banner.setBackground(bg(fill,d(16)));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(d(4),d(8),d(4),d(4));banner.setLayoutParams(lp);
        TextView body=text(this,message,13,ink);body.setMaxLines(4);banner.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        if(!buttonText.isEmpty()&&!buttonLink.isEmpty()){
            TextView go=title(this,buttonText.length()>25?buttonText.substring(0,25):buttonText,14,ink);pad(go,12,10,12,10);go.setBackground(ripple(bg(0x00000000,d(20)),d(20)));
            final String link=buttonLink;go.setOnClickListener(v->openLink(link));banner.addView(go);
        }
        return banner;
    }
    /**
     * Protocol as the user knows it. The core type stays "hysteria2" — in sing-box plain
     * "hysteria" is protocol v1 and cannot talk to a Hysteria 2 server — but Happ shows it as
     * HYSTERIA, and so do we.
     */
    static String protocolLabel(String type){
        if(type.equals("hysteria2"))return "HYSTERIA";
        if(type.equals("shadowsocks"))return "SS";
        return type.toUpperCase(Locale.ROOT);
    }
    /** Compact, per-host description built from the normalized outbound actually used by the core. */
    static String profileSummary(JSONObject profile){
        String source=profile.optString("source").trim();
        boolean json=profile.optBoolean("jsonSource")||profile.optBoolean("full")||source.startsWith("{")||source.startsWith("[");
        JSONObject outbound=profile.optJSONObject("outbound");
        if(outbound==null)return json?"JSON":"СЕРВЕР";
        String type=outbound.optString("type");
        String protocol=protocolLabel(type);
        JSONObject transport=outbound.optJSONObject("transport");
        String network=transport==null?"":transport.optString("type");
        if(network.isEmpty()){
            if(type.equals("hysteria")||type.equals("hysteria2")||type.equals("tuic"))network="quic";
            else if(type.equals("wireguard"))network="udp";
            else network="tcp";
        }
        List<String> parts=new ArrayList<>();parts.add(protocol);parts.add(network.toUpperCase(Locale.ROOT));
        JSONObject tls=outbound.optJSONObject("tls");
        if(tls!=null&&tls.optBoolean("enabled",true)){
            JSONObject reality=tls.optJSONObject("reality");
            parts.add(reality!=null&&reality.optBoolean("enabled",true)?"REALITY":"TLS");
        }
        String summary=String.join(" / ",parts);
        if(json)summary+=" | JSON";
        JSONArray pool=profile.optJSONArray("xrayCandidates");
        if(pool!=null&&pool.length()>1)summary+=" · серверов: "+pool.length();
        return summary;
    }
    /** Favourites first, then Настройки → Подписки → Сортировка: none, by ping (untested last) or by name. */
    private Comparator<JSONObject> serverOrder(){
        Comparator<JSONObject> order=Comparator.comparing((JSONObject p)->!p.optBoolean("favorite"));
        String sort=store.settings().optString("sort","none");
        if(sort.equals("ping"))order=order.thenComparingInt(p->p.optInt("latency",-1)<0?Integer.MAX_VALUE:p.optInt("latency"));
        else if(sort.equals("name"))order=order.thenComparing(p->PingInfo.displayName(p.optString("name")).toLowerCase(Locale.ROOT));
        return order;
    }
    /** Name, address and protocol only. Matching the whole outbound JSON made UUIDs searchable. */
    private String searchable(JSONObject p){
        JSONObject o=p.optJSONObject("outbound");
        return (p.optString("name")+" "+(o==null?"":o.optString("server")+" "+o.optString("type"))).toLowerCase(Locale.ROOT);
    }
    private int subscriptionProgress(String value){long used=0,total=0;for(String part:value.split(";")){String[] kv=part.trim().split("=",2);if(kv.length!=2)continue;try{long n=Long.parseLong(kv[1]);if(kv[0].equals("total"))total=n;else if(kv[0].equals("upload")||kv[0].equals("download"))used+=n;}catch(Exception ignored){}}return total>0?(int)Math.min(1000,used*1000.0/total):0;}
    /** M3 standard icon button: 48dp target, circular state layer, tooltip on long press. */
    private View iconAction(String icon,String description,Runnable click){
        FrameLayout b=new FrameLayout(this);b.setContentDescription(description);
        android.graphics.drawable.RippleDrawable state=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(withAlpha(TEXT,.12f)),null,null);state.setRadius(d(20));b.setBackground(state);
        Icon glyph=new Icon(this,icon,MUTED);b.addView(glyph,new FrameLayout.LayoutParams(d(24),d(24),Gravity.CENTER));b.setLayoutParams(new LinearLayout.LayoutParams(d(48),d(48)));
        b.setOnClickListener(v->{if(icon.equals("refresh"))glyph.animate().rotationBy(360f).setDuration(520).setInterpolator(Anim.out()).start();click.run();});
        if(Build.VERSION.SDK_INT>=26)b.setTooltipText(description);
        return b;}
    private android.graphics.drawable.Drawable rowBackground(boolean selected){return glass(selected?SECONDARY_CONTAINER:SURFACE_LOW,d(20));}
    /** One server as an M3 list item: avatar, name, protocol and delay, actions; swipe right to ping. */
    private void drawProfile(LinearLayout parent,JSONObject p){
        boolean selected=p.optString("id").equals(store.selectedId());LinearLayout r=row(this);pad(r,12,10,0,10);r.setMinimumHeight(d(68));r.setBackground(rowBackground(selected));
        TextView mark=avatar(p.optBoolean("favorite")?"★":serverGlyph(p.optString("name")),selected);r.addView(mark,new LinearLayout.LayoutParams(d(40),d(40)));
        LinearLayout names=column(this);pad(names,16,0,8,0);TextView title=title(this,PingInfo.displayName(p.optString("name")),15,selected?ON_SECONDARY_CONTAINER:TEXT);title.setMaxLines(2);names.addView(title);
        boolean checking=pingingIds.contains(p.optString("id"));String delay=checking?"Проверяем…":p.optInt("latency",-1)>=0?p.optInt("latency")+" мс":p.has("pingError")?PingInfo.shortError(p.optString("pingError")):"";String details=profileSummary(p)+(delay.isEmpty()?"":" · "+delay);TextView meta=text(this,details,12,p.has("pingError")&&!checking?RED:selected?ON_SECONDARY_CONTAINER:MUTED);meta.setMaxLines(2);pad(meta,0,4,0,0);names.addView(meta);profileMetaViews.put(p.optString("id"),meta);r.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        if(checking){CircularProgressIndicator spinner=new CircularProgressIndicator(this);spinner.setIndeterminate(true);spinner.setIndicatorSize(d(20));spinner.setTrackThickness(d(3));r.addView(spinner,new LinearLayout.LayoutParams(-2,-2));}
        if(selected&&p.optString("id").equals(justSelected)){Anim.pop(mark);justSelected="";}
        r.addView(iconAction("more","Действия с сервером",()->serverMenu(p)));
        FrameLayout swipeFrame=new FrameLayout(this);swipeFrame.setTag(p.optString("id"));LinearLayout swipeAction=column(this);swipeAction.setGravity(Gravity.CENTER);swipeAction.setBackground(bg(MINT,d(20)));Icon swipeIcon=new Icon(this,"pulse",ON_PRIMARY);swipeAction.addView(swipeIcon,new LinearLayout.LayoutParams(d(20),d(20)));TextView swipeLabel=text(this,"Пинг",11,ON_PRIMARY);pad(swipeLabel,0,2,0,0);swipeAction.addView(swipeLabel);FrameLayout.LayoutParams actionLp=new FrameLayout.LayoutParams(d(64),-1,Gravity.START|Gravity.CENTER_VERTICAL);swipeFrame.addView(swipeAction,actionLp);swipeAction.setAlpha(0f);swipeFrame.addView(r,new FrameLayout.LayoutParams(-1,-2));
        final float[] down={0,0};final boolean[] swiped={false},vertical={false};r.setOnTouchListener((v,e)->{switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:down[0]=e.getRawX();down[1]=e.getRawY();swiped[0]=false;vertical[0]=false;r.setPressed(true);r.getBackground().setHotspot(e.getX(),e.getY());return true;
            case MotionEvent.ACTION_MOVE:{float dx=e.getRawX()-down[0],dy=e.getRawY()-down[1];if(swiped[0]){float shift=Math.min(d(72),Math.max(0,dx)*.5f);r.setTranslationX(shift);swipeAction.setAlpha(Math.min(1f,shift/d(48)));return true;}if(Math.abs(dy)>d(14)&&Math.abs(dy)>Math.abs(dx)*1.2f){vertical[0]=true;r.setPressed(false);r.getParent().requestDisallowInterceptTouchEvent(false);return false;}if(dx>d(26)&&dx>Math.abs(dy)*1.35f){swiped[0]=true;r.setPressed(false);r.getParent().requestDisallowInterceptTouchEvent(true);r.setTranslationX(Math.min(d(72),dx*.5f));swipeAction.setAlpha(Math.min(1f,r.getTranslationX()/d(48)));}return true;}
            case MotionEvent.ACTION_UP:{float dx=e.getRawX()-down[0];r.setPressed(false);r.getParent().requestDisallowInterceptTouchEvent(false);if(swiped[0]&&dx>d(48)){r.animate().translationX(0).setDuration(260).setInterpolator(Anim.spring()).start();swipeAction.animate().alpha(0f).setStartDelay(180).setDuration(160).start();Anim.press(mark);pingProfile(p);}else if(swiped[0]){r.animate().translationX(0).setDuration(190).setInterpolator(Anim.spring()).start();swipeAction.animate().alpha(0f).setDuration(120).start();}else if(!vertical[0])r.performClick();return true;}
            case MotionEvent.ACTION_CANCEL:r.setPressed(false);r.getParent().requestDisallowInterceptTouchEvent(false);r.animate().translationX(0).setDuration(180).start();swipeAction.animate().alpha(0f).setDuration(120).start();return true;
            default:return true;}});r.setOnClickListener(v->selectProfile(p));r.setOnLongClickListener(v->{serverMenu(p);return true;});profileRows.put(p.optString("id"),swipeFrame);LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,-2);rowLp.topMargin=d(4);parent.addView(swipeFrame,rowLp);
    }
    /** Everything that can be done with one server, in an M3 bottom sheet (tap ⋮ or long-press the row). */
    private void serverMenu(JSONObject p){
        boolean fav=p.optBoolean("favorite");String name=PingInfo.displayName(p.optString("name"));
        String[] items={"Проверить задержку",fav?"Убрать из избранного":"В избранное","Скопировать ссылку","Показать QR-код","Изменить конфигурацию","Диагностика","Удалить"};
        String[] icons={"pulse","star","copy","qr","edit","info","delete"};
        new InlinePanel.Builder(this).setTitle(name).setActions(items,icons,new boolean[]{false,false,false,false,false,false,true},(d,w)->{try{switch(w){
            case 0:pingProfile(p);break;
            case 1:p.put("favorite",!fav);store.save();refreshServers();toast(fav?"Убрано из избранного":"Добавлено в избранное");break;
            case 2:copy(exportProfile(p));break;
            case 3:qr(exportProfile(p));break;
            case 4:editProfileConfig(p);break;
            case 5:task("Проверяем конфигурацию…",()->{String report=diagnose(p);runOnUiThread(()->message("Диагностика · "+name,report));return null;});break;
            default:confirm("Удалить сервер?","«"+name+"» будет удалён с устройства."+(p.optString("group").isEmpty()?"":" Серверы подписки вернутся при её следующем обновлении."),()->{store.deleteProfile(p.getString("id"));refreshServers();toast("Сервер удалён");});
        }}catch(Exception e){error(e);}}).show();
    }
    /**
     * Selects a server. While connected the running core is switched through its selector, so the
     * tunnel is never torn down; a full restart happens only if the core refuses the switch.
     */
    private void selectProfile(JSONObject p){
        try{
            if(p.optString("id").equals(store.selectedId()))return;
            store.select(p.getString("id"));
            justSelected=p.getString("id");
            refreshServers();updateHome();
            // Picking another server mid-connect used to be ignored until the next reconnect.
            if(WraithService.state==WraithService.CONNECTING){WraithService.restart(this);return;}
            if(WraithService.state!=WraithService.CONNECTED)return;
            final JSONObject target=Json.copy(p);
            WraithApp.IO.execute(()->{
                boolean switched=WraithService.switchProfile(target);
                if(!switched)WraithService.restart(this);
                runOnUiThread(()->{if(isFinishing())return;updateHome();if(switched)toast("Сервер: "+PingInfo.displayName(target.optString("name")));});
            });
        }catch(Exception e){error(e);}
    }
    private String formatSubscription(String info){Map<String,Long> vals=new HashMap<>();for(String p:info.split(";")){String[] kv=p.trim().split("=",2);if(kv.length==2)try{vals.put(kv[0],Long.parseLong(kv[1]));}catch(Exception ignored){}}String text=bytes(vals.getOrDefault("upload",0L)+vals.getOrDefault("download",0L));if(vals.getOrDefault("total",0L)>0)text+=" / "+bytes(vals.get("total"));else text+=" / ∞";if(vals.getOrDefault("expire",0L)>0)text+=" · до "+android.text.format.DateFormat.format("dd.MM.yyyy",vals.get("expire")*1000);return text;}
    private void subscriptionMenu(JSONObject sub){
        String support=sub.optString("support-url",sub.optString("profile-web-page-url"));
        List<String> items=new ArrayList<>(List.of("Обновить","Изменить","Скопировать ссылку")),icons=new ArrayList<>(List.of("refresh","edit","copy"));
        if(!support.isEmpty()){items.add("Поддержка провайдера");icons.add("link");}
        items.add("Удалить");icons.add("delete");boolean[] danger=new boolean[items.size()];danger[items.size()-1]=true;
        new InlinePanel.Builder(this).setTitle(sub.optString("name")).setActions(items.toArray(new String[0]),icons.toArray(new String[0]),danger,(d,w)->{
            switch(items.get(w)){
                case "Обновить":task("Обновляем «"+sub.optString("name")+"»…",()->{Subscriptions.update(this,sub);return "Подписка обновлена";});break;
                case "Изменить":editSubscription(sub);break;
                case "Скопировать ссылку":getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Wraith subscription",sub.optString("url")));toast("Ссылка скопирована");break;
                case "Поддержка провайдера":openLink(support);break;
                default:confirmDeleteSubscription(sub);
            }}).show();
    }
    private void editSubscription(JSONObject sub){LinearLayout form=column(this);EditText name=edit("Название",sub.optString("name"),false),url=edit("HTTPS URL подписки",sub.optString("url"),false);form.addView(name);gap(form,10);form.addView(url);InlinePanel panel=new InlinePanel.Builder(this).setTitle("Изменить подписку").setCentered(true).setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();panel.setOnShowListener(x->panel.getButton(-1).setOnClickListener(v->{try{String n=name.getText().toString().trim(),u=url.getText().toString().trim();if(n.isEmpty())throw new IllegalArgumentException("Введите название");if(!u.startsWith("https://"))throw new IllegalArgumentException("Нужен HTTPS URL");sub.put("name",n);sub.put("url",u);store.save();panel.dismiss();refreshServers();}catch(Exception e){url.setError(Subscriptions.safeError(e));}}));panel.show();}
    private void confirmDeleteSubscription(JSONObject sub){new InlinePanel.Builder(this).setTitle("Удалить подписку?").setCentered(true).setMessage("«"+sub.optString("name")+"» и все её серверы будут удалены с устройства.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{try{store.removeSubscription(sub.getString("id"));refreshServers();}catch(Exception e){error(e);}}).show();}
    private void editProfileConfig(JSONObject p){
        try{
            String raw=p.optString("source").trim();
            String initial;
            if(raw.startsWith("{")){initial=new JSONObject(raw).toString(2);}
            else if(raw.startsWith("[")){initial=new JSONArray(raw).toString(2);}
            else initial=p.getJSONObject("outbound").toString(2);
            fullScreenConfigEditor("Конфигурация сервера · "+p.optString("name"),initial,value->{
                List<JSONObject> parsed=ImportParser.parse(value);
                if(parsed.size()!=1)throw new IllegalArgumentException("Вставьте конфигурацию ровно одного сервера.");
                JSONObject replacement=parsed.get(0);
                replacement.put("id",p.getString("id"));
                replacement.put("group",p.optString("group"));
                replacement.put("favorite",p.optBoolean("favorite"));
                if(!replacement.has("routing")&&p.has("routing"))replacement.put("routing",p.get("routing"));
                if(!replacement.has("description")&&p.has("description"))replacement.put("description",p.get("description"));
                store.replaceProfile(replacement);
                refreshServers();changed();toast("Конфигурация сервера сохранена");
            });
        }catch(Exception e){error(e);}
    }

    /** Full-height raw config editor; the generic form input is intentionally short. */
    private void fullScreenConfigEditor(String title,String value,Change change){
        FrameLayout overlay=new FrameLayout(this);overlay.setTag("wraith-config-editor");overlay.setBackgroundColor(BG);overlay.setFocusableInTouchMode(true);
        LinearLayout page=column(this);pad(page,18,10,18,14);overlay.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=row(this);header.addView(iconAction("back","Назад",()->closeConfigEditor(overlay)));
        TextView heading=title(this,title,20,TEXT);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);pad(heading,4,0,0,0);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));page.addView(header);
        EditText editor=edit("JSON",value,true);editor.setMinLines(1);editor.setMaxLines(Integer.MAX_VALUE);editor.setHorizontallyScrolling(false);editor.setGravity(Gravity.TOP|Gravity.START);editor.setTextSize(13);editor.setPadding(d(16),d(16),d(16),d(16));editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams editorLp=new LinearLayout.LayoutParams(-1,0,1);editorLp.topMargin=d(14);editorLp.bottomMargin=d(12);page.addView(editor,editorLp);
        LinearLayout actions=row(this);actions.addView(button("Отмена",null,false,()->closeConfigEditor(overlay)),new LinearLayout.LayoutParams(0,d(54),1));View spacer=new View(this);actions.addView(spacer,new LinearLayout.LayoutParams(d(10),1));actions.addView(button("Сохранить",null,true,()->{try{change.run(editor.getText().toString().trim());closeConfigEditor(overlay);}catch(Exception e){editor.setError(Subscriptions.safeError(e));}}),new LinearLayout.LayoutParams(0,d(54),1));page.addView(actions);
        overlay.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        addContentView(overlay,new android.view.ViewGroup.LayoutParams(-1,-1));overlay.requestApplyInsets();editor.requestFocus();editor.setSelection(editor.length());
    }
    private void closeConfigEditor(FrameLayout overlay){if(overlay.getParent()!=null)((android.view.ViewGroup)overlay.getParent()).removeView(overlay);}

    /** Builds the actual one-host runtime configuration, including DNS and user routing rules. */
    private String effectiveConfig(JSONObject profile)throws Exception{
        JSONObject config=ConfigBuilder.build(configState(profile),"preview",new File(getFilesDir(),"preview.log").getAbsolutePath());
        Libbox.checkConfig(config.toString());
        return config.toString(2);
    }
    private String exportProfile(JSONObject p)throws Exception{return p.optString("source").isEmpty()?p.getJSONObject("outbound").toString(2):p.optString("source");}
    /**
     * Shows exactly what the core is given for one server and what it says about it: the normalized
     * outbound after Reality repair, the core validation result and the last core log lines. Field
     * names are kept intact — a redacted Reality error is useless for finding the broken field.
     */
    private String diagnose(JSONObject p)throws Exception{
        StringBuilder out=new StringBuilder();
        out.append("sing-box ").append(Libbox.version()).append('\n');
        out.append("Тип: ").append(p.optBoolean("full")?"полный JSON":p.getJSONObject("outbound").optString("type")).append('\n');
        JSONObject routing=RoutingConfig.profileFor(store.snapshot(),p);
        out.append("Профиль маршрутизации: ").append(routing==null?"нет":routing.optString("Name","есть")).append('\n');
        if(!p.optBoolean("full")){
            JSONObject normalized=Json.copy(p.getJSONObject("outbound"));
            ImportParser.repairReality(normalized,p.optString("source"));
            normalized.put("tag",ConfigBuilder.tagOf(p));
            out.append("\nНормализованный outbound:\n").append(normalized.toString(2)).append('\n');
        }
        try{ Libbox.checkConfig(ConfigBuilder.build(configState(p),"preview",new File(getFilesDir(),"preview.log").getAbsolutePath()).toString()); out.append("\nПроверка ядра: конфигурация принята\n"); }
        catch(Throwable e){ out.append("\nПроверка ядра: ").append(e.getMessage()==null?e.toString():e.getMessage()).append('\n'); }
        if(!WraithService.error.isEmpty())out.append("\nПоследняя ошибка подключения: ").append(WraithService.error).append('\n');
        String core=readLogs();
        out.append("\nПоследние строки журнала:\n").append(core.length()>4000?core.substring(core.length()-4000):core);
        return out.toString();
    }
    private JSONObject configState(JSONObject profile)throws Exception{
        JSONObject state=store.snapshot();
        state.put("profiles",Json.arr(Json.copy(profile)));
        state.put("selected",profile.getString("id"));
        return state;
    }
    private int ping(JSONObject p,String session,AtomicBoolean cancelled)throws Exception{
        JSONObject o=p.getJSONObject("outbound"),settings=store.settings();String type=PingInfo.effectiveType(p,settings);int timeout=settings.optInt("pingTimeout",5000);
        if(type.startsWith("proxy")){
            if(p.optBoolean("full"))throw new IllegalArgumentException("Выберите отдельный сервер вместо полного JSON");
            String url=settings.optString("pingUrl","https://www.gstatic.com/generate_204"),method=type.equals("proxyHead")?"HEAD":"GET",mode=settings.optString("pingMode","keepalive");
            if(CoreRouter.engine(p,settings).equals(CoreRouter.XRAY)){
                JSONArray candidates=p.optJSONArray("xrayCandidates");
                if(candidates!=null&&candidates.length()>1){
                    Throwable last=null;JSONObject probeSettings=Json.copy(settings);probeSettings.put("autoSelect",false).put("pinCandidate",true);
                    for(int i=0;i<candidates.length();i++){
                        if(cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException("Проверка отменена.");
                        try{JSONObject one=Json.copy(p);one.put("outbound",candidates.getJSONObject(i));one.remove("xrayCandidates");return pingXray(one,probeSettings,url,method,mode,timeout,session,cancelled);}catch(InterruptedException e){throw e;}catch(Exception e){last=e;}
                    }
                    throw new IOException("Не ответил ни один сервер из пула ("+candidates.length()+"). "+(last==null?"":Subscriptions.safeError(last)),last);
                }
                return pingXray(p,settings,url,method,mode,timeout,session,cancelled);
            }
            JSONArray pool=p.optJSONArray("xrayCandidates");
            if(pool!=null&&pool.length()>1){
                Throwable last=null;
                for(int i=0;i<pool.length();i++)try{
                    JSONObject one=Json.copy(p);one.put("outbound",pool.getJSONObject(i));one.remove("xrayCandidates");
                    return (int)Libbox.probeConfig(ConfigBuilder.probeConfig(one,Json.copy(settings)).toString(),"node-"+one.getString("id"),url,method,mode,timeout,WraithService::protectProbeFd);
                }catch(Throwable e){last=e;}
                throw new IOException("Не ответил ни один сервер из пула ("+pool.length()+"). "+(last==null?"":Subscriptions.safeError(last)),last);
            }
            return (int)Libbox.probeConfig(ConfigBuilder.probeConfig(p,Json.copy(settings)).toString(),"node-"+p.getString("id"),url,method,mode,timeout,WraithService::protectProbeFd);
        }
        if(p.optBoolean("full"))throw new IllegalArgumentException("Нет отдельного адреса в полном JSON");String host=o.getString("server");
        if(type.equals("icmp")){String ip=InetAddress.getByName(host).getHostAddress();java.lang.Process process=new ProcessBuilder("/system/bin/ping",ip.contains(":")?"-6":"-4","-n","-c","1","-W",String.valueOf(Math.max(1,timeout/1000)),ip).redirectErrorStream(true).start();try{if(!process.waitFor(timeout+1000,TimeUnit.MILLISECONDS))throw new IOException("ICMP: тайм-аут");String output=new String(Subscriptions.bounded(process.getInputStream()),StandardCharsets.UTF_8);java.util.regex.Matcher m=java.util.regex.Pattern.compile("time[=<]([0-9.]+)").matcher(output);if(process.exitValue()!=0||!m.find())throw new IOException("ICMP недоступен или заблокирован");return Math.max(1,(int)Math.ceil(Double.parseDouble(m.group(1))));}finally{process.destroy();}}
        long start=SystemClock.elapsedRealtime();try(Socket socket=new Socket()){WraithService.protectProbe(socket);socket.connect(new InetSocketAddress(host,o.getInt("server_port")),timeout);}return (int)(SystemClock.elapsedRealtime()-start);
    }
    private int pingXray(JSONObject profile,JSONObject settings,String url,String method,String mode,int timeout,String session,AtomicBoolean cancelled)throws Exception{
        CountDownLatch started=new CountDownLatch(1),done=new CountDownLatch(1);AtomicReference<Integer> latency=new AtomicReference<>();AtomicReference<String> failure=new AtomicReference<>();
        ResultReceiver receiver=new ResultReceiver(handler){@Override protected void onReceiveResult(int resultCode,Bundle data){if(cancelled.get())return;if(resultCode==XrayProbeService.STARTED){started.countDown();return;}latency.set(data==null?-1:data.getInt("latency",-1));failure.set(data==null?"Нет ответа от проверки Xray.":data.getString("error",""));done.countDown();started.countDown();}};
        Intent request=new Intent(this,XrayProbeService.class).setAction(XrayProbeService.ACTION)
            .putExtra(XrayProbeService.EXTRA_PROFILE,profile.toString()).putExtra(XrayProbeService.EXTRA_URL,url)
            .putExtra(XrayProbeService.EXTRA_SETTINGS,settings.toString()).putExtra(XrayProbeService.EXTRA_SESSION,session)
            .putExtra(XrayProbeService.EXTRA_TIMEOUT,timeout).putExtra(XrayProbeService.EXTRA_METHOD,method).putExtra(XrayProbeService.EXTRA_MODE,mode).putExtra(XrayProbeService.EXTRA_RECEIVER,receiver);
        if(cancelled.get())throw new InterruptedException("Проверка отменена.");
        startService(request);
        // Time spent waiting for a previous native batch is not server latency. The service
        // acknowledges when this batch begins; only then does the network deadline start.
        if(!started.await(60,TimeUnit.SECONDS))throw new IOException("Очередь проверки Xray не ответила.");
        if(!done.await(timeout*2L+5000L,TimeUnit.MILLISECONDS))throw new IOException("Xray не завершил проверку за отведённое время.");
        Integer ms=latency.get();if(ms==null||ms<0)throw new IOException(failure.get()==null||failure.get().isEmpty()?"Xray не получил ответ от сервера.":failure.get());return ms;
    }
    static String routingModeLabel(String mode){return mode.equals("global")?"Всё в VPN":mode.equals("direct")?"Напрямую":"По правилам";}
    private void routes(){heading("Маршрутизация","Какой трафик идёт через VPN");
        String mode=store.settings().optString("mode","rules");
        body.addView(segmented(new String[]{"rules","global","direct"},new String[]{"По правилам","Всё в VPN","Напрямую"},mode,id->{store.setting("mode",id);render();changed();}),new LinearLayout.LayoutParams(-1,-2));
        TextView explain=label(mode.equals("global")?"Весь трафик идёт через VPN, правила маршрутизации не применяются.":mode.equals("direct")?"VPN не используется: весь трафик идёт напрямую.":"Трафик распределяется по правилам провайдера и вашим правилам ниже.",13,MUTED);pad(explain,4,8,4,0);
        section("Общие");
        toggle("Локальная сеть напрямую","Домашние устройства и частные IP","bypassLan",true);toggle("Анализ протокола","Определять домен и протокол соединений","sniff",true);
        section("Свои правила");
        int index=0;for(JSONObject r:store.rules()){final int n=index++;String action=r.optString("action","proxy");String sub=ruleKindLabel(r.optString("kind"))+" → "+(action.equals("direct")?"напрямую":action.equals("block")?"блокировать":"через VPN")+(r.optBoolean("enabled",true)?"":" · выключено")+"\n"+r.optString("value").replace('\n',' ');
            rowSetting(action.equals("block")?"close":action.equals("direct")?"arrow":"shield",r.optString("name",r.optString("value")),sub,()->new InlinePanel.Builder(this).setTitle(r.optString("name","Правило")).setActions(new String[]{"Изменить",r.optBoolean("enabled",true)?"Выключить":"Включить","Удалить"},new String[]{"edit","power","delete"},new boolean[]{false,false,true},(di,w)->{try{if(w==0)ruleDialog(r,n);else if(w==1){r.put("enabled",!r.optBoolean("enabled",true));store.save();render();changed();}else{store.removeRule(n);render();changed();}}catch(Exception e){error(e);}}).show());}
        if(store.rules().isEmpty())rowSetting("route","Правил пока нет","Домены, IP-адреса, приложения и наборы правил. Правила выше — приоритетнее.",()->ruleDialog(null,-1));
        space(12);pair(button("Добавить","plus",true,()->ruleDialog(null,-1)),button("Импорт","download",false,()->input("Ссылка профиля маршрутизации","",true,this::importRouting)));
        note("Правила «Имя пакета Android» и «URL набора .srs / .json» понимает только ядро sing-box (Hysteria, TUIC, WireGuard и др.). Для серверов на Xray они пропускаются.");
    }
    private static String ruleKindLabel(String kind){switch(kind){case "domain_suffix":return "Домен и поддомены";case "domain":return "Точный домен";case "domain_keyword":return "Домен содержит";case "domain_regex":return "Регулярное выражение";case "ip_cidr":return "IP / CIDR";case "package_name":return "Приложение";case "rule_set":return "Набор правил";default:return kind;}}
    private void ruleDialog(JSONObject original,int index){LinearLayout form=column(this);pad(form,0,8,0,0);EditText name=edit("Название",original==null?"":original.optString("name"),false);EditText value=edit("example.org — одна запись на строку",original==null?"":original.optString("value"),true);String[] kinds={"domain_suffix","domain","domain_keyword","domain_regex","ip_cidr","package_name","rule_set"};String[] labels={"Домен и поддомены","Точный домен","Домен содержит","Регулярное выражение","IP / CIDR","Имя пакета Android","URL набора .srs / .json"};Spinner type=spinner(labels,original==null?0:Math.max(0,Arrays.asList(kinds).indexOf(original.optString("kind"))));String[] actions={"proxy","direct","block"};Spinner action=spinner(new String[]{"Через прокси","Напрямую","Блокировать"},original==null?0:Math.max(0,Arrays.asList(actions).indexOf(original.optString("action"))));form.addView(name);gap(form,12);form.addView(type);gap(form,12);form.addView(value);gap(form,12);form.addView(action);InlinePanel dialog=new InlinePanel.Builder(this).setTitle("Правило трафика").setView(form).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(btn->{try{String content=value.getText().toString().trim();if(content.isEmpty())throw new IllegalArgumentException("Заполните правило");JSONObject r=Json.obj("name",name.getText().toString().trim(),"value",content,"kind",kinds[type.getSelectedItemPosition()],"action",actions[action.getSelectedItemPosition()],"enabled",true);if(r.optString("name").isEmpty())r.put("name",content);if(original==null)store.addRule(r);else{Iterator<String> keys=r.keys();while(keys.hasNext()){String k=keys.next();original.put(k,r.get(k));}store.save();}dialog.dismiss();render();changed();}catch(Exception e){error(e);}}));dialog.show();}
    /** Imports a Happ routing profile through the same converter the config builder uses. */
    private void importRouting(String text)throws Exception{
        JSONObject json=RoutingConfig.decode(text);
        JSONArray converted=new JSONArray();RoutingConfig.append(converted,json);
        List<JSONObject> rules=Json.objects(converted);
        if(rules.isEmpty())throw new IllegalArgumentException("В профиле нет правил доменов или IP.");
        int entries=0,geo=0;
        for(JSONObject r:rules){
            int count=ConfigBuilder.split(r.optString("value"),r.optString("kind")).size();
            entries+=count;
            if(r.optString("kind").equals("geosite")||r.optString("kind").equals("geoip"))geo+=count;
        }
        String note="Правил: "+rules.size()+", записей: "+entries+".";
        if(geo>0)note+=" Списков geosite/geoip: "+geo+" — они переводятся в наборы правил sing-box и загружаются через прокси.";
        if(!json.optString("RemoteDns").isEmpty())note+=" DNS профиля применяется только к серверам его подписки.";
        confirm("Импортировать правила маршрутизации?",note,()->{for(JSONObject r:rules){r.remove("provider");store.addRule(r);}tab=SETTINGS;settingsPage="routes";render();changed();});
    }
    /**
     * Settings in Happ's order: interface on top, tunnel transport in the middle, advanced
     * and service pages at the bottom. Every control here maps to something the config builder
     * or the service really reads; Xray-only options are shown as explanations, not switches.
     */
    /** Settings grouped like Android's own: connection, servers, appearance, data, about. */
    private void settings(){heading("Настройки","");
        JSONObject s=store.settings();
        section("Подключение");
        rowSetting("route","Маршрутизация",routingModeLabel(s.optString("mode","rules"))+" · своих правил: "+store.rules().size(),()->openSettings("routes"));
        rowSetting("shield","Туннель и обход блокировок","TUN, DPI, DNS, MTU, локальный прокси",()->openSettings("tunnel"));
        rowSetting("apps","Приложения через VPN",appModeSummary(),()->openSettings("apps"));
        toggle("power","Автозапуск","Подключаться после перезагрузки телефона","autoConnect",false);
        toggle("pulse","Быстрый сервер автоматически","Переключаться на самый быстрый сервер профиля, проверка каждые 3 минуты","autoSelect",false);
        section("Серверы");
        rowSetting("refresh","Подписки","Обновление, User-Agent, HWID, сортировка",()->openSettings("subscriptions"));
        rowSetting("pulse","Проверка задержки",PingInfo.label(s.optString("pingType","proxyGet")),()->openSettings("ping"));
        section("Оформление");
        rowSetting("palette","Тема",label(s.optString("theme","system"),new String[]{"system","light","dark"},new String[]{"Как в системе","Светлая","Тёмная"}),this::themeChoice);
        toggle("palette","Цвета из обоев",Build.VERSION.SDK_INT>=31?"Material You: палитра под ваши обои":"Доступно с Android 12","dynamicColor",Build.VERSION.SDK_INT>=31);
        String font=s.optString("fontSize","normal");
        rowSetting("edit","Размер шрифта",font.equals("large")?"Крупный":font.equals("small")?"Мелкий":"Обычный",this::fontChoice);
        section("Данные");
        rowSetting("chart","Статистика сессии","Время, скорость, трафик",()->openSettings("stats"));
        rowSetting("download","Резервная копия","Экспорт и восстановление профилей",this::backupMenu);
        rowSetting("refresh","Сброс настроек","Серверы, подписки и правила останутся",this::resetSettings);
        section("О приложении");
        rowSetting("download","Проверить обновления","Установлена версия "+WraithApp.VERSION,this::checkUpdates);
        rowSetting("info","Частые вопросы","",this::faq);
        rowSetting("link","Схемы URL","Команды wraith://",()->openSettings("schemes"));
        rowSetting("shield","О Wraith",WraithApp.VERSION+" · Xray + sing-box",this::about);
    }
    private void tunnelSettings(){heading("Туннель и обход блокировок","Сеть, обход DPI и локальный прокси");
        JSONObject s=store.settings();
        section("ОСНОВНОЕ");
        toggle("Скорость в уведомлении","Показывать текущую скорость загрузки и отдачи","notifySpeed",false);

        toggle("Анализ пакетов","Определять домен и протокол соединения","sniff",true);
        rowSetting("Стек TUN",s.optString("tunStack","mixed"),()->choice("Стек TUN",new String[]{"mixed · рекомендуется","system · системный","gvisor · совместимость"},new String[]{"mixed","system","gvisor"},"tunStack","mixed"));
        section("ОБХОД DPI");
        toggle("Использовать фрагментирование","Разбивает начало соединения с сервером на мелкие части, чтобы DPI не распознал его. Применяется ко всем TCP-серверам.","fragment",false);
        if(s.optBoolean("fragment")){
            String type=s.optString("fragmentType","xray");
            rowSetting("Тип",type.equals("xray")?"Расширенная · диапазоны":"TLS ClientHello · по SNI",()->choice("Тип фрагментации",new String[]{"Расширенная · длина, задержка и число частей","TLS ClientHello · дробление по SNI"},new String[]{"xray","singbox"},"fragmentType","xray"));
            if(type.equals("xray")){
                rowSetting("Фрагментирование пакетов",s.optString("fragmentPackets","tlshello"),()->choice("Какие пакеты дробить",new String[]{"tlshello · только ClientHello","1-3 · первые три записи","1-5 · первые пять записей"},new String[]{"tlshello","1-3","1-5"},"fragmentPackets","tlshello"));
                rangeSetting("Длина фрагмента (от - до)","fragmentLength","50-100");
                rangeSetting("Задержка фрагмента (мин-макс), мс","fragmentInterval","10-20");
                rangeSetting("Максимальное разделение фрагмента","fragmentMaxSplit","100-200");
            }else{
                numberSetting("Задержка фрагмента, мс","fragmentDelay",10,0,1000);
                toggle("Фрагментирование TLS-записей","record_fragment: дробить и TLS-записи","recordFragment",false);
            }
        }
        toggle("Включить шумы","Мусорные UDP-пакеты перед первым настоящим: помогает Hysteria, TUIC и QUIC пройти DPI.","noise",false);
        if(s.optBoolean("noise")){
            String type=s.optString("noiseType","array");
            rowSetting("Тип",type,()->choice("Тип шума",new String[]{"array · байты в hex через запятую","rand · случайные байты","str · строка","base64"},new String[]{"array","rand","str","base64"},"noiseType","array"));
            if(!type.equals("rand"))rangeSetting("Пакет","noisePacket","1A,2B,3C");
            rangeSetting("Задержка, мс","noiseDelay","10-16");
            if(type.equals("rand")){rangeSetting("Rand (длина)","noiseRand","1-8192");rangeSetting("RandRange (значения байт)","noiseRange","0-255");}
        }
        toggle("Использовать Mux","Несколько соединений в одном потоке. Применяется ко всем серверам, кроме Vision, Hysteria и TUIC.","mux",false);
        if(s.optBoolean("mux")){
            rowSetting("Протокол",s.optString("muxProtocol","h2mux"),()->choice("Протокол мультиплексора",new String[]{"H2MUX","SMUX","YAMUX"},new String[]{"h2mux","smux","yamux"},"muxProtocol","h2mux"));
            rowSetting("Ограничение",s.optString("muxLimit","connections").equals("streams")?"по потокам":"по соединениям",()->choice("Ограничение",new String[]{"По соединениям","По потокам"},new String[]{"connections","streams"},"muxLimit","connections"));
            if(s.optString("muxLimit","connections").equals("streams"))numberSetting("Максимум потоков","muxStreams",8,1,1024);
            else numberSetting("TCP-соединения","muxConnections",4,1,1024);
            toggle("Заполнение","Маскировать размеры пакетов мультиплексора","muxPadding",true);
        }
        section("СЕТЬ");
        rowSetting("Обработка QUIC",s.optString("quic","allow").equals("reject")?"reject · браузеры уходят на TCP":"allow",()->choice("Обработка QUIC (UDP 443)",new String[]{"allow · пропускать","reject · блокировать, браузер перейдёт на TCP"},new String[]{"allow","reject"},"quic","allow"));
        rowSetting("Предпочтительный тип IP",label(s.optString("ipPreference","auto"),new String[]{"ipv4","ipv6","auto"},new String[]{"IPv4","IPv6","Auto"}),()->choice("Предпочтительный тип IP",new String[]{"IPv4 · меньше сбоев в мобильных сетях","IPv6","Auto · оба семейства"},new String[]{"ipv4","ipv6","auto"},"ipPreference","auto"));
        toggle("Использовать DNS из JSON","DNS провайдера из конфигурации сервера или профиля маршрутизации","dnsFromJson",false);
        boolean customDns=s.optBoolean("customDns");
        toggle("Свой DNS туннеля",customDns?"Запросы идут на адрес ниже":"Выключено — используется DNS телефона (Wi-Fi или оператора)","customDns",false);
        if(customDns)rowSetting("Адрес DNS",s.optString("tunnelDns").isEmpty()?DnsChoice.DEFAULT_CUSTOM+" · по умолчанию":s.optString("tunnelDns"),()->input("DNS: https://…, tls://… или IP (пусто — "+DnsChoice.DEFAULT_CUSTOM+")",s.optString("tunnelDns"),false,v->{if(!v.isEmpty())ConfigBuilder.server("check",v,"");store.setting("tunnelDns",v);render();changed();}));
        rowSetting("MTU туннеля",s.optInt("mtu",1500)+" байт",this::mtuMenu);
        String excluded=s.optString("excludedRoutes").trim();
        rowSetting("Исключённые маршруты",excluded.isEmpty()?"нет":excluded.split("\\n").length+" подсетей в обход туннеля",()->input("Подсети CIDR, по одной на строку",excluded,true,v->{for(String line:v.split("\\n")){String c=line.trim();if(!c.isEmpty()&&!c.matches("[0-9a-fA-F:.]+/[0-9]{1,3}"))throw new IllegalArgumentException("Не CIDR: "+c);}store.setting("excludedRoutes",v.trim());render();changed();}));
        rowSetting("Постоянный VPN / блокировка","Системные настройки Android",()->startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        section("ЛОКАЛЬНЫЙ ПРОКСИ");
        toggle("SOCKS5 / HTTP прокси","Для других приложений на телефоне: 127.0.0.1:"+s.optInt("proxyPort",2081),"localProxy",false);
        if(s.optBoolean("localProxy",false)){rowSetting("Порт",String.valueOf(s.optInt("proxyPort",2081)),()->input("Порт: 1024–65535",String.valueOf(s.optInt("proxyPort",2081)),false,v->{int n=Integer.parseInt(v.trim());if(n<1024||n>65535||n==9097)throw new IllegalArgumentException("1024–65535, кроме 9097");store.setting("proxyPort",n);render();changed();}));
        toggle("Разрешить подключения из LAN","0.0.0.0:"+s.optInt("proxyPort",2081)+" · требует пароль","allowLan",false);
        rowSetting("Пароль (для LAN)",s.optString("proxyPassword").isEmpty()?"Не задан":"Задан · пользователь wraith",()->input("Пароль LAN: от 8 символов",s.optString("proxyPassword"),false,v->{if(v.length()<8)throw new IllegalArgumentException("Минимум 8 символов");store.setting("proxyPassword",v);render();changed();}));}
    }
    /** Text setting validated by building the real config fragment, so a typo is caught here, not at connect. */
    private void rangeSetting(String title,String key,String def){
        rowSetting(title,store.settings().optString(key,def),()->input(title,store.settings().optString(key,def),false,v->{
            JSONObject probe=Json.copy(store.settings());probe.put(key,v.trim());
            if(key.startsWith("noise"))ConfigBuilder.wraithNoise(probe);else ConfigBuilder.wraithFragment(probe);
            store.setting(key,v.trim());render();changed();
        }));
    }
    private static String label(String value,String[] values,String[] labels){int i=Arrays.asList(values).indexOf(value);return i<0?value:labels[i];}
    /** Theme and font change the palette itself, so the activity is recreated instead of re-rendered. */
    private void themeChoice(){
        String[] values={"system","light","dark"};
        int selected=Math.max(0,Arrays.asList(values).indexOf(store.settings().optString("theme","system")));
        new InlinePanel.Builder(this).setTitle("Тема").setSingleChoiceItems(new String[]{"Как в системе","Светлая","Тёмная"},selected,(dialog,which)->{try{store.setting("theme",values[which]);dialog.dismiss();getDelegate().setLocalNightMode(Ui.nightMode(values[which]));}catch(Exception e){error(e);}}).setNegativeButton("Отмена",null).show();
    }
    private void fontChoice(){
        String[] values={"small","normal","large"};int selected=Math.max(0,Arrays.asList(values).indexOf(store.settings().optString("fontSize","normal")));
        new InlinePanel.Builder(this).setTitle("Размер шрифта").setSingleChoiceItems(new String[]{"Мелкий","Обычный","Крупный"},selected,(dialog,which)->{try{store.setting("fontSize",values[which]);dialog.dismiss();recreate();}catch(Exception e){error(e);}}).setNegativeButton("Отмена",null).show();
    }
    /** Small bottom sheet for a single numeric value; keeps the user on the tunnel settings page. */
    private void mtuMenu(){numberInput("MTU туннеля","mtu",1500,1280,9000);}
    private void numberInput(String title,String key,int def,int min,int max){input(title+" ("+min+"–"+max+")",String.valueOf(store.settings().optInt(key,def)),false,v->{int n;try{n=Integer.parseInt(v.trim());}catch(NumberFormatException e){throw new IllegalArgumentException("Введите целое число");}if(n<min||n>max)throw new IllegalArgumentException("Допустимо "+min+"–"+max);store.setting(key,n);render();changed();});}
    private void subscriptionSettings(){heading("Подписки","Обновление и данные, которые получает провайдер");
        JSONObject s=store.settings();
        section("ГРУППЫ");
        toggle("Автоматическое обновление подписок","Фоновая проверка раз в час","autoUpdate",true);
        int interval=s.optInt("updateInterval",0);
        rowSetting("Интервал автообновления",interval<=0?"как задал провайдер":interval+" ч",()->input("Часы: 1–730, 0 — как задал провайдер",String.valueOf(interval),false,v->{int n=Integer.parseInt(v.trim());if(n<0||n>730)throw new IllegalArgumentException("0–730");store.setting("updateInterval",n);render();}));
        toggle("Обновить при открытии","Если подошёл интервал","updateOnOpen",false);
        toggle("Пинг при открытии","Проверить все серверы при запуске","pingOnOpen",false);
        toggle("Подключаться при открытии","Сразу подключиться к выбранному серверу","connectOnOpen",false);
        section("ОТПРАВКА ДАННЫХ");
        toggle("Передавать HWID","Случайный ID установки, модель и версия Android","sendHwid",true);
        rowSetting("Время ожидания запроса",s.optInt("subTimeout",10)+" с",()->input("Секунды: 5–15",String.valueOf(s.optInt("subTimeout",10)),false,v->{int n=Integer.parseInt(v.trim());if(n<5||n>15)throw new IllegalArgumentException("5–15 секунд");store.setting("subTimeout",n);render();}));
        rowSetting("Идентификатор клиента",s.optString("userAgent",Subscriptions.USER_AGENT),()->input("User-Agent (пусто — автоматический режим)",s.optString("userAgent",Subscriptions.USER_AGENT),false,v->{if(v.trim().isEmpty())store.settings().remove("userAgent");else store.setting("userAgent",v.trim());store.save();render();}));
        section("СОРТИРОВКА СЕРВЕРОВ В ПОДПИСКЕ");
        rowSetting("Сортировка",label(s.optString("sort","none"),new String[]{"none","ping","name"},new String[]{"Без сортировки","По пингу","По алфавиту"}),()->choice("Сортировка серверов",new String[]{"Без сортировки","По пингу","По алфавиту"},new String[]{"none","ping","name"},"sort","none"));
    }
    private void statsPage(){heading("Статистика","Текущая сессия · обновляется каждую секунду");
        boolean on=WraithService.state==WraithService.CONNECTED;
        section("СЕРВЕР");
        TextView server=infoSetting("Сервер",""),uptime=infoSetting("Время коннекта","");
        section("СКОРОСТЬ");
        TextView down=infoSetting("Входящая",""),up=infoSetting("Исходящая","");
        section("ТРАФИК СЕССИИ");
        TextView received=infoSetting("Входящий",""),sent=infoSetting("Исходящий","");
        // The page used to be a frozen snapshot that needed a manual "Обновить".
        statsUpdater=()->{
            boolean live=WraithService.state==WraithService.CONNECTED;long secs=live?Math.max(0,(SystemClock.elapsedRealtime()-WraithService.since)/1000):0;
            server.setText(live?PingInfo.displayName(WraithService.profileName):"не подключено");
            uptime.setText(String.format(Locale.ROOT,"%02d:%02d:%02d",secs/3600,(secs/60)%60,secs%60));
            down.setText(live?bytes(WraithService.rxRate)+"/с":"0 Б/с");up.setText(live?bytes(WraithService.txRate)+"/с":"0 Б/с");
            received.setText(bytes(WraithService.rx));sent.setText(bytes(WraithService.tx));
        };
        statsUpdater.run();
        if(on){space(16);body.addView(button("Активные соединения","route",true,this::connections),new LinearLayout.LayoutParams(-1,-2));}
    }
    private void schemesPage(){heading("Схемы URL-адресов","Нажмите, чтобы скопировать команду Wraith");
        String[][] groups={{"ЗАПУСК VPN","connect","connect_without_ui","open","open_without_ui"},{"ОСТАНОВКА VPN","disconnect","disconnect_without_ui","close","close_without_ui"},{"ПЕРЕКЛЮЧЕНИЕ VPN","toggle","toggle_without_ui"},{"ДОБАВИТЬ СЕРВЕР","add/{url}"}};
        for(String[] group:groups){section(group[0]);for(int i=1;i<group.length;i++){String link="wraith://"+group[i];rowSetting(link,"",()->{copy(link);});}}
    }
    private void resetSettings(){confirm("Сбросить настройки?","Серверы, подписки и правила останутся. Настройки вернутся к значениям по умолчанию.",()->{
        // Installation identity is not a preference: dropping the IDs made
        // the provider count this phone as a new device (and could hit its device limit).
        Set<String> keep=Set.of("deviceId","clientNumericId");
        Iterator<String> keys=store.settings().keys();List<String> all=new ArrayList<>();while(keys.hasNext())all.add(keys.next());
        for(String k:all)if(!keep.contains(k))store.settings().remove(k);
        store.save();toast("Настройки сброшены");changed();recreate();
    });}
    private void faq(){message("FAQ",
        "Сайты из списка провайдера не открываются?\nЛоги → «Соединения». У сайта должно быть 🟢 DIRECT. Если в журнале «network is unreachable» — оставьте «Предпочтительный тип IP: IPv4».\n\n"+
        "Всё идёт напрямую и Telegram не работает?\nМаршрутизация → режим «По правилам». На главном экране под сервером не должно быть ⚠.\n\n"+
        "Подписка пришла без правил?\nОставьте автоматический идентификатор клиента: Wraith сам запросит расширенный формат у панели.\n\n"+
        "Какое ядро используется?\nVLESS, VMess, Trojan, Shadowsocks и Xray-JSON работают через Xray. Hysteria, TUIC, WireGuard, AnyTLS, SSH и полные JSON sing-box — через sing-box. Ядро выбирается автоматически по серверу.\n\n"+
        "Не подключается?\nПричина показывается под кнопкой подключения, полный текст — во вкладке «Логи» (разделы Xray и sing-box).\n\n"+
        "Как работают шумы и фрагментация?\nРасширенная фрагментация дробит начало TCP-соединения, шумы отправляются перед первым UDP-пакетом (Hysteria, TUIC).\n\n"+
        "Изменил настройку — ничего не поменялось?\nНастройки туннеля применяются после переподключения.");}
    private void openSettings(String page){if(tab==SETTINGS&&settingsPage.isEmpty())settingsRootScroll=scroll.getScrollY();settingsPage=page;tab=SETTINGS;restoreScroll=0;render();}
    @Override public void onBackPressed(){if(InlinePanel.dismissTop(this))return;if(dismissConfigEditor())return;if(tab==SETTINGS&&!settingsPage.isEmpty()){settingsPage="";restoreScroll=settingsRootScroll;render();}else if(tab!=HOME){tab=HOME;render();}else super.onBackPressed();}
    private boolean dismissConfigEditor(){android.view.ViewGroup content=findViewById(android.R.id.content);for(int i=content.getChildCount()-1;i>=0;i--){View child=content.getChildAt(i);if("wraith-config-editor".equals(child.getTag())){closeConfigEditor((FrameLayout)child);return true;}}return false;}
    private void pingSettings(){heading("Проверка задержки","Как измерять пинг серверов");
        JSONObject s=store.settings();String type=s.optString("pingType","proxyGet"),mode=s.optString("pingMode","keepalive");
        rowSetting("Способ",PingInfo.label(type)+" · "+(type.startsWith("proxy")?"запрос через сервер":type.equals("tcp")?"подключение к порту":"ICMP-пинг"),()->choice("Способ проверки",new String[]{"Proxy GET · запрос через сервер","Proxy HEAD · запрос без тела ответа","TCP · подключение к порту","ICMP · обычный ping"},new String[]{"proxyGet","proxyHead","tcp","icmp"},"pingType","proxyGet"));
        if(type.startsWith("proxy"))rowSetting("Соединения",mode.equals("double")?"Два отдельных соединения":"Одно соединение (keep-alive)",()->choice("Соединения при проверке",new String[]{"Одно соединение (keep-alive)","Два отдельных соединения"},new String[]{"keepalive","double"},"pingMode","keepalive"));
        if(type.startsWith("proxy"))rowSetting("Адрес проверки",s.optString("pingUrl","https://www.gstatic.com/generate_204"),()->input("HTTPS-адрес",s.optString("pingUrl","https://www.gstatic.com/generate_204"),false,v->{URI u=new URI(v);if(!"https".equals(u.getScheme())||u.getHost()==null)throw new IllegalArgumentException("Нужен HTTPS-адрес");store.setting("pingUrl",v);render();}));
        numberSetting("Тайм-аут, мс","pingTimeout",5000,1000,30000);
        note(type.startsWith("proxy")?"Через сервер: два запроса через временное ядро, без включения VPN; показывается лучший результат. Для Hysteria и TUIC всегда используется этот способ.":type.equals("tcp")?"TCP проверяет только, открыт ли порт сервера. Для Hysteria и TUIC автоматически используется проверка через сервер.":"ICMP может блокироваться сетью, оператором или Android.");
    }
    /** Footnote under a settings group, aligned with the text inside the rows. */
    private void note(String text){TextView t=label(text,13,MUTED);pad(t,16,12,16,0);}
    private void numberSetting(String title,String key,int def,int min,int max){rowSetting(title,String.valueOf(store.settings().optInt(key,def)),()->input(title+" ("+min+"–"+max+")",String.valueOf(store.settings().optInt(key,def)),false,v->{int n;try{n=Integer.parseInt(v.trim());}catch(NumberFormatException e){throw new IllegalArgumentException("Введите целое число");}if(n<min||n>max)throw new IllegalArgumentException("Допустимо "+min+"–"+max);store.setting(key,n);render();changed();}));}
    /**
     * Live connections from the core's Clash API: which host went through which outbound and by
     * which rule. The one definitive answer to "does this site really go direct?".
     */
    private void connections(){
        if(WraithService.state!=WraithService.CONNECTED){toast("Подключитесь, чтобы увидеть соединения");return;}
        // Only sing-box exposes the Clash API (random localhost port). For an Xray tunnel the request
        // used to fail with a bare "connection refused"; show Xray's own access log instead.
        if(CoreRouter.XRAY.equals(CoreRouter.activeEngine(this))){
            String access=tailLog("core/xray-access.log").trim();String[] lines=access.isEmpty()?new String[0]:access.split("\n");
            StringBuilder recent=new StringBuilder();for(int i=Math.max(0,lines.length-60);i<lines.length;i++)recent.append(lines[i]).append('\n');
            message("Соединения · Xray","Подключение работает через ядро Xray. Ниже — последние записи его журнала соединений (адрес → outbound).\n\n"+(recent.length()==0?"Пока пусто. Откройте сайт и вернитесь сюда.":recent.toString()));
            return;
        }
        task("Читаем соединения…",()->{
            JSONArray list=WraithService.api("/connections").optJSONArray("connections");
            StringBuilder out=new StringBuilder();
            int direct=0,proxy=0,other=0;
            List<JSONObject> items=Json.objects(list);
            items.sort((a,b)->b.optString("start").compareTo(a.optString("start")));
            for(JSONObject c:items){
                JSONObject m=c.optJSONObject("metadata");if(m==null)m=new JSONObject();
                String host=m.optString("host");
                if(host.isEmpty())host=m.optString("destinationIP");
                host+=":"+m.optString("destinationPort");
                JSONArray chains=c.optJSONArray("chains");
                String exit=chains==null||chains.length()==0?"?":chains.optString(0);
                boolean isDirect=exit.equals("direct");
                if(isDirect)direct++;else if(exit.startsWith("node-")||exit.equals("proxy"))proxy++;else other++;
                String rule=c.optString("rule");
                out.append(isDirect?"🟢 DIRECT  ":"🔒 VPN     ").append(host).append('\n');
                if(!rule.isEmpty())out.append("          ").append(rule.length()>70?rule.substring(0,70)+"…":rule).append('\n');
            }
            String header="Напрямую: "+direct+" · через VPN: "+proxy+(other>0?" · прочие: "+other:"")+"\n"+WraithService.routingSummary+"\n\n";
            String text=header+(out.length()==0?"Активных соединений нет. Откройте сайт и обновите.":out.toString());
            runOnUiThread(()->message("Соединения",text));
            return null;
        });
    }
    private void logsPage(){heading("Журнал","События приложения, Xray и sing-box",iconAction("refresh","Обновить",this::render),iconAction("copy","Скопировать журнал",()->copy(readLogs())));
        body.addView(button("Активные соединения","route",false,this::connections),new LinearLayout.LayoutParams(-1,-2));space(12);
        TextView log=text(this,readLogs(),12,TEXT);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);log.setLineSpacing(d(2),1);pad(log,16,16,16,16);log.setBackground(bg(CARD,d(20)));body.addView(log,new LinearLayout.LayoutParams(-1,-2));}
    private String readLogs(){return AppLog.text()+"\n\nXRAY · СЛУЖБА\n"+tailLog("core/xray-service.log")+"\n\nSING-BOX\n"+tailLog("core/session.log")+"\n\nXRAY · ОШИБКИ\n"+tailLog("core/xray-error.log")+"\n\nXRAY · СОЕДИНЕНИЯ\n"+tailLog("core/xray-access.log");}
    private String tailLog(String relative){StringBuilder out=new StringBuilder();File file=new File(getFilesDir(),relative);if(file.exists())try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.seek(Math.max(0,in.length()-30000));byte[] bytes=new byte[(int)(in.length()-in.getFilePointer())];in.readFully(bytes);out.append(new String(bytes,StandardCharsets.UTF_8));}catch(Exception e){out.append(Subscriptions.safeError(e));}return out.toString();}
    private String appModeSummary(){String apps=store.settings().optString("apps","").trim();String mode=appMode();if(mode.equals("off")||apps.isEmpty())return "Все приложения";int count=apps.split("\\n").length;return (mode.equals("include")?"Только выбранные: ":"Кроме выбранных: ")+count;}
    /** off: every app uses the tunnel; include: only the checked apps; exclude: the checked apps bypass it. */
    private String appMode(){return store.settings().optString("appMode","off");}
    private Map<String,String> installedApps;private String appSearch="";
    private void appsPage(){heading("Приложения через VPN","Какие приложения используют туннель");
        String mode=appMode();
        body.addView(segmented(new String[]{"off","include","exclude"},new String[]{"Все","Только выбранные","Кроме выбранных"},mode,id->{store.setting("appMode",id);render();changed();}),new LinearLayout.LayoutParams(-1,-2));space(12);
        label(mode.equals("off")?"Все приложения используют VPN.":mode.equals("include")?"Через VPN идут только отмеченные приложения, остальные — напрямую.":"Отмеченные приложения подключаются напрямую, остальные используют VPN.",12,MUTED);space(14);
        if(mode.equals("off"))return;
        if(installedApps==null){
            label("Загружаем приложения…",13,MUTED);
            WraithApp.IO.execute(()->{
                Map<String,String> names2=new TreeMap<>();
                PackageManager pm=getPackageManager();
                for(ApplicationInfo info:pm.getInstalledApplications(0)){
                    if(info.packageName.equals(getPackageName()))continue;
                    names2.put(info.packageName,info.loadLabel(pm).toString()+((info.flags&ApplicationInfo.FLAG_SYSTEM)!=0?" *":""));
                }
                runOnUiThread(()->{installedApps=names2;if(tab==SETTINGS&&settingsPage.equals("apps"))render();});
            });
            return;
        }
        Set<String> selected=new LinkedHashSet<>();for(String a:store.settings().optString("apps").split("\\n"))if(!a.trim().isEmpty())selected.add(a.trim());
        if(mode.equals("include")&&selected.isEmpty()){label("Отметьте хотя бы одно приложение, иначе VPN не запустится.",12,RED);space(8);}
        EditText search=edit("Поиск приложений",appSearch,false);body.addView(search,new LinearLayout.LayoutParams(-1,d(48)));space(10);
        LinearLayout list=column(this);body.addView(list);
        Runnable draw=()->{
            list.removeAllViews();
            List<Map.Entry<String,String>> entries=new ArrayList<>(installedApps.entrySet());
            // Checked apps first, then user apps before system ones (marked *), then by name.
            entries.sort(Comparator.comparing((Map.Entry<String,String> e)->!selected.contains(e.getKey())).thenComparing(e->e.getValue().endsWith(" *")).thenComparing(e->e.getValue().toLowerCase(Locale.ROOT)));
            String q=appSearch.toLowerCase(Locale.ROOT);int shown=0;
            for(Map.Entry<String,String> e:entries){
                if(!q.isEmpty()&&!(e.getValue()+" "+e.getKey()).toLowerCase(Locale.ROOT).contains(q))continue;
                if(++shown>300)break;
                CheckBox box=new com.google.android.material.checkbox.MaterialCheckBox(this);box.setChecked(selected.contains(e.getKey()));
                box.setText(e.getValue()+"\n"+e.getKey());box.setTextColor(TEXT);box.setTextSize(13*FONT);pad(box,4,8,4,8);
                box.setOnCheckedChangeListener((v,on)->{try{if(on)selected.add(e.getKey());else selected.remove(e.getKey());store.setting("apps",String.join("\n",selected));changed();}catch(Exception ex){error(ex);}});
                list.addView(box,new LinearLayout.LayoutParams(-1,-2));
            }
        };
        draw.run();
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int c,int f){}public void onTextChanged(CharSequence t,int a,int b,int c){appSearch=t.toString();draw.run();}public void afterTextChanged(Editable e){}});
    }
    private void choice(String title,String[] labels,String[] values,String key,String def){String current=store.settings().optString(key,def);int selected=Math.max(0,Arrays.asList(values).indexOf(current));new InlinePanel.Builder(this).setTitle(title).setCompact(true).setSingleChoiceItems(labels,selected,(dialog,which)->{try{store.setting(key,values[which]);dialog.dismiss();render();changed();}catch(Exception e){error(e);}}).setNegativeButton("Отмена",null).show();}
    private LinearLayout group;
    /** M3 list section header: primary colour, sentence case. */
    private void section(String s){closeGroup();space(16);TextView t=title(this,sentence(s),14,MINT);pad(t,16,0,16,8);body.addView(t);}
    static String sentence(String s){StringBuilder out=new StringBuilder();for(String word:s.split(" ")){if(out.length()>0)out.append(' ');out.append(word.matches(".*[А-ЯЁ].*")&&!word.matches(".*[A-Z].*")?word.toLowerCase(Locale.ROOT):word);}String r=out.toString();return r.isEmpty()?r:Character.toUpperCase(r.charAt(0))+r.substring(1);}
    private void addSettingRow(View v){if(group==null){group=column(this);body.addView(group,new LinearLayout.LayoutParams(-1,-2));}LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);if(group.getChildCount()>0)lp.topMargin=d(2);group.addView(v,lp);}
    /** Rows of one section form a single rounded group: large outer corners, small inner ones. */
    private void closeGroup(){if(group==null)return;LinearLayout g=group;group=null;int n=g.getChildCount();float big=d(24),small=d(4);for(int i=0;i<n;i++)g.getChildAt(i).setBackground(ripple(corners(CARD,i==0?big:small,i==n-1?big:small),d(4)));}
    private LinearLayout settingRow(String icon,String title,String sub){LinearLayout r=row(this);pad(r,16,14,16,14);r.setMinimumHeight(d(sub==null||sub.isEmpty()?56:72));
        if(icon!=null){Icon glyph=new Icon(this,icon,MUTED);LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(d(24),d(24));il.rightMargin=d(16);r.addView(glyph,il);}
        LinearLayout names=column(this);names.addView(text(this,title,16,TEXT));if(sub!=null&&!sub.isEmpty()){TextView s=text(this,sub,14,MUTED);s.setMaxLines(6);pad(s,0,4,0,0);names.addView(s);}
        r.addView(names,new LinearLayout.LayoutParams(0,-2,1));return r;}
    private void rowSetting(String title,String sub,Runnable click){rowSetting(null,title,sub,click);}
    private void rowSetting(String icon,String title,String sub,Runnable click){LinearLayout r=settingRow(icon,title,sub);r.setOnClickListener(v->click.run());addSettingRow(r);}
    private TextView infoSetting(String title,String value){LinearLayout r=row(this);pad(r,16,16,16,16);r.setMinimumHeight(d(56));r.addView(text(this,title,16,TEXT),new LinearLayout.LayoutParams(0,-2,1));TextView v=text(this,value,14,MUTED);v.setGravity(Gravity.END);r.addView(v);addSettingRow(r);return v;}
    private void toggle(String title,String desc,String key,boolean def){toggle(null,title,desc,key,def);}
    /** Setting row with an M3 switch; the whole row toggles it. */
    private void toggle(String icon,String title,String desc,String key,boolean def){
        LinearLayout r=settingRow(icon,title,desc);MaterialSwitch toggle=new MaterialSwitch(this);toggle.setChecked(store.settings().optBoolean(key,def));toggle.setClickable(false);toggle.setFocusable(false);toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(-2,-2);tl.leftMargin=d(16);r.addView(toggle,tl);
        r.setContentDescription(title);r.setOnClickListener(v->{boolean on=!toggle.isChecked();try{
            if(key.equals("allowLan")&&on&&store.settings().optString("proxyPassword").length()<8)throw new IllegalArgumentException("Сначала задайте пароль LAN-прокси");
            if(key.equals("dynamicColor")&&on&&Build.VERSION.SDK_INT<31)throw new IllegalArgumentException("Цвета из обоев доступны с Android 12");
            store.setting(key,on);toggle.setChecked(on);
            if(key.equals("dynamicColor")){handler.postDelayed(this::recreate,250);return;}
            changed();
            if(key.equals("fragment")||key.equals("noise")||key.equals("mux")||key.equals("dnsFromJson")||key.equals("customDns")||key.equals("localProxy"))handler.postDelayed(this::render,200);
        }catch(Exception e){error(e);}});
        addSettingRow(r);}
    private void changed(){if(WraithService.state==WraithService.CONNECTED)toast("Настройки применятся после переподключения");}
    private void importDialog(String initial){LinearLayout content=column(this);pad(content,0,4,0,0);TextView help=text(this,"Ссылка сервера, HTTPS-подписка или JSON sing-box",13,MUTED);content.addView(help);gap(content,14);EditText input=edit("vless://…  или  https://…",initial,true);input.setMinLines(3);content.addView(input);gap(content,12);LinearLayout actions=row(this);actions.addView(button("Вставить",null,false,()->{ClipboardManager cm=getSystemService(ClipboardManager.class);if(cm.hasPrimaryClip())input.setText(cm.getPrimaryClip().getItemAt(0).coerceToText(this));}),new LinearLayout.LayoutParams(0,-2,1));actions.addView(button("QR","qr",false,()->new IntentIntegrator(this).setOrientationLocked(false).setBeepEnabled(false).setPrompt("QR-код сервера или подписки").initiateScan()),new LinearLayout.LayoutParams(0,-2,1));actions.addView(button("Файл",null,false,()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),20)),new LinearLayout.LayoutParams(0,-2,1));content.addView(actions);InlinePanel dialog=new InlinePanel.Builder(this).setTitle("Новое подключение").setView(content).setNegativeButton("Отмена",null).setPositiveButton("Добавить",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(btn->{String value=input.getText().toString().trim();if(value.isEmpty()){input.setError("Вставьте ссылку");return;}dialog.dismiss();doImport(value);}));dialog.show();}
    private void importClipboard(){ClipboardManager cm=getSystemService(ClipboardManager.class);if(cm==null||!cm.hasPrimaryClip()){toast("Буфер обмена пуст");return;}CharSequence value=cm.getPrimaryClip().getItemAt(0).coerceToText(this);if(value==null||value.toString().trim().isEmpty()){toast("Буфер обмена пуст");return;}importDialog(value.toString().trim());}
    private void scanQr(){new IntentIntegrator(this).setOrientationLocked(false).setBeepEnabled(false).setPrompt("QR-код сервера или подписки").initiateScan();}
    private void doImport(String raw){if(raw.startsWith("happ://routing/")){try{importRouting(raw);}catch(Exception e){error(e);}return;}task("Добавляем подключение…",()->{String input=ImportParser.unwrap(raw);if(input.startsWith("https://")){JSONObject sub=Subscriptions.create(this,input);tab=SERVERS;return "Подписка «"+sub.optString("name")+"» добавлена";}if(input.startsWith("http://"))throw new IllegalArgumentException("Для подписок требуется HTTPS.");List<String> skipped=new ArrayList<>();List<JSONObject> parsed=ImportParser.parse(input,skipped);for(JSONObject p:parsed)if(p.optBoolean("full"))Libbox.checkConfig(p.getJSONObject("outbound").toString());store.addProfiles(parsed);tab=SERVERS;return "Добавлено серверов: "+parsed.size()+(skipped.isEmpty()?"":". Пропущено: "+skipped.size()+" — "+skipped.get(0));});}
    private void backupMenu(){new InlinePanel.Builder(this).setTitle("Резервная копия").setItems(new String[]{"Экспорт в файл","Восстановить из файла"},(dialog,w)->{if(w==0)confirm("Экспорт содержит ключи подключения","Резервная копия будет обычным JSON-файлом. Храните её в закрытом месте.",()->export(store.backup(),"wraith-backup.json"));else confirm("Заменить текущие данные?","После выбора резервной копии профили и настройки будут заменены. Активное подключение остановится.",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),22));}).show();}
    private void export(String data,String filename){exportData=data;exportName=filename;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,filename),21);}
    private void about(){String singbox;try{singbox=Libbox.version();}catch(Throwable e){singbox="недоступно";}message("Wraith · "+WraithApp.VERSION,"Ядра: Xray (VLESS, VMess, Trojan, Shadowsocks, Xray-JSON) и sing-box "+singbox+" (Hysteria, TUIC, WireGuard, AnyTLS, SSH, полный JSON sing-box). Ядро выбирается автоматически по серверу.\n\nСерверы приложение не предоставляет.");}
    private void checkUpdates(){task("Проверяем обновления…",()->{UpdateChecker.Result result=UpdateChecker.check(this);runOnUiThread(()->{if(result.available)showUpdateDialog(result);else toast("Установлена последняя версия Wraith "+WraithApp.VERSION);});return null;});}
    private void showUpdateDialog(UpdateChecker.Result result){
        LinearLayout content=column(this);TextView version=text(this,"Wraith "+result.name,18,TEXT);version.setTypeface(null,Typeface.BOLD);content.addView(version);if(!result.notes.isEmpty()){TextView notes=text(this,result.notes,13,MUTED);pad(notes,0,10,0,0);content.addView(notes);}new InlinePanel.Builder(this).setTitle("Доступно обновление").setCentered(true).setView(content).setNegativeButton("Позже",null).setPositiveButton("Обновить",(d,w)->downloadUpdate(result)).show();
    }
    private void downloadUpdate(UpdateChecker.Result result){
        if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
            pendingUpdate=result;toast("Разрешите Wraith устанавливать обновления");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));return;
        }
        if(busy){toast("Дождитесь текущей операции");return;}busy=true;
        LinearLayout content=column(this);TextView percent=text(this,"0%",22,TEXT);percent.setGravity(Gravity.CENTER);percent.setTypeface(null,Typeface.BOLD);content.addView(percent);ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(0);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,d(12));bp.setMargins(0,d(14),0,d(8));content.addView(bar,bp);TextView size=text(this,"Подготовка загрузки…",12,MUTED);size.setGravity(Gravity.CENTER);content.addView(size);InlinePanel progress=new InlinePanel.Builder(this).setTitle("Загрузка Wraith "+result.name).setCentered(true).setView(content).create();progress.show();
        WraithApp.IO.execute(()->{try{File apk=UpdateChecker.download(this,result,(done,total)->runOnUiThread(()->{int value=total>0?(int)Math.min(100,done*100/total):0;bar.setIndeterminate(total<=0);bar.setProgress(value);percent.setText(total>0?value+"%":bytes(done));size.setText(total>0?bytes(done)+" из "+bytes(total):"Загружено "+bytes(done));}));runOnUiThread(()->{busy=false;progress.dismiss();installUpdate(apk);});}catch(Exception e){runOnUiThread(()->{busy=false;progress.dismiss();error(e);});}});
    }
    private void installUpdate(File apk){
        try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",apk);Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(install);}
        catch(Exception e){error(e);}
    }
    private void qr(String value){try{BitMatrix matrix=new MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,720,720);Bitmap bitmap=Bitmap.createBitmap(720,720,Bitmap.Config.RGB_565);for(int y=0;y<720;y++)for(int x=0;x<720;x++)bitmap.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);pad(image,20,20,20,20);new InlinePanel.Builder(this).setTitle("QR конфигурации").setView(image).setPositiveButton("Готово",null).show();}catch(Exception e){message("QR недоступен","Конфигурация слишком большая. Поделитесь текстом или файлом.");}}
    private void copy(String text){getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Wraith",text));toast("Скопировано");}
    private void openLink(String url){try{Uri u=Uri.parse(url);if(!Set.of("https","tg").contains(u.getScheme()))throw new IllegalArgumentException("Провайдер не указал безопасную ссылку.");startActivity(new Intent(Intent.ACTION_VIEW,u));}catch(Exception e){error(e);}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan!=null){if(scan.getContents()!=null)importDialog(scan.getContents());return;}if(request==10){if(result==RESULT_OK)WraithService.start(this);else toast("Разрешение VPN не выдано");return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==20||request==22)task("Читаем файл…",()->{String text=new String(Subscriptions.bounded(getContentResolver().openInputStream(uri)),StandardCharsets.UTF_8);if(request==22){if(WraithService.state!=WraithService.OFF)WraithService.stop(this);store.restore(text);return "Резервная копия восстановлена";}runOnUiThread(()->importDialog(text));return null;});if(request==21){String text=exportData;task("Сохраняем…",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Не удалось открыть файл");out.write(text.getBytes(StandardCharsets.UTF_8));}return "Файл сохранён";});}}
    /** M3 outlined text field look: 1dp outline, 2dp primary outline while focused. */
    private EditText edit(String hint,String value,boolean multi){EditText e=new EditText(this);e.setTextColor(TEXT);e.setHintTextColor(MUTED);e.setTextSize(16*FONT);e.setHint(hint);e.setText(value);
        android.graphics.drawable.StateListDrawable outline=new android.graphics.drawable.StateListDrawable();android.graphics.drawable.GradientDrawable focused=bg(0x00000000,d(12));focused.setStroke(d(2),MINT);android.graphics.drawable.GradientDrawable idle=bg(0x00000000,d(12));idle.setStroke(d(1),OUTLINE);outline.addState(new int[]{android.R.attr.state_focused},focused);outline.addState(new int[0],idle);e.setBackground(outline);pad(e,16,14,16,14);e.setMinHeight(d(56));
        e.setSelectAllOnFocus(false);e.setSingleLine(!multi);e.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS|(multi?android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));if(multi){e.setMinLines(3);e.setMaxLines(12);e.setGravity(Gravity.TOP|Gravity.START);e.setHorizontallyScrolling(false);}
        if(Build.VERSION.SDK_INT>=29){android.graphics.drawable.GradientDrawable cursor=bg(MINT,d(1));cursor.setSize(d(2),d(20));e.setTextCursorDrawable(cursor);}
        return e;}
    private Spinner spinner(String[] names,int selected){Spinner s=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);s.setAdapter(adapter);s.setSelection(selected);android.graphics.drawable.GradientDrawable outline=bg(0x00000000,d(12));outline.setStroke(d(1),OUTLINE);s.setBackground(outline);s.setPopupBackgroundDrawable(bg(CARD2,d(12)));s.setMinimumHeight(d(56));pad(s,8,0,8,0);return s;}
    private void gap(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,d(height)));}
    interface Change{void run(String value)throws Exception;}interface Action{void run()throws Exception;}interface Background{String run()throws Exception;}
    private void input(String title,String value,boolean multi,Change change){LinearLayout layout=column(this);pad(layout,0,4,0,0);EditText e=edit(title,value,multi);layout.addView(e);InlinePanel dialog=new InlinePanel.Builder(this).setTitle(title).setCompact(!multi).setView(layout).setNegativeButton("Отмена",null).setPositiveButton("Сохранить",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(button->{try{change.run(e.getText().toString().trim());dialog.dismiss();}catch(Exception ex){e.setError(Subscriptions.safeError(ex));}}));dialog.show();}
    /** Real confirmation dialog. It used to be a top banner whose only button read «Открыть», even for "reset settings". */
    private void confirm(String title,String content,Action action){new InlinePanel.Builder(this).setTitle(title).setCentered(true).setMessage(content).setNegativeButton("Отмена",null).setPositiveButton("Продолжить",(d,w)->{try{action.run();}catch(Exception e){error(e);}}).show();}
    private void message(String title,String content){TextView text=text(this,content,14,TEXT);text.setTextIsSelectable(true);text.setLineSpacing(d(2),1);new InlinePanel.Builder(this).setTitle(title).setView(text).setPositiveButton("Закрыть",null).show();}
    private void task(String title,Background action){if(busy){toast("Дождитесь текущей операции");return;}busy=true;toast(title);WraithApp.IO.execute(()->{try{String result=action.run();runOnUiThread(()->{busy=false;if(isFinishing())return;refresh();if(result!=null)toast(result);});}catch(Exception e){runOnUiThread(()->{busy=false;refresh();error(e);});}});}
    private void error(Throwable e){showNotice(PingInfo.explain(Subscriptions.safeError(e)),true,null);}
    private void toast(String text){showNotice(text,false,null);}
    /** M3 snackbar above the navigation bar (or above the add button on the Servers tab). */
    private void showNotice(String value,boolean error,Runnable action){
        View host=findViewById(android.R.id.content);if(host==null||isFinishing())return;
        Snackbar bar=Snackbar.make(host,value,error?7000:Snackbar.LENGTH_SHORT);
        if(nav!=null&&nav.isAttachedToWindow())bar.setAnchorView(fab!=null&&fab.getVisibility()==View.VISIBLE&&tab==SERVERS?fab:nav);
        TextView t=bar.getView().findViewById(com.google.android.material.R.id.snackbar_text);if(t!=null)t.setMaxLines(4);
        if(action!=null)bar.setAction("Открыть",v->action.run());else if(error&&value.length()>80)bar.setAction("Подробнее",v->message("Ошибка",value));
        bar.show();
    }
}
