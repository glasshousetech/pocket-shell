package network.ght.pocketshell

import android.content.Intent
import android.net.Uri
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

/** Audit regressions. These intentionally expose baseline defects; never run on a user's phone. */
class ProductAuditTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun requireEmulator() {
        assumeTrue("Synthetic audit fixture must be isolated", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        // Match normal app startup, which reaches the distro registry before a named object.
        // A separate audit run retained evidence of the inverse initialization-order defect.
        check(Distro.ALL.all { it.id.isNotBlank() })
        if (android.os.Build.VERSION.SDK_INT >= 33)
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
    }

    private fun fixture(service: TermService, title: String): TermSession {
        lateinit var holder: TermSession
        instrumentation.runOnMainSync {
            val system = service.newSession(SessionMode.SYSTEM)
            system.session.updateSize(80, 24)
            holder = TermSession(system.id, system.label, system.alive, system.session, SessionMode.LINUX)
            service.sessions[service.sessions.indexOf(system)] = holder
            holder.label.value = title
        }
        return holder
    }

    private fun terminal(view: android.view.View): network.ght.pocketshell.term.TerminalView? {
        if (view is network.ght.pocketshell.term.TerminalView) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
            terminal(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun shot(name: String) {
        device.waitForIdle()
        val directory = File(context.filesDir, "audit-screenshots").apply { mkdirs() }
        check(device.takeScreenshot(File(directory, "$name.png")))
    }

    @Test fun rotationKeepsSelectedSession() {
        requireEmulator()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        val alpha = fixture(service, "AUDIT ALPHA")
        val beta = fixture(service, "AUDIT BETA")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("AUDIT BETA")), 10000))
                device.findObject(By.text("AUDIT BETA")).click()
                device.waitForIdle()
                activity.onActivity { assertSame(beta.session, terminal(it.window.decorView)!!.currentSession) }
                shot("selected-beta-before")
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 10000))
                shot("selected-tab-after-recreation")
                activity.onActivity {
                    assertSame("The selected tab must remain BETA after Activity recreation", beta.session,
                        terminal(it.window.decorView)!!.currentSession)
                }
            }
        } finally { instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) } }
    }

    @Test fun rotationKeepsReviewedDraft() {
        requireEmulator()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        fixture(service, "AUDIT DRAFT")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 10000))
                device.findObject(By.text("Text / voice")).click()
                assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5000))
                device.findObject(By.clazz("android.widget.EditText")).text = "echo SAFE_AUDIT_DRAFT"
                assertTrue(device.wait(Until.hasObject(By.text("echo SAFE_AUDIT_DRAFT")), 5000))
                shot("draft-before")
                activity.recreate()
                device.waitForIdle()
                if (!device.hasObject(By.clazz("android.widget.EditText"))) {
                    assertTrue(device.wait(Until.hasObject(By.text("Text / voice")), 5000))
                    device.findObject(By.text("Text / voice")).click()
                }
                shot("draft-after-recreation")
                assertTrue("An unsubmitted draft must survive Activity recreation",
                    device.wait(Until.hasObject(By.text("echo SAFE_AUDIT_DRAFT")), 4000))
            }
        } finally { instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) } }
    }

    @Test fun invalidKeyImportPreservesExistingIdentity() {
        requireEmulator()
        val root = Userland.rootfsDir(context, Distro.Ubuntu)
        // No Linux installation is present in the fresh isolated debug fixture.
        check(!Userland.installedMarker(context, Distro.Ubuntu).exists())
        val marker = Userland.installedMarker(context, Distro.Ubuntu)
        marker.parentFile!!.mkdirs()
        marker.writeText("ubuntu synthetic-audit schema=3\n")
        check(Userland.installedDistro(context) == Distro.Ubuntu)
        val key = File(root, "root/.ssh/id_ed25519").apply { parentFile!!.mkdirs(); writeText("SYNTHETIC_EXISTING_IDENTITY") }
        val invalid = File(context.cacheDir, "invalid-audit-key").apply {
            writeText("-----BEGIN OPENSSH PRIVATE KEY-----\nnot-a-key\n-----END OPENSSH PRIVATE KEY-----\n")
        }
        try {
            val result = SshKeyStore.import(context, Uri.fromFile(invalid))
            File(context.filesDir, "audit-key-import.json").writeText(
                "{\"malformedAccepted\":${result.isSuccess},\"existingIdentityPreserved\":${key.readText() == "SYNTHETIC_EXISTING_IDENTITY"},\"errorType\":\"${result.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}\"}")
            assertTrue("Malformed key contents must be rejected before replacement", result.isFailure)
            assertEquals("SYNTHETIC_EXISTING_IDENTITY", key.readText())
            val events = DiagnosticLog.read(context)
            assertTrue(events.contains("key_import_rejected"))
            assertFalse(events.contains("SYNTHETIC_EXISTING_IDENTITY"))
            assertFalse(events.contains("not-a-key"))
        } finally {
            invalid.delete()
            root.deleteRecursively()
        }
    }

    @Test fun closeConfirmationKeepsTheSelectedTabAndDraftUntilConfirmed() {
        requireEmulator()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        fixture(service, "AUDIT ALPHA")
        val beta = fixture(service, "AUDIT BETA")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("AUDIT BETA")), 10000))
                device.findObject(By.text("AUDIT BETA")).click()
                instrumentation.runOnMainSync { service.reviewedTextDrafts[beta.id] = "SAFE_DRAFT" }
                assertTrue(device.wait(Until.hasObject(By.desc("Close tab AUDIT BETA")), 5000))
                device.findObject(By.desc("Close tab AUDIT BETA")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Keep tab")), 5000))
                assertEquals(2, service.sessions.size)
                device.findObject(By.text("Keep tab")).click()
                activity.onActivity { assertSame(beta.session, terminal(it.window.decorView)!!.currentSession) }
                assertEquals("SAFE_DRAFT", service.reviewedTextDrafts[beta.id])
                device.findObject(By.desc("Close tab AUDIT BETA")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Close tab")), 5000))
                device.findObject(By.text("Close tab")).click()
                device.waitForIdle()
                assertEquals(1, service.sessions.size)
                assertNull(service.reviewedTextDrafts[beta.id])
                assertFalse(beta.session.isRunning)
                shot("candidate-close-confirmed")
            }
        } finally { instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) } }
    }

    @Test fun linuxRemovalRequiresTypedConfirmationAndKeepsSharedFiles() {
        requireEmulator()
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        val live = fixture(service, "AUDIT LINUX")
        val root = Userland.rootfsDir(context, Distro.Ubuntu)
        check(!Userland.installedMarker(context, Distro.Ubuntu).exists())
        val marker = Userland.installedMarker(context, Distro.Ubuntu).apply {
            parentFile!!.mkdirs(); writeText("ubuntu synthetic-audit schema=3\n")
        }
        val shared = File(context.getExternalFilesDir(null), "audit-shared-file.txt").apply { writeText("KEEP_ME") }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("⚙")), 10000))
                device.findObject(By.text("⚙")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Linux & connections")), 5000))
                device.findObject(By.text("Linux & connections")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Manage Linux")), 5000))
                device.findObject(By.text("Manage Linux")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Uninstall")), 5000))
                device.findObject(By.text("Uninstall")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Keep Linux")), 5000))
                assertTrue(marker.exists())
                assertTrue(live.session.isRunning)
                device.findObject(By.text("Keep Linux")).click()
                device.findObject(By.text("Uninstall")).click()
                assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5000))
                device.findObject(By.clazz("android.widget.EditText")).text = "remove"
                device.findObject(By.text("Remove Linux")).click()
                device.waitForIdle()
                assertTrue("Wrong confirmation must keep Linux files", marker.exists())
                assertTrue("Wrong confirmation must keep the terminal alive", live.session.isRunning)
                device.findObject(By.clazz("android.widget.EditText")).text = "REMOVE"
                shot("candidate-remove-confirmation")
                device.findObject(By.text("Remove Linux")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Set up Pocket Shell")), 8000))
                assertFalse(root.exists())
                assertTrue(service.sessions.isEmpty())
                assertEquals("KEEP_ME", shared.readText())
                device.findObject(By.text("Cancel")).click()
                device.findObject(By.text("Settings and diagnostics")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Pocket Shell Settings")), 5000))
                shot("candidate-settings-without-linux")
            }
        } finally {
            shared.delete()
            root.deleteRecursively()
            instrumentation.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }
}
