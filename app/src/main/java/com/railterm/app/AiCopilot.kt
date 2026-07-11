package com.railterm.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * The feature that beats Termux: an AI copilot in the terminal. Natural language
 * -> shell command, and "explain this error". Talks to the Anthropic Messages
 * API directly (single POST, raw HTTP — no heavyweight JVM SDK in the APK) using
 * the user's own key ([Secrets]). Thinking is omitted for low latency.
 */
object AiCopilot {
    enum class Mode { COMMAND, EXPLAIN }

    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val VERSION = "2023-06-01"

    private const val SYSTEM_COMMAND =
        "You are a command-line copilot embedded in Railterm, an Android terminal that runs either " +
            "the Android system shell (Toybox) or Alpine Linux via proot. The user tells you what they " +
            "want to do; reply with ONLY the shell command(s) that accomplish it. No explanation, no " +
            "markdown, no backticks, no code fences. Chain steps with && or newlines. Prefer POSIX/busybox " +
            "compatible commands. If the request is unsafe or unclear, reply with a single line starting " +
            "with '# ' explaining why."

    private const val SYSTEM_EXPLAIN =
        "You are a command-line copilot embedded in Railterm (Android terminal). The user shares recent " +
            "terminal output or an error. Explain what it means in 1-3 short sentences, then, if there is a " +
            "fix, add a final line 'Fix: <command>'. Be concise. No markdown."

    suspend fun ask(
        context: Context,
        userText: String,
        terminalContext: String?,
        mode: Mode,
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = Secrets.apiKey(context)
        if (apiKey.isBlank()) return@withContext Result.failure(IllegalStateException("No API key set."))

        val userContent = buildString {
            if (!terminalContext.isNullOrBlank()) {
                append("Recent terminal output:\n```\n")
                append(terminalContext.takeLast(4000))
                append("\n```\n\n")
            }
            append(userText)
        }

        val body = JSONObject().apply {
            put("model", Secrets.model(context))
            put("max_tokens", 1024)
            put("system", if (mode == Mode.COMMAND) SYSTEM_COMMAND else SYSTEM_EXPLAIN)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userContent)
                    },
                ),
            )
        }

        try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", VERSION)
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            conn.disconnect()

            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).getJSONObject("error").getString("message")
                }.getOrDefault("Request failed (HTTP $code)")
                return@withContext Result.failure(IllegalStateException(msg))
            }

            val content = JSONObject(text).getJSONArray("content")
            val out = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if (block.optString("type") == "text") out.append(block.optString("text"))
            }
            Result.success(out.toString().trim())
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
