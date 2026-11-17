package app.wraith.client;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.function.BooleanSupplier;

/** Executes the selected via-proxy HTTP probe through a temporary local Xray SOCKS inbound. */
final class XrayHttpProbe {
    private XrayHttpProbe() {}

    static int measure(String url,String method,String mode,int timeoutMs,int socksPort,BooleanSupplier cancelled)throws Exception{
        if(!method.equals("GET")&&!method.equals("HEAD"))throw new IllegalArgumentException(L.t("Неподдерживаемый HTTP-метод проверки.","Unsupported HTTP test method."));
        if(!mode.equals("keepalive")&&!mode.equals("double"))throw new IllegalArgumentException(L.t("Неизвестный режим проверки.","Unknown test mode."));
        Proxy proxy=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",socksPort));
        long best=Long.MAX_VALUE;Exception last=null;
        for(int i=0;i<2;i++){
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedException(L.t("Проверка отменена.","Test cancelled."));
            // Each sample gets the configured timeout, matching sing-box's per-request behavior.
            int remaining=timeoutMs;
            HttpURLConnection connection=null;long started=android.os.SystemClock.elapsedRealtime();
            try{
                connection=(HttpURLConnection)new URL(url).openConnection(proxy);
                connection.setRequestMethod(method);connection.setConnectTimeout(remaining);connection.setReadTimeout(remaining);
                connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                if(mode.equals("double"))connection.setRequestProperty("Connection","close");
                int code=connection.getResponseCode();
                if(code<100||code>599)throw new java.io.IOException(L.t("Сервер не вернул HTTP-ответ.","The server returned no HTTP response."));
                InputStream body=code>=400?connection.getErrorStream():connection.getInputStream();
                if(method.equals("GET")&&body!=null)try(InputStream in=body){
                    byte[] buffer=new byte[8192];int total=0,n;
                    while((n=in.read(buffer))!=-1&&total<1024*1024)total+=n;
                }
                long elapsed=Math.max(1,android.os.SystemClock.elapsedRealtime()-started);best=Math.min(best,elapsed);
            }catch(Exception e){last=e;break;}finally{if(connection!=null&&mode.equals("double"))connection.disconnect();}
        }
        if(best==Long.MAX_VALUE)throw last==null?new java.io.IOException(L.t("Xray не получил ответ.","Xray got no answer.")):last;
        return (int)Math.min(Integer.MAX_VALUE,best);
    }
}
