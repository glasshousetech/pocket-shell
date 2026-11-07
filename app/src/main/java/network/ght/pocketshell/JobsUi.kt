package network.ght.pocketshell

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import java.text.DateFormat
import java.util.Date

@Composable
fun JobsPanel(service: TermService, onOutput: (RemoteJob) -> Unit) {
    val state = service.jobs
    val context = LocalContext.current
    val profiles = (service.workbench.connections.map { it.profile } + listOf(SshProfiles.agentDroplet) +
        SshShortcuts.machines.filter { it.id != "alientop" }.map(SshShortcuts::profile)).distinct()
    LaunchedEffect(state.loaded) { if (state.loaded) state.refresh() }
    var choosing by remember { mutableStateOf(false) }
    val keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { state.editorOpen = !state.editorOpen }, modifier = Modifier.weight(1f)) {
                    Text(if (state.editorOpen) "Hide editor" else "New job")
                }
                OutlinedButton(onClick = state::refresh, enabled = state.loaded, modifier = Modifier.weight(1f)) { Text("Refresh jobs") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            if (!state.loaded) Text("Reading saved job results…")
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Enable job notifications") }
            }
        }
        if (state.editorOpen) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start a remote job", style = MaterialTheme.typography.titleMedium)
                Text("The server keeps working if your phone disconnects. Use a terminal for commands that need prompts.",
                    style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) { Text(state.profile?.name ?: "Choose a job host") }
                    DropdownMenu(choosing, { choosing = false }) {
                        profiles.forEach { host -> DropdownMenuItem(text = { Text(host.name) }, onClick = { state.profile = host; choosing = false }) }
                    }
                }
                OutlinedTextField(state.name, { state.name = it }, label = { Text("Job name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.command, { state.command = it }, label = { Text("Command to run") }, minLines = 2, maxLines = 8,
                    keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.directory, { state.directory = it }, label = { Text("Working directory") },
                    supportingText = { Text("Absolute path, or leave blank for the remote home.") },
                    keyboardOptions = keyboard, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = state::prepare, enabled = state.loaded, modifier = Modifier.fillMaxWidth()) { Text("Review remote job") }
                HorizontalDivider()
            }
        }
        item {
            Text("Saved jobs", style = MaterialTheme.typography.titleMedium)
            if (state.loaded && state.jobs.isEmpty()) Text("No jobs yet. Review a command to start your first one.")
        }
        items(state.jobs, key = { it.id }) { job ->
            val result = job.observation
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.name, style = MaterialTheme.typography.titleMedium)
                Text(job.profile.name, style = MaterialTheme.typography.labelLarge)
                Text(when (result?.state) {
                    null -> "Start not yet confirmed"
                    "MISSING" -> "No job directory found at the last check"
                    "PREPARING" -> "Start incomplete · no running process confirmed"
                    "STARTING" -> "Prepared · waiting for the runner"
                    "RUNNING" -> "Running at the last check"
                    "SUCCEEDED" -> "Finished · exit 0"
                    "FAILED" -> "Failed · exit " + result.exit
                    else -> "Interrupted · no confirmed exit result"
                }, color = if (result?.state in setOf("FAILED", "INTERRUPTED")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                result?.let { Text("Checked " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it.checked)), style = MaterialTheme.typography.bodySmall) }
                job.connectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                SelectionContainer { Text(job.command, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp)) }
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { onOutput(job) }, enabled = result?.remoteDirectory != null, modifier = Modifier.weight(1f)) { Text("Read output") }
                    TextButton(onClick = {
                        service.files.choose(job.profile)
                        service.files.pathInput = requireNotNull(result).remoteDirectory
                        service.workbench.page = WorkbenchState.Page.FILES
                        service.files.browse()
                    }, enabled = result?.remoteDirectory != null, modifier = Modifier.weight(1f)) { Text("Result files") }
                }
                if (result == null || result.state == "MISSING") {
                    OutlinedButton(onClick = { state.launch(job) }, modifier = Modifier.fillMaxWidth()) { Text("Retry this same job") }
                }
                TextButton(onClick = { state.setWatching(job, !job.watching) }) { Text(if (job.watching) "Stop watching this job" else "Watch this job") }
                if (!job.watching) Text("Automatic checks are off. The remote command is not stopped.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
        item {
            TextButton(onClick = { state.checkSettingsOpen = !state.checkSettingsOpen }) {
                Text(if (state.checkSettingsOpen) "Hide check settings" else "Check settings")
            }
            if (state.checkSettingsOpen) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PocketShell checks while its service is running and catches up when reopened.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.timeoutInput, { state.timeoutInput = it }, label = { Text("Job SSH timeout in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(state.intervalInput, { state.intervalInput = it }, label = { Text("Check interval in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = state::applySettings) { Text("Save check settings") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }) { Text("Notification settings") }
            }
        }
    }
    state.review?.let { job ->
        AlertDialog(onDismissRequest = { state.review = null }, title = { Text("Start " + job.name + "?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Host: " + job.profile.name + " · " + job.profile.host)
                    Text("Directory: " + job.directory.ifBlank { "Remote home" })
                    Text("Exact command", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                    SelectionContainer { Text(job.command, fontFamily = FontFamily.Monospace) }
                }
            },
            confirmButton = { TextButton(onClick = { state.launch(job) }) { Text("Start remote job") } },
            dismissButton = { TextButton(onClick = { state.review = null }) { Text("Keep editing") } })
    }
}
