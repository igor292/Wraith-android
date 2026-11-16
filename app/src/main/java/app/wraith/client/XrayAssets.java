package app.wraith.client;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline bootstrap and silent refresh of Xray's native GeoData files. */
final class XrayAssets {
    private static final String[] NAMES={"geoip.dat","geosite.dat"};
    private static final String[] URLS={
        "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
        "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
    };
    private static final long MAX_BYTES=128L*1024*1024,UPDATE_INTERVAL=24L*60*60*1000;
    private static final AtomicBoolean UPDATING=new AtomicBoolean();
    private static volatile String bundledGeneration;
    private XrayAssets(){}

    /** No network access: a first connection can compile geosite/geoip rules immediately. */
    static synchronized File ensure(Context context)throws IOException{
        File dir=directory(context);
        try(RandomAccessFile lockFile=new RandomAccessFile(new File(dir,".lock"),"rw");FileLock ignored=lockFile.getChannel().lock()){
            String generation=bundleGeneration(context);
            boolean newBundle=!generation.equals(readMarker(new File(dir,".bundle-generation")));
            for(String name:NAMES){
                File target=new File(dir,name);
                if(newBundle||!valid(target))try(InputStream in=context.getAssets().open("xray-geodata/"+name)){
                    File pending=File.createTempFile(name+"-",".tmp",dir);
                    try{copy(in,pending,MAX_BYTES);if(!valid(pending))throw new IOException("Повреждён встроенный файл Xray GeoData.");replace(pending,target);}
                    finally{if(pending.exists())pending.delete();}
                }
            }
            if(newBundle){writeMarker(new File(dir,".bundle-generation"),generation);writeMarker(new File(dir,".update-attempt"),"0");}
        }
        return dir;
    }

    /** Called by the app process only; failures retain the bundled/cached files without UI notices. */
    static void scheduleUpdate(Context context){
        Context app=context.getApplicationContext();if(!mainProcess(app)||!UPDATING.compareAndSet(false,true))return;
        WraithApp.IO.execute(()->{
            try{
                File dir=ensure(app);long now=System.currentTimeMillis();
                long last=number(readMarker(new File(dir,".update-attempt")));
                if(last>0&&now>=last&&now-last<UPDATE_INTERVAL)return;
                writeMarker(new File(dir,".update-attempt"),Long.toString(now));
                Network network=physicalNetwork(app);
                for(int i=0;i<NAMES.length;i++)try{refresh(dir,NAMES[i],URLS[i],network);}catch(Exception ignored){}
            }catch(Exception ignored){}
            finally{UPDATING.set(false);}
        });
    }

    private static void refresh(File dir,String name,String address,Network network)throws Exception{
        String checksum;
        try(InputStream in=open(address+".sha256sum",network);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count,total=0;
            while((count=in.read(buffer))!=-1){total+=count;if(total>4096)throw new IOException("Invalid GeoData checksum.");out.write(buffer,0,count);}
            checksum=out.toString("UTF-8").trim().split("\\s+",2)[0].toLowerCase(Locale.ROOT);
        }
        if(!checksum.matches("[0-9a-f]{64}"))throw new IOException("Invalid GeoData checksum.");
        File pending=File.createTempFile(name+"-",".download",dir);
        try{
            try(InputStream in=open(address,network)){copy(in,pending,MAX_BYTES);}
            if(!valid(pending)||!checksum.equals(hash(pending)))throw new IOException("Invalid GeoData download.");
            synchronized(XrayAssets.class){
                try(RandomAccessFile lockFile=new RandomAccessFile(new File(dir,".lock"),"rw");FileLock ignored=lockFile.getChannel().lock()){
                    replace(pending,new File(dir,name));
                }
            }
        }finally{if(pending.exists())pending.delete();}
    }

