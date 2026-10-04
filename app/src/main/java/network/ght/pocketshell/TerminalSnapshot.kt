package network.ght.pocketshell

import com.termux.terminal.TerminalSession

/** Main-thread only. Never materialize 20,000 rows to retain an 8-KB tail. */
internal object TerminalSnapshot {
    fun tail(session: TerminalSession, maxChars: Int = 8000): String {
        val emulator = session.emulator ?: return ""
        val firstRow = maxOf(-emulator.screen.activeTranscriptRows, emulator.mRows - 128)
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
