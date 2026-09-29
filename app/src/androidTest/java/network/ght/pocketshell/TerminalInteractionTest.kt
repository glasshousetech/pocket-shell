package network.ght.pocketshell

import android.content.Context
import android.content.Intent
import android.text.InputType
import androidx.test.core.app.ActivityScenario
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import org.junit.Assert.*
import org.junit.Test

/** Real PTY + pinned terminal engine, with controlled output and no personal sessions. */
class TerminalInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun terminal(command: String, block: (TerminalView, RailViewClient, TerminalSession) -> Unit) {
        lateinit var view: TerminalView
        lateinit var client: RailViewClient
        lateinit var session: TerminalSession
        val activity = ActivityScenario.launch<android.app.Activity>(Intent().setClassName(context, "network.ght.pocketshell.TerminalTestActivity"))
        activity.onActivity { host ->
            session = TerminalSession("/system/bin/sh", context.filesDir.absolutePath,
                arrayOf("/system/bin/sh", "-c", command), arrayOf("PATH=/system/bin", "TERM=xterm-256color"),
                20000, RailSessionClient({}, {}, {}))
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
            block(view, client, session)
        } finally {
            instrumentation.runOnMainSync { session.finishIfRunning() }
            activity.close()
        }
    }

    private fun waitFor(session: TerminalSession, expected: String) {
        val deadline = System.currentTimeMillis() + 8000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync { text = session.emulator?.screen?.transcriptText.orEmpty() }
            if (expected in text) return
            Thread.sleep(30)
        }
        fail("Expected $expected in controlled PTY output: $text")
    }

    private fun receiver(bytes: Int) = "stty raw -echo; printf 'INPUT_READY\\r\\n'; dd bs=1 count=$bytes 2>/dev/null | od -An -tx1"

    @Test fun ctrlBIsOneShotAndAltBUsesEscapePrefix() = terminal(receiver(4)) { view, client, session ->
        waitFor(session, "INPUT_READY")
        instrumentation.runOnMainSync {
            client.toggleCtrl()
            view.inputCodePoint('b'.code, false, false)
            assertFalse(client.ctrlDown)
            view.inputCodePoint('x'.code, false, false)
            client.toggleAlt()
            view.inputCodePoint('b'.code, false, false)
            assertFalse(client.altDown)
        }
        waitFor(session, "02 78 1b 62")
    }

    @Test fun shiftTabAndCtrlArrowHaveModifiersAndDoNotLatch() = terminal(receiver(9)) { _, client, session ->
        waitFor(session, "INPUT_READY")
        instrumentation.runOnMainSync {
            client.toggleShift()
            assertTrue(client.sendKey(KeyEvent.KEYCODE_TAB))
            assertFalse(client.shiftDown)
            client.toggleCtrl()
            assertTrue(client.sendKey(KeyEvent.KEYCODE_DPAD_LEFT))
            assertFalse(client.ctrlDown)
        }
        waitFor(session, "1b 5b 5a 1b 5b 31 3b 35 44")
    }

    @Test fun hardwareSpecialKeyConsumesStickyCtrl() = terminal(receiver(7)) { view, client, session ->
        waitFor(session, "INPUT_READY")
        instrumentation.runOnMainSync {
            client.toggleCtrl()
            view.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            assertFalse(client.ctrlDown)
            view.inputCodePoint('x'.code, false, false)
        }
        waitFor(session, "1b 5b 31 3b 35 43 78")
    }

    @Test fun scrollbackStaysOnSameLinesWhileNewOutputArrives() = terminal("printf 'INPUT_READY\\r\\n'; sleep 60") { view, _, session ->
        instrumentation.runOnMainSync {
            fun output(s: String) { val bytes = s.toByteArray(); session.emulator.append(bytes, bytes.size) }
            output((1..100).joinToString("") { "ROW_$it\r\n" })
            TerminalInteraction.redraw(view)
            TerminalInteraction.pageHistory(view, up = true)
            val beforeRow = view.topRow
            fun visibleText() = session.emulator.screen.getSelectedText(0, view.topRow, session.emulator.mColumns, view.topRow + session.emulator.mRows)
            val beforeText = visibleText()
            assertTrue(beforeRow < 0)
            output("NEW_OUTPUT\r\n")
            TerminalInteraction.redraw(view)
            assertEquals(beforeRow - 1, view.topRow)
            assertEquals(beforeText, visibleText())
            TerminalInteraction.followOutput(view)
            assertEquals(0, view.topRow)
            assertTrue(visibleText().contains("NEW_OUTPUT"))
        }
    }

    @Test fun tmuxHistorySendsMouseWheelInsteadOfArrowKeys() = terminal(receiver(6)) { view, _, session ->
        waitFor(session, "INPUT_READY")
        instrumentation.runOnMainSync {
            val enableMouse = "\u001b[?1000h\u001b[?1006h".toByteArray()
            session.emulator.append(enableMouse, enableMouse.size)
            TerminalInteraction.pageHistory(view, up = true)
        }
        waitFor(session, "1b 5b 3c 36 34 3b")
    }

    @Test fun alternateScreenWithoutMouseUsesShiftPageUp() = terminal(receiver(6)) { view, _, session ->
        waitFor(session, "INPUT_READY")
        instrumentation.runOnMainSync {
            val alternate = "\u001b[?1049h".toByteArray()
            session.emulator.append(alternate, alternate.size)
            TerminalInteraction.pageHistory(view, up = true)
        }
        waitFor(session, "1b 5b 35 3b 32 7e")
    }

    @Test fun composingTextAndBackspaceRoundTripThroughIme() = terminal(receiver(7)) { view, client, session ->
        // BaseInputConnection dispatches backspace via Android's served editor.
        // Wait for a real focused IME connection instead of racing Activity launch.
        instrumentation.runOnMainSync { client.showKeyboard() }
        val focusDeadline = System.currentTimeMillis() + 6000
        var ready = false
        while (!ready && System.currentTimeMillis() < focusDeadline) {
            instrumentation.runOnMainSync {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                ready = view.hasWindowFocus() && view.hasFocus() && imm.isActive(view)
            }
            if (!ready) Thread.sleep(30)
        }
        assertTrue("Terminal must be the connected IME editor", ready)
        lateinit var input: android.view.inputmethod.InputConnection
        instrumentation.runOnMainSync {
            input = view.onCreateInputConnection(EditorInfo())
            input.setComposingText("he", 1)
            input.setComposingText("hello", 1)
            input.commitText("hello", 1)
            input.deleteSurroundingText(1, 0)
        }
        // BaseInputConnection queues delete KeyEvents through the attached window.
        // Drain that dispatch before simulating the next independent IME call.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { input.commitText("!", 1) }
        waitFor(session, "68 65 6c 6c 6f 7f 21")
    }

    @Test fun fullKeyboardModeAsksTheImeForAnOrdinaryTextField() = terminal(receiver(1)) { view, _, _ ->
        // Regression guard for the keyboard's missing top row. Upstream Termux declares the
        // terminal as a password-style field, and every mainstream keyboard answers by hiding
        // the toolbar row that carries voice typing, the clipboard and emoji — Android also
        // refuses voice input on password fields outright.
        try {
            KeyboardPrefs.setFullImeFeatures(context, true)
            val full = EditorInfo()
            instrumentation.runOnMainSync { view.onCreateInputConnection(full) }

            assertEquals("The terminal must present as a real text field",
                InputType.TYPE_CLASS_TEXT, full.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals("Any password variation hides the keyboard's toolbar row",
                0, full.inputType and InputType.TYPE_MASK_VARIATION)
            assertTrue("Predictions and autocorrect must stay off at a prompt",
                full.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
            assertTrue("Enter must stay a newline key rather than a Done action key",
                full.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
            assertEquals("Commands must never be auto-capitalised", 0,
                full.inputType and (InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS))
            assertTrue("A keyboard must not learn what is typed at a prompt",
                full.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)

            KeyboardPrefs.setFullImeFeatures(context, false)
            val charBased = EditorInfo()
            instrumentation.runOnMainSync { view.onCreateInputConnection(charBased) }
            assertEquals("Switching the setting off restores upstream char-based input",
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                charBased.inputType)
        } finally {
            KeyboardPrefs.setFullImeFeatures(context, true)
        }
    }
}