    private static InputStream open(String address,Network network)throws IOException{
        URL url=new URL(address);
        for(int redirect=0;redirect<6;redirect++){
            if(!"https".equalsIgnoreCase(url.getProtocol()))throw new IOException("GeoData requires HTTPS.");
            HttpURLConnection connection=(HttpURLConnection)(network==null?url.openConnection(Proxy.NO_PROXY):network.openConnection(url));
            connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","Wraith/"+WraithApp.VERSION);
            int code=connection.getResponseCode();
            if(code>=300&&code<400){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("Invalid GeoData redirect.");url=new URL(url,location);continue;}
            if(code!=200){connection.disconnect();throw new IOException("GeoData HTTP "+code);}
            long size=connection.getContentLengthLong();if(size>MAX_BYTES){connection.disconnect();throw new IOException("GeoData too large.");}
            try{
                InputStream in=connection.getInputStream();
                return new FilterInputStream(in){@Override public void close()throws IOException{try{super.close();}finally{connection.disconnect();}}};
            }catch(IOException e){connection.disconnect();throw e;}
        }
        throw new IOException("Too many GeoData redirects.");
    }

    private static Network physicalNetwork(Context context)throws IOException{
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager==null)return null;
        Network active=manager.getActiveNetwork(),selected=null;boolean vpn=false;int priority=-1;
        for(Network network:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps==null)continue;
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){vpn=true;continue;}
            if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            int score=(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?2:0)+(network.equals(active)?1:0);
            if(score>priority){selected=network;priority=score;}
        }
        if(selected==null&&vpn)throw new IOException("No underlying network for GeoData.");
        return selected;
    }

    /** GeoIPList and GeoSiteList are nonempty repeated length-delimited protobuf messages. */
    static boolean valid(File file){
        if(!file.isFile()||file.length()<1024||file.length()>MAX_BYTES)return false;
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            int entries=0;
            while(input.getFilePointer()<input.length()){
                if(varint(input)!=10)return false;
                long length=varint(input),start=input.getFilePointer(),end=start+length;
                if(length<=0||end<start||end>input.length()||varint(input)!=10)return false;
                long label=varint(input);if(label<=0||label>128||input.getFilePointer()+label>end)return false;
                for(long i=0;i<label;i++){int value=input.read();if(value<33||value>126)return false;}
                input.seek(end);entries++;
            }
            return entries>0;
        }catch(IOException e){return false;}
    }
    private static long varint(RandomAccessFile input)throws IOException{
        long value=0;
        for(int shift=0;shift<63;shift+=7){int next=input.read();if(next<0)throw new EOFException();value|=(long)(next&127)<<shift;if((next&128)==0)return value;}
        throw new IOException("Invalid protobuf length.");
    }
    private static File directory(Context context)throws IOException{
        File dir=new File(context.getFilesDir(),"core/xray-geodata");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Не удалось создать каталог Xray GeoData.");return dir;
    }
    private static String bundleGeneration(Context context)throws IOException{
        if(bundledGeneration!=null)return bundledGeneration;
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String name:NAMES)try(InputStream in=context.getAssets().open("xray-geodata/"+name)){
                byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);
            }
            return bundledGeneration=hex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void copy(InputStream input,File output,long limit)throws IOException{
        try(FileOutputStream out=new FileOutputStream(output)){
            byte[] buffer=new byte[32768];int count;long total=0;
            while((count=input.read(buffer))!=-1){total+=count;if(total>limit)throw new IOException("GeoData too large.");out.write(buffer,0,count);}
            out.getFD().sync();
        }
    }
    private static void replace(File pending,File target)throws IOException{Files.move(pending.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private static String hash(File file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)digest.update(buffer,0,count);}
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder(bytes.length*2);for(byte value:bytes)text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();}
    private static String readMarker(File file){try{return new String(Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8).trim();}catch(IOException ignored){return "";}}
    private static void writeMarker(File file,String value)throws IOException{
        File pending=File.createTempFile("marker-",".tmp",file.getParentFile());
        try{Files.write(pending.toPath(),value.getBytes(java.nio.charset.StandardCharsets.UTF_8));replace(pending,file);}finally{if(pending.exists())pending.delete();}
    }
    private static long number(String value){try{return Long.parseLong(value);}catch(NumberFormatException ignored){return 0;}}
    private static boolean mainProcess(Context context){
        if(Build.VERSION.SDK_INT>=28)return context.getPackageName().equals(Application.getProcessName());
        ActivityManager manager=context.getSystemService(ActivityManager.class);if(manager==null)return false;
        java.util.List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();if(processes==null)return false;
        for(ActivityManager.RunningAppProcessInfo process:processes)if(process.pid==android.os.Process.myPid())return context.getPackageName().equals(process.processName);
        return false;
    }
}
