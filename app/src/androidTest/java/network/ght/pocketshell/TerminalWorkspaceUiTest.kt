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

    /** Isolate the ended-workspace regression from keyboard/rotation timing. */
    @Test fun endedWorkspaceKeepsDraftAndErrorOnRecreation() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        lateinit var fixture: TermSession
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            fixture = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX)
            service.sessions[0] = fixture
            service.selectedSessionId.value = fixture.id
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                device.wait(Until.findObject(By.text("Text / voice")), 10000)!!.click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 5000))
                device.findObject(By.clazz("android.widget.EditText")).text = "retained draft"
                assertTrue(device.wait(Until.hasObject(By.text("retained draft")), 5000))
                inst.runOnMainSync { fixture.session.finishIfRunning() }
                val deadline = System.currentTimeMillis() + 5000
                while (fixture.alive.value && System.currentTimeMillis() < deadline) Thread.sleep(30)
                assertFalse(fixture.alive.value)
                device.findObject(By.text("Insert")).click()
                assertTrue(device.wait(Until.hasObject(By.textContains("This session has ended.")), 5000))
                activity.recreate()
                assertTrue("Ended-workspace error remains visible after recreation",
                    device.wait(Until.hasObject(By.textContains("This session has ended.")), 10000))
                assertTrue(device.hasObject(By.text("retained draft")))
                inst.runOnMainSync { assertSame(fixture, service.sessions.single()) }
                val dir = File(ctx.filesDir, "screenshots").apply { mkdirs() }
                device.waitForIdle()
                device.takeScreenshot(File(dir, "ended-draft-isolated.png"))
            }
        } finally {
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }

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
                fun rotateAndWait(orientation: Int, rotate: () -> Unit) {
                    rotate()
                    val deadline = System.currentTimeMillis() + 12000
                    var ready = false
                    while (!ready && System.currentTimeMillis() < deadline) {
                        activity.onActivity { host ->
                            // MainActivity handles orientation via configChanges; it is
                            // the new configuration/layout, not a new instance, we await.
                            ready = host.resources.configuration.orientation == orientation &&
                                host.window.decorView.hasWindowFocus()
                        }
                        if (!ready) Thread.sleep(50)
                    }
                    assertTrue("Workspace has the requested orientation and window focus", ready)
                    device.waitForIdle()
                    val rendered = java.util.concurrent.CountDownLatch(1)
                    activity.onActivity { host -> host.window.decorView.postOnAnimation {
                        host.window.decorView.postOnAnimation { rendered.countDown() }
                    } }
                    assertTrue("Rotated layout rendered", rendered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                }
                assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 8000))
                // A short deterministic prompt avoids mksh horizontally scrolling
                // the command out of the screen on the smallest hosted viewport.
                instrumentation.runOnMainSync { fixture.session.write("PS1='> '; clear; echo UI_FIXTURE_READY\n") }
                val readyDeadline = System.currentTimeMillis() + 4000
                var ready = false
                while (!ready && System.currentTimeMillis() < readyDeadline) {
                    instrumentation.runOnMainSync { ready = fixture.session.emulator.screen.transcriptText.contains("UI_FIXTURE_READY") }
                    if (!ready) Thread.sleep(30)
                }
                assertTrue("Fixture shell is ready", ready)
                lateinit var voiceInput: android.view.inputmethod.InputConnection
                activity.onActivity { host ->
                    fun terminal(view: android.view.View): network.ght.pocketshell.term.TerminalView? {
                        if (view is network.ght.pocketshell.term.TerminalView) return view
                        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) {
                            terminal(view.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    voiceInput = terminal(host.window.decorView)!!.onCreateInputConnection(android.view.inputmethod.EditorInfo())
                    voiceInput.setComposingText("Voice preview stays local until the keyboard commits it", 1)
                }
                assertTrue("Provisional speech is visible before commit",
                    device.wait(Until.hasObject(By.textStartsWith("Composing: Voice preview")), 4000))
                // Accessibility nodes can arrive before the compositor presents the frame.
                // Do not archive a black transitional surface as visual evidence.
                device.waitForIdle()
                val drawn = java.util.concurrent.CountDownLatch(1)
                activity.onActivity { host ->
                    host.window.decorView.postOnAnimation {
                        host.window.decorView.postOnAnimation { drawn.countDown() }
                    }
                }
                assertTrue("Preview frame presented", drawn.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(device.takeScreenshot(File(dir, "voice-composition-preview.png")))
                instrumentation.runOnMainSync {
                    assertFalse(fixture.session.emulator.screen.transcriptText.contains("Voice preview"))
                    voiceInput.closeConnection()
                }
                // Close the terminal keyboard for a full-height workspace preview.
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
                device.waitForIdle()
                device.takeScreenshot(File(dir, "workspace-portrait.png"))
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                val editor = device.findObject(By.clazz("android.widget.EditText"))
                assertNotNull(editor)
                editor.text = "printf UX_PROOF"
                assertTrue(device.wait(Until.hasObject(By.text("printf UX_PROOF")), 4000))
                device.findObject(By.text("Voice typing")).click()
                assertTrue("Mode change preserves draft", device.wait(Until.hasObject(By.text("printf UX_PROOF")), 4000))
                device.findObject(By.text("Code typing")).click()
                assertTrue("Returning to code preserves draft", device.wait(Until.hasObject(By.text("printf UX_PROOF")), 4000))
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
                if (keyboard == null) {
                    device.takeScreenshot(File(dir,"keyboard-return-failure.png"))
                    device.dumpWindowHierarchy(File(dir,"keyboard-return-failure.xml"))
                    File(dir,"keyboard-return-input-method.txt").writeText(device.executeShellCommand("dumpsys input_method"))
                    activity.onActivity { host ->
                        fun findTerminal(v: android.view.View): network.ght.pocketshell.term.TerminalView? {
                            if(v is network.ght.pocketshell.term.TerminalView)return v
                            if(v is android.view.ViewGroup)for(i in 0 until v.childCount)findTerminal(v.getChildAt(i))?.let{return it}
                            return null
                        }
                        val v=findTerminal(host.window.decorView)!!
                        val imm=host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        val visible=androidx.core.view.ViewCompat.getRootWindowInsets(v)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())
                        File(dir,"keyboard-return-view.txt").writeText("attached=${v.isAttachedToWindow} focused=${v.hasFocus()} windowFocus=${v.hasWindowFocus()} immActive=${imm.isActive(v)} accepting=${imm.isAcceptingText} imeVisible=$visible softInputMode=${host.window.attributes.softInputMode}")
                    }
                }
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
                // print UX_PROOF a second time on its own line.
                Thread.sleep(300)
                instrumentation.runOnMainSync {
                    val output = fixture.session.emulator.screen.transcriptText
                    assertTrue(output.contains("printf UX_PROOF"))
                    assertEquals(1, Regex("UX_PROOF").findAll(output).count())
                }
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
                rotateAndWait(android.content.res.Configuration.ORIENTATION_LANDSCAPE) { device.setOrientationLeft() }
                device.takeScreenshot(File(dir, "workspace-landscape.png"))
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                val closeVisible = device.wait(Until.hasObject(By.text("Close")), 4000)
                if (!closeVisible) {
                    device.takeScreenshot(File(dir, "editor-landscape-failure.png"))
                    device.dumpWindowHierarchy(File(dir, "editor-landscape-failure.xml"))
                }
                assertTrue("Close stays reachable above the keyboard", closeVisible)
                device.waitForIdle()
                device.takeScreenshot(File(dir, "text-voice-landscape.png"))
                device.findObject(By.text("Close")).click()
                assertTrue(device.wait(Until.gone(By.text("Text to insert")), 4000))
                rotateAndWait(android.content.res.Configuration.ORIENTATION_PORTRAIT) { device.setOrientationNatural() }
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Text to insert")), 4000))
                device.findObject(By.clazz("android.widget.EditText")).text = "retained draft"
                val draftVisible = device.wait(Until.hasObject(By.text("retained draft")), 4000)
                if (!draftVisible) {
                    device.takeScreenshot(File(dir,"rotation-draft-failure.png"))
                    device.dumpWindowHierarchy(File(dir,"rotation-draft-failure.xml"))
                }
                assertTrue("Typed draft is visible in the rotated workspace", draftVisible)
                instrumentation.runOnMainSync { fixture.session.finishIfRunning() }
                val exitDeadline = System.currentTimeMillis() + 4000
                while (fixture.alive.value && System.currentTimeMillis() < exitDeadline) Thread.sleep(30)
                assertFalse(fixture.alive.value)
                device.findObject(By.text("Insert")).click()
                assertTrue("An ended session presents an actionable error",
                    device.wait(Until.hasObject(By.textContains("This session has ended.")), 8000))
                assertTrue(device.hasObject(By.text("retained draft")))
                activity.recreate()
                assertTrue("The failure and draft survive recreation together",
                    device.wait(Until.hasObject(By.textContains("This session has ended.")),8000))
                assertTrue(device.hasObject(By.text("retained draft")))
                device.takeScreenshot(File(dir,"ended-draft-kept.png"))
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
