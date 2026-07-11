package com.railterm.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.railterm.app.ui.*

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
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf(initialKey) }
    var model by remember { mutableStateOf(initialModel) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("AI Copilot", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Bring your own Anthropic API key. Free to use — you pay Anthropic directly, nothing goes through Railterm.",
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
            Text(
                "Get a key at console.anthropic.com/settings/keys. @ght.network staff: Halo support coming.",
                color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
                TextButton(onClick = { onSave(key, model) }) { Text("Save", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
