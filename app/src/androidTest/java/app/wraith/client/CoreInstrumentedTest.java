package app.wraith.client;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import io.nekohasekai.libbox.Libbox;
import android.content.Context;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CoreInstrumentedTest {
    @Test public void standaloneProbeRunsWithoutVpnOrLogs()throws Exception{
        java.net.ServerSocket proxy=new java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"));
        java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger(),protectedSockets=new java.util.concurrent.atomic.AtomicInteger();
        executor.submit(()->{while(!proxy.isClosed())try{java.net.Socket socket=proxy.accept();executor.submit(()->{try(java.net.Socket client=socket){client.setSoTimeout(3000);java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII));java.io.OutputStream out=client.getOutputStream();String line;while((line=reader.readLine())!=null){String first=line;while((line=reader.readLine())!=null&&!line.isEmpty()){}if(first.startsWith("CONNECT "))out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));else{int status=requests.incrementAndGet()%2==0?503:404;String reason=status==503?"Service Unavailable":"Not Found";out.write(("HTTP/1.1 "+status+" "+reason+"\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));}out.flush();}}catch(Exception ignored){}});}catch(Exception ignored){}});
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();java.io.File log=new java.io.File(context.getFilesDir(),"core/session.log");long before=log.length();
        try{assertEquals(WraithService.OFF,WraithService.state);JSONObject p=ImportParser.parse("http://127.0.0.1:"+proxy.getLocalPort()).get(0);String config=ConfigBuilder.probeConfig(p,new JSONObject()).toString();
            for(String method:new String[]{"GET","HEAD"})for(String mode:new String[]{"double","keepalive"})assertTrue(Libbox.probeConfig(config,"node-"+p.getString("id"),"http://127.0.0.1:18080/test",method,mode,1500,fd->{protectedSockets.incrementAndGet();return true;})>=0);
            assertEquals(8,requests.get());assertTrue(protectedSockets.get()>0);assertEquals(WraithService.OFF,WraithService.state);assertEquals(before,log.length());
        }finally{proxy.close();executor.shutdownNow();}
    }
    @Test public void nativeCoreValidatesGeneratedConfigurations()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();assertEquals("",WraithApp.coreError);
        JSONObject p=ImportParser.parse("socks://127.0.0.1:12080#NativeTest").get(0);
        for(JSONObject settings:new JSONObject[]{new JSONObject(),Json.obj("fakeDns",true),Json.obj("ipv6",false),Json.obj("mode","direct"),Json.obj("allowLan",true,"proxyPassword","test-password-123")}){
            JSONObject state=Json.obj("profiles",Json.arr(p),"selected",p.getString("id"),"settings",settings,"rules",new JSONArray());String config=ConfigBuilder.build(state,"test-secret",c.getFilesDir()+"/native-test.log").toString();Libbox.checkConfig(config);
        }
    }
    @Test public void encryptedStoreSurvivesSaveAndBackupRoundtrip()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();Store store=Store.get(c);String before=store.backup();
        try{JSONObject p=ImportParser.parse("socks://127.0.0.1:12080#StoreTest").get(0);store.addProfiles(java.util.List.of(p));store.select(p.getString("id"));store.save();byte[] encrypted=java.nio.file.Files.readAllBytes(new java.io.File(c.getFilesDir(),"wraith.enc").toPath());assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).contains("StoreTest"));assertEquals("StoreTest",store.selected().getString("name"));}finally{store.restore(before);}
    }
}
