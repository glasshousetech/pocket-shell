package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeTextCodecTest {
    @Test fun preservesVoiceUnicodeAndMapsOnlyTerminalSpecialCharacters() {
        assertEquals("café 日本語 🚀\r\t\u0003~`^",
            ImeTextCodec.normalize("café 日本語 🚀\n\t\u0003\u02DC\u02CB\u02C6"))
    }
    @Test fun invalidSurrogateDoesNotEatTheFollowingCharacterOrCrash() {
        assertEquals("\uFFFDa\uFFFDb\uFFFD", ImeTextCodec.normalize("\uD800a\uDC00b\uD800"))
    }
}
