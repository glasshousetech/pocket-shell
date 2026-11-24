package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysLayoutsTest {

    @Test
    fun defaultPresetIncludesModifiersAndNavigation() {
        assertEquals(
            listOf(
                "esc", "tab", "enter", "ctrl", "alt", "shift",
                "left", "down", "up", "right",
                "home", "end", "pgup", "pgdn", "del",
                "dash", "underscore", "tilde", "slash", "pipe", "colon",
                "dot", "star", "equals", "dquote", "squote", "dollar",
                "history_up", "history_down", "live", "actions", "text_voice",
            ),
            ExtraKeysLayouts.DEFAULT.keys.map { it.id },
        )
    }

    @Test
    fun everyPresetHasEnterNextToTab() {
        ExtraKeysLayouts.PRESETS.forEach { preset ->
            val ids = preset.keys.map { it.id }
            assertEquals(preset.id, ids.indexOf("tab") + 1, ids.indexOf("enter"))
        }
    }

    @Test
    fun terminalToolsCloseEveryPresetInOrder() {
        val tools = listOf("history_up", "history_down", "live", "actions", "text_voice")
        assertEquals(tools, ExtraKeysLayouts.TOOLS.map { it.id })
        ExtraKeysLayouts.PRESETS.forEach { preset ->
            assertEquals(preset.id, tools, preset.keys.map { it.id }.takeLast(tools.size))
        }
        assertEquals(ExtraKeysLayouts.ALL.size, ExtraKeysLayouts.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun customizedRowsGainTheToolsAtTheEndWithoutDuplicates() {
        val e = ExtraKeysLayouts
        assertEquals(
            listOf("esc", "up", "history_up", "history_down", "live", "actions", "text_voice"),
            e.withTools(listOf(e.ESC, e.UP)).map { it.id },
        )
        // A row that already placed a tool keeps its position; only the missing ones are appended.
        assertEquals(
            listOf("actions", "esc", "history_up", "history_down", "live", "text_voice"),
            e.withTools(listOf(e.ACTIONS, e.ESC)).map { it.id },
        )
        val complete = e.withTools(listOf(e.ESC))
        assertEquals(complete, e.withTools(complete))
        assertEquals(complete, e.decode(e.encode(complete)))
    }

    @Test
    fun customizedRowsGainEnterOnceInAPredictablePlace() {
        val e = ExtraKeysLayouts
        assertEquals(listOf("esc", "tab", "enter", "ctrl"), e.withEnter(listOf(e.ESC, e.TAB, e.CTRL)).map { it.id })
        assertEquals(listOf("esc", "enter", "up"), e.withEnter(listOf(e.ESC, e.UP)).map { it.id })
        assertEquals(listOf("enter", "up", "down"), e.withEnter(listOf(e.UP, e.DOWN)).map { it.id })
        val already = listOf(e.UP, e.ENTER)
        assertEquals(already, e.withEnter(already))
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
