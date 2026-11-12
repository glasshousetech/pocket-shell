package network.ght.pocketshell

import android.os.SystemClock
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/**
 * Reaches the terminal tools (History, Live, Actions, Text / voice) the way a person does: they
 * sit at the far end of the collapsible key row, so the row is opened and scrolled until the
 * tool is on screen. The handle under the row is always present, which makes it the signal that
 * the terminal workspace has finished loading.
 */
object ToolbarRobot {
    const val HANDLE = "Terminal keys handle"
    const val ROW = "Terminal keys"

    fun workspaceReady(device: UiDevice, timeoutMs: Long = 10000): Boolean =
        device.wait(Until.hasObject(By.desc(HANDLE)), timeoutMs)

    /** Taps a tool, finding it again if its node went stale between the read and the tap. */
    fun click(device: UiDevice, label: String, timeoutMs: Long = 15000) {
        var last: Throwable? = null
        repeat(4) {
            try { tool(device, label, timeoutMs).click(); return }
            catch (stale: androidx.test.uiautomator.StaleObjectException) { last = stale }
        }
        throw AssertionError("\"$label\" could not be tapped", last)
    }

    private fun keyLabels(device: UiDevice): List<String>? = runCatching {
        device.findObject(By.desc(ROW))?.findObjects(By.clazz("android.widget.TextView"))?.mapNotNull { it.text }
    }.getOrNull()

    /**
     * The tool's node once it has stopped moving, or null when it is not on screen. A row that is
     * still coasting after a swipe swallows the next touch to stop itself, so a tap sent the
     * moment the label appears would never reach the key.
     */
    private fun settled(device: UiDevice, label: String): UiObject2? {
        var last: android.graphics.Rect? = null
        var steady = 0
        repeat(40) {
            val found = device.findObject(By.text(label)) ?: return null
            val bounds = runCatching { found.visibleBounds }.getOrNull()
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return null
            steady = if (bounds == last) steady + 1 else 0
            if (steady >= 3) return found
            last = bounds
            SystemClock.sleep(100)
        }
        return null
    }

    fun tool(device: UiDevice, label: String, timeoutMs: Long = 20000): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        if (!workspaceReady(device, timeoutMs)) throw AssertionError("The terminal workspace did not load")
        var towardEnd = true
        val trace = mutableListOf<String>()
        while (SystemClock.uptimeMillis() < deadline) {
            // Compose rebuilds its accessibility nodes while a scroll settles, so read the tool
            // only once the row is idle and hand back a node from that settled tree.
            device.waitForIdle()
            settled(device, label)?.let { return it }
            val row = device.findObject(By.desc(ROW))
            if (row == null) {
                // The toolbar is folded away: its handle opens it.
                trace += "row absent, tapping handle"
                device.findObject(By.desc(HANDLE))?.click()
                device.wait(Until.hasObject(By.desc(ROW)), 3000)
                continue
            }
            // Swipe along the row itself and keep going the same way until the visible keys stop
            // changing; only then turn around, so a tool the user moved toward the front is
            // still found. Direction comes from what is on screen, never from scroll events.
            val before = keyLabels(device)
            val swiped = runCatching {
                val b = row.visibleBounds
                val inset = b.width() / 8
                val y = b.top + b.height() / 3
                if (towardEnd) device.swipe(b.right - inset, y, b.left + inset, y, 24)
                else device.swipe(b.left + inset, y, b.right - inset, y, 24)
            }
            device.waitForIdle()
            val after = keyLabels(device)
            trace += "${if (towardEnd) "to end" else "to start"}: ${before?.firstOrNull()}..${before?.lastOrNull()} -> ${after?.firstOrNull()}..${after?.lastOrNull()}${swiped.exceptionOrNull()?.let { " ($it)" } ?: ""}"
            if (swiped.isSuccess && before != null && before == after) towardEnd = !towardEnd
        }
        runCatching {
            val dir = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screenshots").apply { mkdirs() }
            device.takeScreenshot(java.io.File(dir, "toolbar-robot-missing-${label.filter { it.isLetterOrDigit() }}.png"))
        }
        throw AssertionError("\"$label\" was not found in the terminal toolbar. ${trace.takeLast(10)}")
    }
}
