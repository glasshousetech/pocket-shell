package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.ght.pocketshell.ui.*
import java.util.UUID

@Composable
fun ConnectionLibraryDialog(keyImportMessage: String?, publicKey: String?, onImportKey: () -> Unit,
    onCopyPublicKey: () -> Unit, onConnect: (SshProfile) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SavedConnection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var showShortcuts by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    fun update(operation: () -> Result<List<SavedConnection>>) {
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { operation() }
            loading = false
            result.onSuccess { items = it; error = null }.onFailure { error = it.message ?: "Could not open the connection library." }
        }
    }
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { ConnectionStore.load(context) }
        loading = false
        result.onSuccess { items = it }.onFailure { error = it.message ?: "Could not open the connection library." }
    }
    fun connect(profile: SshProfile) {
        runCatching { profile.command(); onConnect(profile) }.onFailure { error = it.message }
    }
    val editing = editingId
    if (editing != null && !loading) {
        ConnectionEditor(items.firstOrNull { it.id == editing } ?: SavedConnection(editing, SshProfile("", "", tmuxSession = "workspace")),
            onSaved = { items = it; editingId = null; error = null }, onDismiss = { editingId = null })
        return
    }
    val deleting = items.firstOrNull { it.id == deletingId }
    if (deleting != null) {
        AlertDialog(onDismissRequest = { deletingId = null }, title = { Text("Remove ${deleting.profile.name}?") },
            text = { Text("This removes the saved connection. Open tabs, server files and SSH keys are kept.") },
            confirmButton = { TextButton(onClick = { deletingId = null; update { ConnectionStore.delete(context, deleting.id) } }) { Text("Remove connection") } },
            dismissButton = { TextButton(onClick = { deletingId = null }) { Text("Keep connection") } })
        return
    }
    if (confirmImport) {
        AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("Replace the SSH identity?") },
            text = { Text("A valid imported key replaces this app’s current identity for future connections. Existing servers may need its public key. Invalid files leave the current identity intact. Keep your own secure backup before replacing a working key.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; onImportKey() }) { Text("Choose key file") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Keep current key") } })
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)).background(RailSurface)
            .heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connections", color = RailPromptText, style = MaterialTheme.typography.headlineSmall)
            Text("Your servers. A separate workspace for every tab.", color = RailDimText)
            OutlinedTextField(query, { query = it }, label = { Text("Search saved connections") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = RailError)
                TextButton(onClick = { update { ConnectionStore.load(context) } }) { Text("Retry library") }
            }
            val visible = items.filter { "${it.profile.name} ${it.profile.host} ${it.profile.user}".contains(query.trim(), true) }
                .sortedWith(compareByDescending<SavedConnection> { it.favorite }.thenBy { it.profile.name.lowercase() })
            if (!loading && error == null && visible.isEmpty()) Text(
                if (query.isBlank()) "No saved connections yet. Add your first server below." else "No matching connections.", color = RailDimText)
            visible.forEach { saved ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(RailKeyChip).padding(12.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(saved.profile.name, color = RailPromptText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(modifier = Modifier.width(48.dp).semantics { contentDescription = if (saved.favorite) "Unfavorite ${saved.profile.name}" else "Favorite ${saved.profile.name}" }, enabled = !loading, onClick = { update { ConnectionStore.save(context, saved.copy(favorite = !saved.favorite)) } }) { Text(if (saved.favorite) "★" else "☆") }
                    }
                    Text("${saved.profile.user.takeIf { it.isNotBlank() }?.plus("@").orEmpty()}${saved.profile.host}:${saved.profile.port}", color = RailDimText)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = !loading, onClick = { editingId = saved.id }) { Text("Edit") }
                        TextButton(enabled = !loading, onClick = { deletingId = saved.id }) { Text("Remove") }
                        TextButton(enabled = !loading, onClick = { connect(saved.profile) }) { Text("Connect") }
                    }
                }
            }
            Button(enabled = !loading && error == null, onClick = { editingId = UUID.randomUUID().toString() }) { Text("Add connection") }
            HorizontalDivider()
            TextButton(onClick = { showShortcuts = !showShortcuts }) { Text(if (showShortcuts) "Hide GHT shortcuts" else "GHT shortcuts") }
            if (showShortcuts) {
                listOf(SshProfiles.agentDroplet).plus(SshShortcuts.machines.map(SshShortcuts::profile)).forEach { profile ->
                    TextButton(onClick = { connect(profile) }, modifier = Modifier.fillMaxWidth()) { Text(profile.name) }
                }
            }
            TextButton(onClick = { showKeys = !showKeys }) { Text(if (showKeys) "Hide SSH identity" else "SSH identity") }
            if (showKeys) {
                Text("Keys stay in private Linux storage. This identity is used for saved connections and GHT shortcuts.", color = RailDimText)
                Text(publicKey?.let(::keyFingerprint) ?: "No public key is available. Run Linux self-test or repair.", color = RailOutText)
                TextButton(enabled = publicKey != null, onClick = { onCopyPublicKey(); copied = true }) { Text(if (copied) "Public key copied" else "Copy public key") }
                TextButton(enabled = keyImportMessage != "Validating SSH key…", onClick = { confirmImport = true }) { Text("Import SSH private key") }
                keyImportMessage?.let { Text(it, color = if (it.startsWith("Key imported")) RailAccent else RailDimText) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close connections") }
        }
    }
}

private fun keyFingerprint(publicKey: String): String = runCatching {
    val parts = publicKey.trim().split(Regex("\\s+"))
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(parts[1]))
    "${parts[0]} · SHA256:${java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)}"
}.getOrDefault("Public key fingerprint unavailable.")

@Composable private fun ConnectionEditor(initial: SavedConnection,
    onSaved: (List<SavedConnection>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.profile.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.profile.host) }
    var user by rememberSaveable(initial.id) { mutableStateOf(initial.profile.user) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.profile.port.toString()) }
    var tmux by rememberSaveable(initial.id) { mutableStateOf(initial.profile.tmuxSession) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(RailSurface).heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Connection details", color = RailPromptText, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text("User (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(port, { port = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tmux, { tmux = it }, label = { Text("tmux prefix (blank for plain SSH)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Save keeps this profile for later. Connect opens its own tab.", color = RailDimText)
            error?.let { Text(it, color = RailError) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                Button(enabled = !busy, onClick = {
                    busy = true
                    val item = initial.copy(profile = SshProfile(name.trim(), host.trim(), user.trim(), port.toIntOrNull() ?: 0, tmux.trim()))
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { ConnectionStore.save(context, item) }
                        busy = false
                        result.onSuccess(onSaved).onFailure { error = it.message ?: "Could not save the connection." }
                    }
                }) { Text(if (busy) "Saving…" else "Save connection") }
            }
        }
    }
}
