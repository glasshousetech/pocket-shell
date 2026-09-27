package network.ght.pocketshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A real text editor gives IMEs composition, cursor editing, and dictation support. */
@Composable
fun TextInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            val compact = maxHeight < 260.dp
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    // Keep actions above the editor and keyboard, including landscape.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Text / voice", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("Close") }
                        TextButton(onClick = onInsert, enabled = TerminalInteraction.reviewedText(value).isNotBlank()) { Text("Insert") }
                    }
                    if (!compact) {
                        Text("Use your keyboard’s microphone, then review and insert. Press Enter in the terminal when ready.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).focusRequester(focus),
                        label = { Text("Text to insert") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrect = false,
                            keyboardType = KeyboardType.Text,
                        ),
                        minLines = if (compact) 1 else 2,
                        maxLines = if (compact) 2 else 5,
                    )
                    if (!compact) {
                        Text("Line breaks become spaces.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
