package network.ght.pocketshell

import android.content.Context
import java.io.File
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Bounded, local operational events. Never pass terminal text, URIs, keys or exception messages. */
object DiagnosticLog {
    private const val MAX_BYTES = 128 * 1024
    private val correlation = java.util.UUID.randomUUID().toString()
    @Volatile var storageError: String? = null
        private set
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "pocketshell-diagnostics").apply { isDaemon = true } },
        { _, _ -> storageError = "Diagnostic queue is full; some events could not be saved." })

    fun record(context: Context, component: String, event: String, errorType: String? = null,
        tabId: String? = null, exitCode: Int? = null) {
        val app = context.applicationContext
        require(listOfNotNull(component, event, errorType).all { it.matches(Regex("[A-Za-z0-9_]{1,80}")) })
        tabId?.let(TabIdentity::requireValid)
        val timestamp = Instant.now().toString()
        writer.execute {
            runCatching {
                val directory = File(app.filesDir, "diagnostics").apply { mkdirs() }
                val file = File(directory, "events.jsonl")
                if (file.length() >= MAX_BYTES) {
                    val previous = File(directory, "events.previous.jsonl")
                    check(!previous.exists() || previous.delete())
                    check(file.renameTo(previous))
                }
                val entry = JSONObject().put("at", timestamp).put("version", BuildConfig.VERSION_NAME)
                    .put("build", BuildConfig.VERSION_CODE).put("component", component).put("event", event)
                    .put("session", correlation)
                    .put("severity", if (errorType == null) "info" else "warning")
                errorType?.let { entry.put("errorType", it) }
                tabId?.let { entry.put("tab", it) }
                exitCode?.let { entry.put("exitCode", it) }
                file.appendText("$entry\n")
            }.onFailure { storageError = "Diagnostics could not be saved (${it.javaClass.simpleName})." }
        }
    }

    /** Call from Dispatchers.IO. A barrier includes preceding queued events. */
    fun read(context: Context): String {
        return writer.submit<String> {
            val directory = File(context.filesDir, "diagnostics")
            listOf("events.previous.jsonl", "events.jsonl").map { File(directory, it) }
                .filter { it.isFile }.joinToString("") { it.readText() }
        }.get(5, TimeUnit.SECONDS)
    }
}
