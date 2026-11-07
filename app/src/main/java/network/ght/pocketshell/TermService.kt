package network.ght.pocketshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

/** One live terminal tab. State is observable so tab labels/dots update reactively. */
class TermSession(
    val id: Int,
    val label: MutableState<String>,
    val alive: MutableState<Boolean>,
    val session: TerminalSession,
    val mode: SessionMode,
    val tabId: String = TabIdentity.create(),
    val reconnectCommand: String? = null,
    val connectionProfile: SshProfile? = null,
    val customName: MutableState<String?> = mutableStateOf(null),
    val color: MutableState<String?> = mutableStateOf(null),
    val remoteWorkspace: RemoteWorkspace? = null,
) {
    val displayName: String get() = customName.value ?: label.value
    val startupCommand: String? get() = reconnectCommand
}

/**
 * Foreground service that OWNS the terminal sessions. This is what makes Pocket Shell
 * a real terminal: shells (and long-running commands) survive the app being
 * backgrounded or the Activity being recreated, because Android won't reap a
 * foreground-service process. A partial wake lock keeps work alive with the
 * screen off; a persistent notification shows session count + an Exit action.
 *
 * None of that helps if the process is actually killed outright (low-memory
 * killer, user force-stop, reboot) — the PTYs die with it and there is no way
 * to keep a child process's fds alive across that. What we *can* do is make a
 * cold restart non-destructive in appearance: [SessionStore] periodically saves
 * each tab's mode/title/cwd/scrollback, and [onCreate] replays that snapshot
 * into freshly-spawned shells so the user sees their prior output again above
 * a clearly-marked new prompt, instead of every tab silently vanishing.
 */
class TermService : Service() {

    inner class LocalBinder : Binder() {
        val service: TermService get() = this@TermService
    }

    private val binder = LocalBinder()

    /** Source of truth for open sessions — the Compose UI observes this directly. */
    val sessions = mutableStateListOf<TermSession>()
    // Drafts and selection belong to the same live service as their PTYs. Do not
    // put arbitrary terminal text in an Activity Bundle or persist it as diagnostics.
    val selectedSessionId = mutableStateOf<Int?>(null)
    val reviewedTextTarget = mutableStateOf<Int?>(null)
    val reviewedTextDrafts = mutableStateMapOf<Int, String>()
    val reviewedTextErrors = mutableStateMapOf<Int, String>()
    val keyImportMessage = mutableStateOf<String?>(null)
    val copilot = CopilotState()
    enum class RemovalState { IDLE, RUNNING, REMOVED, FAILED }
    val linuxRemovalState = mutableStateOf(RemovalState.IDLE)
    private val operations = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val workbench = WorkbenchState()
    private val filesState = lazy { FilesWorkbench(applicationContext, operations, ::refreshNotification) }
    val files: FilesWorkbench get() = filesState.value
    private val jobsState = lazy { JobsWorkbench(applicationContext, operations, ::refreshNotification) }
    val jobs: JobsWorkbench get() = jobsState.value

    fun openWorkbench(page: WorkbenchState.Page = WorkbenchState.Page.PALETTE) {
        workbench.page = page
        loadWorkbench()
    }

    fun loadWorkbench() {
        if (workbench.loading) return
        workbench.loading = true
        operations.launch {
            try {
                val commands = withContext(Dispatchers.IO) { CommandStore.load(applicationContext) }
                val connections = withContext(Dispatchers.IO) { ConnectionStore.load(applicationContext) }
                commands.onSuccess { workbench.commands = it; workbench.commandError = null }
                    .onFailure { workbench.commandError = "Could not read saved commands. Existing data was kept." }
                connections.onSuccess { workbench.connections = it; workbench.connectionError = null }
                    .onFailure { workbench.connectionError = "Could not read saved connections. Existing data was kept." }
                DiagnosticLog.record(applicationContext, "workbench", "libraries_loaded",
                    if (commands.isFailure || connections.isFailure) "StorageReadFailure" else null)
            } finally { workbench.loading = false }
        }
    }

