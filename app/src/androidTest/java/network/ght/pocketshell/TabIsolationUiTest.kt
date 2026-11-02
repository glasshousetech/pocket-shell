package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** UI routing uses real shells in an isolated emulator; no customer SSH session is touched. */
class TabIsolationUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @Test fun switchingClosingAndReconnectingTabsKeepsTheirIdentity() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val device = UiDevice.getInstance(inst)
        device.executeShellCommand("pm grant " + ctx.packageName + " android.permission.POST_NOTIFICATIONS")
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        lateinit var a: TermSession
        lateinit var b: TermSession
        inst.runOnMainSync {
            a = service.newSession(SessionMode.SYSTEM, startupCommand = "true")
            val real = service.newSession(SessionMode.SYSTEM)
            // This one live Linux-labelled fixture skips provisioning; both processes are real isolated PTYs.
            b = TermSession(real.id, real.label, real.alive, real.session, SessionMode.LINUX, real.tabId)
            service.sessions[service.sessions.indexOf(real)] = b
            a.session.updateSize(80,24); b.session.updateSize(80,24)
            a.label.value = "Tab Alpha"; b.label.value = "Tab Beta"
        }
        val id = a.tabId
        val shots = File(ctx.filesDir,"screenshots").apply { mkdirs() }
        fun write(t: TermSession, s: String) { inst.runOnMainSync { t.session.write(s) } }
        fun waitOutput(t: TermSession, value: String) {
            val deadline = System.currentTimeMillis() + 8000
            var found = false
            while (!found && System.currentTimeMillis() < deadline) {
                inst.runOnMainSync { found = t.session.emulator.screen.transcriptText.contains(value) }
                if (!found) Thread.sleep(50)
            }
            assertTrue(value, found)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                val ready = device.wait(Until.hasObject(By.text("Tab Alpha")),8000)
                device.takeScreenshot(File(shots,"tabs-start.png"))
                device.dumpWindowHierarchy(File(shots,"tabs-start.xml"))
                assertTrue("Initial tab labels visible", ready)
                Thread.sleep(400)
                write(a,"PS1='alpha> '; clear; export TAB_VALUE=ALPHA; printf 'TAB_%s_INDEPENDENT\\n' \"\$TAB_VALUE\"\n")
                write(b,"PS1='beta> '; clear; export TAB_VALUE=BETA; printf 'TAB_%s_INDEPENDENT\\n' \"\$TAB_VALUE\"\n")
                waitOutput(a,"TAB_ALPHA_INDEPENDENT"); waitOutput(b,"TAB_BETA_INDEPENDENT")
                device.findObject(By.text("Tab Beta")).click(); device.waitForIdle()
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0) }
                device.waitForIdle(); device.takeScreenshot(File(shots,"tabs-beta-portrait.png"))
                device.findObject(By.text("Tab Alpha")).click()
                write(a,"exit 23\n")
                assertTrue(device.wait(Until.hasObject(By.text("Reconnect this tab")),8000))
                device.waitForIdle(); device.takeScreenshot(File(shots,"tabs-reconnect-portrait.png"))
                device.findObject(By.text("Reconnect this tab")).click()
                device.waitForIdle()
                inst.runOnMainSync {
                    assertEquals(2,service.sessions.size)
                    a=service.sessions.first { it.tabId==id }
                    assertTrue(a.alive.value)
                    assertSame(b.session,service.sessions.first { it.tabId==b.tabId }.session)
                    a.label.value="Tab Alpha"
                }
                Thread.sleep(400)
                write(a,"printf 'RECONNECTED_%s_END\\n' \"\$GHT_TAB_ID\"\n")
                waitOutput(a,"RECONNECTED_"+id+"_END")
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("Tab Beta")),8000))
                device.findObject(By.text("Tab Beta")).click()
                write(b,"printf 'STILL_%s_END\\n' \"\$TAB_VALUE\"\n")
                waitOutput(b,"STILL_BETA_END")
                activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0) }
                device.setOrientationLeft(); Thread.sleep(700); device.waitForIdle()
                device.takeScreenshot(File(shots,"tabs-beta-landscape.png"))
                device.setOrientationNatural(); Thread.sleep(700); device.waitForIdle()
                device.wait(Until.findObject(By.text("Tab Alpha")),5000)!!.click()
                val close = device.wait(Until.findObject(By.desc("Close tab Tab Alpha")),5000)
                device.takeScreenshot(File(shots,"tabs-before-close.png"))
                device.dumpWindowHierarchy(File(shots,"tabs-before-close.xml"))
                assertNotNull("Selected tab exposes its named close control after rotation",close)
                close!!.click()
                assertTrue(device.wait(Until.hasObject(By.text("Close tab")), 5000))
                device.findObject(By.text("Close tab")).click()
                device.waitForIdle()
                inst.runOnMainSync { assertEquals(1,service.sessions.size); assertSame(b.session,service.sessions.single().session) }
                write(b,"printf 'FINAL_%s_END\\n' \"\$TAB_VALUE\"\n")
                waitOutput(b,"FINAL_BETA_END")
                device.waitForIdle(); device.takeScreenshot(File(shots,"tabs-survivor.png"))
            }
        } finally {
            device.setOrientationNatural(); device.unfreezeRotation()
            inst.runOnMainSync { service.sessions.toList().forEach { service.closeSession(it) } }
        }
    }
}
