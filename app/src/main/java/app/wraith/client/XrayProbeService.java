package app.wraith.client;

import android.content.Intent;
import android.net.VpnService;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Native probes have a separate Go process and share one temporary Xray instance per batch. */
public final class XrayProbeService extends VpnService {
    static final String ACTION="app.wraith.client.XRAY_PROBE",ACTION_CANCEL="app.wraith.client.XRAY_PROBE_CANCEL";
    static final String EXTRA_PROFILE="profile",EXTRA_SETTINGS="settings",EXTRA_URL="url",EXTRA_TIMEOUT="timeout",EXTRA_METHOD="method",EXTRA_MODE="mode",EXTRA_RECEIVER="receiver",EXTRA_MESSENGER="messenger",EXTRA_SESSION="session";
    static final int RESULT=0,STARTED=1;
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    private final Object lock=new Object();
    private final ArrayDeque<Job> pending=new ArrayDeque<>();
    private final Set<String> cancelledSessions=new HashSet<>();
    private boolean draining,closed;
    private int lastStartId;
    private IsolatedXray xray;

    private static final class Job {
        final String profile,settings,url,session,method,mode;
        final int timeout;
        final ResultReceiver receiver;final Messenger messenger;
        Job(Intent intent,ResultReceiver receiver,Messenger messenger){
            profile=intent.getStringExtra(EXTRA_PROFILE);settings=intent.getStringExtra(EXTRA_SETTINGS);
            url=intent.getStringExtra(EXTRA_URL);session=intent.getStringExtra(EXTRA_SESSION);
            method=intent.getStringExtra(EXTRA_METHOD)==null?"GET":intent.getStringExtra(EXTRA_METHOD);
            mode=intent.getStringExtra(EXTRA_MODE)==null?"keepalive":intent.getStringExtra(EXTRA_MODE);
            timeout=Math.max(1,intent.getIntExtra(EXTRA_TIMEOUT,5000));this.receiver=receiver;this.messenger=messenger;
        }
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        synchronized(lock){
            lastStartId=startId;
            if(closed)return START_NOT_STICKY;
            if(intent!=null&&ACTION_CANCEL.equals(intent.getAction())){
                String session=intent.getStringExtra(EXTRA_SESSION);
                if(session!=null){
                    cancelledSessions.add(session);
                    Iterator<Job> it=pending.iterator();while(it.hasNext()){
                        Job job=it.next();if(session.equals(job.session)){it.remove();reply(job,-1,"Проверка отменена.");}
                    }
                }
            }else{
                ResultReceiver receiver=receiver(intent);Messenger messenger=messenger(intent);
                if(intent!=null&&ACTION.equals(intent.getAction())&&(receiver!=null||messenger!=null)){
                    Job job=new Job(intent,receiver,messenger);
                    if(cancelledSessions.contains(job.session))reply(job,-1,"Проверка отменена.");else pending.add(job);
                }
            }
            if(!draining&&!pending.isEmpty()){draining=true;worker.schedule(this::drain,30,TimeUnit.MILLISECONDS);}
            else if(!draining&&pending.isEmpty())stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private void drain(){
        List<Job> batch=new ArrayList<>(5);
        synchronized(lock){
            if(closed)return;
            Job first=pending.poll();
            if(first!=null){
                batch.add(first);Iterator<Job> it=pending.iterator();
                while(it.hasNext()&&batch.size()<5){Job next=it.next();if(next.timeout==first.timeout&&Objects.equals(next.url,first.url)){batch.add(next);it.remove();}}
            }
        }
        List<Job> probing=new ArrayList<>(batch.size());Map<Job,Integer> ports=new IdentityHashMap<>();boolean startedCore=false;
        try{
            if(xray==null)xray=new IsolatedXray(this);
            xray.setDNS(XrayRuntimeConfig.dnsEndpoint("1.1.1.1"));
            for(Job job:batch){
                if(cancelled(job))continue;
                probing.add(job);
            }
            if(probing.isEmpty())return;
            JSONObject runtime=runtimeProbeConfig(probing,ports);
            if(probing.isEmpty())return;
            JSONObject start=new JSONObject(xray.invoke(Json.obj("apiVersion",3,"method","runXray","payload",Json.obj("xrayJson",runtime.toString())).toString()));
            if(!start.optBoolean("success"))throw new IllegalStateException(start.optString("error","Не удалось запустить Xray для проверки."));
            startedCore=true;
            // runXray reports that the native core has started before all SOCKS listeners are
            // necessarily bound. Do not race the first HTTP probe against that async startup.
            for(Job job:probing)awaitLoopback(ports.get(job),job.timeout,()->cancelled(job));
            for(Job job:probing)send(job,STARTED,new Bundle());
            ExecutorService checks=Executors.newFixedThreadPool(Math.min(5,probing.size()));
            try{Map<Job,Future<Integer>> futures=new IdentityHashMap<>();for(Job job:probing)futures.put(job,checks.submit(()->XrayHttpProbe.measure(job.url,job.method,job.mode,job.timeout,ports.get(job),()->cancelled(job))));
                for(Job job:probing)if(!cancelled(job))try{reply(job,futures.get(job).get(job.timeout*2L+5000,TimeUnit.MILLISECONDS),"");}catch(Throwable e){Throwable cause=e instanceof ExecutionException&&e.getCause()!=null?e.getCause():e;reply(job,-1,Subscriptions.safeError(cause));}
            }finally{checks.shutdownNow();}
        }catch(Throwable e){for(Job job:probing.isEmpty()?batch:probing)if(!cancelled(job))reply(job,-1,Subscriptions.safeError(e));}
        finally{
            if(startedCore)try{xray.invoke(Json.obj("apiVersion",3,"method","stopXray","payload",new JSONObject()).toString());}catch(Throwable ignored){}
            if(xray!=null)xray.resetDNS();
            synchronized(lock){
                if(closed)return;
                if(!pending.isEmpty())worker.schedule(this::drain,30,TimeUnit.MILLISECONDS);
                else{draining=false;stopSelf(lastStartId);}
            }
        }
    }
    private boolean cancelled(Job job){synchronized(lock){return closed||cancelledSessions.contains(job.session);}}

    /** Build one short-lived Xray instance with one forced-outbound loopback SOCKS inlet per job. */
    private JSONObject runtimeProbeConfig(List<Job> jobs,Map<Job,Integer> ports)throws Exception{
        JSONArray outbounds=new JSONArray(),inbounds=new JSONArray(),rules=new JSONArray();JSONObject firstDns=null;
        List<Job> valid=new ArrayList<>();int index=0;
        for(Job job:new ArrayList<>(jobs))try{
            JSONObject profile=new JSONObject(job.profile),settings=job.settings==null?new JSONObject():new JSONObject(job.settings);
            int port=freeLoopbackPort();
            JSONObject dns=appendProbe(profile,settings,XrayRuntimeConfig.source(profile,xray),index,port,outbounds,inbounds,rules);
            if(firstDns==null)firstDns=dns;
            ports.put(job,port);valid.add(job);index++;
        }catch(Throwable e){reply(job,-1,Subscriptions.safeError(e));}
        jobs.clear();jobs.addAll(valid);if(valid.isEmpty())return new JSONObject();
        return assembleProbe(inbounds,outbounds,rules,firstDns,XrayAssets.ensure(this).getAbsolutePath());
    }
    /** Adds one job's server (and the dialers it depends on) behind its own loopback SOCKS inlet. Returns the provider DNS, if any. */
    static JSONObject appendProbe(JSONObject profile,JSONObject settings,JSONObject source,int index,int port,JSONArray outbounds,JSONArray inbounds,JSONArray rules)throws Exception{
        settings=Json.copy(settings);settings.put("pinCandidate",true);
        JSONObject root=XrayRuntimeConfig.prepare(profile,settings,source);
        JSONArray sourceOutbounds=root.optJSONArray("outbounds");if(sourceOutbounds==null)throw new IllegalArgumentException("В конфигурации нет Xray outbounds.");
        LinkedHashMap<String,JSONObject> byTag=new LinkedHashMap<>();for(JSONObject outbound:Json.objects(sourceOutbounds)){String tag=outbound.optString("tag");if(tag.isEmpty())continue;if(byTag.put(tag,outbound)!=null)throw new IllegalArgumentException("Повторяется outbound tag: "+tag);}
        String selected=XrayRuntimeConfig.proxyTag(root,profile);LinkedHashSet<String> dependencies=new LinkedHashSet<>();collectDependencies(selected,byTag,dependencies,new HashSet<>());
        Map<String,String> renamed=new HashMap<>();int dependencyIndex=0;for(String tag:dependencies)renamed.put(tag,"wraith-probe-"+index+"-"+(dependencyIndex++));
        for(String tag:dependencies){JSONObject copy=Json.copy(byTag.get(tag));copy.put("tag",renamed.get(tag));JSONObject stream=copy.optJSONObject("streamSettings"),sockopt=stream==null?null:stream.optJSONObject("sockopt");if(sockopt!=null){String dependency=sockopt.optString("dialerProxy");if(!dependency.isEmpty())sockopt.put("dialerProxy",renamed.get(dependency));}JSONObject legacy=copy.optJSONObject("proxySettings");if(legacy!=null&&renamed.containsKey(legacy.optString("tag")))legacy.put("tag",renamed.get(legacy.optString("tag")));outbounds.put(copy);}
        String inboundTag="wraith-probe-in-"+index;
        inbounds.put(Json.obj("tag",inboundTag,"listen","127.0.0.1","port",port,"protocol","socks","settings",Json.obj("auth","noauth","udp",false)));
        rules.put(Json.obj("type","field","inboundTag",Json.arr(inboundTag),"outboundTag",renamed.get(selected)));
        return probeDns(root.optJSONObject("dns"));
    }
    static JSONObject assembleProbe(JSONArray inbounds,JSONArray outbounds,JSONArray rules,JSONObject dns,String assets)throws Exception{
        // Xray's own DNS queries and anything unmatched leave directly, never through a random probed server.
        JSONArray all=new JSONArray(outbounds.toString());all.put(Json.obj("tag","wraith-probe-direct","protocol","freedom"));
        JSONArray ordered=new JSONArray();ordered.put(all.getJSONObject(all.length()-1));for(int i=0;i<all.length()-1;i++)ordered.put(all.getJSONObject(i));
        JSONObject runtime=Json.obj("log",Json.obj("loglevel","none"),"inbounds",inbounds,"outbounds",ordered,"routing",Json.obj("domainStrategy","AsIs","rules",rules));
        if(dns!=null)runtime.put("dns",dns);
        runtime.put("env",Json.obj("xray.location.asset",assets));return runtime;
    }
    /**
     * Provider DNS sections in auto configs are written for the whole tunnel: servers bound to
     * inbound/outbound tags, geosite domain lists, fakedns, "localhost". Only plain resolver addresses
     * are useful (and safe) for a latency probe; everything else made Xray refuse to start.
     */
    static JSONObject probeDns(JSONObject dns)throws Exception{
        if(dns==null)return null;JSONArray servers=new JSONArray();
        JSONArray list=dns.optJSONArray("servers");
        for(int i=0;list!=null&&i<list.length();i++){Object item=list.opt(i);
            String address=item instanceof JSONObject?((JSONObject)item).optString("address"):String.valueOf(item);address=address.trim();
            if(address.isEmpty()||address.equalsIgnoreCase("localhost")||address.startsWith("fakedns")||address.contains("+local"))continue;
            servers.put(address);if(servers.length()>=3)break;
        }
        if(servers.length()==0)return null;
        JSONObject out=Json.obj("servers",servers);String strategy=dns.optString("queryStrategy");if(!strategy.isEmpty())out.put("queryStrategy",strategy);
        JSONObject hosts=dns.optJSONObject("hosts");if(hosts!=null){JSONObject plain=new JSONObject();for(Iterator<String> it=hosts.keys();it.hasNext();){String k=it.next();if(!k.contains(":"))plain.put(k,hosts.get(k));}if(plain.length()>0)out.put("hosts",plain);}
        return out;
    }
    private static void collectDependencies(String tag,Map<String,JSONObject> byTag,LinkedHashSet<String> ordered,Set<String> visiting)throws Exception{
        if(ordered.contains(tag))return;if(!visiting.add(tag))throw new IllegalArgumentException("Циклическая зависимость Xray outbound: "+tag);
        JSONObject outbound=byTag.get(tag);if(outbound==null)throw new IllegalArgumentException("Не найдена зависимость Xray outbound: "+tag);
        JSONObject stream=outbound.optJSONObject("streamSettings"),sockopt=stream==null?null:stream.optJSONObject("sockopt");String dependency=sockopt==null?"":sockopt.optString("dialerProxy");if(!dependency.isEmpty())collectDependencies(dependency,byTag,ordered,visiting);
        visiting.remove(tag);ordered.add(tag);
    }
    private static int freeLoopbackPort()throws IOException{try(java.net.ServerSocket socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private static void awaitLoopback(int port,int timeoutMs,java.util.function.BooleanSupplier cancelled)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+Math.min(Math.max(timeoutMs,1000),5000);IOException last=null;
        while(SystemClock.elapsedRealtime()<deadline){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException("Проверка отменена.");
            try(java.net.Socket socket=new java.net.Socket()){
                socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),100);return;
            }catch(IOException e){last=e;Thread.sleep(20);}
        }
        throw new IOException("Xray не открыл локальный SOCKS-вход для проверки.",last);
    }

