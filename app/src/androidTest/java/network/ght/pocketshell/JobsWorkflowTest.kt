package network.ght.pocketshell

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Socket
import java.util.UUID

/** Executes the generated runner on the CI host; SSH authentication is tested separately. */
class JobsWorkflowTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(40) }
        val captures = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        device.takeScreenshot(File(captures, "jobs-failure.png"))
        device.dumpWindowHierarchy(File(captures, "jobs-failure.xml"))
        error(message)
    }
    private fun fresh() { if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache() }
    private fun text(label: String): UiObject2 {
        waitFor("Missing UI: " + label) { fresh(); device.hasObject(By.text(label)) }
        return requireNotNull(device.findObject(By.text(label)))
    }
    private fun scroll(label: String): UiObject2 {
        fresh()
        if (!device.hasObject(By.text(label))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(label))
        return text(label)
    }
    private suspend fun fixture(script: String, timeout: Long): GuestResult = withContext(Dispatchers.IO) {
        Socket("10.0.2.2", 48373).use { socket ->
            socket.soTimeout = (timeout * 1000).toInt()
            socket.getOutputStream().write((JSONObject().put("script", script).toString() + "\n").toByteArray())
            socket.getOutputStream().flush()
            val result = JSONObject(socket.getInputStream().bufferedReader().readLine())
            GuestResult(result.getInt("exit"), result.getString("output"))
        }
    }


    private fun demoBeat(id: String) {
        if (InstrumentationRegistry.getArguments().getString("demoRecording") != "true") return
        val directory = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(directory, "jobs-demo-timeline.jsonl").appendText(
            org.json.JSONObject().put("beat", id).put("uptimeMs", SystemClock.uptimeMillis()).toString() + "\n")
        // Narrated recording only: leave the verified screen readable.
        SystemClock.sleep(InstrumentationRegistry.getArguments().getString("demoHoldMs", "10000").toLong())
    }

    @Test fun reviewedRunPostsActualFailureResultAndRestoresWithoutDuplicateExecution() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant network.ght.pocketshell android.permission.POST_NOTIFICATIONS").close()
        val recordFile = File(context.noBackupFilesDir, "remote-jobs-v1.json")
        val previous = recordFile.takeIf { it.exists() }?.readBytes()
        recordFile.delete()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        val preferences = context.getSharedPreferences("remote_jobs", android.content.Context.MODE_PRIVATE)
        val previousTimeout = preferences.getLong("timeout", 120)
        val previousInterval = preferences.getLong("interval", 10)
        val marker = UUID.randomUUID().toString() + ".count"
        val command = "printf x >> '" + marker + "'; printf 'NATIVE_JOB_RESULT\\n'; sleep 1; exit 7"
        val manager = context.getSystemService(NotificationManager::class.java)
        val state = main {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            service.sessions[0] = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
            service.selectedSessionId.value = shell.id
            service.jobs.apply {
                available = { true }
                request = { _, script, timeout -> fixture(script, timeout) }
                profile = SshProfile("Linux job fixture", "example.test", "connor", 22, "", "")
                name = "Native job verification"; this.command = command; directory = ""
                timeoutInput = "30"; intervalInput = "1"; applySettings()
            }
        }
        waitFor("Job records did not load") { main { state.loaded } }
        var resultJobId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                main { service.workbench.page = WorkbenchState.Page.JOBS }
                text("Remote jobs")
                scroll("Review remote job").click()
                text("Start Native job verification?")
                assertEquals(command, main { state.review!!.command })
                demoBeat("review")
                text("Start remote job").click()
                waitFor("Actual job result was not observed: " + main { state.error }) {
                    main { state.jobs.firstOrNull()?.observation?.state == "FAILED" }
                }
                text("Failed · exit 7")
                demoBeat("result")
                val job = main { state.jobs.first() }
                assertEquals(7, job.observation!!.exit)
                waitFor("Result notification was not posted") { manager.activeNotifications.any { it.tag == job.id } }
                val notification = manager.activeNotifications.single { it.tag == job.id }
                assertEquals(job.name, notification.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertTrue(notification.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("code 7"))
                val posted = notification.postTime
                val observation = job.observation!!
                val output = runBlocking { fixture("cat " + CommandTemplate.quoteArgument(observation.remoteDirectory + "/output.log"), 30) }
                assertEquals("NATIVE_JOB_RESULT\n", output.output)
                assertEquals(0, output.exitCode)
                main { state.launch(job) }
                waitFor("Same-identity retry did not finish") { main { !state.busy } }
                val count = runBlocking { fixture("cat " + CommandTemplate.quoteArgument(marker), 30) }
                assertEquals("x", count.output)
                assertEquals(posted, manager.activeNotifications.single { it.tag == job.id }.postTime)

                scenario.recreate()
                text("Remote jobs")
                assertEquals(job.id, main { state.jobs.first().id })
                val saved = JobStore.load(context).single { it.id == job.id }
                assertEquals(7, saved.observation!!.exit)
                assertEquals(command, saved.command)
                assertEquals(requireNotNull(saved.observation).resultKey, saved.notifiedResult)

                resultJobId = job.id

                val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val restored = main { JobsWorkbench(context, freshScope).apply { available = { true }; request = { _, script, timeout -> fixture(script, timeout) } } }
                try {
                    waitFor("Saved job did not reload") { main { restored.loaded } }
                    assertEquals(job.id, main { restored.jobs.first().id })
                    assertEquals(7, main { restored.jobs.first().observation!!.exit })
                    main { restored.refresh() }
                    waitFor("Restored observer did not reconnect") { main { !restored.busy } }
                    assertEquals(7, main { restored.jobs.first().observation!!.exit })
                    assertEquals(posted, manager.activeNotifications.single { it.tag == job.id }.postTime)
                } finally { main { restored.close(); freshScope.cancel() } }
            }
            // Tap the actual Android notification after closing the scenario-owned Activity.
            // A synthetic PendingIntent.send from the background is correctly blocked by Android.
            main { service.workbench.close() }
            device.openNotification()
            val notificationTitle = text("Native job verification")
            // Android's collapsed automatic group exposes a hybrid title whose tap
            // opens the app's launcher, not the child's content PendingIntent.
            if (notificationTitle.resourceName == "com.android.systemui:id/notification_title") {
                var ancestor: UiObject2? = notificationTitle.parent
                var expand: UiObject2? = null
                while (ancestor != null && expand == null) {
                    expand = ancestor.findObject(By.res("android", "expand_button"))
                    ancestor = ancestor.parent
                }
                checkNotNull(expand) { "Grouped job notification has no expand control" }.click()
            }
            val notificationBody = text("Exited with code 7 on Linux job fixture")
            val notificationEvidence = File(context.filesDir, "screenshots").apply { mkdirs() }
            device.dumpWindowHierarchy(File(notificationEvidence, "jobs-notification.xml"))
            device.takeScreenshot(File(notificationEvidence, "jobs-notification.png"))
            demoBeat("notification")
            notificationBody.click()
            text("Remote jobs")
            text("Failed · exit 7")
            val screenshotDir = File(context.filesDir, "screenshots").apply { mkdirs() }
            device.takeScreenshot(File(screenshotDir, "remote-jobs-result.png"))
        } finally {
            main {
                androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                    .filterIsInstance<MainActivity>().toList().forEach { it.finish() }
                state.jobs.toList().forEach { state.setWatching(it, false) }; service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
            waitFor("Watch records did not settle") { main { !state.busy } }
            resultJobId?.let { manager.cancel(it, 2001) }
            main { state.timeoutInput = previousTimeout.toString(); state.intervalInput = previousInterval.toString(); state.applySettings() }
            if (previous == null) recordFile.delete() else recordFile.writeBytes(previous)
            device.pressBack()
        }
    }

    @Test fun jobStoreKeepsIdentityConcurrentFieldsAndFutureVersionData() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val storage = File(context.noBackupFilesDir, "remote-jobs-v1.json")
        val previous = storage.takeIf { it.exists() }?.readBytes()
        storage.delete()
        try {
            val item = RemoteJob(UUID.randomUUID().toString(), "Store fixture",
                SshProfile("Fixture", "example.test", "connor", 22, "", ""), "printf fixture", "", System.currentTimeMillis())
            JobStore.create(context, item)
            val observed = JobObservation("SUCCEEDED", 1760000000, 1760000001, 0, System.currentTimeMillis(), "/home/fixture/jobs/" + item.id)
            val start = java.util.concurrent.CountDownLatch(1)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val threads = listOf(
                Thread { try { start.await(); JobStore.update(context, item.id) { it.copy(watching = false) } } catch (error: Throwable) { failures.add(error) } },
                Thread { try { start.await(); JobStore.update(context, item.id) { it.copy(observation = observed) } } catch (error: Throwable) { failures.add(error) } },
            )
            threads.forEach { it.start() }; start.countDown(); threads.forEach { it.join(10000); assertFalse(it.isAlive) }
            assertTrue(failures.toString(), failures.isEmpty())
            val saved = JobStore.load(context).single()
            assertFalse(saved.watching)
            assertEquals(observed, saved.observation)
            // A duplicate launch must preserve the observed result instead of resetting the receipt.
            JobStore.create(context, item)
            assertEquals(saved, JobStore.load(context).single())
            try { JobStore.create(context, item.copy(command = "printf different")); fail("An identity must not select different work") }
            catch (_: IllegalArgumentException) { }
            assertEquals(saved, JobStore.load(context).single())
            val unsupported = "{\"version\":999,\"jobs\":[]}"
            storage.writeText(unsupported)
            try { JobStore.update(context, item.id) { it.copy(watching = true) }; fail("Future format must be preserved") }
            catch (_: IllegalArgumentException) { }
            assertEquals(unsupported, storage.readText())
        } finally { if (previous == null) storage.delete() else storage.writeBytes(previous) }
    }
}
