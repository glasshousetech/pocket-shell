package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test

class TermThemeTest {
    @Test fun twentyPalettesHaveStableIdsAndCompleteTerminalProperties() {
        assertEquals(20, TermThemes.ALL.size)
        assertEquals(20, TermThemes.ALL.map { it.id }.toSet().size)
        TermThemes.ALL.forEach { theme ->
            assertEquals(16, theme.ansi.size)
            assertEquals(19, theme.toProperties().size)
            assertEquals(theme, TermThemes.byId(theme.id))
            assertTrue(theme.toProperties().values.all { Regex("#[0-9A-F]{6}").matches(it.toString()) })
        }
        assertEquals(5, TermThemes.ALL.count { !it.dark })
        assertTrue(TermThemes.ALL.count { it.oled } >= 2)
    }

    @Test fun legacyIdsAndUnknownPreferenceRemainUsable() {
        listOf("default", "dracula", "solarized_dark", "nord", "gruvbox_dark").forEach {
            assertEquals(it, TermThemes.byId(it).id)
        }
        assertSame(TermThemes.DEFAULT, TermThemes.byId("unknown-future-theme"))
        assertEquals(0x282A36L, TermThemes.byId("dracula").background)
        assertEquals(0xFFFFFFL, TermThemes.DEFAULT.foreground)
    }

    @Test fun searchMatchesNamesDescriptionsAndTagsWithoutCaseSensitivity() {
        assertTrue(TermThemes.byId("ght_signature").matches("  GOLD "))
        assertTrue(TermThemes.byId("phosphor").matches("retro"))
        assertTrue(TermThemes.byId("arctic").matches("ARCTIC"))
        assertFalse(TermThemes.byId("arctic").matches("unknown-query"))
        assertTrue(TermThemes.ALL.all { it.matches(" ") })
    }
}
