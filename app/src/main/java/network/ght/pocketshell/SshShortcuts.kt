package network.ght.pocketshell

import android.system.Os
import java.io.File

/** A GHT machine reached through the agent droplet. [id] is the `ght-hop` target. */
data class GhtMachine(
    val id: String,
    val name: String,
    val aliases: List<String>,
    val detail: String,
)

/**
 * GHT machine shortcuts: the Connect cards and the typed aliases
 * (`ssh alientop.ght.network`, `ssh mob`, `ssh dev`, ...).
 *
 * Every machine is reached through the agent droplet, which already holds the
 * onward keys, so the phone's key only has to be enrolled on gh-cloud-01.
 * `ght-hop` on the droplet tries each known route (mesh, WireGuard, reverse
 * tunnel) and keeps the remote shell in a droplet tmux session, so a dropped
 * signal reattaches to the same shell.
 *
 * The aliases live in an app-managed file included from ~/.ssh/config, so they
 * arrive with app updates and never overwrite the user's own hosts.
 */
object SshShortcuts {
    const val INCLUDE_LINE = "Include config.d/*.conf"
    const val MANAGED_FILE = "config.d/pocketshell-ght.conf"
    private const val HOP = "~/.local/bin/ght-hop"
    private val droplet = SshProfiles.agentDroplet
    private val dropletAliases = listOf("agent", "agent.ght.network", "gh-cloud-01", "droplet", "ght")

    /** Written when ~/.ssh/config does not exist yet (the pre-shortcuts default, plus the Include). */
    const val DEFAULT_CONFIG = "Host *\n  ServerAliveInterval 30\n  ServerAliveCountMax 3\n" +
        "  TCPKeepAlive yes\n  IdentitiesOnly yes\n  IdentityFile ~/.ssh/id_ed25519\n"

    val machines = listOf(
        GhtMachine("alientop", "Laptop · GHT-AlienTop", listOf("alientop", "alientop.ght.network", "laptop"),
            "Connor's Alienware"),
        GhtMachine("mob", "MOB server", listOf("mob", "mob.ght.network"), "GHT-MOB-SERVER build + Recall worker"),
        GhtMachine("dev", "Dev server", listOf("dev", "gh-cloud-2", "ght-dev-server"), "gh-cloud-2 · builds and experiments"),
        GhtMachine("deploy", "Deploy server", listOf("deploy", "ght-deploy", "ght-deploy-server"), "Vultr · OnlyTechs live traffic"),
        GhtMachine("sfo", "SFO VPN node", listOf("sfo", "gh-cloud-vpn-sfo"), "gh-cloud-vpn-sfo"),
    )

    /** One-tap Connect profile: droplet login that runs `ght-hop <id>` (tmux is handled by ght-hop). */
    fun profile(machine: GhtMachine) = SshProfile(
        name = machine.name,
        host = droplet.host,
        user = droplet.user,
        port = droplet.port,
        tmuxSession = "",
        remoteCommand = "$HOP ${machine.id}",
    )

    fun managedConfig(): String = buildString {
        append("# Managed by Pocket Shell. Rewritten every time the app starts, so edits here are lost.\n")
        append("# Put your own hosts in ~/.ssh/config. Machines hop through gh-cloud-01 with ght-hop.\n\n")
        append("Host ${dropletAliases.joinToString(" ")}\n")
        append("    HostName ${droplet.host}\n    User ${droplet.user}\n\n")
        machines.forEach { m ->
            append("# ${m.name} (${m.detail})\n")
            append("Host ${m.aliases.joinToString(" ")}\n")
            append("    HostName ${droplet.host}\n    User ${droplet.user}\n")
            append("    RequestTTY force\n    RemoteCommand $HOP ${m.id}\n\n")
        }
        append("Host ${(dropletAliases + machines.flatMap { it.aliases }).joinToString(" ")}\n")
        append("    IdentityFile ~/.ssh/id_ed25519\n")
        append("    StrictHostKeyChecking accept-new\n")
        append("    ServerAliveInterval 20\n    ServerAliveCountMax 3\n")
    }

    /**
     * Returns [existing] with the Include as its first directive (OpenSSH applies
     * an Include inside a Host block only to that block), or null when no change
     * is needed. A missing config gets the default block.
     */
    fun withInclude(existing: String?): String? {
        if (existing.isNullOrBlank()) return "$INCLUDE_LINE\n\n$DEFAULT_CONFIG"
        val firstDirective = existing.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (firstDirective == INCLUDE_LINE) return null
        val rest = existing.lineSequence().filterNot { it.trim() == INCLUDE_LINE }.joinToString("\n")
        return "$INCLUDE_LINE\n\n${rest.trimStart('\n')}"
    }

    /** Writes the managed aliases into the guest's /root/.ssh. Cheap and idempotent. */
    fun install(rootfs: File) {
        val sshDir = File(rootfs, "root/.ssh").apply { mkdirs() }
        Os.chmod(sshDir.absolutePath, 448) // 0700
        File(sshDir, "config.d").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        writeIfChanged(File(sshDir, MANAGED_FILE), managedConfig())
        val config = File(sshDir, "config")
        withInclude(config.takeIf { it.isFile }?.readText())?.let { writeIfChanged(config, it) }
        Os.chmod(config.absolutePath, 384) // 0600: ssh rejects a group/world-writable config
    }

    private fun writeIfChanged(target: File, text: String) {
        if (!(target.isFile && target.readText() == text)) {
            val temp = File(target.parentFile, ".${target.name}.tmp")
            temp.writeText(text)
            Os.chmod(temp.absolutePath, 384)
            if (!temp.renameTo(target)) {
                target.writeText(text)
                temp.delete()
            }
        }
        Os.chmod(target.absolutePath, 384)
    }
}
