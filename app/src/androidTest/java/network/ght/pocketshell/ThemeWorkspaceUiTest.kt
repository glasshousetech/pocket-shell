package network.ght.pocketshell

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.termux.terminal.TextStyle
import network.ght.pocketshell.term.TerminalView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Drives the production picker with two real PTYs on an isolated emulator. */
class ThemeWorkspaceUiTest {
    @get:Rule val serviceRule = ServiceTestRule()

    @Test fun allThemesApplyPersistAndKeepLiveSessions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue("Themes fixture requires an isolated emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.MODEL.contains("sdk_gphone"))
        val ctx = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val previous = TermThemes.saved(ctx)
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        val fixtures = mutableListOf<TermSession>()
        val dir = File(ctx.filesDir, "screenshots/themes").apply { mkdirs() }
        fun capture(name: String) { device.waitForIdle(); assertTrue(device.takeScreenshot(File(dir, "$name.png"))) }
        if (android.os.Build.VERSION.SDK_INT >= 33) device.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("wm size 720x1280")
        device.executeShellCommand("wm density 320")
        instrumentation.runOnMainSync {
            repeat(2) {
                val system = service.newSession(SessionMode.SYSTEM)
                system.session.updateSize(80, 24)
                val fixture = TermSession(system.id, system.label, system.alive, system.session, SessionMode.LINUX)
                service.sessions[service.sessions.indexOf(system)] = fixture
                fixtures.add(fixture)
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 8000))
                fun hideKeyboard() {
                    activity.onActivity { (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken, 0) }
                    device.waitForIdle()
                }
                fun openThemes() {
                    hideKeyboard()
                    device.findObject(By.text("Theme")).click()
                    assertTrue(device.wait(Until.hasObject(By.text("Find a theme")), 5000))
                }
                hideKeyboard()
                instrumentation.runOnMainSync {
                    fixtures.first().session.write("PS1='preview ~ $ '; clear; printf '\\033[36mPocket Shell theme preview\\033[0m\\n\\n'; printf '\\033[32mReady\\033[0m  Two live terminal sessions\\n\\033[33mTip\\033[0m    Switch themes without reconnecting\\n\\n'\n")
                }
                Thread.sleep(500)
                openThemes()
                capture("picker-360")
                device.findObject(By.text("Light")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Apply theme Porcelain")), 4000))
                assertFalse(device.hasObject(By.desc("Apply theme GHT Signature")))
                capture("picker-light-360")
                device.findObject(By.clazz("android.widget.EditText")).text = "no such theme"
                assertTrue(device.wait(Until.hasObject(By.text("No matching themes")), 3000))
                device.findObject(By.text("Show all themes")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Apply theme GHT Signature")), 3000))
                device.findObject(By.text("Close")).click()

                for (theme in TermThemes.ALL) {
                    openThemes()
                    device.findObject(By.clazz("android.widget.EditText")).text = theme.label
                    val card = device.wait(Until.findObject(By.desc("Apply theme ${theme.label}")), 4000)
                    assertNotNull("Search reaches ${theme.label}", card)
                    card.click()
                    assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 4000))
                    hideKeyboard()
                    assertEquals(theme.id, TermThemes.saved(ctx).id)
                    activity.onActivity {
                        assertEquals(2, service.sessions.size)
                        fixtures.forEachIndexed { index, fixture ->
                            assertSame("Theme changes must preserve every PTY", fixture.session, service.sessions[index].session)
                            assertTrue(fixture.alive.value)
                            val colors = fixture.session.emulator.mColors.mCurrentColors
                            assertEquals((0xFF000000L or theme.background).toInt(), colors[TextStyle.COLOR_INDEX_BACKGROUND])
                            theme.ansi.forEachIndexed { i, rgb -> assertEquals((0xFF000000L or rgb).toInt(), colors[i]) }
                        }
                        val view = terminal(it.window.decorView)!!
                        assertEquals((0xFF000000L or theme.background).toInt(), (view.background as ColorDrawable).color)
                        assertEquals((0xFF000000L or theme.ui.surfaceAlt).toInt(), it.window.statusBarColor)
                    }
                    capture("workspace-${theme.id}")
                }
                val last = TermThemes.ALL.last()
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000))
                assertEquals(last.id, TermThemes.saved(ctx).id)
                activity.onActivity {
                    assertSame(fixtures.first().session, terminal(it.window.decorView)!!.currentSession)
                    assertEquals((0xFF000000L or last.background).toInt(), (terminal(it.window.decorView)!!.background as ColorDrawable).color)
                }
                // Populate scrollback and verify recoloring doesn't jump to live output.
                instrumentation.runOnMainSync { fixtures.first().session.write("i=1; while [ \"$" + "i\" -le 120 ]; do echo history-$" + "i; i=$" + "((i+1)); done\n") }
                Thread.sleep(1200)
                hideKeyboard()
                var top = 0
                activity.onActivity { terminal(it.window.decorView)!!.let { v -> v.topRow = -12; top = v.topRow; v.invalidate() } }
                openThemes()
                device.findObject(By.clazz("android.widget.EditText")).text = "Obsidian"
                device.wait(Until.findObject(By.desc("Apply theme Obsidian")), 4000).click()
                hideKeyboard()
                activity.onActivity { assertEquals("Keep the scrollback position", top, terminal(it.window.decorView)!!.topRow) }

                openThemes()
                device.setOrientationLeft()
                assertTrue(device.wait(Until.hasObject(By.text("Close")), 5000))
                capture("picker-landscape")
                device.findObject(By.text("Close")).click()
                device.setOrientationNatural()
                device.executeShellCommand("wm size 1600x1800")
                device.executeShellCommand("wm density 320")
                assertTrue(device.wait(Until.hasObject(By.text("Theme")), 5000))
                openThemes()
                capture("picker-fold-800")
                device.findObject(By.text("Close")).click()
                device.findObject(By.text("⚙")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Browse 20 themes")), 4000))
                device.findObject(By.text("Browse 20 themes")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Find a theme")), 4000))
            }
        } finally {
            device.executeShellCommand("wm size reset")
            device.executeShellCommand("wm density reset")
            device.setOrientationNatural()
            device.unfreezeRotation()
            instrumentation.runOnMainSync {
                fixtures.forEach(service::closeSession)
                TermThemes.save(ctx, previous)
                TermThemes.apply(previous, emptyList()) {}
            }
        }
    }

    private fun terminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) terminal(view.getChildAt(i))?.let { return it }
        return null
    }
}
