package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

/**
 * Extra-keys layout picker: preset rows, plus a customize section to reorder
 * (↑/↓), remove (×), and re-add individual keys. Opened by long-pressing the
 * extra-keys handle; the choice is persisted via [ExtraKeysLayouts].
 */
@Composable
fun ExtraKeysDialog(
    current: List<ExtraKey>,
    onPickPreset: (ExtraKeysLayouts.Preset) -> Unit,
    onChange: (List<ExtraKey>) -> Unit,
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
            Text("Extra-Keys Row", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Pick a preset, or reorder and toggle keys below. Long-press the row's handle to get back here.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            ExtraKeysLayouts.PRESETS.forEach { preset ->
                PresetRow(
                    preset = preset,
                    selected = ExtraKeysLayouts.matchesPreset(current, preset),
                    onClick = { onPickPreset(preset) },
                )
            }

            Text("Customize", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Column(
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                current.forEachIndexed { i, key ->
                    KeyEditRow(
                        key = key,
                        canMoveUp = i > 0,
                        canMoveDown = i < current.size - 1,
                        onMoveUp = { onChange(current.toMutableList().apply { add(i - 1, removeAt(i)) }) },
                        onMoveDown = { onChange(current.toMutableList().apply { add(i + 1, removeAt(i)) }) },
                        onRemove = { onChange(current.toMutableList().apply { removeAt(i) }) },
                    )
                }
            }

            val available = ExtraKeysLayouts.ALL.filter { k -> current.none { it.id == k.id } }
            if (available.isNotEmpty()) {
                Text("Add", color = RailDimText, fontFamily = RailMono, fontSize = 11.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    available.forEach { key ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(RailKeyChip)
                                .clickable { onChange(current + key) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("+ ${key.label}", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Close", color = RailAccentDim, fontFamily = RailMono) }
            }
        }
    }
}

@Composable
private fun PresetRow(preset: ExtraKeysLayouts.Preset, selected: Boolean, onClick: () -> Unit) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                preset.label,
                color = if (selected) RailPromptText else RailOutText,
                fontFamily = RailMono,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                fontSize = 13.sp,
            )
            Text(preset.blurb, color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 14.sp)
        }
        if (selected) {
            Text("✓", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun KeyEditRow(
    key: ExtraKey,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(RailKeyChip)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(key.label, color = RailPromptText, fontFamily = RailMono, fontSize = 11.sp, modifier = Modifier.weight(1f))
        EditButton("↑", enabled = canMoveUp, onClick = onMoveUp)
        EditButton("↓", enabled = canMoveDown, onClick = onMoveDown)
        EditButton("×", enabled = true, onClick = onRemove)
    }
}

@Composable
private fun EditButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) RailAccentDim else Color(0xFF3A4368),
        fontFamily = RailMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
