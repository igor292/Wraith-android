package app.wraith.client;

import android.content.Context;
import android.security.keystore.*;
import android.util.AtomicFile;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** UI mutations use one cached encrypted document; native service reads fresh immutable snapshots. */
public final class Store {
    private static Store instance;
    private final Context context;
    private JSONObject state;
    public static synchronized Store get(Context c) { if(instance==null) instance=new Store(c.getApplicationContext());return instance; }
    static JSONObject readOnlySnapshot(Context c)throws Exception{
        AtomicFile f=new AtomicFile(new File(c.getFilesDir(),"wraith.enc"));
        if(!f.getBaseFile().exists())return Json.obj("profiles",new JSONArray(),"subscriptions",new JSONArray(),"rules",new JSONArray(),"settings",new JSONObject(),"selected","");
        byte[] bytes=f.readFully();if(bytes.length<29)throw new IOException(L.t("Повреждён файл профилей","Profiles file is damaged"));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
        return new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8));
    }
    /** Test hook (Robolectric has no AndroidKeyStore): an unencrypted store that never touches disk. */
    static synchronized void useInMemory(Context c,JSONObject initial){instance=new Store(c.getApplicationContext(),initial);}
    private final boolean memory;
    private Store(Context c,JSONObject initial){context=c;state=initial;memory=true;}
    private Store(Context c) {
        context=c;memory=false;
        try {
            AtomicFile f=file();
            if(f.getBaseFile().exists()) {
                byte[] bytes=f.readFully(); if(bytes.length<29)throw new IOException(L.t("Повреждён файл профилей","Profiles file is damaged"));
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(bytes,12)));
                state=new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8));
            } else { state=Json.obj("profiles",new JSONArray(),"subscriptions",new JSONArray(),"rules",new JSONArray(),"settings",new JSONObject(),"selected",""); }
            boolean migrated=migrate();
            migrated=repairDuplicateProfileIds()||migrated;
            if(collapseExpandedXrayPools()||migrated)save();
        } catch(Exception e) { throw new IllegalStateException(L.t("Не удалось открыть зашифрованное хранилище. Данные сохранены; не очищайте приложение.","Could not open the encrypted storage. Your data is kept; do not clear the app."),e); }
    }
    /**
     * Drops settings that earlier builds stored but the config builder never emitted, and repairs
     * muxProtocol=xudp: sing-box has no XUDP multiplexer, so a profile saved by 0.7.3 with Mux on
     * would fail to build a configuration at connect time.
     */
    private boolean migrate() throws JSONException {
        JSONObject settings=state.optJSONObject("settings");
        if(settings==null){state.put("settings",new JSONObject());return true;}
        boolean changed=false;
        if(settings.optString("muxProtocol").equals("xudp"))settings.put("muxProtocol","h2mux");
        // Force refetches to use the proven Happ UA rather than a manually saved Wraith/Sing-box
        // identity that Remnawave routes to its other response template.
        String agent=settings.optString("userAgent");
        if(agent.startsWith("Wraith/")||agent.startsWith("singbox-Wraith/")||agent.startsWith("Happ/4.4.1/Android")){settings.remove("userAgent");changed=true;}
        // noise* and fragmentPackets/Length/MaxSplit are live again (0.9.1, patched core); only these stay dead.
        // 0.10.40: the custom tunnel DNS got its own switch (off = the phone's DNS). Someone who
        // had typed an address keeps using it.
        if(!settings.has("customDns")&&!settings.optString("tunnelDns").trim().isEmpty()){settings.put("customDns",true);changed=true;}
        // Anonymous statistics were removed entirely in 0.10.36; drop their consent and IDs.
        for(String dead:List.of("fragmentDelayRange","preferredIp","fakeDns","remoteDns","domesticDns","ipv6","anonymousStats","anonymousStatsConsent","anonymousStatsId"))if(settings.has(dead)){settings.remove(dead);changed=true;}
        context.deleteSharedPreferences("wraith_telemetry");
        return changed;
    }
    /** Outbound tags are derived from profile IDs, so persisted duplicate IDs make core startup fail. */
    private boolean repairDuplicateProfileIds() throws JSONException {
        JSONArray profiles=state.optJSONArray("profiles");if(profiles==null)return false;
        Set<String> used=new HashSet<>();boolean changed=false;
        for(int i=0;i<profiles.length();i++){
            JSONObject profile=profiles.optJSONObject(i);if(profile==null)continue;
            String id=profile.optString("id");
            if(id.isEmpty()||!used.add(id)){
                String replacement;do{replacement=UUID.randomUUID().toString();}while(!used.add(replacement));
                profile.put("id",replacement);changed=true;
            }
        }
        return changed;
    }
    /** Undo the 0.10.11 import that exposed every internal Xray balancer candidate as a row. */
    private boolean collapseExpandedXrayPools() throws Exception {
        Map<String,List<JSONObject>> bySource=new LinkedHashMap<>();
        for(JSONObject p:profiles()){
            String source=p.optString("source");
            if(!p.optBoolean("jsonSource")||!source.startsWith("{")||p.optString("name").lastIndexOf(" · ")<0)continue;
            bySource.computeIfAbsent(p.optString("group")+"\n"+source,k->new ArrayList<>()).add(p);
        }
        if(bySource.isEmpty())return false;
        JSONArray next=new JSONArray();Set<String> removedIds=new HashSet<>();Map<String,JSONObject> replacements=new HashMap<>();
        for(List<JSONObject> expanded:bySource.values()){
            if(expanded.size()<2)continue;
            String source=expanded.get(0).optString("source");JSONObject root;
            try{root=new JSONObject(source);}catch(Exception ignored){continue;}
            if(!XrayConfig.looksLike(root))continue;
            String base=XrayConfig.str(root,"remarks","name");if(base.isEmpty())base="Xray";
            boolean generated=true;
            for(JSONObject p:expanded)if(!p.optString("name").matches(java.util.regex.Pattern.quote(base)+" · [0-9]{2}")){generated=false;break;}
            if(!generated)continue;
            JSONObject merged=XrayConfig.profile(root,source),keep=expanded.get(0);
            merged.put("id",keep.getString("id"));merged.put("group",keep.optString("group"));
            boolean favorite=false,selected=false;
            for(JSONObject p:expanded){favorite|=p.optBoolean("favorite");selected|=p.optString("id").equals(state.optString("selected"));if(p!=keep)removedIds.add(p.optString("id"));}
            merged.put("favorite",favorite).put("latency",-1);
            if(selected)state.put("selected",keep.getString("id"));
            replacements.put(keep.optString("id"),merged);
        }
        if(replacements.isEmpty())return false;
        for(JSONObject p:profiles()){
            if(removedIds.contains(p.optString("id")))continue;
            JSONObject replacement=replacements.get(p.optString("id"));
            next.put(replacement==null?p:replacement);
        }
        state.put("profiles",next);
        return true;
    }
    private AtomicFile file(){return new AtomicFile(new File(context.getFilesDir(),"wraith.enc"));}
    private javax.crypto.SecretKey key() throws Exception {return storageKey();}
    private static javax.crypto.SecretKey storageKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(!ks.containsAlias("wraith.storage.v1")) {
            KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("wraith.storage.v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());gen.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey("wraith.storage.v1",null);
    }
    public synchronized void save() throws Exception {
        if(memory)return;
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] data=cipher.doFinal(state.toString().getBytes(StandardCharsets.UTF_8));
        AtomicFile f=file();FileOutputStream out=null;
        try {out=f.startWrite();out.write(cipher.getIV());out.write(data);f.finishWrite(out);}catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
    public synchronized JSONObject snapshot() throws JSONException {return Json.copy(state);}
    public synchronized List<JSONObject> profiles(){return Json.objects(state.optJSONArray("profiles"));}
    public synchronized List<JSONObject> subscriptions(){return Json.objects(state.optJSONArray("subscriptions"));}
    public synchronized List<JSONObject> rules(){return Json.objects(state.optJSONArray("rules"));}
    public synchronized JSONObject settings(){return state.optJSONObject("settings");}
    public synchronized String selectedId(){return state.optString("selected");}
    public synchronized JSONObject selected(){for(JSONObject p:profiles())if(p.optString("id").equals(selectedId()))return p;return null;}
    /** Ping results describe the previous process and must not reorder a fresh app launch. */
    public synchronized void clearPingResults()throws Exception{for(JSONObject p:profiles()){p.remove("latency");p.remove("latencyType");p.remove("pingError");}save();}
    public synchronized void select(String id) throws Exception {state.put("selected",id);save();QuickTile.sync(context);}
    public synchronized void replaceProfile(JSONObject profile)throws Exception{
        JSONArray a=state.getJSONArray("profiles");String id=profile.getString("id");
        for(int i=0;i<a.length();i++)if(a.getJSONObject(i).optString("id").equals(id)){a.put(i,profile);save();return;}
        throw new IllegalArgumentException(L.t("Сервер больше не найден в списке.","The server is no longer in the list."));
    }
    public synchronized void setting(String key,Object value)throws Exception{settings().put(key,value);save();}
    public synchronized void addProfiles(List<JSONObject> list)throws Exception{for(JSONObject p:list)state.getJSONArray("profiles").put(p);if(selected()==null&&!list.isEmpty())state.put("selected",list.get(0).getString("id"));save();}
    public synchronized void deleteProfile(String id)throws Exception{JSONArray a=state.getJSONArray("profiles");for(int i=a.length()-1;i>=0;i--)if(a.getJSONObject(i).optString("id").equals(id))a.remove(i);if(selected()==null)state.put("selected",a.length()>0?a.getJSONObject(0).getString("id"):"");save();}
    public synchronized void addSubscription(JSONObject sub)throws Exception{state.getJSONArray("subscriptions").put(sub);save();}
    public synchronized void removeSubscription(String id)throws Exception{
        JSONArray a=state.getJSONArray("subscriptions");for(int i=a.length()-1;i>=0;i--)if(a.getJSONObject(i).optString("id").equals(id))a.remove(i);
        a=state.getJSONArray("profiles");for(int i=a.length()-1;i>=0;i--)if(a.getJSONObject(i).optString("group").equals(id))a.remove(i);
        if(selected()==null)state.put("selected",a.length()>0?a.getJSONObject(0).getString("id"):"");save();
    }
    public synchronized void replaceSubscription(JSONObject sub,List<JSONObject> incoming)throws Exception{
        String id=sub.getString("id");List<JSONObject> previous=new ArrayList<>();Set<String> usedIds=new HashSet<>();
        for(JSONObject p:profiles())if(p.optString("group").equals(id))previous.add(p);else usedIds.add(p.optString("id"));
        JSONArray next=new JSONArray();for(JSONObject p:profiles())if(!p.optString("group").equals(id))next.put(p);
        reconcileSubscriptionProfiles(previous,incoming,usedIds,id);for(JSONObject p:incoming)next.put(p);
        state.put("profiles",next);if(selected()==null&&next.length()>0)state.put("selected",next.getJSONObject(0).getString("id"));
        JSONArray subs=state.getJSONArray("subscriptions");boolean exists=false;for(int i=0;i<subs.length();i++)if(subs.getJSONObject(i).optString("id").equals(id)){subs.put(i,sub);exists=true;break;}if(!exists)subs.put(sub);
        save();
    }
    /**
     * Stable identity of a subscription node. Providers rename nodes constantly (they append usage
     * or load to the label), so matching on the endpoint and credential keeps the selection,
     * favourite flag and measured latency attached to the same server across updates.
     */
    private static String identity(JSONObject p){
        JSONObject o=p.optJSONObject("outbound");
        if(o==null)return "name|"+p.optString("name");
        String secret=o.optString("uuid",o.optString("password",""));
        return o.optString("type")+"|"+o.optString("server")+"|"+o.optInt("server_port")+"|"+secret;
    }
    /** Preserve IDs and metadata across refreshes while consuming duplicate endpoint matches once. */
    static void reconcileSubscriptionProfiles(List<JSONObject> previous,List<JSONObject> incoming,Set<String> usedIds,String group)throws JSONException{
        Map<String,ArrayDeque<JSONObject>> byIdentity=new HashMap<>();
        for(JSONObject p:previous)byIdentity.computeIfAbsent(identity(p),k->new ArrayDeque<>()).addLast(p);
        for(JSONObject p:incoming){p.put("group",group);ArrayDeque<JSONObject> matches=byIdentity.get(identity(p));JSONObject prev=matches==null||matches.isEmpty()?null:matches.removeFirst();
            if(prev!=null){String oldId=prev.optString("id");if(!oldId.isEmpty()&&!usedIds.contains(oldId))p.put("id",oldId);p.put("favorite",prev.optBoolean("favorite"));if(prev.optInt("latency",-1)>=0){p.put("latency",prev.optInt("latency"));p.put("latencyType",prev.optString("latencyType","tcp"));}}
            String id=p.optString("id");if(id.isEmpty()||!usedIds.add(id)){do{id=UUID.randomUUID().toString();}while(!usedIds.add(id));p.put("id",id);}
        }
    }
    public synchronized void addRule(JSONObject r)throws Exception{state.getJSONArray("rules").put(r);save();}
    public synchronized void removeRule(int index)throws Exception{state.getJSONArray("rules").remove(index);save();}
    public synchronized String backup()throws Exception{return snapshot().put("format","wraith-backup-v1").toString(2);}
    public synchronized void restore(String input)throws Exception{
        JSONObject restored=new JSONObject(input);
        if(!restored.optString("format").equals("wraith-backup-v1")||restored.optJSONArray("profiles")==null||restored.optJSONArray("subscriptions")==null||restored.optJSONObject("settings")==null||restored.optJSONArray("rules")==null)throw new IllegalArgumentException(L.t("Это не резервная копия Wraith.","This is not a Wraith backup."));
        for(JSONObject p:Json.objects(restored.getJSONArray("profiles")))if(p.optJSONObject("outbound")==null||p.optString("id").isEmpty())throw new IllegalArgumentException(L.t("Некорректный профиль в резервной копии.","Invalid profile in the backup."));
        JSONObject previous=state;state=restored;try{save();}catch(Exception e){state=previous;throw e;}
    }
}
