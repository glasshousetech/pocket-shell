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
) {
    fun command(): String {
        val safeHost = host.trim().takeIf { it.matches(Regex("[A-Za-z0-9._:-]+")) }
            ?: throw IllegalArgumentException("Enter a valid hostname or IP address.")
        val safeUser = user.trim().takeIf { it.isEmpty() || it.matches(Regex("[A-Za-z0-9._-]+")) }
            ?: throw IllegalArgumentException("Username contains unsupported characters.")
        val safePort = port.takeIf { it in 1..65535 }
            ?: throw IllegalArgumentException("Port must be between 1 and 65535.")
        val destination = if (safeUser.isEmpty()) safeHost else "$safeUser@$safeHost"
        val base = "ssh -t -p $safePort $destination"
        val session = tmuxSession.trim()
        return if (session.isEmpty()) base else {
            require(session.matches(Regex("[A-Za-z0-9._-]+"))) { "tmux session contains unsupported characters." }
            "$base \"tmux new-session -A -s $session\""
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

@Composable
fun ConnectionsDialog(
    keyImportMessage: String?,
    onImportKey: () -> Unit,
    onConnect: (SshProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val saved = remember { SshProfiles.custom(ctx) }
    var name by remember { mutableStateOf(saved.name) }
    var host by remember { mutableStateOf(saved.host) }
    var user by remember { mutableStateOf(saved.user) }
    var port by remember { mutableStateOf(saved.port.toString()) }
    var tmux by remember { mutableStateOf(saved.tmuxSession) }
    var error by remember { mutableStateOf<String?>(null) }
    val maxHeight = with(LocalConfiguration.current) { (screenHeightDp * .88f).dp }

    fun connect(profile: SshProfile) {
        runCatching { profile.command() }
            .onSuccess { SshProfiles.save(ctx, profile); onConnect(profile) }
            .onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(RailSurface)
                .heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Connect", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Jump into a persistent remote workspace. If your signal drops, reconnecting returns to the same tmux session and running agent.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )

            ConnectionCard(SshProfiles.agentDroplet, "ssh agent.ght.network • tmux agents") {
                connect(SshProfiles.agentDroplet)
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(RailKeyChip))
            Text("Saved server", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            ProfileField("Name", name) { name = it }
            ProfileField("Host", host) { host = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { ProfileField("User (optional)", user) { user = it } }
                Box(Modifier.width(88.dp)) { ProfileField("Port", port) { port = it.filter(Char::isDigit).take(5) } }
            }
            ProfileField("tmux session (blank to disable)", tmux) { tmux = it }

            error?.let { Text(it, color = Color(0xFFE0714F), fontFamily = RailMono, fontSize = 11.sp) }
            Text(
                "Keys stay private inside Pocket Shell's Linux environment and are never copied to shared phone storage.",
                color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
            )
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(RailKeyChip)
                    .clickable(onClick = onImportKey).padding(11.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Import SSH private key", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
            keyImportMessage?.let {
                Text(it, color = if (it.startsWith("Key imported")) RailAccent else Color(0xFFE0714F), fontFamily = RailMono, fontSize = 10.sp)
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
                TextButton(onClick = {
                    connect(SshProfile(name.trim().ifEmpty { "My server" }, host, user, port.toIntOrNull() ?: 0, tmux))
                }) { Text("Save & connect", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun ConnectionCard(profile: SshProfile, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(RailKeyChip)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(profile.name, color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(detail, color = RailAccentDim, fontFamily = RailMono, fontSize = 10.sp)
        }
        Text("CONNECT ›", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun ProfileField(label: String, value: String, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = RailAccentDim, fontFamily = RailMono, fontSize = 10.sp)
        TextField(
            value = value, onValueChange = onValue, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = RailMono, fontSize = 12.sp, color = RailPromptText),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = RailKeyChip, focusedContainerColor = RailKeyChip,
                unfocusedIndicatorColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                cursorColor = RailAccent,
            ),
        )
    }
}
