package network.ght.pocketshell

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.termux.terminal.TerminalBuffer
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * Complete per-tab terminal history on device storage.
 *
 * The emulator keeps the most recent rows in memory for instant scrolling. Every row that becomes scrollback is
 * also appended, exactly once, to `noBackupFiles/history/<tabId>.log`, which grows as needed with no line limit.
 * That file is what survives a process kill, what restore replays, and what Find in output searches.
 *
 * Writes happen on one background thread with a stream kept open per tab; the main thread only appends finished
 * rows to a small buffer that is handed off once per message-loop turn. History is app-private, excluded from
 * backups, and deleted when its tab is closed or Pocket Shell exits, the same lifetime as restored tabs.
 */
object SessionHistory {
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "PocketShell-history").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Hand a long burst to the writer before the end of the message-loop turn. */
    private const val HAND_OFF_CHARS = 256 * 1024

    // Writer-thread only.
    private val streams = HashMap<String, FileOutputStream>()

    private fun directory(context: Context) = File(context.noBackupFilesDir, "history")

    fun file(context: Context, tabId: String): File = File(directory(context), TabIdentity.requireValid(tabId) + ".log")

    /** Receives rows on the main thread (from the emulator) and appends them to the tab's history file. */
    class Recorder internal constructor(private val context: Context, val tabId: String) : TerminalBuffer.HistoryListener {
        private val pending = StringBuilder()
        private var handOffPosted = false
        private var closed = false
        private val handOff = Runnable { handOffPosted = false; flush() }

        override fun onHistoryRow(text: String, wrapsToNext: Boolean) {
            if (closed) return
            pending.append(text)
            if (!wrapsToNext) pending.append('\n')
            if (pending.length >= HAND_OFF_CHARS) flush()
            else if (!handOffPosted) { handOffPosted = true; mainHandler.post(handOff) }
        }

        /** Queue everything received so far for writing. Main thread. */
        fun flush() {
            if (closed || pending.isEmpty()) return
            val text = pending.toString()
            pending.setLength(0)
            append(context, tabId, text)
        }

        /** Drop rows not yet handed off (the user cleared this tab's history). */
        fun discardPending() { pending.setLength(0) }

        fun close() {
            flush()
            closed = true
            mainHandler.removeCallbacks(handOff)
        }
    }

    fun recorder(context: Context, tabId: String): Recorder = Recorder(context.applicationContext, TabIdentity.requireValid(tabId))

    /** Append [text] to the tab's history, after everything queued before it. */
    fun append(context: Context, tabId: String, text: String) {
        if (text.isEmpty()) return
        val app = context.applicationContext
        writer.execute {
            runCatching { stream(app, tabId).write(text.toByteArray(Charsets.UTF_8)) }.onFailure {
                streams.remove(tabId)?.let { stream -> runCatching { stream.close() } }
                Log.w("PocketShell.History", "Could not save terminal history (${it.javaClass.simpleName})")
                DiagnosticLog.record(app, "session_history", "write_failed", it.javaClass.simpleName, tabId)
            }
        }
    }

    private fun stream(context: Context, tabId: String): FileOutputStream = streams.getOrPut(tabId) {
        directory(context).mkdirs()
        FileOutputStream(file(context, tabId), true)
    }

    /** The last [lines] lines of the tab's history. Blocks until earlier writes are on disk; never on input paths. */
    fun tail(context: Context, tabId: String, lines: Int): String {
        val app = context.applicationContext
        return writer.submit<String> { runCatching { HistoryTail.tailOf(file(app, tabId), lines) }.getOrDefault("") }.get()
    }

    /** The tab's complete history. Call from a background dispatcher. */
    fun readAll(context: Context, tabId: String): String {
        val app = context.applicationContext
        return writer.submit<String> {
            val f = file(app, tabId)
            if (f.isFile) f.readText(Charsets.UTF_8) else ""
        }.get()
    }

    /** Remove the tab's history (tab closed or cleared). Later rows start a new file. */
    fun delete(context: Context, tabId: String) {
        val app = context.applicationContext
        writer.execute {
            streams.remove(tabId)?.let { runCatching { it.close() } }
            file(app, tabId).delete()
        }
    }

    /** Delete history for every tab except [tabIds] (dead tabs after a clean start or a deliberate exit). */
    fun retainOnly(context: Context, tabIds: Set<String>) {
        val app = context.applicationContext
        writer.execute {
            streams.keys.filter { it !in tabIds }.forEach { id -> streams.remove(id)?.let { runCatching { it.close() } } }
            directory(app).listFiles()?.forEach { f ->
                if (f.name.removeSuffix(".log") !in tabIds) f.delete()
            }
        }
    }
}

/** File helpers with no Android dependencies, so they are unit-tested on the JVM. */
internal object HistoryTail {
    /** Reads backwards from the end so a large history costs only the requested tail. */
    fun tailOf(file: File, lines: Int): String {
        if (!file.isFile || lines <= 0) return ""
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return ""
            val buffer = ByteArray(64 * 1024)
            var position = length
            var newlines = 0
            var start = 0L
            // A final newline ends the last line rather than starting an empty one.
            raf.seek(length - 1)
            val endsWithNewline = raf.read() == '\n'.code
            search@ while (position > 0) {
                val count = minOf(buffer.size.toLong(), position).toInt()
                position -= count
                raf.seek(position)
                raf.readFully(buffer, 0, count)
                for (i in count - 1 downTo 0) {
                    if (buffer[i] != '\n'.code.toByte()) continue
                    if (endsWithNewline && position + i == length - 1) continue
                    if (++newlines == lines) { start = position + i + 1; break@search }
                }
            }
            val bytes = ByteArray((length - start).toInt())
            raf.seek(start)
            raf.readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
