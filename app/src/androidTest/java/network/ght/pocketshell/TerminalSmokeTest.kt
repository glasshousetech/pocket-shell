package network.ght.pocketshell

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the bug that made every basic command (including
 * `help`) go silently unresponsive: [TermService.newSession]'s onRedraw
 * closure read a `lateinit` session holder before it was assigned, killing
 * the PTY reader thread on first output with no visible error anywhere. This
 * exercises that exact path — a real bound TermService, a real spawned
 * shell — rather than TermCore in isolation, since TermCore itself was never
 * the buggy piece.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSmokeTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun basicCommandRoundTripsThroughTermService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val binder = serviceRule.bindService(Intent(context, TermService::class.java))
        val service = (binder as TermService.LocalBinder).service

        val holder = service.newSession(SessionMode.SYSTEM)
        holder.session.updateSize(80, 24)

        val sentinel = "POCKETSHELL_SMOKE_${System.nanoTime()}"
        Thread.sleep(500) // let the shell finish starting before writing
        holder.session.write("echo $sentinel\n")

        val deadline = System.currentTimeMillis() + 8_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            val text = holder.session.emulator?.screen?.transcriptText.orEmpty()
            found = text.contains(sentinel)
            if (!found) Thread.sleep(150)
        }

        service.closeSession(holder)
        assertTrue("Expected '$sentinel' to round-trip through a real shell session", found)
    }
}
