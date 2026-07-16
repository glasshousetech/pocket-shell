package network.ght.pocketshell

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Installs a default uncaught-exception handler so a crash leaves a trace
 * instead of vanishing: the bug that made Pocket Shell go totally unresponsive
 * (a background PTY reader thread dying on an uninitialized-property read)
 * produced zero signal anywhere until someone read the commit diff. This
 * writes the stack trace to a local file — checkable from Settings — then
 * hands off to whatever handler was installed before (so the process still
 * dies/restarts the normal way; this only adds a trace).
 */
object CrashHandler {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appCtx, thread.name, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun record(context: Context, threadName: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = "$stamp on thread \"$threadName\"\n$sw"
        file(context).writeText(text)
    }

    private fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** Null if the app hasn't crashed since the last [clear]. */
    fun lastCrash(context: Context): String? =
        file(context).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
