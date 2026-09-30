package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import network.ght.pocketshell.ui.*

/** Everything the tab long-press menu can ask the app to do to one tab. */
sealed interface TabAction {
    data object Rename : TabAction
    data class SetColor(val colorId: String?) : TabAction
    data object Duplicate : TabAction
    data object Restart : TabAction
    data object MoveLeft : TabAction
    data object MoveRight : TabAction
    data object CopyOutput : TabAction
    data object ClearScrollback : TabAction
    data object ResetTerminal : TabAction
    data object CloseOthers : TabAction
    data object Close : TabAction
}

/** A tab color in the tone that suits the current theme, or null for "use the accent". */
@Composable
fun tabColor(id: String?): Color? =
    TabColors.byId(id)?.let { Color(it.argbFor(LocalPocketTheme.current.dark)) }

/** The tab's own color, or the theme accent when it has none. */
@Composable
fun TermSession.accent(): Color = tabColor(color.value) ?: RailAccent

private val MenuShape = RoundedCornerShape(14.dp)

/**
 * Long-press menu for one tab, anchored under it. Color picks apply live and
 * keep the menu open, so the tab can be seen changing; every other action
 * closes it. Actions that would end a running process ask for a second tap
 * inside the menu instead of stacking a confirmation dialog on top.
 */
@Composable
fun TabMenu(
    expanded: Boolean,
    holder: TermSession,
    index: Int,
    count: Int,
    onDismiss: () -> Unit,
    onAction: (TabAction) -> Unit,
) {
    var confirming by remember(expanded, holder.id) { mutableStateOf<TabAction?>(null) }
    fun fire(action: TabAction) { onDismiss(); onAction(action) }
    fun confirmThen(action: TabAction, needsConfirm: Boolean) {
        if (needsConfirm && confirming != action) confirming = action else fire(action)
    }

    val alive = holder.alive.value
    val isSsh = holder.startupCommand != null
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = RailSurface, surfaceContainer = RailSurface, surfaceTint = Color.Transparent),
        shapes = MaterialTheme.shapes.copy(extraSmall = MenuShape),
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.width(288.dp).border(1.dp, RailBorder, MenuShape),
        ) {
            // Header: which tab this is, and what it's running.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (alive) holder.accent() else RailDimText))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        holder.displayName,
                        color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val kind = when {
                        isSsh -> "SSH"
                        holder.mode == SessionMode.LINUX -> "Linux shell"
                        else -> "Shell"
                    }
                    val state = when {
                        alive && isSsh -> "connected"
                        alive -> "running"
                        isSsh -> "disconnected"
                        else -> "ended"
                    }
                    Text("$kind · $state", color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, maxLines = 1)
                }
            }

            ColorSwatches(selected = holder.color.value, onPick = { onAction(TabAction.SetColor(it)) })
            MenuDividerLine()

            MenuRow("Rename…", onClick = { fire(TabAction.Rename) })
            MenuRow("Duplicate tab", onClick = { fire(TabAction.Duplicate) })
            MenuRow(
                text = when {
                    confirming == TabAction.Restart && isSsh -> "Tap again to drop and reconnect"
                    confirming == TabAction.Restart -> "Tap again to end and restart"
                    isSsh -> "Reconnect"
                    else -> "Restart shell"
                },
                tone = if (confirming == TabAction.Restart) Tone.Armed else Tone.Normal,
                onClick = { confirmThen(TabAction.Restart, needsConfirm = alive) },
            )
            Row(Modifier.fillMaxWidth()) {
                MenuRow("← Move left", enabled = index > 0, modifier = Modifier.weight(1f), onClick = { fire(TabAction.MoveLeft) })
                MenuRow("Move right →", enabled = index < count - 1, modifier = Modifier.weight(1f), onClick = { fire(TabAction.MoveRight) })
            }
            MenuDividerLine()

            MenuRow("Copy output", onClick = { fire(TabAction.CopyOutput) })
            MenuRow("Clear scrollback", onClick = { fire(TabAction.ClearScrollback) })
            MenuRow("Reset terminal", onClick = { fire(TabAction.ResetTerminal) })
            MenuDividerLine()

            if (count > 1) {
                val others = count - 1
                MenuRow(
                    text = if (confirming == TabAction.CloseOthers) {
                        if (others == 1) "Tap again to close 1 tab" else "Tap again to close $others tabs"
                    } else "Close other tabs",
                    tone = if (confirming == TabAction.CloseOthers) Tone.Armed else Tone.Normal,
                    onClick = { confirmThen(TabAction.CloseOthers, needsConfirm = true) },
                )
            }
            MenuRow("Close tab", tone = Tone.Danger, onClick = { fire(TabAction.Close) })
        }
    }
}

// Armed = waiting for the confirming second tap. Both it and Danger use the
// theme's error color so they read as "this ends something" in every theme.
private enum class Tone { Normal, Armed, Danger }

@Composable
private fun MenuRow(
    text: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    tone: Tone = Tone.Normal,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> RailDimText.copy(alpha = 0.6f)
        tone == Tone.Armed -> RailError
        tone == Tone.Danger -> RailError
        else -> RailPromptText
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = color, fontFamily = RailMono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MenuDividerLine() {
    HorizontalDivider(color = RailBorder, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
}

/** Default + the nine [TabColors], two rows of five with 44dp touch targets. */
@Composable
private fun ColorSwatches(selected: String?, onPick: (String?) -> Unit) {
    val dark = LocalPocketTheme.current.dark
    val entries: List<Pair<TabColor?, Color>> =
        listOf<Pair<TabColor?, Color>>(null to RailAccent) + TabColors.ALL.map { it to Color(it.argbFor(dark)) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        entries.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (tabColor, swatch) ->
                    val id = tabColor?.id
                    val isSelected = id == selected
                    val name = tabColor?.label ?: "Default"
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.RadioButton) { onPick(id) }
                            .semantics {
                                contentDescription = "$name tab color"
                                this.selected = isSelected
                            },
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .then(if (isSelected) Modifier.border(2.dp, RailPromptText, CircleShape) else Modifier)
                                .padding(if (isSelected) 4.dp else 0.dp)
                                .clip(CircleShape)
                                .background(swatch),
                        ) {
                            if (id == null) {
                                Text("A", color = RailOnAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Rename one tab. The name is pinned: shells and SSH servers set window
 * titles constantly, and a name the user chose shouldn't be overwritten by
 * the next prompt. "Use automatic" hands the label back to the program.
 */
@Composable
fun RenameTabDialog(
    customName: String?,
    automaticTitle: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = customName ?: automaticTitle
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rename tab", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            OutlinedTextField(
                value = value,
                onValueChange = { if (TabNames.fits(it.text)) value = it },
                singleLine = true,
                label = { Text("Tab name", fontFamily = RailMono) },
                placeholder = { Text(automaticTitle, fontFamily = RailMono, color = RailDimText) },
                textStyle = TextStyle(fontFamily = RailMono, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrect = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = RailPromptText,
                    unfocusedTextColor = RailPromptText,
                    cursorColor = RailAccent,
                    focusedBorderColor = RailAccent,
                    unfocusedBorderColor = RailKeyChip,
                    focusedLabelColor = RailAccent,
                    unfocusedLabelColor = RailDimText,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Text(
                "A name you set stays put when the program changes its title. Leave it blank to go back to automatic.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (customName != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Use automatic", color = RailAccentDim, fontFamily = RailMono) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
                TextButton(onClick = { onSave(value.text) }) {
                    Text("Save", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold)
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
