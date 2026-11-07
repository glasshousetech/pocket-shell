package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test

class ConnectionDiagnosticsTest {
    private val profile = SshProfile("Test", "server.example", "test")
    private fun result(text: String, code: Int = 255) = ConnectionDiagnostics.classify(GuestResult(code, text), profile)

    @Test fun diagnosisDistinguishesActionableFailures() {
        assertEquals("dns", result("ssh: Could not resolve hostname server: Name or service not known").category)
        assertEquals("refused", result("ssh: connect to host server port 22: Connection refused").category)
        assertEquals("route", result("Network is unreachable").category)
        assertEquals("timeout", result("Connection timed out").category)
        assertEquals("host_changed", result("REMOTE HOST IDENTIFICATION HAS CHANGED!\nHost key verification failed.").category)
        assertEquals("host_trust", result("Host key verification failed.").category)
        assertEquals("authentication", result("test@server: Permission denied (publickey).").category)
        assertEquals("ssh_failed", result("Connection closed by remote host").category)
    }

    @Test fun readinessNeedsActualRemoteMarkersAndSuccessfulExit() {
        assertFalse(result("__POCKET_AUTH_OK__\n__POCKET_TMUX_OK__", 1).successful)
        assertEquals("tmux_missing", result("__POCKET_AUTH_OK__\n__POCKET_TMUX_MISSING__", 0).category)
        assertTrue(result("__POCKET_AUTH_OK__\n__POCKET_TMUX_OK__", 0).successful)
        assertFalse(result("debug: sent command containing __POCKET_AUTH_OK__", 0).successful)
        val hop = profile.copy(remoteCommand = "ght-hop mobile")
        assertEquals("first_hop", ConnectionDiagnostics.classify(GuestResult(0, "__POCKET_AUTH_OK__\n__POCKET_TMUX_OK__"), hop).category)
    }

    @Test fun probeCannotReplaceHostTrustOrPromptForSecrets() {
        val command = ConnectionDiagnostics.command(profile, 240)
        assertTrue(command.contains("StrictHostKeyChecking=yes"))
        assertTrue(command.contains("UpdateHostKeys=no"))
        assertTrue(command.contains("BatchMode=yes"))
        assertTrue(command.contains("ConnectTimeout=240"))
        assertFalse(command.contains("accept-new"))
        assertThrows(IllegalArgumentException::class.java) { ConnectionDiagnostics.command(profile.copy(host = "-bad"), 120) }
    }
}
