package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalSnapshotTest {
    @Test fun lastLinesKeepsOnlyTheNewestRows() {
        assertEquals("c\nd", TerminalSnapshot.lastLines("a\nb\nc\nd", 2))
        assertEquals("d", TerminalSnapshot.lastLines("a\nb\nc\nd", 1))
    }

    @Test fun lastLinesReturnsShortHistoryUnchanged() {
        val text = "a\nb"
        assertSame(text, TerminalSnapshot.lastLines(text, 2))
        assertSame(text, TerminalSnapshot.lastLines(text, 5))
        assertEquals("", TerminalSnapshot.lastLines("", 3))
    }

    @Test fun lastLinesBoundsAFullScrollbackSnapshot() {
        val history = (1..20_000).joinToString("\n") { "ROW_$it" }
        val kept = TerminalSnapshot.lastLines(history, TermService.PERSISTED_HISTORY_ROWS)
        assertEquals(TermService.PERSISTED_HISTORY_ROWS, kept.split('\n').size)
        assertEquals("ROW_18001", kept.substringBefore('\n'))
        assertEquals("ROW_20000", kept.substringAfterLast('\n'))
    }
}
