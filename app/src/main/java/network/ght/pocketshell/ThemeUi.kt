package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import network.ght.pocketshell.ui.*

/** Terminal color-scheme picker: Kali default plus a few real, correct palettes. */
@Composable
fun ThemePickerDialog(
    currentId: String,
    onPick: (TermTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Terminal Theme", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Changes the 16-color ANSI palette (ls, prompts, vim…). The frosted chrome stays the same.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            TermThemes.ALL.forEach { theme -> ThemeRow(theme, selected = theme.id == currentId, onClick = { onPick(theme) }) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Close", color = RailAccentDim, fontFamily = RailMono) }
            }
        }
    }
}

@Composable
private fun ThemeRow(theme: TermTheme, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) RailAccent.copy(alpha = 0.22f) else RailKeyChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Small swatch strip from this theme's own palette — a real preview, not a placeholder.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(1, 2, 3, 4, 5, 6).forEach { idx ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF000000L or theme.ansi[idx])),
                )
            }
        }
        Text(
            theme.label,
            color = if (selected) RailPromptText else RailOutText,
            fontFamily = RailMono,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text("✓", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}
