package network.ght.pocketshell

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import network.ght.pocketshell.term.TerminalView

/** UI policies layered over the pinned terminal engine without changing its key protocol. */
object TerminalInteraction {
    fun sendKey(view: TerminalView, keyCode: Int, modifiers: Int): Boolean {
        val session = view.currentSession ?: return false
        if (session.emulator == null) return false
        if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            // The pinned KeyHandler drops paging modifiers. Use xterm's modified
            // function-key encoding so Shift+PageUp can reach tmux copy mode.
            val page = if (keyCode == KeyEvent.KEYCODE_PAGE_UP) 5 else 6
            var parameter = 1
            if (modifiers and KeyHandler.KEYMOD_SHIFT != 0) parameter += 1
            if (modifiers and KeyHandler.KEYMOD_ALT != 0) parameter += 2
            if (modifiers and KeyHandler.KEYMOD_CTRL != 0) parameter += 4
            session.write(if (parameter == 1) "\u001b[${page}~" else "\u001b[$page;${parameter}~")
            return true
        }
        return view.handleKeyCode(keyCode, modifiers)
    }

    fun redraw(view: TerminalView) {
        val emulator = view.currentSession?.emulator ?: return
        val readingHistory = view.topRow < 0 && !view.isSelectingText && !emulator.isAlternateBufferActive
        val anchoredRow = view.topRow - emulator.scrollCounter
        view.onScreenUpdated()
        // Upstream jumps to the bottom on every update. Preserve the same historical
        // lines as output arrives, including when the finite transcript rolls over.
        if (readingHistory) {
            view.topRow = anchoredRow.coerceIn(-emulator.screen.activeTranscriptRows, 0)
            view.invalidate()
        }
    }

    fun followOutput(view: TerminalView) {
        if (view.topRow != 0) {
            view.topRow = 0
            view.invalidate()
        }
    }

    fun pageHistory(view: TerminalView, up: Boolean) {
        val emulator = view.currentSession?.emulator ?: return
        val rows = (emulator.mRows - 2).coerceAtLeast(1)
        when {
            emulator.isMouseTrackingActive -> repeat(rows) {
                // Target the pane interior, never tmux's status line at the bottom.
                emulator.sendMouseEvent(
                    if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON,
                    (emulator.mColumns / 2).coerceAtLeast(1), (emulator.mRows / 2).coerceAtLeast(1), true,
                )
            }
            emulator.isAlternateBufferActive -> {
                // Shift+PageUp enters tmux copy mode when mouse is disabled. Unlike
                // synthetic Up arrows this does not cycle shell command history.
                sendKey(view, if (up) KeyEvent.KEYCODE_PAGE_UP else KeyEvent.KEYCODE_PAGE_DOWN, if (up) KeyHandler.KEYMOD_SHIFT else 0)
            }
            else -> {
                view.topRow = (view.topRow + if (up) -rows else rows)
                    .coerceIn(-emulator.screen.activeTranscriptRows, 0)
                view.invalidate()
            }
        }
    }

    /** Dictation is reviewed before insertion and cannot submit a command via a newline. */
    fun reviewedText(text: String): String = text
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .filter { it.code >= 32 && it.code != 127 }
}
