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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class ConnectionLibraryTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private fun isolated() { assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    @Test fun migratesLegacyProfileAndPreservesMultipleProfilesOnReload() {
        isolated()
        val prefs = ctx.getSharedPreferences("ssh_profiles", 0)
        val old = prefs.all.toMap()
        try {
            prefs.edit().clear().putString("name", "Legacy server").putString("host", "legacy.example.test").putInt("port", 2222).commit()
            val legacy = ConnectionStore.load(ctx).getOrThrow().single()
            assertEquals("legacy.example.test", legacy.profile.host)
            assertEquals(2222, legacy.profile.port)
            val second = SavedConnection(UUID.randomUUID().toString(), SshProfile("New server", "new.example.test"))
            ConnectionStore.save(ctx, second).getOrThrow()
            ConnectionStore.save(ctx, legacy.copy(favorite = true)).getOrThrow()
            val loaded = ConnectionStore.load(ctx).getOrThrow()
            assertEquals(2, loaded.size)
            assertTrue(loaded.first { it.id == legacy.id }.favorite)
            assertEquals("legacy.example.test", prefs.getString("host", null))
            prefs.edit().putString("library_v1", "CORRUPT_FIXTURE").commit()
            assertTrue(ConnectionStore.load(ctx).isFailure)
            assertTrue(ConnectionStore.save(ctx, second).isFailure)
            assertEquals("CORRUPT_FIXTURE", prefs.getString("library_v1", null))
        } finally {
            val edit = prefs.edit().clear()
            old.forEach { (key, value) -> when(value) { is String -> edit.putString(key,value); is Int -> edit.putInt(key,value); is Boolean -> edit.putBoolean(key,value) } }
            edit.commit()
        }
    }

    @Test fun createFavoriteSearchReloadAndRemoveUsingTheRealUi() {
        isolated()
        device.executeShellCommand("pm grant ${ctx.packageName} android.permission.POST_NOTIFICATIONS")
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80,24)
            service.sessions[0] = TermSession(shell.id,shell.label,shell.alive,shell.session,SessionMode.LINUX,shell.tabId)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(device.wait(Until.hasObject(By.text("Connect")),8000))
                device.findObject(By.text("Connect")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Add connection")),8000))
                device.findObject(By.text("Add connection")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Connection details")),5000))
                val inputs = device.findObjects(By.clazz("android.widget.EditText"))
                assertEquals(5, inputs.size)
                inputs[0].text = "Audit UI server"
                inputs[1].text = "audit.example.test"
                // Accessibility setText does not open the IME; Back would dismiss the editor.
                if (!device.hasObject(By.text("Save connection"))) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Save connection")
                device.takeScreenshot(File(File(ctx.filesDir,"screenshots").apply{mkdirs()},"connection-editor.png"))
                device.findObject(By.text("Save connection")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Audit UI server")),8000))
                device.findObject(By.desc("Favorite Audit UI server")).click()
                assertTrue(device.wait(Until.hasObject(By.desc("Unfavorite Audit UI server")),8000))
                activity.recreate()
                assertTrue(device.wait(Until.hasObject(By.text("Audit UI server")),8000))
                assertTrue(device.hasObject(By.desc("Unfavorite Audit UI server")))
                device.findObject(By.clazz("android.widget.EditText")).text = "no-such-connection"
                assertTrue(device.wait(Until.hasObject(By.text("No matching connections.")),5000))
                device.findObject(By.clazz("android.widget.EditText")).text = "Audit UI server"
                if (!device.hasObject(By.text("Remove"))) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Remove")
                assertTrue(device.wait(Until.hasObject(By.text("Remove")),5000))
                device.findObject(By.text("Remove")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Keep connection")),5000))
                device.findObject(By.text("Keep connection")).click()
                assertTrue(ConnectionStore.load(ctx).getOrThrow().any { it.profile.name == "Audit UI server" })
                val dir = File(ctx.filesDir,"screenshots").apply{mkdirs()}
                device.takeScreenshot(File(dir,"connection-library.png"))
                device.findObject(By.text("Remove")).click()
                // The confirmation is a new dialog; wait for it like the first Remove above.
                val confirm = device.wait(Until.findObject(By.text("Remove connection")),5000)
                assertNotNull("Removal asks for confirmation", confirm)
                confirm.click()
                assertTrue(device.wait(Until.hasObject(By.text("No matching connections.")),5000))
                assertFalse(ConnectionStore.load(ctx).getOrThrow().any { it.profile.name == "Audit UI server" })
            }
        } finally {
            ConnectionStore.load(ctx).getOrThrow().filter { it.profile.name == "Audit UI server" }.forEach { ConnectionStore.delete(ctx,it.id) }
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }
}
