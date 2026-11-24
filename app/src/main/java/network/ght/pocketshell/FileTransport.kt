package network.ght.pocketshell

import android.content.Context

/** Uses existing OpenSSH keys/configuration at each hop, with host verification unchanged. */
object FileTransport {
    fun command(profile: SshProfile, timeoutSeconds: Long): String {
        profile.command()
        require(timeoutSeconds > 0)
        val shortcut = profile.remoteCommand.trim()
        if (shortcut.isBlank()) {
            val shell = RemoteWorkspaces.ssh(profile, "sftp", false, timeoutSeconds = timeoutSeconds)
            return "exec " + shell.replaceFirst("ssh -T ", "ssh -T -s ")
        }
        RemoteWorkspaces.scope(profile)
        val target = shortcut.removePrefix("~/.local/bin/ght-hop ")
        return "exec " + RemoteWorkspaces.ssh(profile, relayScript(target, timeoutSeconds), false, timeoutSeconds = timeoutSeconds)
    }

    /** Mirrors the live ght-hop route names; verifies machine identity before opening the subsystem. */
    fun relayScript(target: String, timeoutSeconds: Long): String {
        val routes: List<String>
        val expected: String
        when (target) {
            "alientop", "laptop" -> { routes = listOf("alientop.ght.network", "alienware-wg"); expected = "ght-alientop" }
            "mob", "mobile" -> { routes = listOf("mobile", "mobile-wg", "mobile-mesh"); expected = "ght-mob-server" }
            "dev", "gh-cloud-2", "ght-dev-server" -> { routes = listOf("ght-dev-server"); expected = "" }
            "deploy", "ght-deploy", "ght-deploy-server" -> { routes = listOf("ght-deploy-server"); expected = "" }
            "sfo", "gh-cloud-vpn-sfo" -> { routes = listOf("gh-cloud-vpn-sfo"); expected = "" }
            "public-01" -> { routes = listOf("public-01"); expected = "public-01" }
            "agent-01" -> { routes = listOf("agent-01"); expected = "agent-01" }
            "agent-prod" -> { routes = listOf("agent-prod"); expected = "ght-agents" }
            else -> error("This shortcut is not mapped for file transfers. Use a direct SSH profile.")
        }
        val options = "-T -o RemoteCommand=none -o BatchMode=yes -o NumberOfPasswordPrompts=0 " +
            "-o StrictHostKeyChecking=yes -o UpdateHostKeys=no -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=" + timeoutSeconds
        return "for route in " + routes.joinToString(" ") { CommandTemplate.quoteArgument(it) } + "; do " +
            "identity=\$(ssh -n " + options + " -- \"\$route\" hostname 2>/dev/null | tr -d '\\r\\n' | tr '[:upper:]' '[:lower:]'); " +
            "[ -n \"\$identity\" ] || continue; " +
            (if (expected.isEmpty()) "" else "[ \"\$identity\" = " + CommandTemplate.quoteArgument(expected) + " ] || continue; ") +
            "exec ssh " + options + " -s -- \"\$route\" sftp; done; " +
            "printf 'No verified SFTP route is available. Open SSH or run a connection check.\\n' >&2; exit 69"
    }

    fun open(context: Context, profile: SshProfile, timeoutSeconds: Long): SftpClient {
        val distro = Userland.installedDistro(context) ?: error("Install Linux before opening remote files.")
        val builder = ProcessBuilder(*Userland.prootExecArgs(context, distro, command(profile, timeoutSeconds)))
        builder.environment().clear()
        Userland.prootEnv(context).forEach {
            val pair = it.split("=", limit = 2); builder.environment()[pair[0]] = pair[1]
        }
        return SftpClient(builder.start(), timeoutSeconds)
    }
}
