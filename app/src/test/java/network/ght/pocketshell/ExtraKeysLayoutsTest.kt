package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysLayoutsTest {

    @Test
    fun defaultPresetMatchesTheOriginalHardcodedRow() {
        assertEquals(
            listOf(
                "esc", "tab", "ctrl", "alt",
                "left", "down", "up", "right",
                "home", "end", "pgup", "pgdn", "del",
                "dash", "underscore", "tilde", "slash", "pipe", "colon",
                "dot", "star", "equals", "dquote", "squote", "dollar",
            ),
            ExtraKeysLayouts.DEFAULT.keys.map { it.id },
        )
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val keys = listOf(ExtraKeysLayouts.ESC, ExtraKeysLayouts.CTRL, ExtraKeysLayouts.ALL.first { it.id == "pipe" })
        assertEquals(keys, ExtraKeysLayouts.decode(ExtraKeysLayouts.encode(keys)))
    }

    @Test
    fun decodeDropsUnknownIdsAndRejectsGarbage() {
        assertNull(ExtraKeysLayouts.decode(null))
        assertNull(ExtraKeysLayouts.decode(""))
        assertNull(ExtraKeysLayouts.decode("bogus,nope"))
        assertEquals(
            listOf(ExtraKeysLayouts.ESC),
            ExtraKeysLayouts.decode("esc,bogus"),
        )
    }

    @Test
    fun presetsOnlyUseKnownKeysAndMinimalIsASubsetOfDefault() {
        ExtraKeysLayouts.PRESETS.forEach { preset ->
            preset.keys.forEach { key -> assertTrue(ExtraKeysLayouts.ALL.contains(key)) }
        }
        assertTrue(ExtraKeysLayouts.DEFAULT.keys.containsAll(ExtraKeysLayouts.MINIMAL.keys))
        assertTrue(ExtraKeysLayouts.PRESETS.size >= 3)
    }

    @Test
    fun matchesPresetComparesOrderToo() {
        assertTrue(ExtraKeysLayouts.matchesPreset(ExtraKeysLayouts.MINIMAL.keys, ExtraKeysLayouts.MINIMAL))
        assertTrue(!ExtraKeysLayouts.matchesPreset(ExtraKeysLayouts.MINIMAL.keys.reversed(), ExtraKeysLayouts.MINIMAL))
    }
}
