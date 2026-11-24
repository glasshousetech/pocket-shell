package network.ght.pocketshell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import network.ght.pocketshell.ui.*

/** Shown before first Linux install: pick which distro to bootstrap. */
@Composable
fun DistroPickerDialog(
    onPick: (Distro) -> Unit,
    onDismiss: () -> Unit,
    setupRequired: Boolean = false,
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Set up Pocket Shell", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "Ubuntu is recommended for SSH and agent CLI compatibility. Setup downloads a sha256-verified base, installs SSH + tmux, and verifies both before it can finish.",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            Distro.ALL.forEach { distro ->
                val available = Userland.isAvailable(ctx, distro)
                DistroRow(distro, available, recommended = distro == Distro.Ubuntu, onClick = { if (available) onPick(distro) })
            }
            if (!setupRequired) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
                }
            }
        }
    }
}

@Composable
private fun DistroRow(distro: Distro, available: Boolean, recommended: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(RailKeyChip)
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🐧", fontSize = 14.sp)
            Text(
                distro.label,
                color = if (available) RailPromptText else RailDimText,
                fontFamily = RailMono,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(if (recommended) "RECOMMENDED" else "LIGHTWEIGHT", color = if (recommended) RailAccent else RailAccentDim, fontFamily = RailMono, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        if (!available) {
            Text(
                "Not available for this device's CPU.",
                color = RailError, fontFamily = RailMono, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Explicit Linux maintenance entry in Settings. */
@Composable
fun LinuxManageDialog(
    distro: Distro?,
    hasLinuxFiles: Boolean = distro != null,
    healthMessage: String?,
    healthBusy: Boolean,
    onCheck: () -> Unit,
    onRepair: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmingRemoval by remember { mutableStateOf(false) }
    var removalText by remember { mutableStateOf("") }
    if (confirmingRemoval) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingRemoval = false; removalText = "" },
            title = { Text("Remove Linux and its files?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This immediately closes all tabs and permanently removes Linux files, packages and SSH keys. Shared phone files are kept. Back up anything you need first.")
                androidx.compose.material3.OutlinedTextField(value = removalText, onValueChange = { removalText = it },
                    label = { Text("Type REMOVE to confirm") }, singleLine = true)
            } },
            confirmButton = { TextButton(enabled = removalText == "REMOVE" && !healthBusy,
                onClick = { confirmingRemoval = false; removalText = ""; onUninstall() }) { Text("Remove Linux") } },
            dismissButton = { TextButton(onClick = { confirmingRemoval = false; removalText = "" }) { Text("Keep Linux") } },
        )
        return
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .85f).dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Linux", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (distro != null) {
                Text(
                    "${distro.label} is installed. Pocket Shell uses only this Linux environment for terminal tabs.",
                    color = RailOutText, fontFamily = RailMono, fontSize = 12.sp,
                )
                Text(
                    healthMessage ?: "Run a self-test to verify Bash, SSH, Git, Python, Node, editors, tmux, and the SSH identity.",
                    color = if (healthMessage?.startsWith("Ready") == true) RailAccent else RailDimText,
                    fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !healthBusy, onClick = onCheck, modifier = Modifier.weight(1f)) {
                        Text(if (healthBusy) "Working…" else "Self-test", color = RailAccent, fontFamily = RailMono)
                    }
                    TextButton(enabled = !healthBusy, onClick = onRepair, modifier = Modifier.weight(1f)) {
                        Text("Repair", color = RailAccent, fontFamily = RailMono)
                    }
                }
                Text(
                    "Removing Linux immediately closes all tabs and deletes Linux files, packages and SSH keys. Shared phone files are kept. Back up your Linux files first.",
                    color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
                )
            } else {
                Text(if (hasLinuxFiles) "Linux files remain from an incomplete installation or removal. You can remove them and set up again." else "Nothing installed yet.", color = RailDimText, fontFamily = RailMono, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Close", color = RailAccentDim, fontFamily = RailMono) }
                if (hasLinuxFiles) {
                    TextButton(enabled = !healthBusy, onClick = { confirmingRemoval = true }) {
                        Text("Uninstall", color = RailError, fontFamily = RailMono, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
