package app.wraith.client;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;

/** Loads libXray's gomobile bindings only in the dedicated :xray process. */
final class IsolatedXray {
    private static final String TAG="WraithXray";
    private static final String DEX_ASSET="libXray-dex.jar";
    // One class loader owns one JNI/Go runtime for the lifetime of this process. Recreating
    // services must reuse it; loading libgojni through another loader is rejected by Android.
    private static Shared shared;
    private final ClassLoader loader;private final Class<?> bridge;private final Object protector;
    IsolatedXray(Context context)throws Exception{
        Shared bindings=bindings(context);loader=bindings.loader;bridge=bindings.bridge;
        Class<?> controller=bindings.controller;
        protector=Proxy.newProxyInstance(loader,new Class<?>[]{controller},(proxy,method,args)->{
            if(method.getName().equals("protectFd")){long fd=((Number)args[0]).longValue();return protect(context,(int)fd);}
            if(method.getName().equals("toString"))return "Wraith VPN socket protector";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            throw new UnsupportedOperationException(method.getName());
        });
        bridge.getMethod("registerDialerController",controller).invoke(null,protector);
        bridge.getMethod("registerListenerController",controller).invoke(null,protector);
    }
    private static boolean protect(Context context,int fd){
        if(context instanceof VpnService&&((VpnService)context).protect(fd))return true;
        // Before first VPN consent Android may reject protect(), although a direct outbound
        // probe needs no VPN interface. Bind that socket to a physical network instead.
        ConnectivityManager connectivity=context.getSystemService(ConnectivityManager.class);
        if(connectivity==null)return false;
        boolean vpn=false;Network physical=null;int priority=-1;
        Network active=connectivity.getActiveNetwork();
        for(Network network:connectivity.getAllNetworks()){
            NetworkCapabilities caps=connectivity.getNetworkCapabilities(network);if(caps==null)continue;
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){vpn=true;continue;}
            if(!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))continue;
            int score=(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)?2:0)+(network.equals(active)?1:0);
            if(score>priority){physical=network;priority=score;}
        }
        if(physical!=null)try(ParcelFileDescriptor duplicate=ParcelFileDescriptor.fromFd(fd)){
            physical.bindSocket(duplicate.getFileDescriptor());return true;
        }catch(IOException e){Log.w(TAG,"Unable to bind Xray socket to the underlying network",e);}
        if(!vpn)return true;
        Log.w(TAG,"Unable to protect Xray socket while a VPN is active");return false;
    }
    private static synchronized Shared bindings(Context context)throws Exception{
        if(shared!=null)return shared;
        byte[] dex;
        try(InputStream in=context.getAssets().open(DEX_ASSET);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[32768];int count;
            while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);
            dex=bytes.toByteArray();
        }
        String hash=hex(MessageDigest.getInstance("SHA-256").digest(dex));
        File dir=new File(context.getCodeCacheDir(),"xray-bindings");
        if(!dir.exists()&&!dir.mkdirs())throw new IOException(L.t("Не удалось создать каталог Xray bindings.","Could not create the Xray bindings folder."));
        File jar=new File(dir,"bindings-"+hash+".jar");
        if(!jar.isFile()||!matches(jar,dex)){
            if(jar.exists()&&!jar.delete())throw new IOException(L.t("Не удалось заменить повреждённые Xray bindings.","Could not replace damaged Xray bindings."));
            File pending=File.createTempFile("bindings-",".tmp",dir);
            try{
                // Android 14+ requires dynamically loaded code to be read-only before it is
                // written. The already opened descriptor can still complete the atomic copy.
                try(FileOutputStream out=new FileOutputStream(pending)){
                    if(!pending.setReadOnly())throw new IOException(L.t("Не удалось защитить Xray bindings от записи.","Could not write-protect Xray bindings."));
                    out.write(dex);out.getFD().sync();
                }
                if(!pending.renameTo(jar))throw new IOException(L.t("Не удалось установить Xray bindings.","Could not install Xray bindings."));
            }finally{if(pending.exists())pending.delete();}
        }
        if(!jar.setReadOnly())throw new IOException(L.t("Не удалось защитить Xray bindings от записи.","Could not write-protect Xray bindings."));
        ClassLoader loader=new ChildFirstDexClassLoader(jar.getAbsolutePath(),dir.getAbsolutePath(),context.getApplicationInfo().nativeLibraryDir,context.getClassLoader());
        Class<?> seq=Class.forName("go.Seq",true,loader);seq.getMethod("setContext",Context.class).invoke(null,context.getApplicationContext());
        Class<?> bridge=Class.forName("libXray.LibXray",true,loader);
        Class<?> controller=Class.forName("libXray.DialerController",true,loader);
        shared=new Shared(loader,bridge,controller);return shared;
    }
    private static boolean matches(File file,byte[] expected)throws IOException{
        if(file.length()!=expected.length)return false;
        try(InputStream in=new FileInputStream(file)){
            for(byte value:expected)if(in.read()!=(value&255))return false;
            return in.read()==-1;
        }
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static final class Shared{
        final ClassLoader loader;final Class<?> bridge,controller;
        Shared(ClassLoader loader,Class<?> bridge,Class<?> controller){this.loader=loader;this.bridge=bridge;this.controller=controller;}
    }
    void setDNS(String endpoint)throws Exception{invokeStatic("setDNS",new Class<?>[]{Class.forName("libXray.DialerController",true,loader),String.class},protector,endpoint);}
    void resetDNS(){try{invokeStatic("resetDNS",new Class<?>[0]);}catch(Exception e){Log.w(TAG,"resetDNS",e);}}
    String invoke(String request)throws Exception{return (String)bridge.getMethod("invoke",String.class).invoke(null,request);}
    private void invokeStatic(String name,Class<?>[] types,Object...args)throws Exception{bridge.getMethod(name,types).invoke(null,args);}
    private static final class ChildFirstDexClassLoader extends DexClassLoader{
        ChildFirstDexClassLoader(String dex,String optimized,String libraries,ClassLoader parent){super(dex,optimized,libraries,parent);}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
            if(name.startsWith("go.")||name.startsWith("libXray."))synchronized(this){
                Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);
                if(resolve)resolveClass(c);return c;
            }
            return super.loadClass(name,resolve);
        }
    }
}