    fun saveCommand(command: SavedCommand) {
        if (workbench.loading) return
        workbench.loading = true
        workbench.error = null
        operations.launch {
            try {
                val result = withContext(Dispatchers.IO) { CommandStore.save(applicationContext, command) }
                result.onSuccess { workbench.commands = it; workbench.editing = null; workbench.commandError = null }
                    .onFailure {
                        workbench.error = if (it is IllegalArgumentException) it.message else "The command could not be saved. Your draft is kept."
                        DiagnosticLog.record(applicationContext, "commands", "save_failed", it.javaClass.simpleName)
                    }
            } finally { workbench.loading = false }
        }
    }

    fun deleteCommand(id: String) {
        if (workbench.loading) return
        workbench.loading = true
        operations.launch {
            try {
                val result = withContext(Dispatchers.IO) { CommandStore.delete(applicationContext, id) }
                result.onSuccess { workbench.commands = it; workbench.deleting = null; workbench.error = null }
                    .onFailure { workbench.error = "The command could not be deleted. Your library was kept." }
            } finally { workbench.loading = false }
        }
    }

    fun checkConnection(profile: SshProfile) {
        if (workbench.diagnosticBusy) return
        val timeout = workbench.diagnosticTimeout.toLongOrNull()
        if (timeout == null || timeout <= 0) { workbench.error = "Enter a positive timeout in seconds."; return }
        workbench.diagnosticBusy = true
        workbench.diagnosticProfile = profile
        workbench.diagnosis = null
        workbench.error = null
        DiagnosticLog.record(applicationContext, "connection_check", "started")
        operations.launch {
            try {
                val result = GuestCommand.run(applicationContext, ConnectionDiagnostics.command(profile, timeout), timeout)
                val diagnosis = ConnectionDiagnostics.classify(result, profile)
                workbench.diagnosis = diagnosis
                DiagnosticLog.record(applicationContext, "connection_check", diagnosis.category,
                    if (diagnosis.successful) null else "CheckNeedsAttention", exitCode = result.exitCode)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                workbench.diagnosis = ConnectionDiagnosis("check_incomplete",
                    "The check could not finish.", "Verify Linux setup and connectivity, or increase the timeout. Open the terminal for interactive sign-in.")
                DiagnosticLog.record(applicationContext, "connection_check", "incomplete", failure.javaClass.simpleName)
            } finally { workbench.diagnosticBusy = false }
        }
    }

