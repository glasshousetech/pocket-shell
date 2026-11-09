package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** History files grow without a line limit; restore reads only the requested tail from the end. */
class TerminalSnapshotTest {
    @get:Rule val folder = TemporaryFolder()

    private fun history(text: String) = folder.newFile().apply { writeText(text) }

    @Test fun tailReturnsTheNewestLines() {
        val f = history("a\nb\nc\nd\n")
        assertEquals("c\nd\n", HistoryTail.tailOf(f, 2))
        assertEquals("d\n", HistoryTail.tailOf(f, 1))
    }

    @Test fun tailOfShortOrMissingHistoryIsEverythingOrNothing() {
        assertEquals("a\nb\n", HistoryTail.tailOf(history("a\nb\n"), 5))
        assertEquals("a\nb", HistoryTail.tailOf(history("a\nb"), 2))
        assertEquals("", HistoryTail.tailOf(history(""), 3))
        assertEquals("", HistoryTail.tailOf(java.io.File(folder.root, "absent.log"), 3))
    }

    @Test fun tailSpansReadBuffersAndKeepsUtf8Intact() {
        // 200,000 lines (several 64 KiB read chunks) of multi-byte text.
        val text = (1..200_000).joinToString("") { "ROW_${it}_café_🚀\n" }
        val kept = HistoryTail.tailOf(history(text), TermService.RESTORED_SCROLLBACK_LINES)
        val lines = kept.removeSuffix("\n").split('\n')
        assertEquals(TermService.RESTORED_SCROLLBACK_LINES, lines.size)
        assertEquals("ROW_195001_café_🚀", lines.first())
        assertEquals("ROW_200000_café_🚀", lines.last())
    }
}
