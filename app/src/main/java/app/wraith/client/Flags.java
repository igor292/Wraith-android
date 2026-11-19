package app.wraith.client;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Round flag badges, the same images as Wraith for Windows: the square flag-icons set (MIT,
 * assets/licenses/flag-icons.txt) packed into one atlas. Emoji fonts draw waving flags that do not
 * fill a circle, and Nordic crosses need the off-centre crop baked into the atlas.
 */
final class Flags {
    private static final int CELL=64,COLS=16;
    private static Bitmap atlas;
    private static List<String> codes;
    private static final Map<String,Bitmap> cells=new HashMap<>();
    private Flags(){}

    /** "lt" for 🇱🇹; "" when the text is not a flag. */
    static String code(String flag){
        if(!PingInfo.isFlag(flag))return "";
        int a=flag.codePointAt(0),b=flag.codePointBefore(flag.length());
        return new String(new char[]{(char)('a'+a-0x1F1E6),(char)('a'+b-0x1F1E6)});
    }

    private static void load(Context c)throws Exception{
        if(codes!=null)return;
        List<String> list=new ArrayList<>();
        try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getAssets().open("flags.txt"),StandardCharsets.UTF_8))){String l;while((l=r.readLine())!=null)if(!l.trim().isEmpty())list.add(l.trim());}
        try(InputStream in=c.getAssets().open("flags.png")){atlas=BitmapFactory.decodeStream(in);}
        codes=list;
    }

    /** The flag as a circle filling its view, or null when there is no image for it. */
    static synchronized Drawable circle(Context c,String flag){
        String code=code(flag);if(code.isEmpty())return null;
        try{
            load(c);
            Bitmap cell=cells.get(code);
            if(cell==null){
                int k=codes.indexOf(code);if(k<0||atlas==null)return null;
                cell=Bitmap.createBitmap(atlas,(k%COLS)*CELL,(k/COLS)*CELL,CELL,CELL);cells.put(code,cell);
            }
            RoundedBitmapDrawable d=RoundedBitmapDrawableFactory.create(c.getResources(),cell);
            d.setCircular(true);d.setAntiAlias(true);d.setFilterBitmap(true);
            return d;
        }catch(Exception e){return null;}
    }
}
