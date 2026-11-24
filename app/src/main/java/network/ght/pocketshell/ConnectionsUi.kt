package network.ght.pocketshell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import network.ght.pocketshell.ui.*

data class SshProfile(
    val name: String,
    val host: String,
    val user: String = "",
    val port: Int = 22,
    val tmuxSession: String = "agents",
    /** Runs instead of tmux when set (the GHT machine hops). */
    val remoteCommand: String = "",
) {
    fun command(tabId: String = TabIdentity.create()): String {
        TabIdentity.requireValid(tabId)
        val safeHost = host.trim().takeIf { !it.startsWith('-') && it.matches(Regex("[A-Za-z0-9._:-]+")) }
            ?: throw IllegalArgumentException("Enter a valid hostname or IP address.")
        val safeUser = user.trim().takeIf { it.isEmpty() || it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: throw IllegalArgumentException("Username contains unsupported characters.")
        val safePort = port.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Port must be between 1 and 65535.")
        val destination = if (safeUser.isEmpty()) safeHost else "$safeUser@$safeHost"
        val base = "ssh -t -p $safePort -i ~/.ssh/id_ed25519 -o IdentitiesOnly=yes -o SendEnv=GHT_TAB_ID " +
            "-o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30 " +
            "-o ServerAliveCountMax=3 -o ConnectTimeout=15 -- $destination"
        val remote = remoteCommand.trim()
        if (remote.isNotEmpty()) {
            require(remote.matches(Regex("[A-Za-z0-9._/~ -]+"))) { "Remote command contains unsupported characters." }
            return "$base 'GHT_TAB_ID=$tabId $remote'"
        }
        val prefix = tmuxSession.trim()
        val session = if (prefix.isEmpty()) "" else "$prefix-$tabId"
        return if (session.isEmpty()) base else {
            require(session.matches(Regex("[A-Za-z0-9._-]+"))) { "tmux session contains unsupported characters." }
            // Mouse routing is session-scoped; do not alter other server sessions.
            // Quote the remote shell argument, and escape tmux's command separator.
            "$base 'tmux new-session -A -s $session -e GHT_TAB_ID=$tabId \\; set-option -t =$session mouse on'"
        }
    }
}

object SshProfiles {
    // agent.ght.network is CNAME/A to gh-cloud-01; SSH is for user `connor` (not root).
    val agentDroplet = SshProfile(
        name = "Agent droplet",
        host = "agent.ght.network",
        user = "connor",
        port = 22,
        tmuxSession = "agents",
    )
    private const val PREFS = "ssh_profiles"

    fun custom(context: Context): SshProfile {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SshProfile(
            name = p.getString("name", "My server") ?: "My server",
            host = p.getString("host", "") ?: "",
            user = p.getString("user", "") ?: "",
            port = p.getInt("port", 22),
            tmuxSession = p.getString("tmux", "agents") ?: "agents",
        )
    }

    fun save(context: Context, profile: SshProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", profile.name)
            .putString("host", profile.host)
            .putString("user", profile.user)
            .putInt("port", profile.port)
            .putString("tmux", profile.tmuxSession)
            .apply()
    }
}
