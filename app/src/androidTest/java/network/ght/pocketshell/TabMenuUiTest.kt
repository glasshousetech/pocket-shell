package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import android.view.View
import android.view.ViewGroup
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Drives the tab long-press menu through the real UI: open it from the tab
 * text, recolor, rename (and prove the name survives a program title change),
 * duplicate, move, restart, copy output, clear scrollback, persist, and close
 * others. Uses controlled Android shells, never a user's SSH account.
 */
class TabMenuUiTest {
    @get:Rule val serviceRule = ServiceTestRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onMain(condition)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $what")
    }

    private fun find(text: String): UiObject2 =
        device.wait(Until.findObject(By.text(text)), 5000) ?: throw AssertionError("\"$text\" is not on screen")

    /** The PTY the on-screen TerminalView is showing, i.e. which tab is really visible. */
    private fun visibleSession(activity: ActivityScenario<MainActivity>): TerminalSession? {
        var found: TerminalSession? = null
        activity.onActivity { a ->
            fun walk(v: View) {
                if (v is TerminalView) found = v.currentSession
                else if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(a.window.decorView)
        }
        return found
    }

    private fun assertVisible(activity: ActivityScenario<MainActivity>, holder: TermSession, why: String) {
        device.waitForIdle()
        assertSame(why, onMain { holder.session }, visibleSession(activity))
    }

    private fun openMenu(tabText: String) {
        find(tabText).longClick()
        assertTrue("Long-pressing the tab text opens its menu", device.wait(Until.hasObject(By.text("Rename…")), 5000))
    }

    @Test fun longPressTabMenuManagesTheTab() {
        assumeTrue(
            "Run the tab fixture only on an isolated emulator",
            android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        val context = instrumentation.targetContext
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        lateinit var anchor: TermSession
        lateinit var work: TermSession
        onMain {
            service.sessions.toList().forEach(service::closeSession)
            // A controlled PTY labelled LINUX stands in for an installed distro,
            // so the activity attaches instead of starting Linux setup.
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            anchor = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX)
            service.sessions[service.sessions.indexOf(shell)] = anchor
            // The tab the menu acts on: a real shell that can be duplicated and restarted.
            work = service.newSession(SessionMode.SYSTEM)
            work.session.updateSize(80, 24)
        }
        val widthDp = context.resources.configuration.screenWidthDp
        val shots = File(context.filesDir, "screenshots").apply { mkdirs() }
        fun shot(name: String) { device.waitForIdle(); Thread.sleep(250); device.takeScreenshot(File(shots, "$name-${widthDp}dp.png")) }

        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                if (!device.wait(Until.hasObject(By.text("Text / voice")), 20000)) {
                    shot("launch-failure")
                    fail("The terminal workspace never appeared")
                }
                activity.onActivity {
                    (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
                }
                val workTitle = onMain { work.displayName }
                val anchorTitle = onMain { anchor.displayName }

                // Color: applies live and keeps the menu open.
                openMenu(workTitle)
                assertTrue(device.hasObject(By.text("Restart shell")))
                assertTrue("Only one tab to the left", device.hasObject(By.text("← Move left")))
                device.findObject(By.desc("Green tab color")).click()
                waitFor("green color on the tab") { work.color.value == "green" }
                assertTrue("Menu stays open after a color pick", device.hasObject(By.text("Rename…")))
                shot("tab-menu")

                // Rename: pinned name replaces the automatic title.
                find("Rename…").click()
                assertTrue(device.wait(Until.hasObject(By.text("Rename tab")), 4000))
                val editor = device.findObject(By.clazz("android.widget.EditText"))
                assertNotNull(editor)
                editor.text = "build box"
                assertTrue(device.wait(Until.hasObject(By.text("build box")), 4000))
                shot("rename-dialog")
                find("Save").click()
                assertTrue(device.wait(Until.gone(By.text("Rename tab")), 4000))
                waitFor("custom name stored") { work.customName.value == "build box" }
                assertTrue(device.wait(Until.hasObject(By.text("build box")), 4000))

                // A program setting the window title must not undo the user's name.
                onMain { work.session.write("printf '\\033]0;remote-title\\007'\n") }
                waitFor("program title received") { work.label.value == "remote-title" }
                device.waitForIdle()
                assertTrue(device.hasObject(By.text("build box")))
                assertFalse("Pinned name wins over the program title", device.hasObject(By.text("remote-title")))

                // Duplicate: lands right after the source, same mode, name and color.
                openMenu("build box")
                find("Duplicate tab").click()
                waitFor("duplicate tab") { service.sessions.size == 3 }
                val duplicate = onMain { service.sessions[2] }
                onMain {
                    assertNotSame(work, duplicate)
                    assertEquals(SessionMode.SYSTEM, duplicate.mode)
                    assertEquals("build box", duplicate.customName.value)
                    assertEquals("green", duplicate.color.value)
                }
                assertVisible(activity, duplicate, "Duplicate opens as the visible tab")
                shot("colored-tabs")

                // Wide layout check while the full fixture is still open.
                device.setOrientationLeft()
                device.waitForIdle()
                openMenu(anchorTitle)
                device.waitForIdle(); Thread.sleep(250)
                device.takeScreenshot(File(shots, "tab-menu-landscape-${widthDp}dp.png"))
                device.pressBack()
                assertTrue(device.wait(Until.gone(By.text("Rename…")), 4000))
                device.setOrientationNatural()
                device.waitForIdle()

                // Move: the anchor tab steps right, the visible tab stays put.
                openMenu(anchorTitle)
                find("Move right →").click()
                waitFor("anchor moved right") { service.sessions.indexOf(anchor) == 1 }
                onMain { assertEquals(listOf(work, anchor, duplicate), service.sessions.toList()) }
                assertVisible(activity, duplicate, "Moving another tab doesn't change the visible one")

                // Close a tab that isn't visible: the visible tab must not slide.
                openMenu(anchorTitle)
                find("Close tab").click()
                waitFor("anchor closed") { anchor !in service.sessions }
                onMain { assertEquals(listOf(work, duplicate), service.sessions.toList()) }
                assertVisible(activity, duplicate, "Closing a background tab keeps the visible tab")

                // Restart: a running shell needs a second tap, then keeps name and color.
                openMenu("build box")
                find("Restart shell").click()
                assertTrue("First tap only arms the restart", device.wait(Until.hasObject(By.text("Tap again to end and restart")), 4000))
                onMain { assertSame(work, service.sessions[0]) }
                find("Tap again to end and restart").click()
                waitFor("tab restarted in place") { service.sessions[0] !== work }
                val fresh = onMain { service.sessions[0] }
                waitFor("old process ended") { !work.alive.value }
                onMain {
                    assertTrue(fresh.alive.value)
                    assertEquals("build box", fresh.customName.value)
                    assertEquals("green", fresh.color.value)
                }
                assertVisible(activity, fresh, "Restart shows the new shell")

                // Copy output: the tab's transcript lands on the clipboard.
                onMain { fresh.session.write("echo COPY_MARKER_42\n") }
                waitFor("marker printed") {
                    Regex("COPY_MARKER_42").findAll(fresh.session.emulator?.screen?.transcriptText.orEmpty()).count() >= 2
                }
                openMenu("build box")
                find("Copy output").click()
                assertTrue(device.wait(Until.gone(By.text("Rename…")), 4000))
                waitFor("clipboard holds the output") { Clip.paste().contains("COPY_MARKER_42") }

                // Clear scrollback: history above the screen is dropped.
                onMain { fresh.session.write("seq 1 300\n") }
                waitFor("scrollback filled") { (fresh.session.emulator?.screen?.activeTranscriptRows ?: 0) > 50 }
                openMenu("build box")
                find("Clear scrollback").click()
                waitFor("scrollback cleared") { fresh.session.emulator?.screen?.activeTranscriptRows == 0 }

                // Reset terminal leaves the process running.
                openMenu("build box")
                find("Reset terminal").click()
                assertTrue(device.wait(Until.gone(By.text("Rename…")), 4000))
                onMain { assertTrue(fresh.alive.value) }

                // Name and color survive a process kill via the session snapshot.
                val saved = SessionStore.load(context)
                assertTrue("Snapshot keeps the pinned name and color",
                    saved.any { it.customName == "build box" && it.color == "green" })

                // Close others: armed first, then leaves only this tab.
                openMenu("build box")
                find("Close other tabs").click()
                assertTrue(device.wait(Until.hasObject(By.text("Tap again to close 1 tab")), 4000))
                onMain { assertEquals(2, service.sessions.size) }
                find("Tap again to close 1 tab").click()
                waitFor("other tabs closed") { service.sessions.size == 1 }
                onMain { assertSame(fresh, service.sessions.single()) }
                assertVisible(activity, fresh, "The kept tab stays visible")

                // With one tab left the menu drops "Close other tabs".
                openMenu("build box")
                assertFalse(device.hasObject(By.text("Close other tabs")))
                device.pressBack()
            }
        } finally {
            device.setOrientationNatural()
            device.unfreezeRotation()
            onMain { service.sessions.toList().forEach(service::closeSession) }
        }
    }
}
