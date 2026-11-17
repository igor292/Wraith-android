package app.wraith.client;

import java.util.Locale;

/** UI language follows the system: Russian for ru/uk/be/kk, English otherwise. */
final class L {
    private L(){}
    private static volatile Boolean en;

    static boolean en(){
        Boolean v=en;
        if(v==null){
            String lang=Locale.getDefault().getLanguage();
            v=!(lang.equals("ru")||lang.equals("uk")||lang.equals("be")||lang.equals("kk"));
            en=v;
        }
        return v;
    }
    /** Re-reads the system language (after a locale change the process may keep running). */
    static void reset(){en=null;}
    static String t(String ru,String english){return en()?english:ru;}
}
