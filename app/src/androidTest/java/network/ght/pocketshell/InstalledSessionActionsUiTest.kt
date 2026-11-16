package network.ght.pocketshell

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real guest check, run after the fresh-install layout cases on an owned emulator. */
class InstalledSessionActionsUiTest {
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun newTabCreatesAndSelectsAnotherWorkingLinuxSession() = runBlocking {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        val installedBefore = Userland.installedDistro(ctx)
        var createdGuest = false
        inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        try {
            if (installedBefore == null) {
                createdGuest = true
                Bootstrap.install(ctx, Distro.Alpine) { status ->
                    inst.sendStatus(2, Bundle().apply { putString("stream", "LINUX_SETUP $status\n") })
                }.getOrThrow()
            } else {
                Bootstrap.healthCheck(ctx).getOrThrow()
            }
            WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
            device.executeShellCommand("wm density 320")
            device.executeShellCommand("wm size 1680x1800")
            lateinit var first: TermSession
            inst.runOnMainSync { first = service.newSession(SessionMode.LINUX); service.selectedSessionId.value = first.id }
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(ToolbarRobot.workspaceReady(device))
                activity.onActivity {
                    (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
                }
                device.wait(Until.findObject(By.desc("New tab")), 10000)!!.click()
                device.waitForIdle()
                lateinit var second: TermSession
                inst.runOnMainSync {
                    assertEquals("New tab created another session", 2, service.sessions.size)
                    second = service.sessions.single { it.id != first.id }
                    assertEquals("New tab is selected", second.id, service.selectedSessionId.value)
                    second.session.write("printf 'NEW_TAB_%s\\n' \"\$((20+22))\"\n")
                }
                val deadline = android.os.SystemClock.uptimeMillis() + 15000
                var completed = false
                while (!completed && android.os.SystemClock.uptimeMillis() < deadline) {
                    inst.runOnMainSync { completed = second.session.emulator.screen.transcriptText.contains("NEW_TAB_42") }
                    if (!completed) Thread.sleep(50)
                }
                assertTrue("The selected tab runs a real Linux shell", completed)
                inst.sendStatus(2, Bundle().apply { putString("stream", "POCKETSHELL_REAL_LINUX_NEW_TAB_OK\n") })
                activity.onActivity {
                    (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
                }
                device.waitForIdle()
                val shots = File(ctx.filesDir, "screenshots/session-actions").apply { mkdirs() }
                assertTrue(device.takeScreenshot(File(shots, "new-tab-real-linux.png")))
                inst.runOnMainSync { assertTrue(first.alive.value); assertTrue(second.alive.value) }
            }
        } finally {
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
            if (createdGuest) Userland.uninstall(ctx).getOrThrow()
            device.executeShellCommand("wm size reset"); device.executeShellCommand("wm density reset")
        }
    }
}
