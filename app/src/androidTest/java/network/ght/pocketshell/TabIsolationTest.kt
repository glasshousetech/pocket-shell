package network.ght.pocketshell

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TabIsolationTest {
    @get:Rule val serviceRule = ServiceTestRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun output(holder: TermSession): String {
        var text = ""
        instrumentation.runOnMainSync { text = holder.session.emulator?.screen?.transcriptText.orEmpty() }
        return text
    }
    private fun waitFor(test: () -> Boolean) {
        val until = System.currentTimeMillis() + 10000
        while (!test() && System.currentTimeMillis() < until) Thread.sleep(40)
        assertTrue("Expected terminal state within ten seconds", test())
    }
    @Test fun actualTerminalsHaveIndependentStateAndFailureDiagnostics() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val service = (serviceRule.bindService(Intent(context, TermService::class.java)) as TermService.LocalBinder).service
        lateinit var a: TermSession
        lateinit var b: TermSession
        instrumentation.runOnMainSync {
            a = service.newSession(SessionMode.SYSTEM)
            b = service.newSession(SessionMode.SYSTEM)
            a.session.updateSize(80, 24); b.session.updateSize(80, 24)
        }
        try {
            assertNotEquals(a.tabId, b.tabId)
            val direct = SshProfile("Isolation fixture", "164.92.81.184", "root", tmuxSession = "ps-direct-20261031")
            File(context.filesDir, "ssh-direct-fixture.txt").writeText(direct.command(a.tabId) + "\n" + direct.command(b.tabId) + "\n")
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                // Split markers prevent terminal command echo from satisfying the assertions.
                a.session.write("export PROBE=alpha; printf 'ID_%s_END\\n' \"\$GHT_TAB_ID\"; printf 'VALUE_%s_END\\n' \"\$PROBE\"\n")
                b.session.write("export PROBE=beta; printf 'ID_%s_END\\n' \"\$GHT_TAB_ID\"; printf 'VALUE_%s_END\\n' \"\$PROBE\"\n")
            }
            waitFor { output(a).contains("ID_" + a.tabId + "_END") && output(b).contains("ID_" + b.tabId + "_END") &&
                output(a).contains("VALUE_alpha_END") && output(b).contains("VALUE_beta_END") }
            assertTrue(output(a).contains("VALUE_alpha_END"))
            assertFalse(output(a).contains("VALUE_beta_END"))
            assertTrue(output(b).contains("VALUE_beta_END"))
            assertFalse(output(b).contains("VALUE_alpha_END"))
            instrumentation.runOnMainSync { a.session.write("exit 23\n") }
            waitFor { !a.alive.value }
            instrumentation.runOnMainSync { service.closeSession(a); b.session.write("printf 'SURVIVED_%s_END\\n' \"\$PROBE\"\n") }
            waitFor { output(b).contains("SURVIVED_beta_END") }
            val diagnostic = DiagnosticLog.read(context)
            val exits = diagnostic.lineSequence().filter { it.isNotBlank() }.map { org.json.JSONObject(it) }
            assertTrue(exits.any { it.optString("event") == "process_exit" && it.optString("tab") == a.tabId && it.optInt("exitCode") == 23 })
            assertFalse(diagnostic.contains("PROBE="))
            assertFalse(diagnostic.contains("VALUE_alpha_END"))
            File(context.filesDir, "tab-isolation-proof.txt").writeText("PASS distinct real PTYs and exported tab IDs; independent alpha/beta state; tab B survives A exit; failure23 persisted without terminal content\n" + diagnostic)
        } finally { instrumentation.runOnMainSync { if (a in service.sessions) service.closeSession(a); service.closeSession(b) } }
    }
    @Test fun snapshotsPreserveIdsAndMigrateLegacyDuplicateAndInvalidIds() {
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val id = TabIdentity.create()
        val snapshot = File(context.noBackupFilesDir, "sessions_snapshot.json")
        SessionStore.load(context) // Drain prior asynchronous writes before the legacy fixture.
        snapshot.writeText("""{"version":1,"sessions":[{"mode":"LINUX","title":"a","tabId":"$id"},{"mode":"LINUX","title":"b","tabId":"$id"},{"mode":"LINUX","title":"legacy"},{"mode":"LINUX","title":"bad","tabId":"x;invalid"}]}""")
        try {
            val saved = SessionStore.load(context)
            assertEquals(4, saved.size)
            assertEquals(4, saved.map { it.tabId }.toSet().size)
            assertEquals(id, saved.first().tabId)
            saved.forEach { TabIdentity.requireValid(it.tabId) }
            SessionStore.save(context, saved)
            assertEquals(saved.map { it.tabId }, SessionStore.load(context).map { it.tabId })
        } finally { SessionStore.clear(context) }
    }
}
