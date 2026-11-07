package network.ght.pocketshell

import androidx.compose.runtime.*
import java.util.UUID

/** UI drafts stay with the live service, never in saved-instance Bundles or diagnostic logs. */
class WorkbenchState {
    enum class Page { PALETTE, COMMANDS, OUTPUT, DIAGNOSIS, WORKSPACES, FILES }
    var page by mutableStateOf<Page?>(null)
    var query by mutableStateOf("")
    var commands by mutableStateOf<List<SavedCommand>>(emptyList())
    var connections by mutableStateOf<List<SavedConnection>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var connectionError by mutableStateOf<String?>(null)
    var commandError by mutableStateOf<String?>(null)
    var editing by mutableStateOf<SavedCommand?>(null)
    var deleting by mutableStateOf<SavedCommand?>(null)
    var using by mutableStateOf<SavedCommand?>(null)
    var targetId by mutableStateOf<Int?>(null)
    val parameters = mutableStateMapOf<String, String>()
    var outputLines by mutableStateOf<List<String>>(emptyList())
    var outputLabel by mutableStateOf("")
    var outputQuery by mutableStateOf("")
    var matchCase by mutableStateOf(false)
    var matchIndex by mutableIntStateOf(0)
    var diagnosticProfile by mutableStateOf<SshProfile?>(null)
    var diagnosticTimeout by mutableStateOf("120")
    var diagnosticBusy by mutableStateOf(false)
    var diagnosis by mutableStateOf<ConnectionDiagnosis?>(null)

    var workspaceProfile by mutableStateOf<SshProfile?>(null)
    var workspaceTimeout by mutableStateOf("120")
    var workspaceBusy by mutableStateOf(false)
    var remoteWorkspaces by mutableStateOf<List<RemoteWorkspace>?>(null)
    var workspaceError by mutableStateOf<String?>(null)
    var handoffCopied by mutableStateOf<String?>(null)

    fun newCommand() {
        editing = SavedCommand(UUID.randomUUID().toString(), "", "")
        using = null
        error = null
    }

    fun use(command: SavedCommand, sessionId: Int?) {
        using = command
        editing = null
        targetId = sessionId
        parameters.clear()
        CommandTemplate.fields(command.template).forEach { parameters[it] = "" }
        error = null
    }

    fun close() { page = null; error = null }
}
