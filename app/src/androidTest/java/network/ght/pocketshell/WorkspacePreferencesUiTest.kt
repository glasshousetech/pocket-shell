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

class WorkspacePreferencesUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @Test fun fontPersistencePowerPolicyAndDiagnosticRetrieval() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val inst=InstrumentationRegistry.getInstrumentation()
        val ctx=inst.targetContext
        val device=UiDevice.getInstance(inst)
        val oldFont=WorkspacePrefs.fontSize(ctx)
        val oldScreen=WorkspacePrefs.keepScreenOn(ctx)
        val oldBackground=WorkspacePrefs.backgroundWork(ctx)
        WorkspacePrefs.setFontSize(ctx,13); WorkspacePrefs.setKeepScreenOn(ctx,true); WorkspacePrefs.setBackgroundWork(ctx,true)
        val service=(serviceRule.bindService(Intent(ctx,TermService::class.java)) as TermService.LocalBinder).service
        lateinit var holder: TermSession
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell=service.newSession(SessionMode.SYSTEM); shell.session.updateSize(80,24)
            holder=TermSession(shell.id,shell.label,shell.alive,shell.session,SessionMode.LINUX,shell.tabId)
            service.sessions[0]=holder
        }
        fun find(text: String)=device.wait(Until.findObject(By.text(text)),8000) ?: error("Missing UI: $text")
        fun scroll(text: String) { device.waitForIdle(); val area=UiScrollable(UiSelector().scrollable(true)); if(area.exists())area.scrollTextIntoView(text) }
        fun waitFor(condition: () -> Boolean) {
            val end=System.currentTimeMillis()+8000
            while(!condition() && System.currentTimeMillis()<end) Thread.sleep(80)
            assertTrue(condition())
        }
        fun columns(): Int { var value=0; inst.runOnMainSync { value=holder.session.emulator.mColumns }; return value }
        fun cpuHeld() = device.executeShellCommand("dumpsys power").lineSequence()
            .any { it.trimStart().startsWith("PARTIAL_WAKE_LOCK") && it.contains("pocketshell:sessions") }
        fun terminal(view: android.view.View): network.ght.pocketshell.term.TerminalView? {
            if(view is network.ght.pocketshell.term.TerminalView) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) terminal(view.getChildAt(i))?.let{return it}
            return null
        }
        val shots=File(ctx.filesDir,"screenshots").apply{mkdirs()}
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                find("Text / voice")
                device.waitForIdle()
                val before=columns()
                find("⚙").click(); find("Appearance").click(); find("Larger").click()
                assertEquals(14,WorkspacePrefs.fontSize(ctx))
                waitFor { columns()<before }
                device.takeScreenshot(File(shots,"settings-appearance.png"))
                scroll("Close settings"); find("Close settings").click()
                activity.recreate()
                find("Text / voice")
                assertEquals(14,WorkspacePrefs.fontSize(ctx))
                waitFor { columns()<before }
                find("⚙").click(); find("Sessions & privacy").click()
                device.findObject(By.desc("Keep screen on")).click()
                assertFalse(WorkspacePrefs.keepScreenOn(ctx))
                activity.onActivity { assertFalse(terminal(it.window.decorView)!!.keepScreenOn) }
                device.findObject(By.desc("Keep CPU awake")).click()
                assertFalse(WorkspacePrefs.backgroundWork(ctx))
                waitFor { !cpuHeld() }
                device.findObject(By.desc("Keep CPU awake")).click()
                waitFor { cpuHeld() }
                device.takeScreenshot(File(shots,"settings-sessions.png"))
                scroll("‹ All settings"); find("‹ All settings").click()
                find("Appearance")
                scroll("Diagnostics"); find("Diagnostics").click()
                assertTrue(SshKeyStore.import(ctx,android.net.Uri.parse("file:///does-not-exist-audit")).isFailure)
                find("Copy diagnostics").click()
                waitFor { Clip.paste().contains("key_import_rejected") }
                assertFalse(Clip.paste().contains("does-not-exist-audit"))
                device.takeScreenshot(File(shots,"settings-diagnostics.png"))
            }
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
            waitFor { !cpuHeld() }
        } finally {
            WorkspacePrefs.setFontSize(ctx,oldFont); WorkspacePrefs.setKeepScreenOn(ctx,oldScreen); WorkspacePrefs.setBackgroundWork(ctx,oldBackground)
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession); service.refreshPowerPolicy() }
        }
    }
}
