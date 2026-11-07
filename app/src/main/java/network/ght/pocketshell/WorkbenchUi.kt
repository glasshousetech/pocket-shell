package network.ght.pocketshell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val commandKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrect = false,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password)

@Composable
fun WorkbenchDialog(
    service: TermService,
    onConnect: (SshProfile) -> Unit,
    onResumeWorkspace: (SshProfile, RemoteWorkspace) -> Unit,
    onJobOutput: (RemoteJob) -> Unit,
    onAction: (String) -> Unit,
    onRefreshOutput: () -> Unit,
    onInsert: (String) -> Unit,
) {
    val state = service.workbench
    val page = state.page ?: return
    val title = when (page) {
        WorkbenchState.Page.PALETTE -> "Actions"
        WorkbenchState.Page.COMMANDS -> if (state.editing != null) "Edit command" else if (state.using != null) "Use command" else "Saved commands"
        WorkbenchState.Page.OUTPUT -> "Search output"
        WorkbenchState.Page.DIAGNOSIS -> "Connection check"
        WorkbenchState.Page.WORKSPACES -> "Remote workspaces"
        WorkbenchState.Page.FILES -> "Remote files"
        WorkbenchState.Page.JOBS -> "Remote jobs"
    }
    Dialog(onDismissRequest = state::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (page != WorkbenchState.Page.PALETTE) TextButton(onClick = {
                            state.page = WorkbenchState.Page.PALETTE; state.error = null
                        }) { Text("Actions") }
                        IconButton(onClick = state::close, modifier = Modifier.size(48.dp).semantics { contentDescription = "Close tools" }) {
                            Text("×", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        when (page) {
                            WorkbenchState.Page.PALETTE -> ActionPalette(service, onConnect, onAction)
                            WorkbenchState.Page.COMMANDS -> CommandLibrary(service, onInsert)
                            WorkbenchState.Page.OUTPUT -> SearchOutput(state, onRefreshOutput)
                            WorkbenchState.Page.DIAGNOSIS -> DiagnosisPanel(service)
                            WorkbenchState.Page.WORKSPACES -> RemoteWorkspacePanel(service, onConnect, onResumeWorkspace)
                            WorkbenchState.Page.FILES -> FilesPanel(service)
                            WorkbenchState.Page.JOBS -> JobsPanel(service, onJobOutput)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ActionPalette(service: TermService, onConnect: (SshProfile) -> Unit, onAction: (String) -> Unit) {
    val state = service.workbench
    val actions = listOf(
        "Search output" to "Find and copy text from this terminal",
        "Saved commands" to "Reusable commands with fill-in parameters",
        "Connection check" to "Diagnose SSH without changing server trust",
        "Remote workspaces" to "Resume a running session or hand it to your PC",
        "Remote files" to "Browse, upload and download with verified results",
        "Remote jobs" to "Run server commands and return for their results",
        "Connections" to "Open or edit your server library",
        "New tab" to "Start another Linux terminal",
        "Text / voice" to "Review a draft before inserting",
        "AI Copilot" to "Ask about a command or recent output",
        "Themes" to "Choose an appearance",
        "Settings" to "Keyboard, workspace, Linux and privacy",
    )
    val query = state.query.trim()
    fun matches(text: String) = text.contains(query, ignoreCase = true)
    val visibleActions = actions.filter { matches(it.first + " " + it.second) }
    val sessions = service.sessions.filter { matches(it.displayName) }
    val connections = state.connections.filter { matches(it.profile.name + " " + it.profile.host) }
    val commands = state.commands.filter { matches(it.name + " " + it.template) }
    Column {
        OutlinedTextField(state.query, { state.query = it }, singleLine = true,
            label = { Text("Find an action, session, host or command") }, keyboardOptions = commandKeyboard,
            modifier = Modifier.fillMaxWidth())
        Text("Keyboard shortcut: Ctrl + Shift + P", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn {
            if (visibleActions.isNotEmpty()) {
                item { SectionLabel("Actions") }
                items(visibleActions) { (title, description) -> ActionItem(title, description) { onAction(title) } }
            }
            if (sessions.isNotEmpty()) {
                item { SectionLabel("Open sessions") }
                items(sessions, key = { "session-" + it.id }) { holder ->
                    ActionItem("Switch to " + holder.displayName, if (holder.alive.value) "Live terminal" else "Ended · output retained") {
                        service.selectedSessionId.value = holder.id; state.close()
                    }
                }
            }
            if (connections.isNotEmpty()) {
                item { SectionLabel("Saved connections") }
                items(connections, key = { "host-" + it.id }) { saved ->
                    ActionItem("Connect to " + saved.profile.name, saved.profile.host) { state.close(); onConnect(saved.profile) }
                }
            }
            if (commands.isNotEmpty()) {
                item { SectionLabel("Saved commands") }
                items(commands, key = { "command-" + it.id }) { command ->
                    ActionItem(command.name, command.template) {
                        state.use(command, service.selectedSessionId.value); state.page = WorkbenchState.Page.COMMANDS
                    }
                }
            }
            if (visibleActions.isEmpty() && sessions.isEmpty() && connections.isEmpty() && commands.isEmpty()) {
                item { Text("No matches. Try a session name, hostname or action.", Modifier.padding(12.dp)) }
            }
            state.connectionError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            state.commandError?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (state.connectionError != null || state.commandError != null) {
                item { TextButton(onClick = { service.loadWorkbench() }) { Text("Retry libraries") } }
            }
        }
    }
}

@Composable private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 8.dp))
}

@Composable private fun ActionItem(title: String, description: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
    )
}

@Composable private fun CommandLibrary(service: TermService, onInsert: (String) -> Unit) {
    val state = service.workbench
    val editing = state.editing
    val using = state.using
    if (editing != null) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editing.name, { state.editing = editing.copy(name = it) },
                label = { Text("Command name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(editing.template, { state.editing = editing.copy(template = it) },
                label = { Text("Command template") }, keyboardOptions = commandKeyboard,
                minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Text("Use {{service}} for a fill-in argument, for example: systemctl status {{service}}. Put parameters outside quotes; values are quoted for you.")
            Row {
                TextButton(onClick = { state.editing = null; state.error = null }, enabled = !state.loading) { Text("Back to commands") }
                Button(onClick = { service.saveCommand(editing) }, enabled = !state.loading) { Text("Save command") }
            }
        }
        return
    }
    if (using != null) {
        val fields = CommandTemplate.fields(using.template)
        val rendered = runCatching { CommandTemplate.render(using.template, state.parameters.toMap()) }
        val target = service.sessions.firstOrNull { it.id == state.targetId }
        val canInsert = target != null && target.alive.value && target.id == service.selectedSessionId.value
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(using.name, style = MaterialTheme.typography.titleMedium)
            Text("Destination: " + (target?.displayName ?: "This tab is no longer open"))
            fields.forEach { name ->
                OutlinedTextField(state.parameters[name].orEmpty(), { state.parameters[name] = it },
                    label = { Text("Parameter: " + name) }, singleLine = true, keyboardOptions = commandKeyboard,
                    modifier = Modifier.fillMaxWidth())
            }
            Text("Exact command", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(rendered.getOrNull() ?: rendered.exceptionOrNull()?.message.orEmpty(), fontFamily = FontFamily.Monospace) }
            Text("Insert puts this text into the destination terminal. Press Enter there when ready.")
            if (!canInsert) Text("Return to the original live tab before inserting.", color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = { state.using = null; state.error = null }) { Text("Back to commands") }
                Button(onClick = { rendered.getOrNull()?.let(onInsert) }, enabled = canInsert && rendered.isSuccess) { Text("Insert command") }
            }
        }
        return
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { state.newCommand() }, enabled = !state.loading && state.commandError == null) { Text("Add command") }
            TextButton(onClick = { service.loadWorkbench() }, enabled = !state.loading) { Text("Refresh commands") }
        }
        state.commandError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn {
            if (!state.loading && state.commands.isEmpty() && state.commandError == null) {
                item { Text("Save the commands you use repeatedly. Their parameters stay editable before insertion.", Modifier.padding(12.dp)) }
            }
            items(state.commands, key = { it.id }) { command ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(command.name, style = MaterialTheme.typography.titleMedium)
                    Text(command.template, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row {
                        TextButton(modifier = Modifier.semantics { contentDescription = "Edit " + command.name }, enabled = !state.loading, onClick = { state.editing = command; state.error = null }) { Text("Edit") }
                        TextButton(modifier = Modifier.semantics { contentDescription = "Delete " + command.name }, enabled = !state.loading, onClick = { state.deleting = command }) { Text("Delete") }
                        TextButton(modifier = Modifier.semantics { contentDescription = "Use " + command.name }, onClick = { state.use(command, service.selectedSessionId.value) }) { Text("Use") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    state.deleting?.let { command ->
        AlertDialog(onDismissRequest = { state.deleting = null }, title = { Text("Delete " + command.name + "?") },
            text = { Text("Remove this saved command from your library?") },
            confirmButton = { TextButton(enabled = !state.loading, onClick = { service.deleteCommand(command.id) }) { Text("Delete command") } },
            dismissButton = { TextButton(onClick = { state.deleting = null }) { Text("Keep command") } })
    }
}

@Composable private fun SearchOutput(state: WorkbenchState, onRefresh: () -> Unit) {
    var matches by remember { mutableStateOf<List<OutputMatch>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(state.outputLines, state.outputQuery, state.matchCase) {
        searching = true
        val lines = state.outputLines
        val query = state.outputQuery
        val matchCase = state.matchCase
        matches = withContext(Dispatchers.Default) { OutputSearch.find(lines, query, matchCase) }
        state.matchIndex = state.matchIndex.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        searching = false
        copied = false
    }
    val current = matches.getOrNull(state.matchIndex)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(state.outputLabel + " · captured output", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(state.outputQuery, { state.outputQuery = it; state.matchIndex = 0 }, singleLine = true,
            label = { Text("Find in output") }, keyboardOptions = commandKeyboard, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.matchCase, { state.matchCase = it })
            Text("Match case", Modifier.weight(1f))
            TextButton(onClick = onRefresh) { Text("Refresh output") }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(when { state.outputQuery.isEmpty() -> "Search all retained lines. Refresh to include new output."
            matches.isEmpty() -> "No matches in this snapshot."
            else -> "Match " + (state.matchIndex + 1) + " of " + matches.size })
        Row {
            TextButton(enabled = matches.size > 1, onClick = { state.matchIndex = (state.matchIndex - 1 + matches.size) % matches.size; copied = false }) { Text("Previous match") }
            TextButton(enabled = matches.size > 1, onClick = { state.matchIndex = (state.matchIndex + 1) % matches.size; copied = false }) { Text("Next match") }
        }
        if (current != null) {
            val highlight = MaterialTheme.colorScheme.primaryContainer
            SelectionContainer(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Column {
                    Text("Line " + (current.line + 1), style = MaterialTheme.typography.labelLarge)
                    Text(buildAnnotatedString {
                        append(current.text)
                        addStyle(SpanStyle(background = highlight), current.start, current.end)
                    }, fontFamily = FontFamily.Monospace)
                }
            }
            TextButton(onClick = { Clip.copy(current.text); copied = true }) { Text(if (copied) "Matching line copied" else "Copy matching line") }
        }
    }
}

@Composable private fun DiagnosisPanel(service: TermService) {
    val state = service.workbench
    val profiles = state.connections.map { it.profile } + listOf(SshProfiles.agentDroplet) + SshShortcuts.machines.map(SshShortcuts::profile)
    LazyColumn {
        item {
            Text("This noninteractive check uses your saved SSH identity, leaves host trust unchanged, and runs read-only remote tool checks.")
            OutlinedTextField(state.diagnosticTimeout, { state.diagnosticTimeout = it },
                label = { Text("Timeout in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            state.diagnosticProfile?.let { Text("Checking: " + it.name, Modifier.padding(vertical = 8.dp)) }
            if (state.diagnosticBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.diagnosis?.let { result ->
                Text(result.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(result.nextStep, Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { Clip.copy(result.category + "\n" + result.summary + "\n" + result.nextStep) }) { Text("Copy check result") }
            }
            SectionLabel("Choose a connection")
        }
        items(profiles) { profile ->
            ListItem(headlineContent = { Text(profile.name) }, supportingContent = { Text(profile.host) },
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.diagnosticBusy) { service.checkConnection(profile) })
        }
        state.connectionError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { service.loadWorkbench() }) { Text("Retry connections") }
        } }
    }
}


@Composable private fun RemoteWorkspacePanel(
    service: TermService,
    onConnect: (SshProfile) -> Unit,
    onResume: (SshProfile, RemoteWorkspace) -> Unit,
) {
    val state = service.workbench
    val profiles = (state.connections.map { it.profile } + listOf(SshProfiles.agentDroplet) + SshShortcuts.machines.map(SshShortcuts::profile)).distinct()
    var choosingHost by remember { mutableStateOf(false) }
    val profile = state.workspaceProfile
    LazyColumn {
        item {
            Text("Resume an existing tmux terminal. Other connected clients and sibling workspaces stay running.")
            Box {
                OutlinedButton(onClick = { choosingHost = true }, enabled = !state.workspaceBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(profile?.name ?: "Choose a host")
                }
                DropdownMenu(expanded = choosingHost, onDismissRequest = { choosingHost = false }) {
                    profiles.forEach { host -> DropdownMenuItem(text = { Text(host.name + " · " + host.host) }, onClick = {
                        state.workspaceProfile = host
                        state.remoteWorkspaces = null
                        state.workspaceError = null
                        state.handoffCopied = null
                        choosingHost = false
                    }) }
                }
            }
            if (profile?.remoteCommand?.isNotBlank() == true) {
                Text("GHT relay workspaces retain their existing onward SSH connection.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(state.workspaceTimeout, { state.workspaceTimeout = it }, enabled = !state.workspaceBusy,
                label = { Text("Discovery timeout in seconds") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { profile?.let(service::discoverWorkspaces) }, enabled = profile != null && !state.workspaceBusy,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.remoteWorkspaces == null) "Find workspaces" else "Refresh workspaces") }
            if (state.workspaceBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.workspaceError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp)) }
            if (state.remoteWorkspaces?.isEmpty() == true) Text("No running tmux workspaces were found on this host.")
            if (profile != null) TextButton(onClick = { state.close(); onConnect(profile) }, enabled = !state.workspaceBusy) { Text("Open SSH") }
        }
        items(state.remoteWorkspaces.orEmpty(), key = { "${it.serverPid}-${it.serverStarted}-${it.id}-${it.created}" }) { workspace ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(workspace.name, style = MaterialTheme.typography.titleMedium)
                Text("${workspace.windows} windows · ${workspace.clients} connected clients", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { profile?.let { onResume(it, workspace) } }, enabled = profile != null && !state.workspaceBusy,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Open remote " + workspace.name }) { Text("Open") }
                    TextButton(onClick = {
                        runCatching { RemoteWorkspaces.attachCommand(requireNotNull(profile), workspace, portable = true) }
                            .onSuccess { Clip.copy(it); state.handoffCopied = workspace.id; state.workspaceError = null }
                            .onFailure { state.workspaceError = it.message ?: "Could not prepare this handoff." }
                    }, enabled = profile != null && !state.workspaceBusy,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Copy PC handoff " + workspace.name }) {
                        Text(if (state.handoffCopied == workspace.id) "PC command copied" else "Copy PC handoff")
                    }
                }
                if (state.handoffCopied == workspace.id) Text("Paste into PowerShell or a POSIX terminal. The PC uses its own SSH credentials.", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
        state.connectionError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { service.loadWorkbench() }) { Text("Retry connections") }
        } }
    }
}
