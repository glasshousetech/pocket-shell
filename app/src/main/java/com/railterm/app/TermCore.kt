package com.railterm.app

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates real PTY-backed shell sessions and the callback plumbing the Termux
 * engine expects. This replaces the old pipe-based ShellSession: sessions now
 * run on a genuine pseudo-terminal, so isatty() is true, job control works, and
 * full-screen apps (vim, htop, ssh, less) render and resize correctly.
 */
object TermCore {

    /** Best available system shell. Android has no login shell of its own. */
    private fun shellPath(): String =
        listOf("/system/bin/sh", "/system/bin/mksh", "/bin/sh")
            .firstOrNull { File(it).canExecute() } ?: "/system/bin/sh"

    /**
     * Spawn a new interactive shell on its own PTY.
     *
     * @param onRedraw invoked on the main thread whenever the screen changes;
     *   the caller pushes this into the attached TerminalView.
     * @param onTitle  invoked when the running program sets the window title.
     * @param onFinished invoked when the shell exits.
     */
    fun newSession(
        context: Context,
        onRedraw: (TerminalSession) -> Unit,
        onTitle: (TerminalSession) -> Unit,
        onFinished: (TerminalSession) -> Unit,
    ): TerminalSession {
        val home = context.filesDir.absolutePath
        val tmp = context.cacheDir.absolutePath
        val shell = shellPath()

        // A sane interactive environment. HOME lives inside the app sandbox so
        // dotfiles/history persist; xterm-256color unlocks color in most tools.
        val env = arrayOf(
            "TERM=xterm-256color",
            "HOME=$home",
            "TMPDIR=$tmp",
            "PATH=/system/bin:/system/xbin",
            "LANG=en_US.UTF-8",
            "COLORTERM=truecolor",
        )

        val client = RailSessionClient(onRedraw, onTitle, onFinished)
        return TerminalSession(
            shell,
            home,                 // cwd
            arrayOf(shell, "-i"), // argv: interactive shell
            env,
            /* transcriptRows = */ 4000,
            client,
        )
    }
}

/**
 * TerminalSessionClient bridges engine events to the UI. The engine calls these
 * on the main thread; we forward the ones the app cares about and satisfy the
 * logging contract by routing to Logcat.
 */
class RailSessionClient(
    private val onRedraw: (TerminalSession) -> Unit,
    private val onTitle: (TerminalSession) -> Unit,
    private val onFinished: (TerminalSession) -> Unit,
) : TerminalSessionClient {

    override fun onTextChanged(session: TerminalSession) = onRedraw(session)
    override fun onTitleChanged(session: TerminalSession) = onTitle(session)
    override fun onSessionFinished(session: TerminalSession) = onFinished(session)

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        Clip.copy(text ?: "")
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = Clip.paste()
        if (text.isNotEmpty()) session?.getEmulator()?.paste(text)
    }

    override fun onBell(session: TerminalSession) { /* no-op for now (haptics later) */ }
    override fun onColorsChanged(session: TerminalSession) = onRedraw(session)
    override fun onTerminalCursorStateChange(enabled: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    private companion object { const val TAG = "Railterm" }
}