    /** One request at a time; rotation must not cancel it or lose its terminal of origin. */
    internal fun requestCopilot(origin: TermSession, prompt: String, terminalText: String?, mode: AiCopilot.Mode,
        request: suspend () -> Result<String> = { AiCopilot.ask(applicationContext, prompt, terminalText, mode) }) {
        if (copilot.busy.value) return
        if (origin !in sessions || !origin.alive.value) {
            copilot.error.value = "This session has ended. Reconnect before asking AI."
            return
        }
        val submittedInput = copilot.input.value
        copilot.error.value = null
        copilot.result.value = null
        copilot.busy.value = true
        copilot.originId.value = origin.id
        copilot.isCommand.value = mode == AiCopilot.Mode.COMMAND
        DiagnosticLog.record(this, "copilot", "request_started", tabId = origin.tabId)
        operations.launch {
            try {
                val response = request()
                response.onSuccess {
                    copilot.result.value = it
                    if (mode == AiCopilot.Mode.COMMAND && copilot.input.value == submittedInput) copilot.input.value = ""
                    DiagnosticLog.record(applicationContext, "copilot", "request_succeeded", tabId = origin.tabId)
                }.onFailure {
                    copilot.error.value = it.message ?: "Request failed."
                    DiagnosticLog.record(applicationContext, "copilot", "request_failed", "ProviderFailure", origin.tabId)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                copilot.error.value = "AI request failed. Please try again."
                DiagnosticLog.record(applicationContext, "copilot", "request_failed", "RequestException", origin.tabId)
            } finally { copilot.busy.value = false }
        }
    }

    fun importSshKey(uri: android.net.Uri) {
        if (keyImportMessage.value == "Validating SSH key…") return
        keyImportMessage.value = "Validating SSH key…"
        operations.launch {
            val result = withContext(Dispatchers.IO) { SshKeyStore.import(applicationContext, uri) }
            keyImportMessage.value = result.fold(onSuccess = { it }, onFailure = { "Key import failed: ${it.message}" })
        }
    }

    fun removeLinux() {
        if (linuxRemovalState.value == RemovalState.RUNNING) return
        linuxRemovalState.value = RemovalState.RUNNING
        sessions.toList().forEach(::closeSession)
        operations.launch {
            val remove: suspend () -> Result<Unit> = { withContext(Dispatchers.IO) { Userland.uninstall(applicationContext) } }
            val removeFiles: suspend () -> Result<Unit> = { if (filesState.isInitialized()) files.withoutTransport(remove) else remove() }
            val result = if (jobsState.isInitialized()) jobs.withoutRequests(removeFiles) else removeFiles()
            linuxRemovalState.value = if (result.isSuccess) RemovalState.REMOVED else RemovalState.FAILED
        }
    }
    private var nextId = 1

    /** Activity registers this to repaint the visible TerminalView on screen changes. */
    var onRedraw: ((TerminalSession) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null

    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotTask = object : Runnable {
        override fun run() {
            persistSnapshot()
            snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // onCreate only runs once per process instance — a warm re-bind from a
        // recreated Activity reuses the live service and never gets here, so
        // "sessions is empty in onCreate" reliably means a fresh process.
        restoreFromSnapshot()
        jobs // Recover watches and verified results on every cold service start.
        snapshotHandler.postDelayed(snapshotTask, SNAPSHOT_INTERVAL_MS)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        goForeground()
        return START_STICKY
    }

    fun discoverWorkspaces(profile: SshProfile) {
        if (workbench.workspaceBusy) return
        val timeout = workbench.workspaceTimeout.toLongOrNull()
        if (timeout == null || timeout <= 0) { workbench.workspaceError = "Enter a positive timeout in seconds."; return }
        workbench.workspaceBusy = true
        workbench.workspaceProfile = profile
        workbench.remoteWorkspaces = null
        workbench.workspaceError = null
        workbench.handoffCopied = null
        DiagnosticLog.record(this, "remote_workspace", "discovery_started")
        operations.launch {
            try {
                val result = GuestCommand.run(applicationContext, RemoteWorkspaces.listCommand(profile, timeout), timeout)
                workbench.remoteWorkspaces = RemoteWorkspaces.parse(result, profile)
                DiagnosticLog.record(applicationContext, "remote_workspace", "discovery_succeeded", exitCode = result.exitCode)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                workbench.workspaceError = when (failure) {
                    is IllegalArgumentException, is IllegalStateException -> failure.message
                    else -> "The workspace list could not be read. Check the connection and refresh."
                }
                DiagnosticLog.record(applicationContext, "remote_workspace", "discovery_failed", failure.javaClass.simpleName)
            } finally { workbench.workspaceBusy = false }
        }
    }

    fun resumeWorkspace(profile: SshProfile, workspace: RemoteWorkspace): TermSession {
        RemoteWorkspaces.attachCommand(profile, workspace) // Validate before changing tabs.
        val existing = sessions.firstOrNull { holder ->
            val connection = holder.connectionProfile
            connection != null && connection.host.equals(profile.host, ignoreCase = true) &&
                connection.port == profile.port && connection.user == profile.user &&
                holder.remoteWorkspace?.sameProcess(workspace) == true
        }
        val holder = if (existing != null) {
            if (existing.alive.value) existing else reconnectSession(existing)
        } else newSession(SessionMode.LINUX, connectionProfile = profile, customName = workspace.name, remoteWorkspace = workspace)
        selectedSessionId.value = holder.id
        workbench.close()
        DiagnosticLog.record(this, "remote_workspace", "attachment_started", tabId = holder.tabId)
        return holder
    }

    fun newSession(mode: SessionMode, startupCommand: String? = null, tabId: String = TabIdentity.create(),
        connectionProfile: SshProfile? = null, customName: String? = null, color: String? = null,
        remoteWorkspace: RemoteWorkspace? = null): TermSession {
        check(linuxRemovalState.value != RemovalState.RUNNING) { "Linux removal is still running." }
        linuxRemovalState.value = RemovalState.IDLE
        TabIdentity.requireValid(tabId)
        require(sessions.none { it.tabId == tabId }) { "Tab identity is already in use." }
        DiagnosticLog.record(this, "session", "create_attempt", tabId = tabId)
        require(remoteWorkspace == null || (connectionProfile != null && mode == SessionMode.LINUX))
        val command = remoteWorkspace?.let { "exec " + RemoteWorkspaces.attachCommand(requireNotNull(connectionProfile), it) }
            ?: connectionProfile?.command(tabId) ?: startupCommand
        val id = nextId++
        val prefix = if (mode == SessionMode.LINUX) "linux" else "sh"
        val label = mutableStateOf("$prefix $id")
        val alive = mutableStateOf(true)
        val session = TermCore.newSession(
            this,
            mode,
            startupCommand = command,
            tabId = tabId,
            onRedraw = { s -> onRedraw?.invoke(s); TranscriptLogger.onRedraw(this, id, mode, s) },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { s -> alive.value = false; DiagnosticLog.record(this, "session", "process_exit", tabId = tabId, exitCode = s.exitStatus); refreshNotification() },
        )
        val holder = TermSession(id, label, alive, session, mode, tabId, command, connectionProfile,
            mutableStateOf(TabNames.normalize(customName)), mutableStateOf(TabColors.byId(color)?.id), remoteWorkspace)
        sessions.add(holder)
        DiagnosticLog.record(this, "session", "tab_registered", tabId = tabId)
        refreshNotification()
        persistSnapshot()
        return holder
    }

    /** Reconnect only an ended tab, retaining its remote identity and unsubmitted draft. */
    fun reconnectSession(holder: TermSession): TermSession {
        require(!holder.alive.value) { "This tab is still connected." }
        val command = holder.reconnectCommand
        val index = sessions.indexOf(holder)
        require(index >= 0) { "This tab has been closed." }
        sessions.removeAt(index)
        return try {
            val replacement = newSession(holder.mode, command, holder.tabId, holder.connectionProfile,
                holder.customName.value, holder.color.value, holder.remoteWorkspace)
            replacement.label.value = holder.label.value
            sessions.remove(replacement)
            sessions.add(index, replacement)
            reviewedTextDrafts.remove(holder.id)?.let { reviewedTextDrafts[replacement.id] = it }
            reviewedTextErrors.remove(holder.id)
            if (selectedSessionId.value == holder.id) selectedSessionId.value = replacement.id
            if (reviewedTextTarget.value == holder.id) reviewedTextTarget.value = replacement.id
            TranscriptLogger.reset(holder.id)
            persistSnapshot()
            DiagnosticLog.record(this, "session", "reconnect_started", tabId = holder.tabId)
            replacement
        } catch (error: Exception) {
            sessions.add(index, holder)
            persistSnapshot()
            DiagnosticLog.record(this, "session", "reconnect_failed", error.javaClass.simpleName, holder.tabId)
            throw error
        }
    }

    fun renameSession(holder: TermSession, name: String?) {
        require(holder in sessions)
        holder.customName.value = TabNames.normalize(name)
        persistSnapshot()
    }

    fun setSessionColor(holder: TermSession, colorId: String?) {
        require(holder in sessions)
        holder.color.value = TabColors.byId(colorId)?.id
        persistSnapshot()
    }

    fun moveSession(holder: TermSession, delta: Int): Boolean {
        val from = sessions.indexOf(holder)
        if (from < 0) return false
        val to = (from + delta).coerceIn(0,sessions.lastIndex)
        if (to == from) return false
        sessions.removeAt(from); sessions.add(to,holder)
        persistSnapshot()
        return true
    }

    fun duplicateSession(holder: TermSession): TermSession {
        require(holder in sessions)
        val index = sessions.indexOf(holder)
        val freshId = TabIdentity.create()
        // Profiles generate a fresh remote workspace. Legacy generated commands have
        // an explicit UUID, which must be replaced when duplicating rather than shared.
        val command = holder.reconnectCommand?.replace(holder.tabId, freshId)
        val fresh = newSession(holder.mode, command, freshId, holder.connectionProfile,
            holder.customName.value,holder.color.value)
        sessions.remove(fresh); sessions.add(index+1,fresh)
        persistSnapshot()
        return fresh
    }

    fun restartSession(holder: TermSession): TermSession? {
        if (holder !in sessions) return null
        holder.session.finishIfRunning()
        holder.alive.value = false
        return reconnectSession(holder)
    }

    fun closeOtherSessions(keep: TermSession) { sessions.filter { it !== keep }.forEach(::closeSession) }

    fun closeSession(holder: TermSession) {
        DiagnosticLog.record(this, "session", "close_requested", tabId = holder.tabId)
        val previousIndex = sessions.indexOf(holder)
        runCatching { holder.session.finishIfRunning() }
        sessions.remove(holder)
        reviewedTextDrafts.remove(holder.id)
        reviewedTextErrors.remove(holder.id)
        if (reviewedTextTarget.value == holder.id) reviewedTextTarget.value = null
        if (selectedSessionId.value == holder.id) {
            selectedSessionId.value = sessions.getOrNull(previousIndex.coerceAtMost(sessions.lastIndex))?.id
        }
        TranscriptLogger.reset(holder.id)
        // The UI always keeps at least one session open, so we don't self-stop here;
        // the user exits explicitly via the notification's Exit action.
        refreshNotification()
        persistSnapshot()
    }

    /**
     * Rebuilds tabs from the last snapshot, if there is one. Each entry gets a
     * brand-new shell (same mode, same cwd when we have one); the saved
     * scrollback is replayed into the emulator's buffer below a dim marker so
     * it reads as history, not as a resumed live process — because it isn't one.
     */
    private fun restoreFromSnapshot() {
        if (sessions.isNotEmpty()) return
        val saved = SessionStore.load(this)
        if (saved.isEmpty()) return

        var restoredAny = false
        saved.forEach { entry ->
            val installed = Userland.installedDistro(this)
            // SYSTEM was an Android toybox escape hatch, not a usable Pocket
            // Shell environment. Migrate old saved sh tabs to Linux when
            // possible and discard them before setup otherwise.
            val effective = when {
                entry.mode == SessionMode.SYSTEM && installed != null ->
                    entry.copy(mode = SessionMode.LINUX, title = "linux")
                entry.mode == SessionMode.SYSTEM -> return@forEach
                installed == null -> return@forEach
                else -> entry
            }
            runCatching { restoreSession(effective) }
                .onSuccess { restoredAny = true }
        }
        // Consume the snapshot only once something actually came back from it,
        // so a transient failure doesn't silently discard the user's history.
        if (restoredAny) SessionStore.clear(this)
    }

    private fun restoreSession(entry: SessionStore.Saved): TermSession {
        val tabId = entry.tabId.takeIf { id -> sessions.none { it.tabId == id } } ?: TabIdentity.create()
        val id = nextId++
        val resumeCommand = entry.remoteWorkspace?.let { workspace ->
            entry.connectionProfile?.let { "exec " + RemoteWorkspaces.attachCommand(it, workspace) }
        }
        val fallbackPrefix = if (entry.mode == SessionMode.LINUX) "linux" else "sh"
        val label = mutableStateOf(entry.title.ifBlank { "$fallbackPrefix $id" })
        val alive = mutableStateOf(true)
        val session = TermCore.newSession(
            this,
            entry.mode,
            cwd = entry.cwd,
            startupCommand = resumeCommand,
            tabId = tabId,
            onRedraw = { s -> onRedraw?.invoke(s); TranscriptLogger.onRedraw(this, id, entry.mode, s) },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { s -> alive.value = false; DiagnosticLog.record(this, "session", "process_exit", tabId = tabId, exitCode = s.exitStatus); refreshNotification() },
        )
        // Spawn now, at a default size — TerminalView.attachSession() later just
        // resizes this (reflowing the buffer) rather than respawning, since the
        // emulator already exists. That lets us inject the saved scrollback
        // before any real UI has attached.
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        injectRestoredTranscript(session, entry.transcript, entry.restoreNotice ?:
            if (resumeCommand != null) "Reconnecting to the saved remote workspace; output above is historical." else null)

        val holder = TermSession(id, label, alive, session, entry.mode, tabId,
            resumeCommand ?: entry.connectionProfile?.command(tabId) ?: entry.startupCommand,
            entry.connectionProfile, mutableStateOf(entry.customName), mutableStateOf(entry.color), entry.remoteWorkspace)
        sessions.add(holder)
        applyPowerPolicy()
        return holder
    }

    /** Feeds saved scrollback text through the emulator as if it were program output. */
    private fun injectRestoredTranscript(session: TerminalSession, transcript: String, notice: String? = null) {
        if (transcript.isBlank() && notice == null) return
        // transcriptText only ever contains rendered characters (no escape codes),
        // but it joins rows with bare '\n'; the emulator needs '\r\n' to actually
        // return to column 0, or replayed lines drift diagonally across the screen.
        val normalized = transcript.replace("\r\n", "\n").replace("\n", "\r\n")
        val marker = "[2m── session restored (best-effort history — not a live process) ──[0m\r\n\r\n"
        val text = "$normalized\r\n$marker" + (notice?.let { it + "\r\n" } ?: "")
        val bytes = text.toByteArray(Charsets.UTF_8)
        runCatching { session.emulator?.append(bytes, bytes.size) }
    }

    private fun persistSnapshot() {
        if (sessions.isEmpty()) {
            SessionStore.clear(this)
            return
        }
        val snap = sessions.map { holder ->
            SessionStore.Saved(
                mode = holder.mode,
                tabId = holder.tabId,
                customName = holder.customName.value,
                color = holder.color.value,
                startupCommand = holder.reconnectCommand,
                connectionProfile = holder.connectionProfile,
                remoteWorkspace = holder.remoteWorkspace,
                title = holder.label.value,
                cwd = if (holder.mode == SessionMode.SYSTEM) {
                    runCatching { holder.session.cwd }.getOrNull()
                } else {
                    null // proot's own -w /root always wins; host cwd isn't meaningful.
                },
                transcript = runCatching {
                    holder.session.emulator?.screen?.transcriptText.orEmpty()
                }.getOrNull().orEmpty(),
            )
        }
        SessionStore.save(this, snap)
    }

    /** Recents-swipe often precedes the process actually being killed — snapshot now. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistSnapshot()
        super.onTaskRemoved(rootIntent)
    }

    private fun shutdown() {
        onRedraw = null
        snapshotHandler.removeCallbacks(snapshotTask)
        // The user explicitly asked to exit — this is a deliberate close, not an
        // accidental kill, so there's nothing to offer back on next launch.
        SessionStore.clear(this)
        sessions.forEach { runCatching { it.session.finishIfRunning() } }
        sessions.forEach { TranscriptLogger.reset(it.id) }
        sessions.clear()
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun goForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun refreshNotification() {
        applyPowerPolicy()
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TermService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = sessions.count { it.alive.value }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Pocket Shell")
            .setContentText(if (filesState.isInitialized() && files.busy) "File operation in progress · $n sessions" else if (jobsState.isInitialized() && jobs.monitoring) "Watching remote jobs · $n sessions" else if (n == 1) "1 session running" else "$n sessions running")
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Exit", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Terminal sessions", NotificationManager.IMPORTANCE_LOW)
                        .apply { setShowBadge(false) },
                )
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketshell:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun applyPowerPolicy() {
        if ((sessions.any { it.alive.value } || (filesState.isInitialized() && files.busy) || (jobsState.isInitialized() && (jobs.monitoring || jobs.busy))) && WorkspacePrefs.backgroundWork(this)) acquireWakeLock()
        else releaseWakeLock()
    }

    fun refreshPowerPolicy() = refreshNotification()

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (jobsState.isInitialized()) jobs.close()
        if (filesState.isInitialized()) files.close()
        operations.cancel()
        sessions.forEach { TranscriptLogger.reset(it.id) }
        snapshotHandler.removeCallbacks(snapshotTask)
        // Graceful stops (e.g. system shutdown) get one last save too; kills that
        // skip onDestroy entirely are exactly why the periodic timer exists.
        persistSnapshot()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "pocketshell_sessions"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "network.ght.pocketshell.STOP"

        /** Safety-net cadence; structural changes (new/close tab) also save immediately. */
        private const val SNAPSHOT_INTERVAL_MS = 20_000L

        /** Initial emulator size for a restored session; the real TerminalView resizes it. */
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
    }
}
