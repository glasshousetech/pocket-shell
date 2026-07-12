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
            Text("Choose a Linux distro", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                "One-time download, sha256-verified. You can uninstall and pick again later (long-press the 🐧 tab).",
                color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
            )
            Distro.ALL.forEach { distro ->
                val available = Userland.isAvailable(ctx, distro)
                DistroRow(distro, available, onClick = { if (available) onPick(distro) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = RailAccentDim, fontFamily = RailMono) }
            }
        }
    }
}

@Composable
private fun DistroRow(distro: Distro, available: Boolean, onClick: () -> Unit) {
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
            val pkgTool = if (distro == Distro.Alpine) "apk" else "apt"
            Text(pkgTool, color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
        }
        if (!available) {
            Text(
                "Not available for this device's CPU.",
                color = Color(0xFFE0714F), fontFamily = RailMono, fontSize = 10.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Reached by long-pressing the 🐧 tab once a distro is installed: info + uninstall. */
@Composable
fun LinuxManageDialog(
    distro: Distro?,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(RailSurface)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Linux", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            if (distro != null) {
                Text(
                    "${distro.label} is installed.",
                    color = RailOutText, fontFamily = RailMono, fontSize = 12.sp,
                )
                Text(
                    "Uninstalling removes the rootfs entirely and closes any open Linux tabs' data on next launch. You'll be able to pick a distro again from scratch.",
                    color = RailDimText, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp,
                )
            } else {
                Text("Nothing installed yet.", color = RailDimText, fontFamily = RailMono, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Close", color = RailAccentDim, fontFamily = RailMono) }
                if (distro != null) {
                    TextButton(onClick = onUninstall) {
                        Text("Uninstall", color = Color(0xFFE0714F), fontFamily = RailMono, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
