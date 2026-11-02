package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.ght.pocketshell.ui.*

/** The AI copilot bar: natural language -> command, or explain-last-output. */
@Composable
fun CopilotPanel(
    modifier: Modifier = Modifier,
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    result: String?,
    error: String?,
    isCommand: Boolean,
    originLabel: String?,
    onSend: () -> Unit,
    onExplain: () -> Unit,
    onInsert: (String) -> Unit,
    onRun: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .verticalScroll(rememberScrollState())
            .padding(10.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Includes up to 4,000 characters of recent terminal output.", color = RailDimText, fontSize = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Ask AI: find big files, kill port 3000…", color = RailDimText, fontFamily = RailMono, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = RailMono, fontSize = 13.sp, color = RailPromptText),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = RailKeyChip,
                    focusedContainerColor = RailKeyChip,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    cursorColor = RailAccent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            AiChip("Ask AI", onSend, enabled = !busy && input.isNotBlank(), accent = true)
            AiChip("⚙", onSettings, Modifier.semantics { contentDescription = "AI settings" })
        }

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RailAccent,
                trackColor = RailKeyChip,
            )
        }

        error?.let {
            Text(it, color = RailError, fontFamily = RailMono, fontSize = 12.sp)
        }

        if (result != null) {
            Text("Suggestion for ${originLabel ?: "a closed tab"}", color = RailDimText, fontSize = 12.sp)
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(RailBg)
                    .verticalScroll(scroll)
                    .padding(12.dp, 10.dp),
            ) {
                Text(result, color = RailOutText, fontFamily = RailMono, fontSize = 13.sp, lineHeight = 19.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val runnable = isCommand && !result.trimStart().startsWith("#")
                if (runnable) {
                    AiChip("Insert", { onInsert(result) }, Modifier.weight(1f))
                    AiChip("Run", { onRun(result) }, Modifier.weight(1f), accent = true)
                }
                AiChip("Explain output", onExplain, Modifier.weight(if (runnable) 1.4f else 1f))
            }
        } else if (!busy) {
            AiChip("Explain last output", onExplain, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun AiChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) RailAccent.copy(alpha = 0.22f) else RailKeyChip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) RailPromptText else RailAccentDim, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

