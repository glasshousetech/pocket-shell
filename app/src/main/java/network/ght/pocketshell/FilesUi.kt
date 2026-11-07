package network.ght.pocketshell

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

@Composable
fun FilesPanel(service: TermService) {
    val state = service.files
    val hosts = (service.workbench.connections.map { it.profile } + listOf(SshProfiles.agentDroplet) +
        SshShortcuts.machines.map(SshShortcuts::profile)).distinct()
    var choosingHost by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var replacement by remember { mutableStateOf<FileTransfer?>(null) }
    var keepBoth by remember { mutableStateOf<FileTransfer?>(null) }
    var newName by remember { mutableStateOf("") }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), state::upload)
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), state::export)
    val keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Browse and transfer files through your existing SSH connection.", style = MaterialTheme.typography.bodyMedium)
            Box {
                OutlinedButton(onClick = { choosingHost = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(state.profile?.name ?: "Choose a file host")
                }
                DropdownMenu(choosingHost, { choosingHost = false }) {
                    hosts.forEach { host -> DropdownMenuItem(text = { Text(host.name) }, onClick = { state.choose(host); choosingHost = false }) }
                }
            }
            OutlinedTextField(state.pathInput, { state.pathInput = it }, enabled = !state.busy,
                label = { Text("Remote folder") }, keyboardOptions = keyboard, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(state.timeout, { state.timeout = it }, enabled = !state.busy,
                label = { Text("File operation timeout in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Button(onClick = { state.browse() }, enabled = state.profile != null && !state.busy, modifier = Modifier.weight(1f)) { Text("Open folder") }
                TextButton(onClick = { state.directory?.parent()?.let(state::browse) },
                    enabled = state.directory != null && !state.busy, modifier = Modifier.weight(1f)) { Text("Parent folder") }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { if (state.prepareUpload()) upload.launch(arrayOf("*/*")) },
                    enabled = state.directory != null && !state.busy && state.receiptError == null, modifier = Modifier.weight(1f)) { Text("Upload file") }
                TextButton(onClick = { folderName = "" }, enabled = state.directory != null && !state.busy,
                    modifier = Modifier.weight(1f)) { Text("New folder") }
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.progress.toString() + " bytes transferred", style = MaterialTheme.typography.bodySmall)
            }
            Text(state.status, modifier = Modifier.padding(vertical = 4.dp))
            state.error?.let { SelectionContainer { Text(it, color = MaterialTheme.colorScheme.error) } }
            state.receiptError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.preparedDownload?.let { ready ->
                OutlinedButton(onClick = { state.prepareExport()?.let(download::launch) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Save " + ready.name + " to phone")
                }
            }
            if (state.entries?.isEmpty() == true) Text("This folder is empty.")
        }
        items(state.entries.orEmpty(), key = { java.util.Base64.getEncoder().encodeToString(it.name.bytes) }) { entry ->
            ListItem(
                headlineContent = { Text(entry.name.display) },
                supportingContent = { Text(if (entry.attributes.directory) "Folder" else (entry.attributes.size?.toString() ?: "Unknown") + " bytes") },
                trailingContent = {
                    if (!entry.attributes.directory) TextButton(onClick = { state.download(entry) }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Download " + entry.name.display }) { Text("Download") }
                },
                modifier = Modifier.fillMaxWidth().clickable(enabled = entry.attributes.directory && !state.busy) {
                    state.directory?.child(entry.name)?.let(state::browse)
                },
            )
            HorizontalDivider()
        }
        if (state.transfers.isNotEmpty()) item {
            Text("Transfer receipts", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text("Interrupted writes need a status check. Verified uploads include a checksum.", style = MaterialTheme.typography.bodySmall)
        }
        items(state.transfers, key = { it.id }) { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(item.profile.name, style = MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(item.target.display) }
                Text(item.phase.name.lowercase().replace('_', ' '), color = if (item.phase == FileTransfer.Phase.COMPLETE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                if (item.message.isNotBlank()) SelectionContainer { Text(item.message, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { state.resolve(item, "check") }, enabled = !state.busy) { Text("Check transfer status") }
                if (item.phase == FileTransfer.Phase.READY) {
                    Button(onClick = { state.resolve(item, "finish") }, enabled = !state.busy) { Text("Finish upload") }
                }
                if (item.phase == FileTransfer.Phase.CONFLICT || item.phase == FileTransfer.Phase.READY) {
                    OutlinedButton(onClick = { keepBoth = item; newName = "" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Keep both with another name") }
                }
                if (item.phase == FileTransfer.Phase.CONFLICT) {
                    OutlinedButton(onClick = { replacement = item }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Replace and keep backup") }
                }
            }
            HorizontalDivider()
        }
    }
    folderName?.let { name ->
        AlertDialog(onDismissRequest = { folderName = null }, title = { Text("Create remote folder") },
            text = { OutlinedTextField(name, { folderName = it }, label = { Text("Folder name") }, keyboardOptions = keyboard) },
            confirmButton = { TextButton(onClick = { state.newFolder(name); folderName = null }, enabled = name.isNotBlank()) { Text("Create folder") } },
            dismissButton = { TextButton(onClick = { folderName = null }) { Text("Cancel") } })
    }
    replacement?.let { item ->
        AlertDialog(onDismissRequest = { replacement = null }, title = { Text("Replace this file?") },
            text = { Text("The current file at " + item.target.display + " will be moved to " + item.backup.display + " before the verified upload is put in its place. The backup stays on the server.") },
            confirmButton = { TextButton(onClick = { state.resolve(item, "replace"); replacement = null }) { Text("Replace with backup") } },
            dismissButton = { TextButton(onClick = { replacement = null }) { Text("Cancel") } })
    }
    keepBoth?.let { item ->
        AlertDialog(onDismissRequest = { keepBoth = null }, title = { Text("Keep both files") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("New file name") }, keyboardOptions = keyboard) },
            confirmButton = { TextButton(onClick = { state.resolve(item, "keep_both", newName); keepBoth = null }, enabled = newName.isNotBlank()) { Text("Save with new name") } },
            dismissButton = { TextButton(onClick = { keepBoth = null }) { Text("Cancel") } })
    }
}
