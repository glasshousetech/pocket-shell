package network.ght.pocketshell

import android.content.Context
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Opt-in capture of a session's terminal transcript (commands + output, taken
 * from the emulator's own scrollback) so a session can be debugged after the
 * fact: written to a local rotating log file, and also batched to
 * POST /v1/pocketshell/transcripts on the configured gateway when a staff
 * proxy key is set and the endpoint isn't the default raw Anthropic one.
 *
 * Off by default (see [Secrets.transcriptLoggingEnabled]) — transcripts can
 * contain passwords, API keys, or other sensitive output typed at the prompt.
 * The log directory lives in [Context.getNoBackupFilesDir], same as
 * [SessionStore]'s scrollback snapshots, so it's excluded from Android's
 * cloud backup even while a user has logging turned on.
 *
 * TESTING ONLY: the gateway route this posts to is not live in production
 * yet, so uploads currently just fail silently — local logging still works
 * either way. Do not treat this as a working remote pipeline until confirmed.
 */
object TranscriptLogger {
    private const val MAX_LOG_BYTES = 512 * 1024
    private val lastLength = mutableMapOf<Int, Int>()
    private val seq = mutableMapOf<Int, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun logDir(context: Context): File = File(context.noBackupFilesDir, "transcripts").apply { mkdirs() }
    private fun logFile(context: Context, sessionId: Int): File = File(logDir(context), "session-$sessionId.log")

    /** Call when a session is closed so a reused id starts a clean baseline. */
    fun reset(sessionId: Int) {
        lastLength.remove(sessionId)
        seq.remove(sessionId)
    }

    /** Call on every TerminalSession redraw; appends only the newly-produced tail. */
    fun onRedraw(context: Context, sessionId: Int, mode: SessionMode, session: TerminalSession) {
        val appCtx = context.applicationContext
        if (!Secrets.transcriptLoggingEnabled(appCtx)) return
        scope.launch {
            val text = runCatching { session.emulator?.screen?.transcriptText }.getOrNull() ?: return@launch
            val prevLen = lastLength[sessionId] ?: 0
            if (text.length <= prevLen) {
                // Screen shrank (clear / scrollback trim) — rebaseline instead of
                // re-sending the whole transcript from scratch.
                lastLength[sessionId] = text.length
                return@launch
            }
            val delta = text.substring(prevLen)
            lastLength[sessionId] = text.length
            if (delta.isBlank()) return@launch

            appendLocal(appCtx, sessionId, delta)
            uploadIfConfigured(appCtx, sessionId, mode, delta)
        }
    }

    private fun appendLocal(context: Context, sessionId: Int, delta: String) {
        runCatching {
            val file = logFile(context, sessionId)
            file.appendText(delta)
            if (file.length() > MAX_LOG_BYTES) {
                val tail = file.readText().takeLast(MAX_LOG_BYTES / 2)
                file.writeText(tail)
            }
        }
    }

    private fun uploadIfConfigured(context: Context, sessionId: Int, mode: SessionMode, delta: String) {
        val key = Secrets.apiKey(context)
        val base = Secrets.baseUrl(context)
        // Only upload when pointed at a GHT gateway (not the default raw
        // Anthropic endpoint) — that's what exposes the ingest route.
        if (key.isBlank() || base.isBlank() || base.contains("api.anthropic.com")) return

        val n = seq[sessionId] ?: 0
        seq[sessionId] = n + 1

        runCatching {
            val url = URL(base.trimEnd('/') + "/v1/pocketshell/transcripts")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", key)
            }
            val body = JSONObject()
                .put("sessionId", sessionId.toString())
                .put("mode", mode.name)
                .put("seq", n)
                .put("chunk", delta)
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            conn.responseCode // best-effort: drain to complete the request, failures are swallowed
            conn.disconnect()
        }
    }
}
