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

    private fun luminance(rgb: Long): Double = listOf(16, 8, 0).zip(listOf(.2126, .7152, .0722)).sumOf { (shift, weight) ->
        val channel = ((rgb shr shift) and 0xFF) / 255.0
        weight * if (channel <= .04045) channel / 12.92 else Math.pow((channel + .055) / 1.055, 2.4)
    }
    private fun contrast(a: Long, b: Long): Double =
        (maxOf(luminance(a), luminance(b)) + .05) / (minOf(luminance(a), luminance(b)) + .05)

    /** The workspace menu, tabs, keys and terminal must read as separate regions in every theme. */
    @Test fun everyThemeKeepsItsRegionsVisiblyApart() {
        TermThemes.ALL.forEach { theme ->
            val ui = theme.ui
            val regions = listOf(theme.background, ui.surface, ui.surfaceAlt, ui.control)
            regions.forEachIndexed { i, a ->
                regions.drop(i + 1).forEach { b -> assertTrue("${theme.id}: two regions blend", contrast(a, b) >= 1.10) }
            }
            assertTrue("${theme.id}: menu blends into the terminal", contrast(ui.surfaceAlt, theme.background) >= 1.25)
            assertTrue("${theme.id}: keys blend into the toolbar", contrast(ui.control, ui.surfaceAlt) >= 1.15)
            regions.forEach { assertTrue("${theme.id}: border blends", contrast(ui.border, it) >= 1.30) }
            listOf(ui.text, ui.muted, ui.accent, ui.error, ui.success, ui.warning, ui.info).forEach { ink ->
                regions.forEach { assertTrue("${theme.id}: text unreadable", contrast(ink, it) >= 4.5) }
            }
            assertTrue("${theme.id}: accent label unreadable", contrast(ui.onAccent, ui.accent) >= 4.5)
        }
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
