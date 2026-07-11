package com.railterm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Best-effort persistence for the tab structure across a full process kill
 * (low-memory-killer, force-stop, reboot). [TermService]'s foreground service
 * already keeps sessions alive across backgrounding/Activity recreation — this
 * covers the case that survives *nothing*: the actual PTY/shell processes are
 * gone either way, so what we save here is just enough to redraw a convincing
 * "as you left it" screen (mode, title, cwd, scrollback text), not to resume
 * a running command.
 *
 * Uses org.json (bundled with the Android SDK) — no extra dependency needed.
 * Lives in [Context.getNoBackupFilesDir] rather than filesDir: it's internal
 * state, not something that belongs in the shell's $HOME, and scrollback can
 * contain sensitive command output that shouldn't ride along in cloud backups.
 */
object SessionStore {

    private const val FILE_NAME = "sessions_snapshot.json"
    private const val VERSION = 1

    /** Cap per session so the snapshot file (and restore) stays cheap. */
    private const val MAX_TRANSCRIPT_CHARS = 8_000

    data class Saved(
        val mode: SessionMode,
        val title: String,
        val cwd: String?,
        val transcript: String,
    )

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** Drop the oldest lines beyond the cap, and never start mid-line. */
    private fun capTranscript(text: String): String {
        if (text.length <= MAX_TRANSCRIPT_CHARS) return text
        val tail = text.takeLast(MAX_TRANSCRIPT_CHARS)
        val newline = tail.indexOf('\n')
        return if (newline in 0 until tail.length - 1) tail.substring(newline + 1) else tail
    }

    /** Overwrites the snapshot. Pass an empty list to effectively clear it. */
    fun save(context: Context, sessions: List<Saved>) {
        if (sessions.isEmpty()) {
            clear(context)
            return
        }
        runCatching {
            val arr = JSONArray()
            sessions.forEach { s ->
                val o = JSONObject()
                o.put("mode", s.mode.name)
                o.put("title", s.title)
                if (!s.cwd.isNullOrBlank()) o.put("cwd", s.cwd)
                o.put("transcript", capTranscript(s.transcript))
                arr.put(o)
            }
            val root = JSONObject().put("version", VERSION).put("sessions", arr)

            val target = file(context)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            // Best-effort atomic swap so a kill mid-write can't corrupt the
            // previous good snapshot.
            if (!tmp.renameTo(target)) {
                target.writeText(root.toString())
                tmp.delete()
            }
        }
    }

    /** Reads the snapshot, if any. Never throws — a corrupt file just yields nothing. */
    fun load(context: Context): List<Saved> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(f.readText())
            val arr = root.optJSONArray("sessions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val mode = runCatching { SessionMode.valueOf(o.optString("mode")) }.getOrNull()
                    ?: return@mapNotNull null
                Saved(
                    mode = mode,
                    title = o.optString("title").ifBlank { "sh" },
                    cwd = o.optString("cwd").takeIf { it.isNotBlank() },
                    transcript = o.optString("transcript"),
                )
            }
        }.getOrElse { emptyList() }
    }

    /** Consumes the snapshot so a later clean cold start doesn't replay stale history. */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { File(file(context).parentFile, "$FILE_NAME.tmp").delete() }
    }
}
