package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshProfileTest {

    @Test
    fun agentDropletUsesDedicatedIdentityAndPersistentWorkspace() {
        val command = SshProfiles.agentDroplet.command()

        assertTrue(command.contains("-i ~/.ssh/id_ed25519"))
        assertTrue(command.contains("-o IdentitiesOnly=yes"))
        assertTrue(command.contains("-o StrictHostKeyChecking=accept-new"))
        assertTrue(command.contains("connor@agent.ght.network"))
        assertTrue(command.endsWith("\"tmux new-session -A -s agents\""))
    }

    @Test
    fun customProfileCanConnectWithoutTmux() {
        assertEquals(
            "ssh -t -p 2222 -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes " +
                "-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 " +
                "-o ServerAliveCountMax=3 -o ConnectTimeout=15 dev@example.test",
            SshProfile("Dev", "example.test", "dev", 2222, "").command(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellInjectionInHostname() {
        SshProfile("Bad", "example.test;touch /tmp/pwned").command()
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellInjectionInTmuxName() {
        SshProfile("Bad", "example.test", tmuxSession = "agents;whoami").command()
    }
}
