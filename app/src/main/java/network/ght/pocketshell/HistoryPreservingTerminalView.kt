package network.ght.pocketshell

import android.content.Context
import com.termux.view.TerminalView

/** Termux resets history on resize, including resizes caused by a dialog's IME. */
class HistoryPreservingTerminalView(context: Context) : TerminalView(context, null) {
    override fun updateSize() {
        val previous = currentSession?.emulator
        val row = topRow
        val historyRows = previous?.screen?.activeTranscriptRows ?: 0
        val columns = previous?.mColumns
        val keepHistory = row < 0 && previous != null && !previous.isAlternateBufferActive && !isSelectingText
        super.updateSize()
        val current = currentSession?.emulator
        if (keepHistory && current === previous && current != null) {
            // At the same column width, newly visible rows move between screen
            // and transcript. Compensate so the same historical line stays at
            // the top. Width reflow preserves distance into available history.
            val anchor = if (columns == current.mColumns) row + historyRows - current.screen.activeTranscriptRows else row
            topRow = anchor.coerceIn(-current.screen.activeTranscriptRows, 0)
            invalidate()
        }
    }
}
