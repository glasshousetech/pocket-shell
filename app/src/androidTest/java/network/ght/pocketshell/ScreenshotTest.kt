package network.ght.pocketshell

import android.graphics.Bitmap
import android.util.Log
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
 * Not a correctness gate (see [TerminalSmokeTest] for that) — walks the app
 * through a handful of real screens and saves a screenshot of each, so
 * someone can see what Pocket Shell actually looks like without needing a
 * local emulator on a resource-constrained host. Manually triggered only
 * (see the `screenshots` CI job): UI-coordinate/text automation like this is
 * more brittle than the smoke test and shouldn't gate releases.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

    @Test
    fun captureAppScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
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
            val bitmap: Bitmap? = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap == null) {
                Log.w(TAG, "takeScreenshot() returned null for $name")
                return
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
                device.wait(Until.findObject(By.clazz(TERMINAL)), 240_000)
            }
            device.wait(Until.findObject(By.clazz(TERMINAL)), 15_000)
            Thread.sleep(1_500) // let the shell prompt actually render
            capture("launch_shell")

            // Focus the terminal (window focus alone doesn't route text input;
            // it needs an explicit tap), then run a real command and capture it.
            device.findObject(By.clazz(TERMINAL))?.click()
            Thread.sleep(300)
            device.executeShellCommand("input text echo%sPOCKETSHELL_SCREENSHOT_DEMO")
            device.pressEnter()
            Thread.sleep(1_000)
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
                    Thread.sleep(400)
                }
            }
            capture("settings_keyboard")
            device.pressBack()
            Thread.sleep(300)

            device.findObject(By.text("AI"))?.click()
            Thread.sleep(500)
            capture("copilot_panel")
            device.findObject(By.text("AI"))?.click() // close it again
            Thread.sleep(300)

            device.findObject(By.text("Theme"))?.click()
            Thread.sleep(500)
            capture("theme_picker")
            device.pressBack()
            Thread.sleep(300)

            device.findObject(By.text("Connect"))?.click()
            Thread.sleep(700)
            capture("connections")
            device.pressBack()
        }

        Log.i(TAG, "final dir listing: ${outDir.listFiles()?.joinToString { it.name }}")
    }

    private companion object {
        const val TAG = "PocketShellScreenshotTest"
        const val TERMINAL = "network.ght.pocketshell.term.TerminalView"
    }
}
