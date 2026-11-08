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

        ActivityScenario.launch(MainActivity::class.java).use {
            // First run gates the whole UI behind a distro install that cannot be
            // dismissed, so take Alpine — a ~3 MB rootfs — to get past it.
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

            device.findObject(By.text("⚙"))?.click()
            device.wait(Until.findObject(By.text("AI Copilot")), 5_000)
            capture("settings")

            // The Keyboard section sits below the AI block in the scrolling dialog.
            repeat(6) {
                if (device.findObject(By.text("Keyboard")) == null) {
                    device.swipe(
                        device.displayWidth / 2, (device.displayHeight * 0.72).toInt(),
                        device.displayWidth / 2, (device.displayHeight * 0.38).toInt(), 12,
                    )
                    device.waitForIdle()
                }
            }
            capture("settings_keyboard")
            device.pressBack()
            device.waitForIdle()

            device.findObject(By.text("AI"))?.click()
            device.waitForIdle()
            capture("copilot_panel")
            device.findObject(By.text("AI"))?.click() // close it again
            device.waitForIdle()

            device.findObject(By.text("Theme"))?.click()
            device.waitForIdle()
            capture("theme_picker")
            device.pressBack()
            device.waitForIdle()

            device.findObject(By.text("Connect"))?.click()
            device.waitForIdle()
            capture("connections")
            device.pressBack()
        }

        Log.i(TAG, "final dir listing: ${outDir.listFiles()?.joinToString { it.name }}")
    }

    private companion object {
        const val TAG = "PocketShellScreenshotTest"
    }
}
