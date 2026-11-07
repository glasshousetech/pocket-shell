package network.ght.pocketshell

import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Base64

/** UI fixture only. Real tmux attachment is covered separately; this never connects to a fixture host. */
class RemoteWorkspaceUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun text(value: String): UiObject2 {
        val end = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < end) {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            device.findObject(By.text(value))?.let { return it }
            SystemClock.sleep(40)
        }
        error("Missing UI: " + value)
    }
    private fun scrollToDescription(value: String): UiObject2 {
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        if (!device.hasObject(By.desc(value))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().description(value))
        if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
        return checkNotNull(device.findObject(By.desc(value))) { "Missing action: " + value }
    }


    private fun demoBeat(id: String) {
        if (InstrumentationRegistry.getArguments().getString("demoRecording") != "true") return
        val directory = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(directory, "handoff-demo-timeline.jsonl").appendText(
            org.json.JSONObject().put("beat", id).put("uptimeMs", SystemClock.uptimeMillis()).toString() + "\n")
        // Narrated recording only: leave the verified screen readable.
        SystemClock.sleep(InstrumentationRegistry.getArguments().getString("demoHoldMs", "10000").toLong())
    }

    @Test fun copyHandoffRetainsItsExactTargetAcrossRecreationAndReportsEmptyAndInvalidInput() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        val workspace = RemoteWorkspace("\$7", 1760000010, "Fixture workspace", 2, 1, 1234, 1760000000)
        main {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            val holder = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
            service.sessions[0] = holder
            service.selectedSessionId.value = holder.id
            service.workbench.apply {
                page = WorkbenchState.Page.WORKSPACES
                workspaceProfile = SshProfile("Fixture host", "example.test", "connor")
                workspaceTimeout = "360"
                remoteWorkspaces = listOf(workspace)
                workspaceError = null
                handoffCopied = null
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                text("Remote workspaces")
                scrollToDescription("Copy PC handoff Fixture workspace").click()
                text("PC command copied")
                val copied = main { Clip.paste() }
                assertTrue(copied.startsWith("ssh -t "))
                assertTrue(copied.contains("connor@example.test"))
                assertFalse(copied.contains("id_ed25519"))
                val payload = Regex("printf %s ([A-Za-z0-9+/=]+) \\| base64 -d \\| sh").find(copied)!!.groupValues[1]
                val remote = String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
                assertTrue(remote.contains("1234 1760000000 1760000010"))
                assertTrue(remote.contains("attach-session -t '\$7'"))
                assertFalse(remote.contains("attach-session -d"))
                scenario.recreate()
                scrollToDescription("Copy PC handoff Fixture workspace")
                text("PC command copied")
                assertEquals(copied, main { Clip.paste() })
                demoBeat("handoff")
                val directory = File(context.filesDir, "screenshots").apply { mkdirs() }
                device.takeScreenshot(File(directory, "remote-workspace-handoff.png"))

                main { service.workbench.remoteWorkspaces = emptyList() }
                if (device.hasObject(By.scrollable(true))) UiScrollable(UiSelector().scrollable(true)).flingToBeginning(5)
                text("No running tmux workspaces were found on this host.")
                if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
                device.findObject(By.clazz(EditText::class.java))!!.text = "0"
                text("Refresh workspaces").click()
                text("Enter a positive timeout in seconds.")
                assertFalse(main { service.workbench.workspaceBusy })
                assertEquals(emptyList<RemoteWorkspace>(), main { service.workbench.remoteWorkspaces })
            }
        } finally {
            main { service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
            device.pressBack()
        }
    }
}
