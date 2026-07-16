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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    result: String?,
    error: String?,
    isCommand: Boolean,
    onSend: () -> Unit,
    onExplain: () -> Unit,
    onInsert: (String) -> Unit,
    onRun: (String) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .padding(10.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✨", fontSize = 15.sp)
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
            AiChip("⚙", onSettings)
        }

        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RailAccent,
                trackColor = RailKeyChip,
            )
        }

        error?.let {
            Text(it, color = Color(0xFFE0714F), fontFamily = RailMono, fontSize = 12.sp)
        }

        if (result != null) {
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
private fun AiChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (accent) RailAccent.copy(alpha = 0.22f) else RailKeyChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) RailPromptText else RailAccentDim, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

/** BYO-key settings: paste an Anthropic API key and pick a model. */
@Composable
fun SettingsDialog(
    initialKey: String,
    initialModel: String,
    initialBase: String,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }
    var base by remember { mutableStateOf(initialBase) }

    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var transcriptLogging by remember { mutableStateOf(Secrets.transcriptLoggingEnabled(ctx)) }
    var lastCrash by remember { mutableStateOf(CrashHandler.lastCrash(ctx)) }
    var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }

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
            Text("AI Copilot", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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

            SettingsDivider()

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
                    "Save command + output history to this device for debugging. Off by default — a transcript can contain anything you typed, including passwords.",
                    color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
                )
            }

            SettingsDivider()

            Text("Updates", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Pocket Shell v${BuildConfig.VERSION_NAME}",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp,
            )
            when (val s = updateState) {
                is UpdateCheckState.Idle -> AiChip("Check for updates", { checkForUpdates() }, Modifier.fillMaxWidth())
                is UpdateCheckState.Checking -> Text("Checking…", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
                is UpdateCheckState.UpToDate -> Text("You're up to date.", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
                is UpdateCheckState.Error -> Text("Couldn't check for updates — try again later.", color = Color(0xFFE0714F), fontFamily = RailMono, fontSize = 11.sp)
                is UpdateCheckState.Available -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("v${s.latestVersion} is available.", color = RailAccent, fontFamily = RailMono, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    AiChip("Download v${s.latestVersion}", {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateChecker.DOWNLOAD_URL)),
                        )
                    }, Modifier.fillMaxWidth(), accent = true)
                }
            }

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

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
                TextButton(onClick = { onSave(key, model, base) }) { Text("Save", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold) }
            }
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
