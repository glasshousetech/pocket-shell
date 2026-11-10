package network.ght.pocketshell

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class GuestResult(val exitCode: Int, val output: String)

/** One owned noninteractive process; cancellation or timeout never kills a terminal session. */
object GuestCommand {
    suspend fun run(context: Context, command: String, timeoutSeconds: Long = 120): GuestResult =
        withContext(Dispatchers.IO) {
            require(timeoutSeconds > 0) { "Choose a positive timeout." }
            val distro = Userland.installedDistro(context) ?: error("Install Linux before checking SSH.")
            val builder = ProcessBuilder(*Userland.prootExecArgs(context, distro, command)).redirectErrorStream(true)
            builder.environment().clear()
            Userland.prootEnv(context).forEach {
                val pair = it.split("=", limit = 2)
                builder.environment()[pair[0]] = pair[1]
            }
            val process = builder.start()
            val output = StringBuilder()
            var readError: Throwable? = null
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { input ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            synchronized(output) { output.append(buffer, 0, count) }
                        }
                    }
                } catch (error: Exception) { readError = error }
            }.apply { isDaemon = true; name = "pocketshell-guest-check"; start() }
            try {
                val started = System.nanoTime()
                val timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds)
                while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    coroutineContext.ensureActive()
                    if (System.nanoTime() - started >= timeoutNanos) error("The check reached its configured timeout. Increase the timeout or check the route.")
                }
                while (reader.isAlive) {
                    reader.join(200)
                    coroutineContext.ensureActive()
                    if (System.nanoTime() - started >= timeoutNanos) error("SSH output did not close within the configured timeout.")
                }
                readError?.let { throw it }
                GuestResult(process.exitValue(), synchronized(output) { output.toString() })
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
                reader.join(2000)
            }
        }
}

data class ConnectionDiagnosis(val category: String, val summary: String, val nextStep: String, val successful: Boolean = false)

object ConnectionDiagnostics {
    fun command(profile: SshProfile, timeoutSeconds: Long): String {
        profile.command() // Share destination validation with normal connections.
        require(timeoutSeconds > 0)
        val destination = if (profile.user.isBlank()) profile.host.trim() else profile.user.trim() + "@" + profile.host.trim()
        return "ssh -v -T -p " + profile.port + " -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes" +
            " -o BatchMode=yes -o StrictHostKeyChecking=yes -o UpdateHostKeys=no" +
            " -o NumberOfPasswordPrompts=0 -o ConnectTimeout=" + timeoutSeconds +
            " -- " + CommandTemplate.quoteArgument(destination) + " " +
            CommandTemplate.quoteArgument("printf '__POCKET_AUTH_OK__\\n'; if command -v tmux >/dev/null 2>&1; then printf '__POCKET_TMUX_OK__\\n'; else printf '__POCKET_TMUX_MISSING__\\n'; fi")
    }

    fun classify(result: GuestResult, profile: SshProfile): ConnectionDiagnosis {
        val text = result.output
        return when {
            text.contains("REMOTE HOST IDENTIFICATION HAS CHANGED") ->
                ConnectionDiagnosis("host_changed", "The server's host key changed.", "Check the new fingerprint with the server owner before changing saved trust.")
            text.contains("Host key verification failed") ->
                ConnectionDiagnosis("host_trust", "SSH could not verify this server's identity.", "Connect normally to review its fingerprint. This check never changes known hosts.")
            text.contains("Could not resolve hostname", true) || text.contains("Name or service not known", true) ->
                ConnectionDiagnosis("dns", "The SSH hostname could not be resolved.", "Check the saved hostname and the DNS or VPN route used by Linux.")
            text.contains("Connection refused", true) ->
                ConnectionDiagnosis("refused", "The address answered, but the SSH port refused the connection.", "Check the saved port and whether SSH is listening on the server.")
            text.contains("No route to host", true) || text.contains("Network is unreachable", true) ->
                ConnectionDiagnosis("route", "The SSH route is unavailable.", "Check Wi-Fi or mobile connectivity and the required Glass Gate/VPN connection.")
            text.contains("Connection timed out", true) || text.contains("Operation timed out", true) ->
                ConnectionDiagnosis("timeout", "The SSH connection timed out.", "Check the route, firewall and port, or increase the check timeout.")
            text.contains("Permission denied", true) && !text.contains("__POCKET_AUTH_OK__") ->
                ConnectionDiagnosis("authentication", "The server rejected noninteractive SSH authentication.", "Check the username and authorized public key. Password or interactive MFA sign-in must be completed in the terminal.")
            result.exitCode == 0 && text.lineSequence().any { it.trim() == "__POCKET_AUTH_OK__" } -> when {
                profile.remoteCommand.isNotBlank() ->
                    ConnectionDiagnosis("first_hop", "The first SSH hop authenticated successfully.", "The saved follow-on command has not been executed by this check. Open the connection to verify that destination.")
                profile.tmuxSession.isNotBlank() && text.lineSequence().none { it.trim() == "__POCKET_TMUX_OK__" } ->
                    ConnectionDiagnosis("tmux_missing", "SSH works; tmux is missing on the server.", "Install tmux or edit the connection to use plain SSH.")
                else -> ConnectionDiagnosis("ready", "SSH authentication and the requested remote tools are ready.", "Connect to open your workspace.", true)
            }
            else -> ConnectionDiagnosis("ssh_failed", "SSH ended before this check could verify the remote shell.", "Open the connection to inspect its interactive error. Raw SSH output is not included in diagnostics.")
        }
    }
}
