package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import network.ght.pocketshell.ui.*

/** Full-height, inset-aware catalog. The grid expands naturally on a foldable. */
@Composable
fun ThemePickerDialog(currentId: String, onPick: (TermTheme) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val visible = remember(query, filter) {
        TermThemes.ALL.filter { theme ->
            theme.matches(query) && when (filter) {
                "Dark" -> theme.dark
                "Light" -> !theme.dark
                "OLED" -> theme.oled
                else -> true
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = RailBg) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                    .widthIn(max = 900.dp).fillMaxSize().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Themes", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = RailPromptText)
                        Text("20 looks. Your whole workspace.", fontSize = 12.sp, color = RailDimText)
                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("Current: ${TermThemes.byId(currentId).label}", color = RailAccent, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Find a theme") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = if (query.isNotEmpty()) ({ TextButton(onClick = { query = "" }) { Text("Clear") } }) else null,
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Dark", "Light", "OLED").forEach { mode ->
                        FilterChip(selected = filter == mode, onClick = { filter = mode },
                            label = { Text(mode, fontSize = 12.sp) }, modifier = Modifier.weight(1f))
                    }
                }
                if (visible.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No matching themes", color = RailPromptText)
                        TextButton(onClick = { query = ""; filter = "All" }) { Text("Show all themes") }
                    }
                } else {
                    LazyVerticalGrid(columns = GridCells.Adaptive(152.dp), modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(visible, key = { it.id }) { theme ->
                            ThemeCard(theme, theme.id == currentId) { onPick(theme) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeCard(theme: TermTheme, selected: Boolean, onClick: () -> Unit) {
    val ui = theme.ui
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(themeColor(ui.surface))
            .border(if (selected) 2.dp else 1.dp, if (selected) RailAccent else themeColor(ui.border), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Apply theme ${theme.label}" },
    ) {
        Column(Modifier.fillMaxWidth().background(themeColor(theme.background)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("user@host ~", color = themeColor(ui.accent), fontFamily = RailMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$ git status", color = themeColor(theme.foreground), fontFamily = RailMono, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("✓ clean", color = themeColor(ui.success), fontFamily = RailMono, fontSize = 11.sp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(1, 2, 3, 4, 5, 6).forEach { i ->
                    Box(Modifier.weight(1f).height(4.dp).background(themeColor(theme.ansi[i]), RoundedCornerShape(2.dp)))
                }
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(theme.label, color = themeColor(ui.text), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                minLines = 2, maxLines = 2)
            Text(theme.description, color = themeColor(ui.muted), fontSize = 11.sp, lineHeight = 16.sp, minLines = 2)
            Text(if (selected) "✓ Selected" else if (theme.oled) "OLED · Dark" else if (theme.dark) "Dark" else "Light",
                color = themeColor(ui.accent), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}
