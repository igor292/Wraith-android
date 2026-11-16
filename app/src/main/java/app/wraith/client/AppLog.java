package app.wraith.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class AppLog {
    private static final Deque<String> lines=new ArrayDeque<>();
    /**
     * The in-memory log belongs to one process. Services running in another process (the Xray
     * tunnel in :xray) mirror their lines into a file, otherwise the Logs page never showed why an
     * Xray connection failed.
     */
    private static File mirror;
    public static synchronized void mirrorTo(File file){
        mirror=file;
        try{File dir=file.getParentFile();if(dir!=null&&!dir.isDirectory())dir.mkdirs();if(file.length()>256*1024)try(FileOutputStream clear=new FileOutputStream(file,false)){clear.write(new byte[0]);}}catch(IOException ignored){}
    }
    public static synchronized void add(String text){
        String line=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+"  "+text;
        lines.addLast(line);while(lines.size()>160)lines.removeFirst();
        if(mirror!=null)try(FileOutputStream out=new FileOutputStream(mirror,true)){out.write((line+"\n").getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}
    }
    public static synchronized String text(){return String.join("\n",lines);}
}
