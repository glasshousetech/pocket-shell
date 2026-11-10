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
import kotlinx.coroutines.launch

/** ENTER on the special-keys row, the collapsible workspace menu, and complete on-device history. */
class WorkspaceControlsUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)

    private fun fixture(service: TermService): TermSession {
        lateinit var fixture: TermSession
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            fixture = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
            service.sessions[0] = fixture
            service.selectedSessionId.value = fixture.id
        }
        return fixture
    }

    private fun waitForOutput(session: TermSession, expected: String, timeoutMs: Long = 6000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var text = ""
            inst.runOnMainSync { text = session.session.emulator.screen.transcriptText }
            if (expected in text) return true
            Thread.sleep(30)
        }
        return false
    }

    @Test fun enterKeyRunsTheTypedCommand() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        val fixture = fixture(service)
        WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertNotNull(device.wait(Until.findObject(By.text("Text / voice")), 10000))
                // Typed but not submitted; the arithmetic proves the shell ran it.
                inst.runOnMainSync { fixture.session.write("echo ENTER_\$((40+2))") }
                assertTrue(waitForOutput(fixture, "echo ENTER_"))
                val enter = device.wait(Until.findObject(By.text("ENTER")), 5000)
                assertNotNull("ENTER is on the special-keys row", enter)
                enter.click()
                assertTrue("ENTER submitted the command", waitForOutput(fixture, "ENTER_42"))
            }
        } finally {
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }

    @Test fun workspaceMenuCollapsesOnPhonesAndFoldablesAndRemembersIt() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        fixture(service)
        WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
        val dir = File(ctx.filesDir, "screenshots").apply { mkdirs() }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                // Phone layout: the action row folds away, tabs stay.
                assertNotNull(device.wait(Until.findObject(By.text("Connect")), 10000))
                device.findObject(By.desc("Collapse workspace menu")).click()
                assertTrue(device.wait(Until.gone(By.text("Connect")), 4000))
                assertTrue(device.hasObject(By.desc("Session tabs")))
                assertTrue(WorkspacePrefs.workspaceCollapsed(ctx))
                device.waitForIdle(); device.takeScreenshot(File(dir, "workspace-collapsed-phone.png"))
                activity.recreate()
                assertTrue("Collapsed state survives recreation", device.wait(Until.hasObject(By.desc("Expand workspace menu")), 6000))
                assertFalse(device.hasObject(By.text("Connect")))
                device.findObject(By.desc("Expand workspace menu")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Connect")), 4000))

                // Foldable inner-screen layout: the WORKSPACES sidebar collapses to a numbered rail.
                device.executeShellCommand("wm size 1600x1800"); device.executeShellCommand("wm density 320")
                assertTrue(device.wait(Until.hasObject(By.text("WORKSPACES")), 8000))
                device.findObject(By.desc("Collapse workspace menu")).click()
                assertTrue(device.wait(Until.gone(By.text("WORKSPACES")), 4000))
                assertTrue(device.wait(Until.hasObject(By.descStartsWith("Tab 1:")), 4000))
                assertTrue(device.hasObject(By.desc("New tab")))
                device.waitForIdle(); device.takeScreenshot(File(dir, "workspace-collapsed-foldable.png"))
                device.findObject(By.desc("Expand workspace menu")).click()
                assertTrue(device.wait(Until.hasObject(By.text("WORKSPACES")), 4000))
                device.waitForIdle(); device.takeScreenshot(File(dir, "workspace-expanded-foldable.png"))
            }
        } finally {
            device.executeShellCommand("wm size reset"); device.executeShellCommand("wm density reset")
            WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }

    @Test fun historyBeyondTheInMemoryScrollbackIsKeptOnDeviceAndSearchable() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        val fixture = fixture(service)
        try {
            // 25,000 lines: more than the 20,000 rows kept in memory.
            inst.runOnMainSync { fixture.session.write("i=1; while [ \$i -le 25000 ]; do echo HISTORY_ROW_\$i; i=\$((i+1)); done; echo HISTORY_DONE\n") }
            assertTrue(waitForOutput(fixture, "\nHISTORY_DONE", 120_000))
            var inMemoryHasFirst = true
            inst.runOnMainSync { inMemoryHasFirst = "HISTORY_ROW_1\n" in fixture.session.emulator.screen.transcriptText }
            assertFalse("The first rows have left the in-memory scrollback", inMemoryHasFirst)
            var full = ""
            val done = java.util.concurrent.CountDownLatch(1)
            inst.runOnMainSync {
                kotlinx.coroutines.MainScope().launch { full = service.fullHistory(fixture); done.countDown() }
            }
            assertTrue(done.await(30, java.util.concurrent.TimeUnit.SECONDS))
            val lines = full.split('\n')
            assertTrue("First row kept on device", "HISTORY_ROW_1" in lines)
            assertTrue("Last row kept on device", "HISTORY_ROW_25000" in lines)
            assertEquals("Each row recorded exactly once", 1, lines.count { it == "HISTORY_ROW_12345" })
            assertTrue(SessionHistory.file(ctx, fixture.tabId).length() > 0)
            inst.runOnMainSync { service.closeSession(fixture) }
            Thread.sleep(500)
            assertFalse("Closing the tab deletes its history", SessionHistory.file(ctx, fixture.tabId).exists())
        } finally {
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }
}
