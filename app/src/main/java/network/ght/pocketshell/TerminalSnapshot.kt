package network.ght.pocketshell

import com.termux.terminal.TerminalSession

/** Main-thread only. Never materialize 20,000 rows to retain a tail. */
internal object TerminalSnapshot {
    /** The last [rows] lines of [text], found by scanning backwards instead of splitting it all. */
    fun lastLines(text: String, rows: Int): String {
        var index = text.length
        repeat(rows) {
            if (index == 0) return text
            index = text.lastIndexOf('\n', index - 1)
            if (index < 0) return text
        }
        return text.substring(index + 1)
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
