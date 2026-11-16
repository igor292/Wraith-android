package app.wraith.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {
    @Test public void newerManifestIsAccepted()throws Exception{
        UpdateChecker.Result result=UpdateChecker.parse("{\"versionCode\":31,\"versionName\":\"0.10.1\",\"apkUrl\":\"https://dl.1337iq.pw/wraith/android/Wraith-0.10.1.apk\",\"sha256\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"changelog\":\"Test\"}",30);
        assertTrue(result.available);assertEquals("0.10.1",result.name);
    }
    @Test public void updateManifestUsesNewDownloadDomain(){assertEquals("https://dl.1337iq.pw/wraith/android/latest.json",UpdateChecker.MANIFEST_URL);}
    @Test(expected=java.io.IOException.class)public void legacyDownloadHostIsRejected()throws Exception{
        UpdateChecker.parse("{\"versionCode\":31,\"versionName\":\"0.10.1\",\"apkUrl\":\"https://c1yu.1337iq.pw/wraith/android/Wraith-0.10.1.apk\",\"sha256\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}",30);
    }
    @Test(expected=java.io.IOException.class)public void foreignDownloadHostIsRejected()throws Exception{
        UpdateChecker.parse("{\"versionCode\":31,\"versionName\":\"0.10.1\",\"apkUrl\":\"https://example.com/app.apk\",\"sha256\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}",30);
    }
}
