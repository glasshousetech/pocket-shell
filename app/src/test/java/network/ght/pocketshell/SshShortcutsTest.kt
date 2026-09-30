package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshShortcutsTest {

    @Test
    fun machineProfileHopsThroughDropletWithoutTmux() {
        val laptop = SshShortcuts.machines.first { it.id == "alientop" }
        val command = SshShortcuts.profile(laptop).command()

        assertTrue(command.endsWith("connor@agent.ght.network '~/.local/bin/ght-hop alientop'"))
        assertTrue(command.contains("-i ~/.ssh/id_ed25519"))
        assertFalse(command.contains("tmux"))
    }

    @Test
    fun everyMachineHasAUniqueHopTargetAndAliases() {
        val ids = SshShortcuts.machines.map { it.id }
        assertEquals(listOf("alientop", "mob", "dev", "deploy", "sfo"), ids)
        val aliases = SshShortcuts.machines.flatMap { it.aliases }
        assertEquals(aliases.size, aliases.toSet().size)
        SshShortcuts.machines.forEach { SshShortcuts.profile(it).command() } // all validate
    }

    @Test
    fun managedConfigDefinesTypedShortcuts() {
        val config = SshShortcuts.managedConfig()

        assertTrue(config.contains("Host alientop alientop.ght.network laptop\n"))
        assertTrue(config.contains("    RemoteCommand ~/.local/bin/ght-hop alientop\n"))
        assertTrue(config.contains("    RemoteCommand ~/.local/bin/ght-hop mob\n"))
        assertTrue(config.contains("Host agent agent.ght.network gh-cloud-01 droplet ght\n    HostName agent.ght.network\n    User connor\n"))
        assertEquals(SshShortcuts.machines.size, Regex("RequestTTY force").findAll(config).count())
        assertTrue(config.contains("    IdentityFile ~/.ssh/id_ed25519\n"))
    }

    @Test
    fun missingConfigGetsIncludeThenDefaults() {
        assertEquals("${SshShortcuts.INCLUDE_LINE}\n\n${SshShortcuts.DEFAULT_CONFIG}", SshShortcuts.withInclude(null))
        assertEquals("${SshShortcuts.INCLUDE_LINE}\n\n${SshShortcuts.DEFAULT_CONFIG}", SshShortcuts.withInclude("  \n"))
    }

    @Test
    fun includeIsPrependedOnceAndUserHostsKept() {
        val user = "Host myserver\n    HostName 10.0.0.2\n\nHost *\n  IdentitiesOnly yes\n"
        val updated = SshShortcuts.withInclude(user)!!

        assertTrue(updated.startsWith("${SshShortcuts.INCLUDE_LINE}\n\nHost myserver\n"))
        assertTrue(updated.contains("Host *\n  IdentitiesOnly yes"))
        assertNull(SshShortcuts.withInclude(updated))
    }

    @Test
    fun includeInsideAHostBlockIsMovedToTheTop() {
        val misplaced = "# mine\nHost a\n    HostName a.test\n${SshShortcuts.INCLUDE_LINE}\n"
        val updated = SshShortcuts.withInclude(misplaced)!!

        assertTrue(updated.startsWith("${SshShortcuts.INCLUDE_LINE}\n\n# mine\nHost a\n"))
        assertEquals(1, Regex(Regex.escape(SshShortcuts.INCLUDE_LINE)).findAll(updated).count())
    }

    @Test
    fun leadingCommentsDoNotHideAnExistingInclude() {
        assertNull(SshShortcuts.withInclude("# comment\n\n${SshShortcuts.INCLUDE_LINE}\nHost x\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShellInjectionInRemoteCommand() {
        SshProfile("Bad", "agent.ght.network", "connor", remoteCommand = "ght-hop dev; rm -rf ~").command()
    }
}
