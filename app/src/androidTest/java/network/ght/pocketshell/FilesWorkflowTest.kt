package network.ght.pocketshell

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real SFTP server + production transfer engine and Android content provider; network fixture excludes SSH authentication. */
class FilesWorkflowTest {
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
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(40)
        }
        val captures = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        device.takeScreenshot(File(captures, "files-failure.png"))
        device.dumpWindowHierarchy(File(captures, "files-failure.xml"))
        error(message)
    }
    private fun text(label: String): UiObject2 {
        waitFor("Missing UI: " + label) {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            device.hasObject(By.text(label))
        }
        return requireNotNull(device.findObject(By.text(label)))
    }
    private fun scrollTo(label: String): UiObject2 {
        waitFor("Missing file content: " + label) {
            if (android.os.Build.VERSION.SDK_INT >= 34) instrumentation.uiAutomation.clearCache()
            device.hasObject(By.text(label)) || device.hasObject(By.scrollable(true))
        }
        if (!device.hasObject(By.text(label))) UiScrollable(UiSelector().scrollable(true)).scrollIntoView(UiSelector().text(label))
        return text(label)
    }
    private fun ready(state: FilesWorkbench) {
        waitFor("File operation did not complete: " + main { state.error ?: state.status }) { main { !state.busy } }
        assertNull(main { state.error })
    }


    private fun demoBeat(id: String) {
        if (InstrumentationRegistry.getArguments().getString("demoRecording") != "true") return
        val directory = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        File(directory, "files-demo-timeline.jsonl").appendText(
            org.json.JSONObject().put("beat", id).put("uptimeMs", SystemClock.uptimeMillis()).toString() + "\n")
        // Narrated recording only: leave the verified screen readable.
        SystemClock.sleep(InstrumentationRegistry.getArguments().getString("demoHoldMs", "10000").toLong())
    }

    @Test fun realUploadConflictDownloadExportAndRecreation() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        println("FILES_PHASE starting")
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant network.ght.pocketshell android.permission.POST_NOTIFICATIONS").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        waitFor("Notification permission fixture was not ready") { context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        println("FILES_PHASE service_bound")
        val id = UUID.randomUUID().toString()
        val inputUri = Uri.Builder().scheme("content").authority("network.ght.pocketshell.test.transfer")
            .appendPath(id).appendPath("upload α.bin").build()
        val outputUri = inputUri.buildUpon().path(null).appendPath(id).appendPath("download α.bin").build()
        val bytes = ByteArray(262181) { (it % 251).toByte() }
        println("FILES_PHASE provider_seed_begin")
        context.contentResolver.openOutputStream(inputUri, "wt")!!.use { it.write(bytes) }
        println("FILES_PHASE provider_seed_finished")
        val receiptFile = File(context.noBackupFilesDir, "file-transfers-v1.json")
        val previousReceipt = receiptFile.takeIf { it.exists() }?.readBytes()
        receiptFile.delete()
        println("FILES_PHASE main_setup_begin")
        val files = main {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            service.sessions[0] = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
            service.selectedSessionId.value = shell.id
            service.workbench.page = WorkbenchState.Page.FILES
            service.files.apply {
                transport = { _, _, timeout -> SftpClient(ProcessBuilder("/system/bin/toybox", "nc", "10.0.2.2", "48372").start(), timeout) }
                choose(SshProfile("SFTP fixture", "example.test", "connor", 22, "", ""))
                timeout = "30"
            }
        }
        println("FILES_PHASE main_setup_finished")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                println("FILES_PHASE activity_open")
                text("Remote files")
                text("Open folder").click()
                ready(files)
                println("FILES_PHASE ready " + main { files.status })
                assertNotNull(main { files.directory })
                assertTrue(main { files.entries!!.isEmpty() })
                main { files.newFolder(id) }
                ready(files)
                main { files.browse(files.directory!!.child(SftpClient.Path.text(id))) }
                ready(files)

                assertTrue(main { files.prepareUpload() })
                main { files.upload(inputUri) }
                ready(files)
                assertEquals(FileTransfer.Phase.COMPLETE, main { files.transfers.first().phase })
                assertEquals(bytes.size.toLong(), main { files.transfers.first().size })
                val firstIdentity = main { files.transfers.first().id }
                scrollTo("upload α.bin")
                demoBeat("upload")

                scenario.recreate()
                text("Remote files")
                assertEquals(firstIdentity, main { files.transfers.first().id })
                assertTrue(main { files.prepareUpload() })
                main { files.upload(inputUri) }
                ready(files)
                val conflict = main { files.transfers.first() }
                assertEquals(FileTransfer.Phase.CONFLICT, conflict.phase)
                scrollTo("Replace and keep backup").click()
                text("Replace this file?")
                demoBeat("conflict")
                text("Replace with backup").click()
                ready(files)
                assertEquals(FileTransfer.Phase.COMPLETE, main { files.transfers.first().phase })
                assertTrue(main { files.entries!!.any { it.name == conflict.backup.let { path -> SftpClient.Path.of(path.bytes.copyOfRange(path.bytes.indexOfLast { it == 47.toByte() } + 1, path.bytes.size)) } } })

                val entry = main { files.entries!!.single { it.name.display == "upload α.bin" } }
                main { files.download(entry) }
                ready(files)
                assertNotNull(main { files.preparedDownload })
                assertArrayEquals(bytes, main { files.preparedDownload!!.file.readBytes() })
                main { files.prepareExport(); files.export(outputUri) }
                ready(files)
                assertEquals("Saved to your phone and checksum verified.", main { files.status })
                assertArrayEquals(bytes, context.contentResolver.openInputStream(outputUri)!!.use { it.readBytes() })
                scrollTo("Saved to your phone and checksum verified.")
                demoBeat("export")
                val screenshots = File(context.filesDir, "screenshots").apply { mkdirs() }
                device.takeScreenshot(File(screenshots, "remote-files-verified.png"))

                // The durable receipt can be reloaded independently of live UI state.
                val loaded = TransferStore.load(context)
                assertEquals(2, loaded.size)
                assertTrue(loaded.all { it.phase == FileTransfer.Phase.COMPLETE })
            }
        } finally {
            main { service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
            context.contentResolver.delete(inputUri, null, null)
            context.contentResolver.delete(outputUri, null, null)
            if (previousReceipt == null) receiptFile.delete() else receiptFile.writeBytes(previousReceipt)
            device.pressBack()
        }
    }
}
