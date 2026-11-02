package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Controlled asynchronous provider fixture; exercises the real Activity and service without paid traffic. */
class CopilotLifecycleTest {
    @get:Rule val serviceRule = ServiceTestRule()
    @Test fun pendingRequestAndResultSurviveRecreationWithoutErasingNewInput() {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val device = UiDevice.getInstance(inst)
        val service = (serviceRule.bindService(Intent(ctx,TermService::class.java)) as TermService.LocalBinder).service
        val response = CompletableDeferred<Result<String>>()
        lateinit var origin: TermSession
        var calls = 0
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80,24)
            origin = TermSession(shell.id,shell.label,shell.alive,shell.session,SessionMode.LINUX,shell.tabId)
            service.sessions[0] = origin
            service.copilot.input.value = "original request"
            service.copilot.open.value = true
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("original request")),8000))
                inst.runOnMainSync {
                    service.requestCopilot(origin,"original request",null,AiCopilot.Mode.COMMAND) { calls++; response.await() }
                    service.requestCopilot(origin,"duplicate submit",null,AiCopilot.Mode.COMMAND) { calls++; Result.success("WRONG") }
                }
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("original request")),8000))
                inst.runOnMainSync { assertTrue(service.copilot.busy.value); assertEquals(origin.id,service.copilot.originId.value) }
                device.findObject(By.clazz("android.widget.EditText")).text = "next unsent question"
                assertTrue("New input has reached the UI before the provider replies",
                    device.wait(Until.hasObject(By.text("next unsent question")),8000))
                val inputDeadline = System.currentTimeMillis()+8000
                var inputAccepted = false
                while (!inputAccepted && System.currentTimeMillis()<inputDeadline) {
                    inst.runOnMainSync { inputAccepted = service.copilot.input.value == "next unsent question" }
                    if (!inputAccepted) Thread.sleep(30)
                }
                assertTrue("User input is accepted before completing the pending provider fixture",inputAccepted)
                response.complete(Result.success("printf COPILOT_REVIEW"))
                assertTrue(device.wait(Until.hasObject(By.text("printf COPILOT_REVIEW")),8000))
                assertTrue(device.hasObject(By.text("next unsent question")))
                assertEquals(1,calls)
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("printf COPILOT_REVIEW")),8000))
                assertTrue(device.hasObject(By.text("next unsent question")))
                val shots=java.io.File(ctx.filesDir,"screenshots").apply{mkdirs()}
                device.waitForIdle();device.takeScreenshot(java.io.File(shots,"copilot-result.png"))
                fun reveal(text:String) {
                    val area=UiScrollable(UiSelector().scrollable(true))
                    if(!device.hasObject(By.text(text)) && area.exists())area.scrollTextIntoView(text)
                    assertTrue("AI action stays reachable: $text",device.wait(Until.hasObject(By.text(text)),8000))
                }
                reveal("Insert");reveal("Ask AI")
                inst.runOnMainSync {
                    assertFalse(service.copilot.busy.value)
                    assertEquals(origin.id,service.copilot.originId.value)
                    assertFalse(origin.session.emulator.screen.transcriptText.contains("COPILOT_REVIEW"))
                }
            }
            val diagnostics = DiagnosticLog.read(ctx)
            assertTrue(diagnostics.contains("request_succeeded"))
            assertFalse(diagnostics.contains("original request"))
            assertFalse(diagnostics.contains("COPILOT_REVIEW"))
        } finally {
            response.complete(Result.failure(IllegalStateException("fixture closed")))
            inst.runOnMainSync {
                service.copilot.open.value=false; service.copilot.input.value=""; service.copilot.result.value=null
                service.sessions.toList().forEach(service::closeSession)
            }
        }
    }
}
