package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Geometry and real interactions for the session-list actions, including overflow. */
class SessionActionsUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private val shots get() = File(ctx.filesDir, "screenshots/session-actions").apply { mkdirs() }

    private fun find(selector: BySelector): UiObject2 {
        // API 34 can retain accessibility nodes across a Compose layout update.
        // Match WorkspaceLayoutUiTest: refresh before each read, not after waiting.
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            ToolbarRobot.refresh()
            device.findObject(selector)?.let { node ->
                if (runCatching { !node.visibleBounds.isEmpty }.getOrDefault(false)) return node
            }
            Thread.sleep(60)
        }
        shot("missing-control-${android.os.SystemClock.uptimeMillis()}")
        throw AssertionError("Missing $selector after fresh accessibility reads")
    }
    private fun shot(name: String) {
        device.waitForIdle()
        assertTrue(device.takeScreenshot(File(shots, "$name.png")))
        device.dumpWindowHierarchy(File(shots, "$name.xml"))
    }
    private fun fixture(service: TermService, count: Int) {
        inst.runOnMainSync {
            repeat(count) {
                val shell = service.newSession(SessionMode.SYSTEM)
                shell.session.updateSize(80, 24)
                // A real Android shell keeps this layout test independent of Linux downloads.
                val item = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
                item.customName.value = "Session ${service.sessions.size}"
                service.sessions[service.sessions.indexOf(shell)] = item
                service.selectedSessionId.value = item.id
            }
        }
    }
    private fun hideKeyboard(activity: ActivityScenario<MainActivity>) {
        activity.onActivity {
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
        device.waitForIdle()
    }
    private fun scrollToActions(direction: Direction = Direction.DOWN) {
        val target = (48 * ctx.resources.displayMetrics.density).toInt()
        repeat(20) {
            ToolbarRobot.refresh()
            val viewport = find(By.desc("Session tabs")).visibleBounds
            val complete = listOf("Connect", "New tab").all { label ->
                val bounds = device.findObject(By.desc(label))?.visibleBounds ?: return@all false
                bounds.width() >= target - 1 && bounds.height() >= target - 1 && viewport.contains(bounds)
            }
            if (complete) return
            find(By.desc("Session tabs")).scroll(direction, 0.8f)
        }
        fail("Trailing 48dp session actions must be reachable by scrolling the session list")
    }
    /** Isolate the first-install branch without destroying another test's synthetic guest. */
    private fun withFreshInstallState(block: () -> Unit) {
        val held = mutableListOf<Pair<File, File>>()
        try {
            Distro.ALL.forEach { distro ->
                val marker = Userland.installedMarker(ctx, distro)
                if (marker.exists()) {
                    val backup = File(ctx.cacheDir, "session-actions-${distro.id}-${java.util.UUID.randomUUID()}.marker")
                    check(marker.renameTo(backup)) { "Could not isolate the installed-guest marker" }
                    held += marker to backup
                }
            }
            assertNull("The first-install case owns its setup state", Userland.installedDistro(ctx))
            block()
        } finally {
            held.asReversed().forEach { (marker, backup) ->
                check(backup.renameTo(marker)) { "Could not restore the installed-guest marker" }
            }
        }
    }
    private fun assertBelow(last: BySelector) {
        val bottom = find(last).visibleBounds.bottom
        val connect = find(By.desc("Connect"))
        val add = find(By.desc("New tab"))
        assertTrue("Connect follows the final session", connect.visibleBounds.top >= bottom)
        assertTrue("New tab follows the final session", add.visibleBounds.top >= bottom)
        assertTrue("Connect stays enabled despite its quiet color", connect.isEnabled && connect.isClickable)
        assertTrue("New tab stays enabled despite its quiet color", add.isEnabled && add.isClickable)
        val density = ctx.resources.displayMetrics.density
        assertTrue("48dp Connect target", connect.visibleBounds.width() >= 48 * density - 1)
        assertTrue("48dp New tab target", add.visibleBounds.height() >= 48 * density - 1)
        assertFalse(device.hasObject(By.text("AI")))
        assertFalse(device.hasObject(By.text("Theme")))
        assertFalse(device.hasObject(By.text("Connect")))
        assertFalse(device.hasObject(By.text("New tab")))
    }
    private fun withWorkspace(block: (TermService, ActivityScenario<MainActivity>) -> Unit) {
        assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
        WorkspacePrefs.setSidebarWidth(ctx, WorkspacePrefs.SIDEBAR_DEFAULT_DP)
        device.executeShellCommand("wm density 320")
        device.executeShellCommand("wm size 1680x1800")
        fixture(service, 1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                find(By.text("WORKSPACES")); hideKeyboard(activity)
                block(service, activity)
            }
        } finally {
            device.executeShellCommand("wm size reset"); device.executeShellCommand("wm density reset")
            device.executeShellCommand("settings put system font_scale 1.0")
            WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }

    @Test fun inlineActionsFollowTheLastSessionAndScrollWithItInBothSidebarStates() = withWorkspace { service, activity ->
        assertBelow(By.text("Session 1"))
        shot("one-session-unfolded")
        fixture(service, 2)
        inst.runOnMainSync {
            assertEquals("Three fixture sessions exist before inspecting the UI", 3, service.sessions.size)
            assertEquals(listOf("Session 1", "Session 2", "Session 3"), service.sessions.map { it.displayName })
        }
        hideKeyboard(activity)
        assertBelow(By.text("Session 3"))
        shot("three-sessions-unfolded")
        find(By.desc("Collapse workspace menu")).click()
        assertBelow(By.desc("Tab 3: Session 3"))
        shot("three-sessions-collapsed")
        activity.recreate(); hideKeyboard(activity)
        find(By.desc("Expand workspace menu"))
        assertBelow(By.desc("Tab 3: Session 3"))
        find(By.desc("Expand workspace menu")).click()
        fixture(service, 27)
        hideKeyboard(activity)
        scrollToActions()
        assertBelow(By.text("Session 30"))
        val settingsY = find(By.desc("Settings")).visibleBounds.top
        shot("overflow-scrolled-to-actions")
        // Scrolling back to the start moves these actions away; the Settings footer stays put.
        repeat(5) { find(By.desc("Session tabs")).scroll(Direction.UP, 1f) }
        ToolbarRobot.refresh()
        assertFalse("Actions are not pinned to the toolbar", device.hasObject(By.desc("New tab")))
        assertEquals("Only Settings is pinned", settingsY, find(By.desc("Settings")).visibleBounds.top)
        shot("overflow-scrolled-to-start")
        scrollToActions()
        find(By.desc("Collapse workspace menu")).click()
        scrollToActions()
        assertBelow(By.desc("Tab 30: Session 30"))
        shot("overflow-collapsed")
    }

    @Test fun compactPhoneActionsFollowOverflowingTabsAndRemainUsable() = withWorkspace { service, activity ->
        fixture(service, 14)
        device.executeShellCommand("wm size 720x1500")
        assertTrue(device.wait(Until.gone(By.text("WORKSPACES")), 8000))
        hideKeyboard(activity)
        find(By.desc("Collapse workspace menu")).click()
        find(By.desc("Expand workspace menu"))
        scrollToActions(Direction.RIGHT)
        shot("compact-phone-overflow-actions")
        find(By.desc("Connect")).click()
        find(By.text("Add connection"))
        device.pressBack()
        // A synthetic installation marker exercises fixture isolation and restoration;
        // the separate InstalledSessionActionsUiTest proves a real working Linux guest.
        val marker = Userland.installedMarker(ctx, Distro.Alpine)
        val seeded = !marker.exists()
        if (seeded) {
            marker.parentFile!!.mkdirs()
            marker.writeText("owned-session-actions-fixture\n")
        }
        val originalMarker = marker.readBytes()
        try {
            withFreshInstallState {
                scrollToActions(Direction.RIGHT)
                find(By.desc("New tab")).click()
                find(By.text("Alpine Linux"))
                device.pressBack()
            }
            assertArrayEquals("An earlier test's marker is restored byte-for-byte", originalMarker, marker.readBytes())
        } finally {
            if (seeded) check(marker.delete()) { "Could not remove the owned synthetic marker" }
        }
        inst.runOnMainSync {
            assertEquals(15, service.sessions.size)
            assertTrue(service.sessions.all { it.alive.value })
        }
    }

    @Test fun settingsOpensAiAndThemesAndSessionActionsKeepTheirBehavior() = withWorkspace { service, activity ->
        val eventStart = java.time.Instant.now()
        find(By.desc("Connect")).click()
        find(By.text("Add connection"))
        shot("connections-from-plus")
        device.pressBack()
        withFreshInstallState {
            find(By.desc("New tab")).click()
            find(By.text("Alpine Linux"))
            shot("new-tab-first-install")
            device.pressBack()
        }
        find(By.desc("Settings")).click()
        find(By.text("AI Copilot")).click()
        find(By.text("Open AI Copilot")).click()
        find(By.text("Close AI"))
        shot("ai-opened-from-settings")
        find(By.text("Close AI")).click()
        find(By.desc("Settings")).click()
        find(By.text("Appearance")).click()
        find(By.text("Browse 20 themes")).click()
        find(By.text("Find a theme"))
        shot("themes-opened-from-settings")
        find(By.text("Close")).click()
        activity.recreate(); hideKeyboard(activity)
        find(By.text("Session 1"))
        inst.runOnMainSync { assertEquals(1, service.sessions.size); assertTrue(service.sessions.single().alive.value) }
        val freshLines = DiagnosticLog.read(ctx).lineSequence().filter { it.isNotBlank() }.filter { line ->
            val event = org.json.JSONObject(line)
            !java.time.Instant.parse(event.getString("at")).isBefore(eventStart)
        }.toList()
        val events = freshLines.map { org.json.JSONObject(it).optString("event") }.toSet()
        listOf("connections_opened", "new_tab_requested", "copilot_opened_from_settings", "themes_opened_from_settings").forEach {
            assertTrue("This test recorded $it after its own start", it in events)
        }
        File(shots, "entrypoint-diagnostics.jsonl").writeText(freshLines.joinToString("\n", postfix = "\n"))
    }
}
