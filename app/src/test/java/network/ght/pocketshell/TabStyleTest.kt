package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test

class TabStyleTest {
    @Test fun blankNamesReturnTheTabToItsAutomaticTitle() {
        assertNull(TabNames.normalize(null))
        assertNull(TabNames.normalize(""))
        assertNull(TabNames.normalize("   \n\t  "))
    }

    @Test fun namesAreFlattenedToOneTidyLine() {
        assertEquals("prod db", TabNames.normalize("  prod \n\t db  "))
        assertEquals("a b", TabNames.normalize("a\u0007\u001bb"))
        assertEquals("line one line two", TabNames.normalize("line one\u2028line two"))
    }

    @Test fun longNamesAreCappedWithoutSplittingAnEmoji() {
        val long = "x".repeat(40)
        assertEquals(TabNames.MAX_LENGTH, TabNames.normalize(long)!!.length)

        // 31 letters + a surrogate-pair emoji = exactly 32 code points: kept whole.
        val edge = "y".repeat(31) + "🚀"
        assertEquals(edge, TabNames.normalize(edge))
        // One more emoji goes past the cap and is dropped, never half-kept.
        val over = edge + "🚀"
        assertEquals(edge, TabNames.normalize(over))
        assertTrue(TabNames.fits(edge))
        assertFalse(TabNames.fits(over))
    }

    @Test fun capDoesNotLeaveATrailingSpace() {
        val name = "a".repeat(31) + " b"
        assertEquals("a".repeat(31), TabNames.normalize(name))
    }

    @Test fun colorIdsAreStableAndUnknownIdsFallBackToDefault() {
        val ids = TabColors.ALL.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        // Persisted in session snapshots; renaming an id would drop users' colors.
        assertEquals(listOf("red", "orange", "yellow", "green", "teal", "sky", "violet", "pink", "gray"), ids)
        assertEquals("green", TabColors.byId("green")?.id)
        assertNull(TabColors.byId(null))
        assertNull(TabColors.byId("chartreuse"))
        TabColors.ALL.forEach {
            assertEquals("${it.id} must be opaque", 0xFFL, it.argb ushr 24)
            assertEquals("${it.id} light tone must be opaque", 0xFFL, it.argbLight ushr 24)
            assertEquals(it.argb, it.argbFor(darkTheme = true))
            assertEquals(it.argbLight, it.argbFor(darkTheme = false))
        }
    }

    @Test fun clipboardKeepsShortOutputWhole() {
        assertEquals("", TabOutput.forClipboard(null))
        assertEquals("hello\nworld", TabOutput.forClipboard("hello\nworld\n\n   "))
    }

    @Test fun clipboardKeepsTheNewestWholeLinesOfHugeOutput() {
        val lines = (1..5000).joinToString("\n") { "line $it" }
        val copied = TabOutput.forClipboard(lines, max = 1000)
        assertTrue(copied.length <= 1000)
        assertTrue(copied.endsWith("line 5000"))
        assertTrue("starts on a line boundary", copied.startsWith("line "))
    }
}
