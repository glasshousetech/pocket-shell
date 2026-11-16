package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Render the real candidate at cover, unfolded and larger-text sizes on an owned emulator. */
class NativeLayoutReviewTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @Test fun coverUnfoldedAndLargeTextKeepTheWorkspaceUsable() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val inst=InstrumentationRegistry.getInstrumentation()
        val ctx=inst.targetContext
        val device=UiDevice.getInstance(inst)
        val oldScale=device.executeShellCommand("settings get system font_scale").trim()
        val oldTheme=TermThemes.saved(ctx)
        val service=(serviceRule.bindService(Intent(ctx,TermService::class.java)) as TermService.LocalBinder).service
        val shots=File(ctx.filesDir,"screenshots/adaptive").apply{mkdirs()}
        lateinit var a:TermSession
        lateinit var b:TermSession
        fun find(text:String)=device.wait(Until.findObject(By.text(text)),10000)?:error("Missing $text")
        fun scroll(text:String) { if(!device.hasObject(By.text(text))) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text) }
        fun shot(name:String){device.waitForIdle();Thread.sleep(600);device.takeScreenshot(File(shots,"$name.png"));device.dumpWindowHierarchy(File(shots,"$name.xml"))}
        inst.runOnMainSync {
            TermThemes.save(ctx,TermThemes.byId("ght_signature"))
            service.sessions.toList().forEach(service::closeSession)
            val shell=service.newSession(SessionMode.SYSTEM);shell.session.updateSize(80,24)
            a=TermSession(shell.id,shell.label,shell.alive,shell.session,SessionMode.LINUX,shell.tabId)
            service.sessions[0]=a
            b=service.newSession(SessionMode.SYSTEM);b.session.updateSize(80,24)
            a.customName.value="Agent workspace";b.customName.value="Local Linux"
            service.selectedSessionId.value=a.id
            a.session.write("PS1='workspace > '; clear; printf 'PocketShell review\\nTwo independent live terminals.\\n'\n")
        }
        try {
            for((name,size,scale) in listOf(Triple("cover","720x1500","1.0"),Triple("unfolded","1680x1800","1.0"),Triple("large-text","720x1700","1.3"),Triple("tablet","2200x1800","1.0"),Triple("landscape","1680x900","1.0"))) {
                device.executeShellCommand("wm size $size");device.executeShellCommand("wm density 320")
                device.executeShellCommand("settings put system font_scale $scale")
                Thread.sleep(700)
                ActivityScenario.launch(MainActivity::class.java).use { activity ->
                    check(ToolbarRobot.workspaceReady(device)) { "Terminal workspace did not load" }
                    activity.onActivity {(it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0)}
                    if(name in setOf("unfolded", "tablet", "landscape")) find("WORKSPACES") else assertFalse(device.hasObject(By.text("WORKSPACES")))
                    val target = (48 * ctx.resources.displayMetrics.density).toInt()
                    fun actionsVisible(): Boolean {
                        ToolbarRobot.refresh()
                        val viewport = device.findObject(By.desc("Session tabs"))?.visibleBounds ?: return false
                        return listOf("Connect", "New tab").all { label ->
                            val bounds = device.findObject(By.desc(label))?.visibleBounds ?: return@all false
                            bounds.width() >= target - 1 && bounds.height() >= target - 1 && viewport.contains(bounds)
                        }
                    }
                    repeat(8) {
                        if (!actionsVisible()) device.findObject(By.desc("Session tabs"))?.scroll(Direction.RIGHT, 0.8f)
                    }
                    assertTrue("Both action targets must be fully visible inside the list", actionsVisible())
                    shot("$name-workspace")
                    device.wait(Until.findObject(By.desc("Connect")),8000)!!.click();find("Add connection");shot("$name-connections")
                    scroll("Close connections");find("Close connections").click()
                    device.wait(Until.findObject(By.desc("Settings")),8000)!!.click()
                    find("Appearance");shot("$name-settings")
                    find("Keyboard").click();shot("$name-keyboard")
                    scroll("Close settings");find("Close settings").click()
                    inst.runOnMainSync {assertEquals(2,service.sessions.size);assertTrue(a.alive.value);assertTrue(b.alive.value)}
                }
            }
        } finally {
            device.executeShellCommand("wm size reset");device.executeShellCommand("wm density reset")
            if(oldScale=="null")device.executeShellCommand("settings delete system font_scale") else device.executeShellCommand("settings put system font_scale $oldScale")
            inst.runOnMainSync {service.sessions.toList().forEach(service::closeSession);TermThemes.save(ctx,oldTheme);TermThemes.apply(oldTheme,emptyList()) {}}
        }
    }
}
