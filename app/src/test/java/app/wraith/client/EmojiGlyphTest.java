package app.wraith.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class EmojiGlyphTest {
    @Test public void flagAnywhereInTheName(){
        assertEquals("🇱🇹",PingInfo.firstEmoji("🇱🇹 Lithuania"));
        assertEquals("🇩🇪",PingInfo.firstEmoji("Germany 🇩🇪 #2"));
        assertTrue(PingInfo.isFlag("🇱🇹"));
    }
    @Test public void otherEmojiKeepItsModifiers(){
        assertEquals("🔥",PingInfo.firstEmoji("Fast 🔥 server"));
        assertEquals("❤️",PingInfo.firstEmoji("❤️ love"));
        assertEquals("👨‍💻",PingInfo.firstEmoji("👨‍💻 dev"));
        assertFalse(PingInfo.isFlag("🔥"));
        assertTrue(PingInfo.isEmoji("🔥"));
    }
    @Test public void noEmoji(){
        assertEquals("",PingInfo.firstEmoji("Lithuania"));
        assertEquals("",PingInfo.firstEmoji("NL · 1 Gbit/s"));
    }
}
