package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real app UI with an isolated, deterministic PTY instead of a user's SSH account. */
class TerminalWorkspaceUiTest {
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun textEditorInsertsWithoutRunningAndActivityRecreationKeepsSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue("Run the workspace fixture only on an isolated emulator",
            android.os.Build.MODEL.contains("sdk_gphone") || android.os.Build.FINGERPRINT.startsWith("generic") ||
                android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val previousImeSetting = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard").trim()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        lateinit var fixture: TermSession
        instrumentation.runOnMainSync {
            val system = service.newSession(SessionMode.SYSTEM)
            system.session.updateSize(80, 24)
            // This controlled PTY stands in for an already-running Linux session.
            // It exercises the production UI and warm reattach path, not provisioning.
            fixture = TermSession(system.id, system.label, system.alive, system.session, SessionMode.LINUX)
            service.sessions[service.sessions.indexOf(system)] = fixture
        }
        val dir = File(context.filesDir, "screenshots").apply { mkdirs() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 8000))
                // Close the terminal keyboard for a full-height workspace preview.
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
                device.waitForIdle()
                device.takeScreenshot(File(dir, "workspace-portrait.png"))
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                val editor = device.findObject(By.clazz("android.widget.EditText"))
                assertNotNull(editor)
                editor.text = "printf UX_COMMAND_EXECUTED"
                assertTrue(device.wait(Until.hasObject(By.text("printf UX_COMMAND_EXECUTED")), 4000))
                device.waitForIdle()
                device.takeScreenshot(File(dir, "text-voice-portrait.png"))
                device.findObject(By.text("Insert")).click()
                assertTrue(device.wait(Until.hasObject(By.text("History ↑")), 4000))
                instrumentation.runOnMainSync {
                    assertEquals(1, service.sessions.size)
                    assertSame(fixture.session, service.sessions.single().session)
                }
                val keyboard = device.wait(Until.findObject(By.pkg("com.google.android.inputmethod.latin")), 3000)
                    ?: device.wait(Until.findObject(By.pkg("com.android.inputmethod.latin")), 1000)
                assertNotNull("Keyboard returns after inserting reviewed text", keyboard)
                if (keyboard != null) {
                    val ctrl = device.findObject(By.text("CTRL"))
                    assertNotNull("Terminal modifiers stay available with the keyboard", ctrl)
                    assertTrue("Keyboard must not cover terminal controls", ctrl.visibleBounds.bottom <= keyboard.visibleBounds.top)
                }
                device.takeScreenshot(File(dir, "workspace-keyboard.png"))
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 4000))
                instrumentation.runOnMainSync {
                    assertSame("Recreation must not kill SSH", fixture.session, service.sessions.single().session)
                    assertTrue(fixture.alive.value)
                }
                // The entered text appears once as shell input. Execution would
                // print UX_COMMAND_EXECUTED a second time on its own line.
                Thread.sleep(300)
                instrumentation.runOnMainSync {
                    val output = fixture.session.emulator.screen.transcriptText
                    assertTrue(output.contains("printf UX_COMMAND_EXECUTED"))
                    assertEquals(1, Regex("UX_COMMAND_EXECUTED").findAll(output).count())
                }
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
                device.setOrientationLeft()
                device.waitForIdle()
                device.takeScreenshot(File(dir, "workspace-landscape.png"))
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                assertTrue("Close stays reachable above the keyboard", device.wait(Until.hasObject(By.text("Close")), 4000))
                device.waitForIdle()
                device.takeScreenshot(File(dir, "text-voice-landscape.png"))
                device.findObject(By.text("Close")).click()
                device.setOrientationNatural()
                device.waitForIdle()
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                device.findObject(By.clazz("android.widget.EditText")).text = "retained draft"
                assertTrue(device.wait(Until.hasObject(By.text("retained draft")), 4000))
                instrumentation.runOnMainSync { fixture.session.finishIfRunning() }
                val exitDeadline = System.currentTimeMillis() + 4000
                while (fixture.alive.value && System.currentTimeMillis() < exitDeadline) Thread.sleep(30)
                assertFalse(fixture.alive.value)
                device.findObject(By.text("Insert")).click()
                assertTrue("An ended session presents an actionable error",
                    device.wait(Until.hasObject(By.textContains("This session has ended.")), 4000))
            }
        } finally {
            if (previousImeSetting == "null") device.executeShellCommand("settings delete secure show_ime_with_hard_keyboard")
            else device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $previousImeSetting")
            device.setOrientationNatural()
            device.unfreezeRotation()
            instrumentation.runOnMainSync { service.closeSession(fixture) }
        }
    }
}
