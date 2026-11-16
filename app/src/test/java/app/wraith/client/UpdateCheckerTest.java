package app.wraith.client;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Updates are read from the GitHub "latest release" API. */
public class UpdateCheckerTest {
    static final String SHA="4f6ae820bfc1a489a5c9b38108b11d919bc4bf68011170208eef877dcf700601";
    static String release(String tag,String url,String digest)throws Exception{
        JSONArray assets=new JSONArray().put(Json.obj("name","SHA256SUMS.txt","browser_download_url","https://github.com/trinity13338/wraith/releases/download/"+tag+"/SHA256SUMS.txt","digest","sha256:"+SHA));
        JSONObject apk=Json.obj("name","Wraith.apk","browser_download_url",url);if(digest!=null)apk.put("digest",digest);assets.put(apk);
        return Json.obj("tag_name",tag,"draft",false,"prerelease",false,"body","## Что нового\n- **Пинг** авто-конфигов.\n\n## Установка\nСкачайте APK.","assets",assets).toString();
    }
    static String url(String tag){return "https://github.com/trinity13338/wraith/releases/download/"+tag+"/Wraith-"+tag.substring(1)+".apk";}

    @Test public void newerReleaseIsOffered()throws Exception{
        UpdateChecker.Result r=UpdateChecker.parse(release("v0.10.42",url("v0.10.42"),"sha256:"+SHA),"0.10.41");
        assertTrue(r.available);assertEquals("0.10.42",r.name);assertEquals(SHA.toUpperCase(),r.sha256);
        assertEquals("Что нового\n- Пинг авто-конфигов.",r.notes);
    }
    @Test public void sameOrOlderReleaseIsNotOffered()throws Exception{
        assertFalse(UpdateChecker.parse(release("v0.10.41",url("v0.10.41"),"sha256:"+SHA),"0.10.41").available);
        assertFalse(UpdateChecker.parse(release("v0.10.9",url("v0.10.9"),"sha256:"+SHA),"0.10.41").available);
    }
    @Test public void versionsCompareNumerically(){
        assertTrue(UpdateChecker.compare("0.10.41","0.10.9")>0);
        assertTrue(UpdateChecker.compare("0.11","0.10.99")>0);
        assertEquals(0,UpdateChecker.compare("0.10.41","0.10.41-debug"));
    }
    @Test(expected=java.io.IOException.class)public void apkFromAnotherRepositoryIsRejected()throws Exception{
        UpdateChecker.parse(release("v0.10.42","https://github.com/someone/else/releases/download/v0.10.42/Wraith.apk","sha256:"+SHA),"0.10.41");
    }
    @Test(expected=java.io.IOException.class)public void foreignHostIsRejected()throws Exception{
        UpdateChecker.parse(release("v0.10.42","https://example.com/Wraith.apk","sha256:"+SHA),"0.10.41");
    }
    @Test(expected=java.io.IOException.class)public void apkWithoutDigestIsRejected()throws Exception{
        UpdateChecker.parse(release("v0.10.42",url("v0.10.42"),null),"0.10.41");
    }
    @Test public void onlyGitHubHostsAreReachable(){
        assertTrue(UpdateChecker.allowedHost("release-assets.githubusercontent.com"));
        assertTrue(UpdateChecker.allowedHost("api.github.com"));
        assertFalse(UpdateChecker.allowedHost("dl.1337iq.pw"));
        assertFalse(UpdateChecker.allowedHost("github.com.evil.example"));
    }
}
