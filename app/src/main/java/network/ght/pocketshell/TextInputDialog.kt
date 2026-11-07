package network.ght.pocketshell

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.util.TypedValue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A real text editor gives IMEs composition, cursor editing, and dictation support. */
@Composable
fun TextInputDialog(
    value: String,
    error: String? = null,
    onValueChange: (String) -> Unit,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
) {
    val latestChange by rememberUpdatedState(onValueChange)
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgb()
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
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        maxLines = if (compact) 2 else 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    if (!compact) {
                        Text("Use your keyboard’s microphone, then review and insert. Press Enter in the terminal when ready.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text("Text to insert", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AndroidView(
                            modifier = Modifier.fillMaxWidth(),
                            factory = { context ->
                                CommandEditText(context).apply {
                                    contentDescription = "Command text"
                                    background = null
                                    setPadding(0, 0, 0, 0)
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                                    setText(value)
                                    setSelection(text.length)
                                    addTextChangedListener(object : TextWatcher {
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                            latestChange(s?.toString().orEmpty())
                                        }
                                        override fun afterTextChanged(s: Editable?) = Unit
                                    })
                                    post {
                                        requestFocus()
                                        context.getSystemService(InputMethodManager::class.java)
                                            .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                                    }
                                }
                            },
                            update = { editor ->
                                editor.minLines = if (compact) 1 else 2
                                editor.maxLines = if (compact) 2 else 5
                                editor.setTextColor(textColor)
                                editor.highlightColor = selectionColor
                                if (editor.text.toString() != value) {
                                    val cursor = editor.selectionStart.coerceAtLeast(0).coerceAtMost(value.length)
                                    editor.setText(value)
                                    editor.setSelection(cursor)
                                }
                            },
                        )
                    }
                    if (!compact) {
                        Text("Line breaks become spaces.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}
