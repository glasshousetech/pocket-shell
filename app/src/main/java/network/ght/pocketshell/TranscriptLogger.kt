package network.ght.pocketshell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/** Opt-in, local-only, sampled debug history. No network upload and no keystore work per redraw. */
object TranscriptLogger {
    private const val MAX_LOG_BYTES = 512 * 1024
    private val handler = Handler(Looper.getMainLooper())
    private class Capture {
        @Volatile var active = true
        var pending: Runnable? = null
        var previous = ""
    }
    private data class Entry(val context: Context, val id: Int, val capture: Capture, val text: String)
    private val captures = mutableMapOf<Int, Capture>()
    private val entries = Channel<Entry>(32, BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (entry in entries) {
                if (!entry.capture.active || !Secrets.transcriptLoggingEnabled(entry.context)) continue
                runCatching {
                    val dir = File(entry.context.noBackupFilesDir, "transcripts").apply { mkdirs() }
                    val file = File(dir, "session-${entry.id}.log")
                    file.appendText(entry.text)
                    if (file.length() > MAX_LOG_BYTES) file.writeText(file.readText().takeLast(64 * 1024))
                }.onFailure {
                    Log.w("PocketShell.Transcript", "Could not save local debug history (${it.javaClass.simpleName})")
                }
            }
        }
    }

    fun reset(sessionId: Int) {
        captures.remove(sessionId)?.let {
            it.active = false
            it.pending?.let(handler::removeCallbacks)
        }
    }

    fun onRedraw(context: Context, sessionId: Int, mode: SessionMode, session: TerminalSession) {
        if (!Secrets.transcriptLoggingEnabled(context)) { reset(sessionId); return }
        val capture = captures.getOrPut(sessionId) { Capture() }
        if (capture.pending != null) return
        val appContext = context.applicationContext
        val task = Runnable {
            capture.pending = null
            if (!capture.active || !Secrets.transcriptLoggingEnabled(appContext)) return@Runnable
            // Read the mutable engine only on its main looper. Hand immutable text to IO.
            val text = TerminalSnapshot.tail(session)
            if (text == capture.previous) return@Runnable
            val delta = if (text.startsWith(capture.previous)) text.substring(capture.previous.length)
                else "\n--- sampled ${mode.name.lowercase()} screen ---\n$text"
            capture.previous = text
            if (delta.isNotBlank()) entries.trySend(Entry(appContext, sessionId, capture, delta))
        }
        capture.pending = task
        handler.postDelayed(task, 1000)
    }
}
