package app.wraith.client;

import java.io.*;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

public class XrayAssetsTest {
    @Test public void acceptsCompleteProtobufGeoList()throws Exception{
        File file=Files.createTempFile("geodata-",".dat").toFile();
        try{
            ByteArrayOutputStream entry=new ByteArrayOutputStream();entry.write(10);entry.write(2);entry.write('R');entry.write('U');
            entry.write(new byte[1100]);
            try(FileOutputStream output=new FileOutputStream(file)){output.write(10);varint(output,entry.size());entry.writeTo(output);}
            assertTrue(XrayAssets.valid(file));
        }finally{file.delete();}
    }
    @Test public void rejectsTruncatedDownloadAndHtmlErrorPage()throws Exception{
        File file=Files.createTempFile("geodata-",".dat").toFile();
        try{
            try(FileOutputStream output=new FileOutputStream(file)){output.write(10);varint(output,5000);output.write(new byte[1100]);}
            assertFalse(XrayAssets.valid(file));
            byte[] html=new byte[1200];java.util.Arrays.fill(html,(byte)'<');Files.write(file.toPath(),html);
            assertFalse(XrayAssets.valid(file));
        }finally{file.delete();}
    }
    @Test public void rejectsEmptyFile()throws Exception{
        File file=Files.createTempFile("geodata-",".dat").toFile();
        try{assertFalse(XrayAssets.valid(file));}finally{file.delete();}
    }
    private static void varint(OutputStream output,int value)throws IOException{
        while(value>127){output.write((value&127)|128);value>>>=7;}output.write(value);
    }
}