    private static ResultReceiver receiver(Intent intent){
        if(intent==null)return null;
        if(Build.VERSION.SDK_INT>=33)return intent.getParcelableExtra(EXTRA_RECEIVER,ResultReceiver.class);
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_RECEIVER);
    }
    private static Messenger messenger(Intent intent){
        if(intent==null)return null;
        if(Build.VERSION.SDK_INT>=33)return intent.getParcelableExtra(EXTRA_MESSENGER,Messenger.class);
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_MESSENGER);
    }
    private static void send(Job job,int code,Bundle data){
        if(job.receiver!=null)job.receiver.send(code,data);
        else try{Message message=Message.obtain(null,code);message.setData(data);job.messenger.send(message);}catch(RemoteException ignored){}
    }
    private static void reply(Job job,int latency,String error){Bundle data=new Bundle();data.putInt("latency",latency);data.putString("error",error);send(job,RESULT,data);}

    static JSONObject probeConfig(JSONObject profile,IsolatedXray xray)throws Exception{return probeConfig(profile,new JSONObject(),xray);}
    static JSONObject probeConfig(JSONObject profile,JSONObject settings,IsolatedXray xray)throws Exception{
        JSONObject root=XrayRuntimeConfig.source(profile,xray);
        root=XrayRuntimeConfig.prepare(profile,Json.copy(settings==null?new JSONObject():settings).put("pinCandidate",true),root);
        // libXray's pingBatch resolves and namespaces dialer/proxy dependencies itself. Retain the
        // complete graph and original tags so a fragment/noise dialer is never silently lost.
        JSONArray outbounds=root.optJSONArray("outbounds");
        if(outbounds==null||outbounds.length()==0)throw new IllegalArgumentException("В конфигурации нет Xray-сервера.");
        return Json.obj("outbounds",new JSONArray(outbounds.toString()));
    }
    @Override public void onDestroy(){
        synchronized(lock){closed=true;for(Job job:pending)reply(job,-1,"Проверка отменена.");pending.clear();}
        worker.execute(()->{if(xray!=null)xray.resetDNS();});
        worker.shutdown(); // Let the bounded native batch close its instance before resetting DNS.
        super.onDestroy();
    }
}
