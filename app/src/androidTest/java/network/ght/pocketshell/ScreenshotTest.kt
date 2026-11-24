package network.ght.pocketshell

import android.graphics.Bitmap
import android.util.Log
import android.Manifest
import android.os.Build
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Verifies fresh Alpine setup reaches a terminal, then captures real app screens.
 * The screenshot gallery complements the behavior assertions in the native tests.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @Test
    fun captureAppScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val packageName = instrumentation.targetContext.packageName
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        // TerminalView exposes android.view.View to accessibility. Match its actual
        // rendered ready transcript instead of assuming the Java class is exported.
        val terminalSelector = By.pkg(packageName).descContains("Pocket Shell is ready.")
        // filesDir (app-private internal storage) is always non-null, unlike
        // getExternalFilesDir(null) which returned null on this AVD image and
        // silently wrote screenshots nowhere useful. Pulled off-device via
        // `adb shell run-as` in the CI job rather than a plain `adb pull`,
        // since this isn't externally-accessible storage.
        val outDir = File(instrumentation.targetContext.filesDir, "screenshots").apply { mkdirs() }
        Log.i(TAG, "outDir=${outDir.absolutePath} exists=${outDir.exists()} canWrite=${outDir.canWrite()}")
        var shot = 0
        fun capture(name: String) {
            shot++
            val bitmap: Bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
                "Screenshot capture returned no frame for $name"
            }
            val file = File(outDir, "%02d_%s.png".format(shot, name))
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "wrote ${file.absolutePath} (${file.length()} bytes)")
        }

        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            // The empty workspace opens the distro chooser. Use Alpine for this owned capture.
            device.wait(Until.findObject(By.text("Set up Ubuntu")), 5_000)?.click()
            if (device.wait(Until.findObject(By.text("Set up Pocket Shell")), 15_000) != null) {
                capture("first_run_setup")
                device.findObject(By.text("Alpine Linux"))?.click()
                val setupTimeout = InstrumentationRegistry.getArguments().getString("setupTimeoutMs")?.toLongOrNull()
                    ?: 20 * 60_000L
                require(setupTimeout > 0)
                val deadline = android.os.SystemClock.elapsedRealtime() + setupTimeout
                while (android.os.SystemClock.elapsedRealtime() < deadline &&
                    !device.hasObject(terminalSelector) &&
                    !device.hasObject(By.text("Couldn't set up Linux"))) {
                    // Wait for the terminal while also checking for a recoverable setup error.
                    device.wait(Until.hasObject(terminalSelector), 1_000)
                }
            }
            if (device.wait(Until.findObject(terminalSelector), 15_000) == null) {
                // A failed or stalled install must fail the run and retain diagnostics.
                capture("setup_failed")
                device.dumpWindowHierarchy(File(outDir, "setup-failure.xml"))
                File(outDir, "setup-diagnostics.jsonl").writeText(DiagnosticLog.read(instrumentation.targetContext))
                val messages = device.findObjects(By.pkg(instrumentation.targetContext.packageName))
                    .mapNotNull { it.text }.filter { it.isNotBlank() }
                throw AssertionError("Fresh Alpine setup did not reach a terminal: " + messages.joinToString(" | "))
            }
            device.waitForIdle() // present the actual ready frame
            capture("launch_shell")

            // Focus the terminal (window focus alone doesn't route text input;
            // it needs an explicit tap), then run a real command and capture it.
            device.findObject(terminalSelector)?.click()
            device.waitForIdle()
            device.executeShellCommand("input text echo%sPOCKETSHELL_SCREENSHOT_DEMO")
            device.pressEnter()
            val outputSelector = By.pkg(packageName).desc(Pattern.compile(
                "(?s).*\nPOCKETSHELL_SCREENSHOT_DEMO\r?\n.*",
            ))
            assertTrue("A real command must produce its own output line, not merely echo input",
                device.wait(Until.hasObject(outputSelector), 15_000))
            File(outDir, "first-use-transcript.txt").writeText(
                device.findObject(outputSelector).contentDescription.orEmpty())
            File(outDir, "first-use-diagnostics.jsonl").writeText(DiagnosticLog.read(instrumentation.targetContext))
            capture("command_output")

            fun find(selector: BySelector): UiObject2 {
                val deadline = android.os.SystemClock.uptimeMillis() + 5_000
                while (android.os.SystemClock.uptimeMillis() < deadline) {
                    ToolbarRobot.refresh()
                    device.findObject(selector)?.let { return it }
                    Thread.sleep(60)
                }
                capture("missing_control")
                throw AssertionError("Missing capture control: $selector")
            }
            fun click(label: String) { find(By.text(label)).click(); device.waitForIdle() }
            fun clickDescription(label: String) { find(By.desc(label)).click(); device.waitForIdle() }
            fun visible(label: String) { find(By.text(label)) }
            activity.onActivity {
                (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
            }
            clickDescription("Settings")
            visible("Pocket Shell Settings")
            capture("settings")

            click("Keyboard")
            visible("Customize terminal keys")
            assertTrue("Keyboard preferences must actually be visible",
                device.wait(Until.hasObject(By.textContains("Your keyboard’s voice, clipboard")), 5_000))
            capture("settings_keyboard")
            device.pressBack()
            assertTrue(device.wait(Until.gone(By.text("Pocket Shell Settings")), 5_000))

            clickDescription("Settings")
            click("AI Copilot")
            click("Open AI Copilot")
            visible("Close AI")
            capture("copilot_panel")
            click("Close AI")
            assertTrue(device.wait(Until.gone(By.text("Close AI")), 5_000))

            clickDescription("Settings")
            click("Appearance")
            click("Browse 20 themes")
            visible("Themes")
            capture("theme_picker")
            click("Close")
            assertTrue(device.wait(Until.gone(By.text("Themes")), 5_000))

            clickDescription("Connect")
            visible("Connections")
            capture("connections")
            click("Close connections")
        }

        Log.i(TAG, "final dir listing: ${outDir.listFiles()?.joinToString { it.name }}")
    }

    private companion object {
        const val TAG = "PocketShellScreenshotTest"
    }
}
