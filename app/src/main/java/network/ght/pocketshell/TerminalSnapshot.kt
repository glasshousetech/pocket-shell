package network.ght.pocketshell

import com.termux.terminal.TerminalSession

/** Main-thread only. Never materialize 20,000 rows to retain a tail. */
internal object TerminalSnapshot {
    /**
     * Normal-screen rows that are not yet in the tab's [SessionHistory] file (scrollback already is). At most one
     * screenful, so it is cheap to read on the main thread. Trailing blank rows are dropped.
     */
    fun unloggedScreenText(session: TerminalSession): String {
        val emulator = session.emulator ?: return ""
        val main = emulator.mainScreen
        val first = main.loggedScreenRows.coerceIn(0, emulator.mRows)
        if (first >= emulator.mRows) return ""
        return main.getSelectedText(0, first, emulator.mColumns, emulator.mRows - 1).trimEnd()
    }

    /** What is on screen now beyond the saved history: the alternate screen (tmux, vim) or unlogged rows. */
    fun searchableScreenText(session: TerminalSession): String {
        val emulator = session.emulator ?: return ""
        return if (emulator.isAlternateBufferActive)
            emulator.screen.getSelectedText(0, 0, emulator.mColumns, emulator.mRows - 1).trimEnd()
        else unloggedScreenText(session)
    }


    fun tail(session: TerminalSession, maxChars: Int = 8000, maxRows: Int = 128): String {
        val emulator = session.emulator ?: return ""
        val firstRow = maxOf(-emulator.screen.activeTranscriptRows, emulator.mRows - maxRows)
        val text = emulator.screen.getSelectedText(0, firstRow, emulator.mColumns, emulator.mRows - 1)
        if (text.length <= maxChars) return text
        val tail = text.takeLast(maxChars)
        val newline = tail.indexOf('\n')
        return when {
            newline in 0 until tail.lastIndex -> tail.substring(newline + 1)
            tail.first().isLowSurrogate() -> tail.drop(1)
            else -> tail
        }
    }
}
