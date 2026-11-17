package app.wraith.client;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/**
 * Updates come from the project's GitHub Releases: the latest release names the version, its
 * APK asset carries the SHA-256 digest GitHub computed on upload, and Android itself refuses an
 * APK signed with a different key.
 */
final class UpdateChecker {
    static final String REPO="trinity13338/Wraith-android";
    static final String LATEST_URL="https://api.github.com/repos/"+REPO+"/releases/latest";
    /** The only hosts an update request may reach, including redirects to GitHub's asset storage. */
    private static final Set<String> HOSTS=Set.of("api.github.com","github.com","objects.githubusercontent.com","release-assets.githubusercontent.com");
    private UpdateChecker(){}

    static Result check(Context context)throws Exception{
        HttpURLConnection connection=open(LATEST_URL,8000,12000);
        try{
            connection.setRequestProperty("Accept","application/vnd.github+json");
            int status=connection.getResponseCode();
            if(status==404)throw new IOException("На GitHub пока нет опубликованных релизов");
            if(status==403||status==429)throw new IOException("GitHub временно ограничил запросы, попробуйте позже");
            if(status!=200)throw new IOException("GitHub вернул HTTP "+status);
            byte[] data=Subscriptions.bounded(connection.getInputStream());if(data.length>512*1024)throw new IOException("Ответ GitHub слишком большой");
            return parse(new String(data,StandardCharsets.UTF_8),WraithApp.VERSION);
        }finally{connection.disconnect();}
    }

    /** GitHub "latest release" JSON → update offer, compared with the installed version name. */
    static Result parse(String body,String currentVersion)throws Exception{
        JSONObject json=new JSONObject(body);
        if(json.optBoolean("draft")||json.optBoolean("prerelease"))throw new IOException("Последний релиз ещё не опубликован");
        String name=json.getString("tag_name").trim().replaceFirst("^[vV]","");
        if(!name.matches("[0-9]+(\\.[0-9]+){1,3}"))throw new IOException("Некорректная версия в релизе: "+name);
        JSONObject apk=null;JSONArray assets=json.optJSONArray("assets");
        for(JSONObject asset:Json.objects(assets))if(asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")){apk=asset;break;}
        if(apk==null)throw new IOException("В релизе "+name+" нет APK");
        String url=apk.getString("browser_download_url").trim();
        URI uri=new URI(url);
        if(!"https".equalsIgnoreCase(uri.getScheme())||!"github.com".equalsIgnoreCase(uri.getHost())||uri.getUserInfo()!=null||!uri.getPath().toLowerCase(Locale.ROOT).startsWith(("/"+REPO+"/releases/download/").toLowerCase(Locale.ROOT)))throw new IOException("APK должен лежать в релизах "+REPO);
        String digest=apk.optString("digest").trim();
        if(!digest.toLowerCase(Locale.ROOT).startsWith("sha256:"))throw new IOException("GitHub не сообщил SHA-256 для APK");
        String sha=digest.substring(7).toUpperCase(Locale.ROOT);
        if(!sha.matches("[0-9A-F]{64}"))throw new IOException("Некорректная SHA-256 в релизе");
        // Release notes end before the install instructions, which only matter on the web page.
        String notes=json.optString("body").split("(?m)^#+\\s*Установка")[0].replaceAll("(?m)^#+\\s*","").replace("`","").replaceAll("\\*\\*","").trim();if(notes.length()>500)notes=notes.substring(0,500).trim()+"…";
        return new Result(name,url,sha,notes,compare(name,currentVersion)>0);
    }

    /** Numeric dotted-version comparison: 0.10.41 > 0.10.9. A suffix like "-debug" is ignored. */
    static int compare(String a,String b){
        String[] x=a.replaceAll("[^0-9.].*$","").split("\\."),y=b.replaceAll("[^0-9.].*$","").split("\\.");
        for(int i=0;i<Math.max(x.length,y.length);i++){
            long p=i<x.length&&!x[i].isEmpty()?Long.parseLong(x[i]):0,q=i<y.length&&!y[i].isEmpty()?Long.parseLong(y[i]):0;
            if(p!=q)return p<q?-1:1;
        }
        return 0;
    }

    interface Progress{void update(long downloaded,long total);}
    static File download(Context context,Result result,Progress progress)throws Exception{
        File directory=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(directory==null)throw new IOException("Хранилище загрузок недоступно");
        if(!directory.exists()&&!directory.mkdirs())throw new IOException("Не удалось создать каталог загрузок");
        // The version comes from the network; never let it become a path.
        String safeName=result.name.replaceAll("[^0-9A-Za-z._-]","_");
        File target=new File(directory,"Wraith-"+safeName+".apk"),part=new File(directory,target.getName()+".part");
        HttpURLConnection connection=open(result.url,10000,30000);
        try{
            // GitHub answers with a redirect to its asset storage; follow it only to GitHub hosts over HTTPS.
            for(int hops=0;;hops++){
                connection.setRequestProperty("Accept","application/octet-stream");
                int status=connection.getResponseCode();
                if(status>=300&&status<400){
                    if(hops>=5)throw new IOException("Слишком много перенаправлений при загрузке APK");
                    String location=connection.getHeaderField("Location");if(location==null)throw new IOException("GitHub не указал адрес APK");
                    String next=new URL(connection.getURL(),location).toString();connection.disconnect();connection=open(next,10000,30000);continue;
                }
                if(status!=200)throw new IOException("Сервер APK вернул HTTP "+status);
                break;
            }
            long declared=connection.getContentLengthLong();if(declared>250L*1024*1024)throw new IOException("APK больше 250 МБ");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;if(progress!=null)progress.update(0,declared);
            try(InputStream in=new BufferedInputStream(connection.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] buffer=new byte[64*1024];int count;while((count=in.read(buffer))!=-1){total+=count;if(total>250L*1024*1024)throw new IOException("APK больше 250 МБ");digest.update(buffer,0,count);out.write(buffer,0,count);if(progress!=null)progress.update(total,declared);}}
            String actual=hex(digest.digest());if(!actual.equals(result.sha256)){part.delete();throw new IOException("SHA-256 загруженного APK не совпадает");}
            if(target.exists()&&!target.delete())throw new IOException("Не удалось заменить старый APK");
            if(!part.renameTo(target))throw new IOException("Не удалось завершить загрузку APK");
            return target;
        }finally{connection.disconnect();if(part.exists())part.delete();}
    }

    /** HTTPS to an allowed GitHub host only. Only the app version is sent: no installation ID or device data. */
    static HttpURLConnection open(String url,int connectTimeout,int readTimeout)throws IOException{
        URL u=new URL(url);
        if(!"https".equalsIgnoreCase(u.getProtocol())||!allowedHost(u.getHost())||u.getUserInfo()!=null)throw new IOException("Обновления загружаются только с GitHub по HTTPS");
        HttpURLConnection connection=(HttpURLConnection)u.openConnection();
        connection.setConnectTimeout(connectTimeout);connection.setReadTimeout(readTimeout);connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent","Wraith/"+WraithApp.VERSION+"/Android");
        return connection;
    }
    static boolean allowedHost(String host){return host!=null&&HOSTS.contains(host.toLowerCase(Locale.ROOT));}
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02X",b));return out.toString();}

    static final class Result{
        final String name,url,sha256,notes;final boolean available;
        Result(String name,String url,String sha256,String notes,boolean available){this.name=name;this.url=url;this.sha256=sha256;this.notes=notes;this.available=available;}
    }
}
