package network.ght.pocketshell

import androidx.compose.runtime.mutableStateOf

/** In-memory request state owned with the PTYs, never written to logs or saved-state Bundles. */
class CopilotState {
    val open = mutableStateOf(false)
    val input = mutableStateOf("")
    val busy = mutableStateOf(false)
    val result = mutableStateOf<String?>(null)
    val error = mutableStateOf<String?>(null)
    val isCommand = mutableStateOf(true)
    val originId = mutableStateOf<Int?>(null)
}
