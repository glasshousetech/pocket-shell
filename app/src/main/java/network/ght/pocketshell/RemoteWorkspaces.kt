package network.ght.pocketshell

import java.util.Base64

/** Includes the tmux server incarnation, so a restarted server cannot recycle the target. */
data class RemoteWorkspace(
    val id: String, val created: Long, val name: String, val windows: Int, val clients: Int,
    val serverPid: Long, val serverStarted: Long,
) {
    fun validate(): RemoteWorkspace {
        require(id.matches(Regex("\\$[0-9]+")) && created > 0 && serverPid > 0 && serverStarted > 0 && windows >= 0 && clients >= 0) {
            "The server returned an invalid workspace identity."
        }
        return this
    }
    fun sameProcess(other: RemoteWorkspace): Boolean = id == other.id && created == other.created &&
        serverPid == other.serverPid && serverStarted == other.serverStarted
}

object RemoteWorkspaces {
    private const val HEADER = "__POCKET_WORKSPACES_V2__"
    private const val END = "__POCKET_WORKSPACES_END__"

    /** GHT shortcuts retain the onward SSH process inside an outer tmux workspace. */
    fun scope(profile: SshProfile): String? {
        val command = profile.remoteCommand.trim()
        if (command.isEmpty()) return null
        val prefix = "~/.local/bin/ght-hop "
        require(command.startsWith(prefix)) { "Use a direct SSH connection or a GHT shortcut to discover workspaces." }
        val target = command.removePrefix(prefix)
        require(target.matches(Regex("[A-Za-z0-9_-]+"))) { "This shortcut has an unsupported target." }
        return "hop-$target-"
    }

    fun ssh(profile: SshProfile, remote: String, interactive: Boolean, portable: Boolean = false, timeoutSeconds: Long = 120): String {
        profile.command()
        require(timeoutSeconds > 0)
        val destination = if (profile.user.isBlank()) profile.host.trim() else profile.user.trim() + "@" + profile.host.trim()
        val identity = if (portable) "" else " -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes"
        val batch = if (interactive) "" else " -o BatchMode=yes -o NumberOfPasswordPrompts=0"
        val remoteArgument = if (portable) {
            require(interactive)
            // The payload avoids competing POSIX/PowerShell quote rules; it contains no credentials.
            val encoded = Base64.getEncoder().encodeToString(remote.toByteArray(Charsets.UTF_8))
            "\"printf %s " + encoded + " | base64 -d | sh\""
        } else CommandTemplate.quoteArgument(remote)
        return "ssh " + (if (interactive) "-t" else "-T") + " -p " + profile.port + identity + batch +
            " -o RemoteCommand=none -o StrictHostKeyChecking=yes -o UpdateHostKeys=no" +
            " -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -o ConnectTimeout=" + timeoutSeconds +
            " -- " + CommandTemplate.quoteArgument(destination) + " " + remoteArgument
    }

    fun listScript(): String = """
        export LC_ALL=C
        command -v tmux >/dev/null 2>&1 || { printf 'tmux is not installed\n' >&2; exit 69; }
        printf '$HEADER\n'
        ids=${'$'}(tmux -u list-sessions -F '#{session_id}' 2>&1)
        result=${'$'}?
        if [ "${'$'}result" -ne 0 ]; then
          case "${'$'}ids" in
            *"no server running"*|*"no sessions"*|*"error connecting to "*"No such file or directory"*)
              printf '$END\n'; exit 0 ;;
            *) printf '%s\n' "${'$'}ids" >&2; exit "${'$'}result" ;;
          esac
        fi
        for sid in ${'$'}ids; do
          metadata=${'$'}(tmux -u display-message -p -t "${'$'}sid" '#{pid} #{start_time} #{session_created} #{session_windows} #{session_attached}') || continue
          encoded=${'$'}(tmux -u display-message -p -t "${'$'}sid" '#{session_name}' | base64 | tr -d '\n')
          printf '%s %s %s\n' "${'$'}sid" "${'$'}metadata" "${'$'}encoded"
        done
        printf '$END\n'
    """.trimIndent()

    fun listCommand(profile: SshProfile, timeoutSeconds: Long = 120): String {
        scope(profile)
        return ssh(profile, listScript(), false, timeoutSeconds = timeoutSeconds)
    }

    fun parse(result: GuestResult, profile: SshProfile): List<RemoteWorkspace> {
        check(result.exitCode == 0) { if (result.exitCode == 69) "tmux is not installed on this host." else "Could not read remote workspaces. Run a connection check or open SSH for its sign-in prompt." }
        val lines = result.output.lines().map { it.trimEnd('\r') }
        val begin = lines.indexOf(HEADER)
        val end = lines.indexOfLast { it == END }
        check(begin >= 0 && end > begin) { "The workspace list was interrupted. Refresh before choosing a session." }
        val seen = mutableSetOf<String>()
        val prefix = scope(profile)
        return lines.subList(begin + 1, end).filter { it.isNotBlank() }.map { line ->
            val parts = line.split(' ', limit = 7)
            check(parts.size == 7) { "The server returned incomplete workspace metadata." }
            val name = String(Base64.getDecoder().decode(parts[6]), Charsets.UTF_8).removeSuffix("\n")
            RemoteWorkspace(parts[0], parts[3].toLong(), name, parts[4].toInt(), parts[5].toInt(), parts[1].toLong(), parts[2].toLong()).validate().also {
                check(seen.add(it.id)) { "The server returned duplicate workspace identities. Refresh the list." }
            }
        }.filter { prefix == null || it.name.startsWith(prefix) }
    }

    fun attachScript(workspace: RemoteWorkspace): String {
        workspace.validate()
        val id = CommandTemplate.quoteArgument(workspace.id)
        val identity = "" + workspace.serverPid + " " + workspace.serverStarted + " " + workspace.created
        return "actual=\$(tmux -u display-message -p -t " + id + " '#{pid} #{start_time} #{session_created}') || exit 75; " +
            "[ \"\$actual\" = '" + identity + "' ] || { printf 'This workspace ended or was replaced. Refresh the list.\\n' >&2; exit 75; }; " +
            "exec tmux -u attach-session -t " + id + " <&2"
    }

    fun attachCommand(profile: SshProfile, workspace: RemoteWorkspace, portable: Boolean = false): String {
        val prefix = scope(profile)
        require(prefix == null || workspace.name.startsWith(prefix)) { "This workspace belongs to another GHT shortcut." }
        return ssh(profile, attachScript(workspace), true, portable)
    }
}
