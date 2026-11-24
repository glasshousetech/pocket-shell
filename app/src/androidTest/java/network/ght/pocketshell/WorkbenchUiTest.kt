package network.ght.pocketshell

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real native UI and PTY, on an isolated emulator. Never touches a handset's commands or shells. */
class WorkbenchUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context get() = instrumentation.targetContext

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (main(condition)) return
            SystemClock.sleep(30)
        }
        fail("Timed out: " + label)
    }

    private fun freshNode(selector: BySelector, label: String): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            try {
                device.findObject(selector)?.let { return it }
            } catch (_: StaleObjectException) { /* Reacquire a read; never replay an input. */ }
            SystemClock.sleep(40)
        }
        error("Not visible: " + label)
    }

    private fun text(value: String): UiObject2 = freshNode(By.text(value), value)
    private fun desc(value: String): UiObject2 = freshNode(By.desc(value), value)

    private fun fields(): List<UiObject2> {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return device.findObjects(By.clazz(EditText::class.java))
    }

    private fun setField(label: String, index: Int, value: String) {
        text(label) // Wait for the destination page, not the departing page's text field.
        device.waitForIdle()
        fields()[index].text = value
    }

    private fun beat(id: String) {
        val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
        device.takeScreenshot(File(directory, "workbench-" + id + ".png"))
        if (InstrumentationRegistry.getArguments().getString("demoRecording") != "true") return
        File(directory, "workbench-demo-timeline.jsonl").appendText(
            org.json.JSONObject().put("beat", id).put("uptimeMs", SystemClock.uptimeMillis()).toString() + "\n")
        // A deliberate on-screen reading hold for the narrated recording only.
        SystemClock.sleep(InstrumentationRegistry.getArguments().getString("demoHoldMs", "10000").toLong())
    }

    @Test fun savedCommandSearchAndPaletteWorkThroughTheRealUi() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val library = File(context.filesDir, "commands-v1.json")
        val previous = library.takeIf { it.exists() }?.readBytes()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant " + context.packageName + " android.permission.POST_NOTIFICATIONS")
        val holder = main {
            service.sessions.toList().forEach(service::closeSession)
            library.delete()
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId).also {
                service.sessions[0] = it
                service.renameSession(it, "Demo workspace")
                service.selectedSessionId.value = it.id
                service.workbench.close()
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                waitFor("PTY initialized") { holder.session.emulator != null }
                device.waitForIdle()
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                beat("terminal")
                ToolbarRobot.click(device, "Actions")
                text("Saved commands").click()
                text("Add command").click()
                text("Save command")
                setField("Command name", 0, "Print a message")
                setField("Command template", 1, "printf 'DEMO_%s %s\\n' MATCH {{message}} MATCH {{message}}")
                waitFor("template draft entered") { service.workbench.editing?.template == "printf 'DEMO_%s %s\\n' MATCH {{message}} MATCH {{message}}" }
                scenario.recreate()
                text("Save command")
                assertEquals("Draft survives Activity recreation", "Print a message", fields()[0].text)
                text("Save command").click()
                desc("Use Print a message")
                assertEquals(1, CommandStore.load(context).getOrThrow().size)
                beat("saved")

                desc("Use Print a message").click()
                // The query appears in two printed lines, never in the shell input echo.
                // Narrow terminals may horizontally scroll echoed input.
                val value = "it's literal " + "$" + "(printf injected)"
                val output = "DEMO_MATCH " + value
                setField("Parameter: message", 0, value)
                waitFor("parameter field committed") { service.workbench.parameters["message"] == value }
                val exact = CommandTemplate.render("printf 'DEMO_%s %s\\n' MATCH {{message}} MATCH {{message}}", mapOf("message" to value))
                text(exact)
                scenario.recreate()
                text(exact)
                beat("preview")
                text("Insert command").click()
                waitFor("insert closes tools") { service.workbench.page == null }
                assertFalse("Insert must not execute",
                    main { holder.session.emulator.screen.transcriptText.lineSequence().any { it.trim() == output } })
                device.pressKeyCode(KeyEvent.KEYCODE_ENTER)
                waitFor("one execution prints exactly two literal lines") {
                    holder.session.emulator.screen.transcriptText.lineSequence().count { it.trim() == output } == 2
                }
                scenario.onActivity { activity ->
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                }
                beat("result")

                device.pressKeyCode(KeyEvent.KEYCODE_P, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
                text("Search output").click()
                setField("Find in output", 0, "DEMO_MATCH")
                text("Match 1 of 2")
                waitFor("search query retained") { service.workbench.outputQuery == "DEMO_MATCH" }
                text("Next match").click()
                text("Match 2 of 2")
                text("Copy matching line").click()
                assertEquals(output, main { Clip.paste() })
                beat("search")
                setField("Find in output", 0, "NO_MATCH_6c16cc41")
                text("No matches in this snapshot.")
                beat("empty")
                desc("Close tools").click()

                // Reload the library from its actual atomic file, then edit and delete through UI.
                ToolbarRobot.click(device, "Actions")
                text("Saved commands").click()
                text("Refresh commands").click()
                desc("Edit Print a message").click()
                setField("Command name", 0, "Message again")
                text("Save command").click()
                desc("Use Message again")
                assertEquals("Message again", CommandStore.load(context).getOrThrow().single().name)
                desc("Delete Message again").click()
                text("Delete command").click()
                waitFor("deletion committed") { service.workbench.commands.isEmpty() }
                assertTrue(CommandStore.load(context).getOrThrow().isEmpty())
                desc("Close tools").click()

                // Palette selection routes the visible service selection without executing anything.
                val sibling = main { service.newSession(SessionMode.SYSTEM).also { service.renameSession(it, "Sibling terminal") } }
                ToolbarRobot.click(device, "Actions")
                setField("Find an action, session, host or command", 0, "Sibling")
                text("Switch to Sibling terminal").click()
                assertEquals(sibling.id, main { service.selectedSessionId.value })
                val shots = File(context.filesDir, "screenshots").apply { mkdirs() }
                device.takeScreenshot(File(shots, "workbench-complete.png"))
                val logs = DiagnosticLog.read(context)
                assertTrue(logs.contains("\"component\":\"commands\""))
                assertFalse("Command values must not enter diagnostic logs", logs.contains(value))
                } catch (failure: Throwable) {
                    // Capture before ActivityScenario closes the app on exception.
                    val shots = File(context.filesDir, "screenshots").apply { mkdirs() }
                    device.takeScreenshot(File(shots, "workbench-failure.png"))
                    if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                    device.dumpWindowHierarchy(File(shots, "workbench-failure.xml"))
                    throw failure
                }
            }
        } finally {
            main { service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
            if (previous == null) library.delete() else library.writeBytes(previous)
            device.pressBack()
        }
    }

    @Test fun coldRestoredTabCanSearchWithoutRetappingItsHeader() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant " + context.packageName + " android.permission.POST_NOTIFICATIONS")
        val marker = "RESTORED_ORIGIN_MARKER"
        val restored = main {
            service.sessions.toList().forEach(service::closeSession)
            service.selectedSessionId.value = null // A fresh service, before any tab is tapped.
            service.workbench.close()
            service.workbench.query = "" // Prior test filtered its still-running service to Sibling.
            service.workbench.outputQuery = ""
            val restore = TermService::class.java.getDeclaredMethod("restoreSession", SessionStore.Saved::class.java).apply { isAccessible = true }
            fun restoreFixture(name: String, transcript: String): TermSession {
                val shell = restore.invoke(service, SessionStore.Saved(SessionMode.SYSTEM, name, null, transcript)) as TermSession
                // Use the emulator's real toybox PTY with the normal workspace UI.
                return TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId).also {
                    service.sessions[service.sessions.indexOf(shell)] = it
                    service.renameSession(it, name)
                }
            }
            val first = restoreFixture("Restored terminal", marker + "\n")
            restoreFixture("Restored sibling", "SIBLING_ONLY\n")
            first
        }
        try {
            assertEquals("Restoring siblings must retain the visible first tab", restored.id, main { service.selectedSessionId.value })
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                ToolbarRobot.click(device, "Actions")
                text("Search output").click()
                setField("Find in output", 0, marker)
                text("Match 1 of 1")
                assertNull(main { service.workbench.error })
                text("Copy matching line").click()
                assertEquals(marker, main { Clip.paste() })
                scenario.recreate()
                text("Match 1 of 1")
                assertEquals(restored.id, main { service.workbench.targetId })
                desc("Close tools").click()
            }
        } finally {
            main { service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
            device.pressBack()
        }
    }

    @Test fun corruptCommandLibraryCannotBeOverwrittenBySaveOrDelete() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val file = File(context.filesDir, "commands-v1.json")
        val previous = file.takeIf { it.exists() }?.readBytes()
        try {
            val broken = "{corrupt library"
            file.writeText(broken)
            assertTrue(CommandStore.load(context).isFailure)
            assertTrue(CommandStore.save(context, SavedCommand(name = "Example", template = "printf ok")).isFailure)
            assertTrue(CommandStore.delete(context, "missing").isFailure)
            assertEquals(broken, file.readText())
        } finally {
            if (previous == null) file.delete() else file.writeBytes(previous)
        }
    }
}