/** BYO-key settings: paste an Anthropic API key and pick a model. */
@Composable
fun SettingsDialog(
    onThemes: () -> Unit,
    onLinux: () -> Unit = {},
    onKeys: () -> Unit = {},
    onConnections: () -> Unit = {},
    onWorkspacePreferencesChanged: () -> Unit = {},
    initialKey: String,
    initialModel: String,
    initialBase: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
    onKeyboardModeChanged: () -> Unit = {},
    saveError: String? = null,
    saving: Boolean = false,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    var base by remember { mutableStateOf(initialBase) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var transcriptLogging by remember { mutableStateOf(Secrets.transcriptLoggingEnabled(ctx)) }
    var fullKeyboard by remember { mutableStateOf(KeyboardPrefs.fullImeFeatures(ctx)) }
    var lastCrash by remember { mutableStateOf(CrashHandler.lastCrash(ctx)) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
    var storageGranted by remember { mutableStateOf(StorageAccess.isGranted(ctx)) }
    var fontPoints by remember { mutableIntStateOf(WorkspacePrefs.fontSize(ctx)) }
    var keepScreenOn by remember { mutableStateOf(WorkspacePrefs.keepScreenOn(ctx)) }
    var backgroundWork by remember { mutableStateOf(WorkspacePrefs.backgroundWork(ctx)) }
    var diagnosticsMessage by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) storageGranted = StorageAccess.isGranted(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun checkForUpdates() {
        updateState = UpdateCheckState.Checking
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(BuildConfig.VERSION_NAME) }
            updateState = result.fold(
                onSuccess = { if (it.isUpdateAvailable) UpdateCheckState.Available(it.latestVersion) else UpdateCheckState.UpToDate },
                onFailure = { UpdateCheckState.Error },
            )
        }
    }

    val maxHeight = with(LocalConfiguration.current) { (screenHeightDp * 0.85f).dp }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pocket Shell Settings", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (saveError != null) Text(saveError, color = RailError, fontSize = 12.sp)
            Text("Switches and appearance apply immediately. Save AI settings applies only to the AI fields.",
                color = RailDimText, fontSize = 12.sp)

            if (section == null) {
                listOf("Appearance", "Keyboard", "Sessions & privacy", "Linux & connections", "AI Copilot", "Phone storage", "Updates", "Diagnostics").forEach { name ->
                    OutlinedButton(onClick = { section = name }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(name) }
                }
            } else {
                TextButton(onClick = { section = null }) { Text("‹ All settings") }
            }
            if (section == "Linux & connections") {
                Text("Linux & connections", color = RailPromptText, fontWeight = FontWeight.Bold)
                TextButton(onClick = onConnections) { Text("Saved connections and SSH identity") }
                TextButton(onClick = onLinux) { Text("Manage Linux") }
            }
            if (section == "Keyboard") {
                TextButton(onClick = onKeys) { Text("Customize terminal keys") }
            }
            if (section == "Appearance") {
            Text("Appearance", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("${LocalPocketTheme.current.label} · app and terminal", color = RailDimText, fontSize = 12.sp)
            TextButton(onClick = onThemes) { Text("Browse 20 themes") }
            Text("Terminal size: $fontPoints sp", color = RailOutText)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = fontPoints > 10, onClick = { fontPoints--; WorkspacePrefs.setFontSize(ctx,fontPoints); onWorkspacePreferencesChanged() }) { Text("Smaller") }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = fontPoints < 28, onClick = { fontPoints++; WorkspacePrefs.setFontSize(ctx,fontPoints); onWorkspacePreferencesChanged() }) { Text("Larger") }
            }
            Text("Pinch changes are saved too.", color = RailDimText)
            SettingsDivider()

            }
            if (section == "Phone storage") {
            Text("Phone storage", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                if (storageGranted) "Connected. Shared files are available in Linux at /sdcard."
                else "Enable direct access so git, editors, rsync, and scripts can work with Downloads and Documents at /sdcard.",
                color = if (storageGranted) RailAccent else RailDimText,
                fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            AiChip(if (storageGranted) "Storage access enabled" else "Enable phone storage", {
                runCatching { ctx.startActivity(StorageAccess.settingsIntent(ctx)) }
                storageGranted = StorageAccess.isGranted(ctx)
            }, Modifier.fillMaxWidth(), accent = !storageGranted)

            SettingsDivider()

            }
            if (section == "AI Copilot") {
            Text("AI Copilot", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Bring your own Anthropic API key. Free to use — you pay Anthropic directly, nothing goes through Pocket Shell.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            TextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("sk-ant-…", color = RailDimText, fontFamily = RailMono, fontSize = 13.sp) },
                visualTransformation = PasswordVisualTransformation(),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = RailMono, fontSize = 13.sp, color = RailPromptText),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = RailKeyChip,
                    focusedContainerColor = RailKeyChip,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    cursorColor = RailAccent,
                ),
            )
            Text("Model", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Secrets.MODELS.forEach { (id, label) ->
                    val sel = id == model
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) RailAccent.copy(alpha = 0.22f) else RailKeyChip)
                            .clickable { model = id }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (sel) RailPromptText else RailAccentDim, fontFamily = RailMono, fontSize = 12.sp)
                    }
                }
            }
            Text("Endpoint (advanced — GHT staff / proxy)", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
            TextField(
                value = base,
                onValueChange = { base = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(Secrets.DEFAULT_BASE, color = RailDimText, fontFamily = RailMono, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = RailMono, fontSize = 12.sp, color = RailPromptText),
                colors = TextFieldDefaults.colors(
                    unfocusedContainerColor = RailKeyChip,
                    focusedContainerColor = RailKeyChip,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    cursorColor = RailAccent,
                ),
            )
            Text(
                "Get a key at console.anthropic.com/settings/keys. Leave the endpoint as-is unless you're pointing at a GHT Halo proxy.",
                color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
            )
            TextButton(onClick = { onSave(key, model, base) }, enabled = !saving) {
                Text(if (saving) "Saving…" else "Save AI settings")
            }

            SettingsDivider()

            }
            if (section == "Keyboard") {
            Text("Keyboard", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = fullKeyboard,
                    onCheckedChange = {
                        fullKeyboard = it
                        KeyboardPrefs.setFullImeFeatures(ctx, it)
                        onKeyboardModeChanged()
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = RailAccent, checkedThumbColor = RailPromptText),
                )
                Text(
                    "Full keyboard features. Keeps your keyboard's own top row, so voice typing, " +
                        "the clipboard, emoji and its settings stay reachable at the prompt. Autocorrect " +
                        "and predictions stay off either way. Turn this off only if your keyboard starts " +
                        "inserting or swallowing characters in the terminal.",
                    color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
                )
            }

            SettingsDivider()

            }
            if (section == "Sessions & privacy") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(modifier = Modifier.semantics { contentDescription = "Keep screen on" }, checked = keepScreenOn, onCheckedChange = { keepScreenOn = it; WorkspacePrefs.setKeepScreenOn(ctx,it); onWorkspacePreferencesChanged() })
                Text("Keep screen on while viewing the terminal", color = RailOutText)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(modifier = Modifier.semantics { contentDescription = "Keep CPU awake" }, checked = backgroundWork, onCheckedChange = { backgroundWork = it; WorkspacePrefs.setBackgroundWork(ctx,it); onWorkspacePreferencesChanged() })
                Text("Keep CPU awake for live sessions", color = RailOutText)
            }
            Text("Keeping the CPU awake can use more battery. Turning it off lets Android suspend work; remote connections may disconnect. Ended tabs never hold the CPU awake.", color = RailDimText)
            Text("Session transcripts", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Switch(
                    checked = transcriptLogging,
                    onCheckedChange = {
                        transcriptLogging = it
                        Secrets.setTranscriptLoggingEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = RailAccent, checkedThumbColor = RailPromptText),
                )
                Text(
                    "Save sampled command + output history on this device only. Off by default. It may contain secrets and is not a complete audit log.",
                    color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
                )
            }

            SettingsDivider()

            }
            if (section == "Updates") {
            Text("Updates", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Pocket Shell v${BuildConfig.VERSION_NAME}",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp,
            )
            when (val s = updateState) {
                is UpdateCheckState.Idle -> AiChip("Check for updates", { checkForUpdates() }, Modifier.fillMaxWidth())
                is UpdateCheckState.Checking -> Text("Checking…", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
                is UpdateCheckState.UpToDate -> Text("You're up to date.", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
                is UpdateCheckState.Error -> Column {
                    Text("Couldn't check for updates. Your installed version is unchanged.", color = RailError, fontSize = 12.sp)
                    TextButton(onClick = { checkForUpdates() }) { Text("Retry update check") }
                }
                is UpdateCheckState.Available -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("v${s.latestVersion} is available.", color = RailAccent, fontFamily = RailMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    AiChip("Download v${s.latestVersion}", {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateChecker.DOWNLOAD_URL)),
                        )
                    }, Modifier.fillMaxWidth(), accent = true)
                }
            }

            }
            if (section == "Diagnostics") {
            if (lastCrash != null) {
                SettingsDivider()
                Text("Last crash", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(RailBg)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(lastCrash.orEmpty(), color = RailOutText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AiChip("Copy", {
                        lastCrash?.let { clipboard.setText(AnnotatedString(it)) }
                    }, Modifier.weight(1f))
                    AiChip("Clear", {
                        CrashHandler.clear(ctx)
                        lastCrash = null
                    }, Modifier.weight(1f))
                }
            }

            SettingsDivider()
            Text("Diagnostics", color = RailPromptText, fontWeight = FontWeight.Bold)
            Text("Local app events only. Terminal output, commands, keys and connection addresses are excluded.",
                color = RailDimText, fontSize = 12.sp)
            TextButton(onClick = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { DiagnosticLog.read(ctx) } }
                    result.onSuccess {
                        if (it.isEmpty()) diagnosticsMessage = "No diagnostic events saved yet."
                        else { clipboard.setText(AnnotatedString(it)); diagnosticsMessage = "Diagnostics copied." }
                    }.onFailure { diagnosticsMessage = "Diagnostics could not be read. Retry after a moment." }
                }
            }) { Text("Copy diagnostics") }
            (diagnosticsMessage ?: DiagnosticLog.storageError)?.let { Text(it, color = RailDimText, fontSize = 12.sp) }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close settings") }
        }
    }
}

private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data object Error : UpdateCheckState
    data class Available(val latestVersion: String) : UpdateCheckState
}

@Composable
private fun SettingsDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(RailKeyChip))
}
