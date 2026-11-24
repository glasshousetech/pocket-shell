package network.ght.pocketshell

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/** Controlled fixtures only: never connects to a user's shell or records dictated text. */
class InputPerformanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Before fun requireDisposableEmulator() {
        org.junit.Assume.assumeTrue("Performance fixtures require a disposable emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }

    @Test fun voiceBurstReturnsBeforeSlowReaderAndPreservesUtf8() {
        val phrase = "A dictated sentence with café, 日本語 and 🚀. ".repeat(1200)
        val bytes = phrase.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        lateinit var session: TerminalSession
        lateinit var view: TerminalView
        val activity = ActivityScenario.launch<android.app.Activity>(Intent().setClassName(context,
            "network.ght.pocketshell.TerminalTestActivity"))
        activity.onActivity { host ->
            // A slow reader makes queue backpressure deterministic, not dependent on SSH latency.
            val command = "stty raw -echo; printf 'BURST_READY\\r\\n'; sleep 2; head -c ${bytes.size} | sha256sum; sleep 30"
            session = TerminalSession("/system/bin/sh", context.filesDir.absolutePath,
                arrayOf("/system/bin/sh", "-c", command), arrayOf("PATH=/system/bin", "TERM=xterm-256color"),
                20000, RailSessionClient({}, {}, {}))
            val client = RailViewClient(host, 20)
            view = TerminalView(host, null).apply {
                setTerminalViewClient(client); client.view = this
                setTextSize(20); attachSession(session)
            }
            host.setContentView(view)
        }
        try {
            waitFor(session, "BURST_READY")
            var elapsedMs = 0L
            var cpuMs = 0L
            instrumentation.runOnMainSync {
                val input = view.onCreateInputConnection(EditorInfo())
                input.setComposingText("A dictated sentence", 1)
                val start = SystemClock.elapsedRealtimeNanos()
                val cpuStart = android.os.Debug.threadCpuTimeNanos()
                input.commitText(phrase, 1)
                cpuMs = (android.os.Debug.threadCpuTimeNanos() - cpuStart) / 1_000_000
                elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
            }
            waitFor(session, digest)
            println("INPUT_BENCHMARK bytes=${bytes.size} mainCommitMs=$elapsedMs mainCpuMs=$cpuMs exactUtf8=true")
            assertTrue("IME must return before a slow reader wakes; took ${elapsedMs}ms", elapsedMs < 200)
        } finally {
            instrumentation.runOnMainSync { session.finishIfRunning() }
            activity.close()
        }
    }

    @Test fun disabledTranscriptChecksStayOffTheKeystoreHotPath() {
        Secrets.setTranscriptLoggingEnabled(context, false)
        var elapsedMs = 0L
        instrumentation.runOnMainSync {
            Secrets.transcriptLoggingEnabled(context) // warm-up / one-time migration
            val start = SystemClock.elapsedRealtimeNanos()
            repeat(100) { assertFalse(Secrets.transcriptLoggingEnabled(context)) }
            elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        }
        println("PREFS_BENCHMARK checks=100 mainThreadMs=$elapsedMs")
        assertTrue("Disabled logging must be a cheap settings read; took ${elapsedMs}ms", elapsedMs < 100)
    }

    private fun waitFor(session: TerminalSession, expected: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var found = false
            instrumentation.runOnMainSync { found = session.emulator.screen.transcriptText.contains(expected) }
            if (found) return
            Thread.sleep(25)
        }
        fail("Controlled PTY did not produce expected marker/hash: $expected")
    }
}
