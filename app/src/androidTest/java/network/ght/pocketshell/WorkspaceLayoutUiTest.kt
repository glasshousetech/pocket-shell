package network.ght.pocketshell

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The terminal tools at the end of the collapsible toolbar, and the sizes a person sets by
 * dragging: the workspace menu's width and the toolbar's key size. Every size is read back three
 * ways: on screen, from the saved preference, and again after the Activity is recreated.
 */
class WorkspaceLayoutUiTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = inst.targetContext
    private val device get() = UiDevice.getInstance(inst)
    private val shots get() = File(ctx.filesDir, "screenshots").apply { mkdirs() }

    private fun fixture(service: TermService): TermSession {
        lateinit var fixture: TermSession
        inst.runOnMainSync {
            service.sessions.toList().forEach(service::closeSession)
            val shell = service.newSession(SessionMode.SYSTEM)
            shell.session.updateSize(80, 24)
            fixture = TermSession(shell.id, shell.label, shell.alive, shell.session, SessionMode.LINUX, shell.tabId)
            service.sessions[0] = fixture
            service.selectedSessionId.value = fixture.id
        }
        return fixture
    }

    private fun resetLayout() {
        WorkspacePrefs.setWorkspaceCollapsed(ctx, false)
        WorkspacePrefs.setSidebarWidth(ctx, WorkspacePrefs.SIDEBAR_DEFAULT_DP)
        WorkspacePrefs.setToolbarKeySize(ctx, WorkspacePrefs.KEY_SIZE_DEFAULT_DP)
        WorkspacePrefs.setToolbarOpen(ctx, true)
        ExtraKeysLayouts.savePreset(ctx, ExtraKeysLayouts.DEFAULT)
    }

    /** Reads from a clean accessibility cache; on API 34 a cached node can predate the last gesture. */
    private fun find(what: String, selector: androidx.test.uiautomator.BySelector, timeoutMs: Long = 8000): UiObject2 {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            ToolbarRobot.refresh()
            device.findObject(selector)?.let { found -> if (runCatching { found.visibleBounds }.isSuccess) return found }
            if (System.currentTimeMillis() >= deadline) throw AssertionError("$what is not on screen")
            Thread.sleep(60)
        }
    }

    private fun gone(selector: androidx.test.uiautomator.BySelector, timeoutMs: Long = 4000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ToolbarRobot.refresh()
            if (!device.hasObject(selector)) return true
            Thread.sleep(60)
        }
        return false
    }

    private fun visible(selector: androidx.test.uiautomator.BySelector): Boolean { ToolbarRobot.refresh(); return device.hasObject(selector) }

    private fun waitFor(what: String, timeoutMs: Long = 6000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $what")
    }

    private fun shot(name: String) { device.waitForIdle(); assertTrue(device.takeScreenshot(File(shots, name))) }

    private fun hideKeyboard(activity: ActivityScenario<MainActivity>) = activity.onActivity {
        (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
    }

    @Test fun terminalToolsSitAtTheEndOfTheCollapsibleToolbar() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        fixture(service)
        resetLayout()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(ToolbarRobot.workspaceReady(device))
                hideKeyboard(activity)
                // The fixed row above the keys is gone: the row opens on the typing keys, and the
                // tools are nowhere on screen until it is scrolled to its end.
                find("ESC", By.text("ESC"))
                listOf("History ↑", "History ↓", "Actions", "Text / voice").forEach {
                    assertFalse("$it no longer sits in a fixed row", visible(By.text(it)))
                }
                shot("toolbar-start-phone.png")

                val textVoice = ToolbarRobot.tool(device, "Text / voice")
                ToolbarRobot.refresh()
                val order = listOf("History ↑", "History ↓", "Live", "Actions", "Text / voice")
                    .mapNotNull { label -> device.findObject(By.text(label))?.visibleCenter?.x?.let { label to it } }
                assertTrue("Several tools are visible together at the end of the row: $order", order.size >= 2)
                assertEquals("The tools keep their order", order.map { it.second }.sorted(), order.map { it.second })
                assertEquals("Text / voice is the last key", "Text / voice", order.last().first)
                val row = find("the key row", By.desc(ToolbarRobot.ROW))
                assertNotNull(row)
                assertTrue(visible(By.text("Text / voice")))
                shot("toolbar-tools-at-end-phone.png")
                assertNotNull(textVoice)

                // Folding the toolbar is remembered, and the tools come back with it.
                find("the handle", By.desc(ToolbarRobot.HANDLE)).click()
                assertTrue(gone(By.desc(ToolbarRobot.ROW)))
                assertFalse(WorkspacePrefs.toolbarOpen(ctx))
                shot("toolbar-folded-phone.png")
                activity.recreate()
                assertTrue(ToolbarRobot.workspaceReady(device))
                hideKeyboard(activity)
                assertFalse("The toolbar stays folded after recreation", visible(By.desc(ToolbarRobot.ROW)))
                ToolbarRobot.click(device, "Actions")
                assertTrue(WorkspacePrefs.toolbarOpen(ctx))
                find("the Actions panel", By.text("Saved commands"))
            }
        } finally {
            resetLayout()
            inst.runOnMainSync { service.workbench.close(); service.sessions.toList().forEach(service::closeSession) }
        }
    }

    @Test fun workspaceMenuResizesByDragAndRemembersItsWidth() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        fixture(service)
        resetLayout()
        try {
            // An unfolded inner screen: 840 dp wide at 2 px per dp.
            device.executeShellCommand("wm density 320"); device.executeShellCommand("wm size 1680x1800")
            val px = 2
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                find("the open menu", By.text("WORKSPACES"), 10000)
                hideKeyboard(activity)
                fun handle() = find("the menu's resize grip", By.desc("Resize workspace menu"))
                fun handleLeftDp() = handle().visibleBounds.left / px
                fun dragHandleTo(x: Int) {
                    val b = handle().visibleBounds
                    device.swipe(b.centerX(), b.centerY(), x, b.centerY(), 40)
                }
                fun assertWidth(expected: Int, why: String) {
                    waitFor("$why: saved width $expected dp, was ${WorkspacePrefs.sidebarWidth(ctx)}") { WorkspacePrefs.sidebarWidth(ctx) == expected }
                    waitFor("$why: grip at $expected dp, was ${handleLeftDp()}") { abs(handleLeftDp() - expected) <= 1 }
                }
                assertWidth(WorkspacePrefs.SIDEBAR_DEFAULT_DP, "default")
                shot("workspace-menu-default-foldable.png")

                // Wider. The gesture loses the touch slop, so read the result rather than assume it.
                dragHandleTo(handle().visibleBounds.centerX() + 240)
                waitFor("menu grew") { WorkspacePrefs.sidebarWidth(ctx) > WorkspacePrefs.SIDEBAR_DEFAULT_DP + 80 }
                val wide = WorkspacePrefs.sidebarWidth(ctx)
                assertTrue("A 120 dp drag widens the menu by about that much: $wide", wide in 300..345)
                assertWidth(wide, "after dragging wider")
                shot("workspace-menu-wide-foldable.png")
                activity.recreate()
                find("the open menu", By.text("WORKSPACES"), 10000)
                hideKeyboard(activity)
                assertWidth(wide, "after recreation")

                // Both ends hold: the active tab keeps room for its name, and the terminal keeps its width.
                dragHandleTo(4)
                assertWidth(WorkspacePrefs.SIDEBAR_MIN_DP, "dragged past the minimum")
                shot("workspace-menu-narrow-foldable.png")
                dragHandleTo(1676)
                assertWidth(840 - WorkspacePrefs.TERMINAL_MIN_DP, "dragged past the maximum")
                shot("workspace-menu-widest-foldable.png")

                // Collapsing swaps the grip for the slim rail; expanding restores the chosen width.
                find("collapse", By.desc("Collapse workspace menu")).click()
                assertTrue(gone(By.desc("Resize workspace menu")))
                find("expand", By.desc("Expand workspace menu")).click()
                assertWidth(840 - WorkspacePrefs.TERMINAL_MIN_DP, "after collapse and expand")

                // Settings sets the same value and the open menu follows it without a restart.
                find("Settings", By.desc("Settings")).click()
                find("Appearance", By.text("Appearance")).click()
                val reset = By.text("Reset layout sizes")
                repeat(8) { if (!visible(reset)) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f); device.waitForIdle() } }
                find("Reset layout sizes", reset).click()
                waitFor("reset saved") { WorkspacePrefs.sidebarWidth(ctx) == WorkspacePrefs.SIDEBAR_DEFAULT_DP }
                device.pressBack()
                assertWidth(WorkspacePrefs.SIDEBAR_DEFAULT_DP, "after Reset layout sizes")
            }
        } finally {
            device.executeShellCommand("wm size reset"); device.executeShellCommand("wm density reset")
            resetLayout()
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }

    @Test fun toolbarKeysResizeByDraggingTheHandleAndFromItsDialog() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val service = (serviceRule.bindService(Intent(ctx, TermService::class.java)) as TermService.LocalBinder).service
        fixture(service)
        resetLayout()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                assertTrue(ToolbarRobot.workspaceReady(device))
                hideKeyboard(activity)
                val density = ctx.resources.displayMetrics.density
                // The key row and its handle, measured from the row's top edge to the handle's bottom
                // edge: a key, the row's 8 dp padding twice, and the 20 dp handle. Neither edge
                // moves with the keyboard, and neither is affected by touch-target expansion.
                fun toolbarHeightDp() = (find("the handle", By.desc(ToolbarRobot.HANDLE)).visibleBounds.bottom -
                    find("the key row", By.desc(ToolbarRobot.ROW)).visibleBounds.top) / density
                fun assertKeys(expected: Int, why: String) {
                    waitFor("$why: saved size $expected dp, was ${WorkspacePrefs.toolbarKeySize(ctx)}") { WorkspacePrefs.toolbarKeySize(ctx) == expected }
                    waitFor("$why: toolbar is ${expected + 36} dp tall, was ${toolbarHeightDp()}") { abs(toolbarHeightDp() - (expected + 36)) <= 2f }
                }
                assertKeys(WorkspacePrefs.KEY_SIZE_DEFAULT_DP, "default")

                // Pull the handle up: the toolbar is anchored to the bottom, so the keys grow.
                val handle = find("the handle", By.desc(ToolbarRobot.HANDLE)).visibleBounds
                device.swipe(handle.centerX(), handle.centerY(), handle.centerX(), handle.centerY() - (40 * density).toInt(), 40)
                waitFor("keys grew") { WorkspacePrefs.toolbarKeySize(ctx) > WorkspacePrefs.KEY_SIZE_DEFAULT_DP + 16 }
                val larger = WorkspacePrefs.toolbarKeySize(ctx)
                assertTrue("A 40 dp pull enlarges the keys by about that much: $larger", larger in 66..90)
                assertKeys(larger, "after dragging")
                assertTrue("The toolbar is still open after a drag", visible(By.desc(ToolbarRobot.ROW)))
                shot("toolbar-keys-larger-phone.png")
                activity.recreate()
                assertTrue(ToolbarRobot.workspaceReady(device))
                hideKeyboard(activity)
                assertKeys(larger, "after recreation")

                // The same size is adjustable from the keys dialog, in fixed steps.
                find("the handle", By.desc(ToolbarRobot.HANDLE)).longClick()
                // On a short screen the size controls start below the presets; Close stays pinned.
                val smaller = By.text("Smaller keys")
                repeat(8) { if (!visible(smaller)) { device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.6f); device.waitForIdle() } }
                find("the keys dialog", By.textStartsWith("Key size: $larger dp"))
                find("Smaller keys", smaller).click()
                find("the updated size", By.textStartsWith("Key size: ${larger - 4} dp"))
                find("Close, pinned below the scrolling settings", By.text("Close")).click()
                assertKeys(larger - 4, "after Smaller keys")

                // A long pull stops at the largest size instead of swallowing the terminal.
                val again = find("the handle", By.desc(ToolbarRobot.HANDLE)).visibleBounds
                device.swipe(again.centerX(), again.centerY(), again.centerX(), again.centerY() - (120 * density).toInt(), 60)
                assertKeys(WorkspacePrefs.KEY_SIZE_MAX_DP, "dragged past the maximum")
                shot("toolbar-keys-largest-phone.png")
                WorkspacePrefs.setToolbarKeySize(ctx, 1)
                assertEquals(WorkspacePrefs.KEY_SIZE_MIN_DP, WorkspacePrefs.toolbarKeySize(ctx))
            }
        } finally {
            resetLayout()
            inst.runOnMainSync { service.sessions.toList().forEach(service::closeSession) }
        }
    }
}
