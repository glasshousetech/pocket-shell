package network.ght.pocketshell

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Types commands on the real Microsoft SwiftKey keyboard into a real PTY and reads back the exact
 * bytes the shell received. SwiftKey ignores NO_SUGGESTIONS and, on space, commits its dictionary
 * spelling of the word it composed: `claude` reached the shell as `Claude` before 0.7.2.
 *
 * Manual probe, not part of CI: it runs only when SwiftKey is the active keyboard and key positions
 * are passed, because SwiftKey draws its keys without accessibility nodes. Example (Fold-size AVD):
 * `am instrument -w -e class network.ght.pocketshell.SwiftKeyCaseProbe -e keys 'c=688,1816;l=1794,1673;...;space=1026,1958;enter=1838,1958' -e text 'claude |linux |' ...`
 * `|` in `-e text` is Enter. Recipe and evidence: docs/keyboard-case-20261119.md.
 */
class SwiftKeyCaseProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @Test fun swiftKeyCannotCapitalizeTheFirstLetterOfACommand() {
        val ime = requireSwiftKey()
        val (presses, expected) = plan()
        val window = (presses.size * 0.35 + 8).toInt()
        val device = UiDevice.getInstance(instrumentation)
        val out = File(context.filesDir, "swiftkey-probe").apply { mkdirs() }

        lateinit var view: TerminalView
        lateinit var client: RailViewClient
        lateinit var session: TerminalSession
        File(out, "input.bin").delete()
        val activity = ActivityScenario.launch<android.app.Activity>(
            Intent().setClassName(context, "network.ght.pocketshell.TerminalTestActivity"))
        activity.onActivity { host ->
            session = TerminalSession("/system/bin/sh", context.filesDir.absolutePath,
                arrayOf("/system/bin/sh", "-c",
                    // Capture every byte for a fixed window, so a delete-and-retype shows up too.
                    "stty raw -echo; printf 'INPUT_READY\\r\\n'; exec 3<&0; dd bs=1 count=4096 of=\"\$PROBE_FILE\" <&3 2>/dev/null & " +
                        "sleep $window; kill \$! 2>/dev/null; printf 'BYTES '; od -An -tx1 \"\$PROBE_FILE\" | tr -s ' \\n' ' '; printf ' END\\r\\n'; sleep 30"),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color", "PROBE_FILE=" + File(out, "input.bin").absolutePath), 20000, RailSessionClient({}, {}, {}))
            client = RailViewClient(host, 20)
            view = TerminalView(host, null).apply {
                setTerminalViewClient(client)
                setTextSize(20)
                client.view = this
                isFocusable = true
                isFocusableInTouchMode = true
                attachSession(session)
            }
            host.setContentView(view)
            view.requestFocus()
        }
        try {
            waitFor(session, "INPUT_READY")
            instrumentation.runOnMainSync { client.showKeyboard() }
            Thread.sleep(2500) // SwiftKey's first draw on a cold emulator
            device.takeScreenshot(File(out, "keyboard.png"))
            for (point in presses) {
                device.click(point.x, point.y)
                Thread.sleep(250)
            }
            device.takeScreenshot(File(out, "after.png"))
            val received = output(session)
            File(out, "received.txt").writeText(received)
            File(out, "expected.txt").writeText(expected)
            println("SWIFTKEY_PROBE ime=$ime expected=$expected received=$received")
            assertEquals("The shell must receive exactly what was typed", expected, received)
        } finally {
            instrumentation.runOnMainSync { session.finishIfRunning() }
            activity.close()
        }
    }

    /** Key presses for `-e text` ("|" is Enter) and the exact bytes they should produce. */
    private fun plan(): Pair<List<Point>, String> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e keys with SwiftKey key positions", args.containsKey("keys"))
        val keys = args.getString("keys").orEmpty().split(';').filter { it.isNotBlank() }.associate { entry ->
            val (name, xy) = entry.split('=')
            val (x, y) = xy.split(',').map(String::toInt)
            name to Point(x, y)
        }
        val typed = args.getString("text") ?: "claude |linux |"
        val names = typed.map { c -> when (c) { ' ' -> "space"; '|' -> "enter"; else -> c.toString() } }
        val missing = names.toSet() - keys.keys
        check(missing.isEmpty()) { "No key position for: $missing" }
        typedText = typed
        return names.map(keys::getValue) to typed.replace('|', '\r').toByteArray().joinToString(" ") { "%02x".format(it) }
    }
    private var typedText = ""

    private fun requireSwiftKey(): String {
        val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        assumeTrue("Needs Microsoft SwiftKey as the active keyboard", ime.startsWith("com.touchtype.swiftkey/"))
        return ime
    }

    @Test fun swiftKeyCannotCapitalizeWordsInTheCommandEditor() {
        val ime = requireSwiftKey()
        val (presses, _) = plan()
        val device = UiDevice.getInstance(instrumentation)
        val out = File(context.filesDir, "swiftkey-probe").apply { mkdirs() }
        lateinit var editor: CommandEditText
        val activity = ActivityScenario.launch<android.app.Activity>(
            Intent().setClassName(context, "network.ght.pocketshell.TerminalTestActivity"))
        try {
            activity.onActivity { host ->
                editor = CommandEditText(host)
                host.setContentView(editor)
                editor.requestFocus()
            }
            instrumentation.runOnMainSync {
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            Thread.sleep(2500)
            device.takeScreenshot(File(out, "editor-keyboard.png"))
            for (point in presses) {
                device.click(point.x, point.y)
                Thread.sleep(250)
            }
            Thread.sleep(800)
            device.takeScreenshot(File(out, "editor-after.png"))
            var text = ""
            instrumentation.runOnMainSync { text = editor.text.toString() }
            val expected = typedText.replace('|', '\n')
            File(out, "editor-received.txt").writeText(text)
            println("SWIFTKEY_EDITOR ime=$ime expected=${expected.replace("\n", "|")} received=${text.replace("\n", "|")}")
            assertEquals("The editor must hold exactly what was typed", expected, text)
        } finally {
            activity.close()
        }
    }

    /** The hex bytes the shell read during the capture window. */
    private fun output(session: TerminalSession): String {
        val deadline = System.currentTimeMillis() + 120000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { text = session.emulator?.screen?.transcriptText.orEmpty() }
            val joined = text.replace("\n", "")
            if ("END" in joined.substringAfter("BYTES", ""))
                return joined.substringAfter("BYTES").substringBefore("END").trim().replace(Regex("\\s+"), " ")
            Thread.sleep(100)
        }
        return "(no capture) " + text
    }

    private fun waitFor(session: TerminalSession, expected: String): String {
        val deadline = System.currentTimeMillis() + 8000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { text = session.emulator?.screen?.transcriptText.orEmpty() }
            if (expected.trim() in text) return text
            Thread.sleep(30)
        }
        return text
    }
}
