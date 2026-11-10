package network.ght.pocketshell

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real PTYs: the completion callback must contain all bytes written before process exit. */
class TerminalOutputDrainTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    @Test fun outputFailureDiagnosticIsRetrievableWithoutTerminalContent() {
        val marker = "PRIVATE_TERMINAL_" + java.util.UUID.randomUUID()
        RailSessionClient({}, {}, {}, inst.targetContext).logWarn(
            "TerminalSession", "Terminal output read failed: " + marker)
        val exported = DiagnosticLog.read(inst.targetContext)
        assertTrue(exported.contains("output_read_failed"))
        assertFalse(exported.contains(marker))
    }

    @Test fun rapidExitKeepsBurstAndFinalUtf8BeforeCompletion() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val callbacks = AtomicInteger()
        repeat(24) { sample ->
            val done = CountDownLatch(1)
            val output = AtomicReference("")
            lateinit var terminal: TerminalSession
            val tail = "FINAL_" + sample + " café 🚀"
            // More than the Java I/O queue; a final partial UTF-8 line, followed by immediate exit.
            val command = "i=0; while [ \"\$i\" -lt 96 ]; do " +
                "printf 'ROW_%03d_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ\\n' \"\$i\"; " +
                "i=\$((i+1)); done; printf '%s' '" + tail + "'; exit 17"
            inst.runOnMainSync {
                terminal = TerminalSession("/system/bin/sh", inst.targetContext.filesDir.absolutePath,
                    arrayOf("/system/bin/sh", "-c", command), arrayOf("PATH=/system/bin", "LANG=C.UTF-8"),
                    20000, RailSessionClient(onRedraw = {}, onTitle = {}, onFinished = {
                        output.set(it.emulator.screen.transcriptText)
                        callbacks.incrementAndGet()
                        done.countDown()
                    }))
                terminal.updateSize(160, 120)
            }
            try {
                assertTrue("PTY sample " + sample + " did not finish", done.await(15, TimeUnit.SECONDS))
                assertEquals(17, terminal.exitStatus)
                val text = output.get()
                for (row in 0 until 96) assertTrue("Missing row " + row + " in sample " + sample,
                    text.contains("ROW_" + row.toString().padStart(3, '0') + "_"))
                assertTrue("Missing final UTF-8 line in sample " + sample, text.contains(tail))
                assertTrue(text, text.indexOf(tail) < text.indexOf("[Process completed"))
            } finally { inst.runOnMainSync { terminal.finishIfRunning() } }
        }
        inst.waitForIdleSync()
        assertEquals("Exactly one completion per shell", 24, callbacks.get())
        println("TERMINAL_FINAL_OUTPUT samples=24 rows=2304 utf8=true exit=17 allRetained=true")
    }

    @Test fun explicitlyStoppedSessionCompletesAfterItsOutput() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val output = AtomicReference("")
        lateinit var terminal: TerminalSession
        inst.runOnMainSync {
            terminal = TerminalSession("/system/bin/sh", inst.targetContext.filesDir.absolutePath,
                arrayOf("/system/bin/sh", "-c", "printf 'STOP_READY\\n'; exec /system/bin/sleep 30"),
                arrayOf("PATH=/system/bin", "LANG=C.UTF-8"), 20000,
                RailSessionClient(onRedraw = { if (it.emulator?.screen?.transcriptText?.contains("STOP_READY") == true) ready.countDown() },
                    onTitle = {}, onFinished = { output.set(it.emulator.screen.transcriptText); done.countDown() }))
            terminal.updateSize(100, 30)
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            inst.runOnMainSync { terminal.finishIfRunning() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(output.get().contains("STOP_READY"))
            assertEquals(-9, terminal.exitStatus)
        } finally { inst.runOnMainSync { terminal.finishIfRunning() } }
    }
}
