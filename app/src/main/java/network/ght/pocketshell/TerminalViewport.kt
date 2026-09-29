package network.ght.pocketshell

import android.content.Context
import android.widget.FrameLayout
import com.termux.view.TerminalView

/** Wrap the final Termux view, which resets history in onSizeChanged/updateSize. */
class TerminalViewport(context: Context) : FrameLayout(context) {
    val terminal = TerminalView(context, null)

    init { addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val previous = terminal.currentSession?.emulator
        val row = terminal.topRow
        val historyRows = previous?.screen?.activeTranscriptRows ?: 0
        val columns = previous?.mColumns
        val keepHistory = row < 0 && previous != null && !previous.isAlternateBufferActive && !terminal.isSelectingText
        // Child layout calls TerminalView.onSizeChanged, which resets mTopRow.
        super.onLayout(changed, left, top, right, bottom)
        val current = terminal.currentSession?.emulator
        if (keepHistory && current === previous && current != null) {
            // Same-width resizing transfers rows between screen and transcript.
            // Preserve the historical line, or distance into history on reflow.
            val anchor = if (columns == current.mColumns) row + historyRows - current.screen.activeTranscriptRows else row
            terminal.topRow = anchor.coerceIn(-current.screen.activeTranscriptRows, 0)
            terminal.invalidate()
        }
    }
}
