package network.ght.pocketshell

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Same-signer release update: runs once on the old APK, then on the new APK. */
class ThemeUpgradeTest {
    @Test fun upgradePreservesExistingThemeAndAppPrivateData() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val prefs = context.getSharedPreferences("pocketshell_prefs", android.content.Context.MODE_PRIVATE)
        val marker = File(context.filesDir, "theme-upgrade-fixture.txt")
        if (args.getString("phase") == "prepare") {
            assertTrue(prefs.edit().putString("terminal_theme", "nord").putString("theme_upgrade_neighbor", "preserve-me").commit())
            marker.writeText("existing-app-private-data")
        } else {
            assertEquals("nord", prefs.getString("terminal_theme", null))
            assertEquals("preserve-me", prefs.getString("theme_upgrade_neighbor", null))
            assertEquals("existing-app-private-data", marker.readText())
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            assertTrue("The prior release APK must have been replaced", info.longVersionCode >= 15)
        }
    }
}
