package app.wraith.client;

import android.content.Context;
import android.os.Environment;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Reads a small, static update manifest from the project's own HTTPS host. */
final class UpdateChecker {
    static final String MANIFEST_URL="https://dl.1337iq.pw/wraith/android/latest.json";
    private static final String HOST="dl.1337iq.pw";
    private UpdateChecker(){}

    static Result check(Context context)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(MANIFEST_URL).openConnection();
        try{
            connection.setConnectTimeout(8000);connection.setReadTimeout(12000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","application/json");
            int currentCode=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode;
            identify(context,connection,currentCode);
            int status=connection.getResponseCode();if(status!=200)throw new IOException("Сервер обновлений вернул HTTP "+status);
            byte[] data=Subscriptions.bounded(connection.getInputStream());if(data.length>128*1024)throw new IOException("Ответ сервера обновлений слишком большой");
            return parse(new String(data,StandardCharsets.UTF_8),currentCode);
        }finally{connection.disconnect();}
    }

    static Result parse(String body,int currentCode)throws Exception{
        JSONObject json=new JSONObject(body);int code=json.getInt("versionCode");
        String name=json.getString("versionName").trim(),url=json.getString("apkUrl").trim(),sha=json.getString("sha256").trim().toUpperCase(Locale.ROOT);
        if(code<1||name.isEmpty())throw new IOException("Некорректная версия в манифесте обновления");
        URI apk=new URI(url);if(!"https".equalsIgnoreCase(apk.getScheme())||!HOST.equalsIgnoreCase(apk.getHost())||apk.getUserInfo()!=null)throw new IOException("APK должен находиться на https://"+HOST);
        if(!sha.matches("[0-9A-F]{64}"))throw new IOException("В манифесте отсутствует корректная SHA-256");
        String notes=json.optString("changelog").trim();if(notes.length()>500)notes=notes.substring(0,500);
        return new Result(code,name,url,sha,notes,code>currentCode);
    }

    interface Progress{void update(long downloaded,long total);}
    static File download(Context context,Result result,Progress progress)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(result.url).openConnection();
        File directory=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);if(directory==null)throw new IOException("Хранилище загрузок недоступно");
        if(!directory.exists()&&!directory.mkdirs())throw new IOException("Не удалось создать каталог загрузок");
        // versionName comes from the network; never let it become a path.
        String safeName=result.name.replaceAll("[^0-9A-Za-z._-]","_");
        File target=new File(directory,"Wraith-"+safeName+".apk"),part=new File(directory,target.getName()+".part");
        try{
            connection.setConnectTimeout(10000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            identify(context,connection,context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionCode);
            int status=connection.getResponseCode();if(status!=200)throw new IOException("Сервер APK вернул HTTP "+status);
            long declared=connection.getContentLengthLong();if(declared>250L*1024*1024)throw new IOException("APK больше 250 МБ");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;if(progress!=null)progress.update(0,declared);
            try(InputStream in=new BufferedInputStream(connection.getInputStream());OutputStream out=new BufferedOutputStream(new FileOutputStream(part))){byte[] buffer=new byte[64*1024];int count;while((count=in.read(buffer))!=-1){total+=count;if(total>250L*1024*1024)throw new IOException("APK больше 250 МБ");digest.update(buffer,0,count);out.write(buffer,0,count);if(progress!=null)progress.update(total,declared);}}
            String actual=hex(digest.digest());if(!actual.equals(result.sha256)){part.delete();throw new IOException("SHA-256 загруженного APK не совпадает");}
            if(target.exists()&&!target.delete())throw new IOException("Не удалось заменить старый APK");
            if(!part.renameTo(target))throw new IOException("Не удалось завершить загрузку APK");
            return target;
        }finally{connection.disconnect();if(part.exists())part.delete();}
    }

    /** Only the app version is sent: no installation ID, Android version, manufacturer or model. */
    private static void identify(Context context,HttpURLConnection connection,int currentCode){
        connection.setRequestProperty("User-Agent","Wraith/"+WraithApp.VERSION+"/Android");
    }
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02X",b));return out.toString();}

    static final class Result{
        final int code;final String name,url,sha256,notes;final boolean available;
        Result(int code,String name,String url,String sha256,String notes,boolean available){this.code=code;this.name=name;this.url=url;this.sha256=sha256;this.notes=notes;this.available=available;}
    }
}
