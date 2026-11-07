package network.ght.pocketshell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.res.ResourcesCompat
import network.ght.pocketshell.ui.*
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<TermService?>(null)
    private val sshKeyPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) serviceState.value?.importSshKey(uri)
    }

    fun pickSshPrivateKey() {
        if (serviceState.value?.keyImportMessage?.value == "Validating SSH key…") return
        serviceState.value?.keyImportMessage?.value = null
        sshKeyPicker.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as? TermService.LocalBinder)?.service
            openRequestedTool()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Window.Callback is public Android API and receives the shortcut before
        // either the terminal view or a text field interprets it as terminal input.
        val previousCallback = window.callback
        window.callback = object : android.view.Window.Callback by previousCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_P && event.isCtrlPressed && event.isShiftPressed) {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        serviceState.value?.openWorkbench()
                    }
                    return true
                }
                return previousCallback.dispatchKeyEvent(event)
            }
        }
        DiagnosticLog.record(this, "workspace", if (savedInstanceState == null) "activity_opened" else "activity_recreated")
        Clip.init(this)
        requestNotificationPermission()

        // Start + bind the foreground service that owns the terminal sessions, so
        // they survive backgrounding and Activity recreation.
        val intent = Intent(this, TermService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            var currentTheme by remember { mutableStateOf(TermThemes.saved(this)) }
            PocketShellTheme(currentTheme) {
                Surface(color = RailBg) {
                    val service = serviceState.value
                    if (service == null) Splash() else PocketShellApp(
                        service, service.keyImportMessage.value, ::pickSshPrivateKey,
                        onThemeChanged = { currentTheme = it },
                    )
                }
            }
        }
    }

    private fun openRequestedTool() {
        if (intent?.action == JobsWorkbench.OPEN_JOBS && serviceState.value != null) {
            serviceState.value?.openWorkbench(WorkbenchState.Page.JOBS)
            intent.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        serviceState.value?.takeIf { it.workbench.page == WorkbenchState.Page.JOBS }?.jobs?.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedTool()
    }

    override fun onDestroy() {
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}

@Composable
private fun Splash() {
    Box(modifier = Modifier.fillMaxSize().background(RailBg))
}

@Composable
private fun PocketShellApp(service: TermService, keyImportMessage: String?, onImportKey: () -> Unit, onThemeChanged: (TermTheme) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    var fontPoints by remember { mutableIntStateOf(WorkspacePrefs.fontSize(ctx)) }
    var screenShouldStayOn by remember { mutableStateOf(WorkspacePrefs.keepScreenOn(ctx)) }
    val fontPx = with(density) { fontPoints.sp.toPx() }.toInt().coerceAtLeast(1)
    val mono = remember {
        runCatching { ResourcesCompat.getFont(ctx, R.font.jbm_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    val currentTheme = LocalPocketTheme.current
    val terminalBackground = themeColor(currentTheme.background).toArgb()

    // Sessions live in the foreground service (survive backgrounding).
    val sessions = service.sessions
    var selectedSessionId by service.selectedSessionId
    val activeIndex = sessions.indexOfFirst { it.id == selectedSessionId }.coerceAtLeast(0)
    // Phone terminals need ESC/TAB/CTRL/navigation constantly. Keep the row
    // visible by default; the handle still collapses it when screen space wins.
    var extraKeysOpen by remember { mutableStateOf(true) }
    var ctrlMenuOpen by remember { mutableStateOf(false) }
    var altMenuOpen by remember { mutableStateOf(false) }
    var textInputSessionId by service.reviewedTextTarget
    val textDrafts = service.reviewedTextDrafts
    // Persisted extra-keys layout (preset or customized); default is the
    // original hardcoded row, so nothing changes until the user picks one.
    var extraKeys by remember { mutableStateOf(ExtraKeysLayouts.saved(ctx)) }
    var keysDialogOpen by remember { mutableStateOf(false) }

    val termViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val viewClient = remember { RailViewClient(ctx, fontPx) { fontPoints = it } }
    var terminalInAlternateBuffer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var distroPickerOpen by remember { mutableStateOf(false) }
    var linuxManageOpen by remember { mutableStateOf(false) }
    var linuxHealthMessage by remember { mutableStateOf<String?>(null) }
    var linuxHealthBusy by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }
    var connectionsOpen by rememberSaveable { mutableStateOf(false) }
    var publicKey by remember { mutableStateOf(SshKeyStore.publicKey(ctx).getOrNull()) }

    // AI copilot state
    var copilotOpen by service.copilot.open
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var settingsSaving by remember { mutableStateOf(false) }
    var settingsSaveError by remember { mutableStateOf<String?>(null) }
    var aiInput by service.copilot.input
    val aiBusy by service.copilot.busy
    var aiResult by service.copilot.result
    var aiError by service.copilot.error
    val aiIsCommand by service.copilot.isCommand
    val aiOriginId by service.copilot.originId
    var closeTargetId by remember { mutableStateOf<Int?>(null) }
    var renameSessionId by remember { mutableStateOf<Int?>(null) }

    // Repaint the visible terminal when its session's screen changes.
    DisposableEffect(service) {
        service.onRedraw = { s ->
            termViewRef.value?.let {
                if (it.currentSession === s) {
                    terminalInAlternateBuffer = s.emulator?.isAlternateBufferActive == true
                    TerminalInteraction.redraw(it)
                }
            }
        }
        onDispose { service.onRedraw = null }
    }
    LaunchedEffect(sessions.getOrNull(activeIndex)?.id) {
        viewClient.clearStickyModifiers()
        ctrlMenuOpen = false
        altMenuOpen = false
    }
    LaunchedEffect(distroPickerOpen, connectionsOpen, settingsOpen, themeOpen, linuxManageOpen, keysDialogOpen) {
        if (distroPickerOpen || connectionsOpen || settingsOpen || themeOpen || linuxManageOpen || keysDialogOpen) {
            delay(100)
            viewClient.hideKeyboard()
        }
    }

    fun addSession(mode: SessionMode) {
        if (mode != SessionMode.LINUX || Userland.installedDistro(ctx) == null) {
            distroPickerOpen = true
            return
        }
        val holder = service.newSession(mode)
        selectedSessionId = holder.id
        termViewRef.value?.let {
            it.attachSession(holder.session)
            it.onScreenUpdated()
            viewClient.showKeyboard()
        }
    }

    fun closeSession(index: Int) {
        val holder = sessions.getOrNull(index) ?: return
        service.closeSession(holder)
        if (sessions.isEmpty()) {
            if (Userland.installedDistro(ctx) != null) addSession(SessionMode.LINUX)
            else distroPickerOpen = true
            return
        }
        val nextIndex = sessions.indexOfFirst { it.id == selectedSessionId }.coerceAtLeast(0)
        termViewRef.value?.let {
            it.attachSession(sessions[nextIndex].session)
            it.onScreenUpdated()
        }
    }

    // First tap on Linux, when nothing is installed yet, opens the distro
    // picker; afterwards it just opens a session in whatever's installed.
    // Long-pressing the tab (see TabRail) opens the manage/uninstall dialog.
    fun addLinux() {
        val installed = Userland.installedDistro(ctx)
        if (installed != null) { addSession(SessionMode.LINUX); return }
        distroPickerOpen = true
    }

    fun installDistro(distro: Distro) {
        distroPickerOpen = false
        if (!Userland.isAvailable(ctx, distro)) {
            setupError = "${distro.label} isn't available for this device's CPU."
            return
        }
        setupError = null
        setupStatus = "Preparing ${distro.label}…"
        scope.launch {
            val result = Bootstrap.install(ctx, distro) { msg ->
                scope.launch(Dispatchers.Main) { setupStatus = msg }
            }
            setupStatus = null
            result.onSuccess {
                service.sessions.toList().forEach(service::closeSession)
                publicKey = SshKeyStore.publicKey(ctx).getOrNull()
                addSession(SessionMode.LINUX)
            }
                .onFailure { setupError = it.message ?: "Install failed." }
        }
    }

    fun uninstallLinux() {
        selectedSessionId = null
        publicKey = null
        linuxManageOpen = false
        setupStatus = "Removing Linux…"
        service.removeLinux()
    }

    LaunchedEffect(service.linuxRemovalState.value) {
        when (service.linuxRemovalState.value) {
            TermService.RemovalState.RUNNING -> setupStatus = "Removing Linux…"
            TermService.RemovalState.REMOVED -> { setupStatus = null; distroPickerOpen = true }
            TermService.RemovalState.FAILED -> {
                setupStatus = null
                setupError = "Linux removal is incomplete. Some files remain. Open Settings → Manage Linux to retry."
            }
            TermService.RemovalState.IDLE -> Unit
        }
    }

    fun setTheme(theme: TermTheme) {
        TermThemes.apply(theme, sessions.map { it.session }) { termViewRef.value?.let(TerminalInteraction::redraw) }
        TermThemes.save(ctx, theme)
        onThemeChanged(theme)
        themeOpen = false
    }

    LaunchedEffect(Unit) {
        if (service.linuxRemovalState.value != TermService.RemovalState.IDLE) return@LaunchedEffect
        // Refresh the GHT ssh shortcuts on every start so updates reach existing
        // installs. File writes only; live SSH sessions are unaffected.
        Userland.installedDistro(ctx)?.let { d ->
            withContext(Dispatchers.IO) {
                runCatching { SshShortcuts.install(Userland.rootfsDir(ctx, d)) }
                    .onFailure { android.util.Log.w("PocketShell.Ssh", "Could not refresh ssh shortcuts", it) }
            }
        }
        // A recreated Activity must reattach to its service-owned workspace,
        // including ended tabs: their history, draft and reconnect state still matter.
        // Treating an ended PTY as a fresh install closed the tab and discarded its draft.
        if (service.sessions.any { it.mode == SessionMode.LINUX }) return@LaunchedEffect
        val distro = Userland.installedDistro(ctx)
        if (distro == null) {
            service.sessions.toList().forEach(service::closeSession)
            distroPickerOpen = true
            return@LaunchedEffect
        }

        // A marker is not proof. Block the terminal, test the real commands,
        // and repair any v0.3/broken rootfs before creating a user-facing tab.
        service.sessions.toList().forEach(service::closeSession)
        setupStatus = "Verifying Linux environment…"
        var ready: Result<Unit> = if (Userland.needsRepair(ctx, distro)) {
            Result.failure(IllegalStateException("setup upgrade required"))
        } else {
            Bootstrap.healthCheck(ctx).map { Unit }
        }
        if (ready.isFailure) {
            ready = Bootstrap.repair(ctx, distro) { msg ->
                scope.launch(Dispatchers.Main) { setupStatus = msg }
            }
        }
        setupStatus = null
        ready.onSuccess {
            publicKey = SshKeyStore.publicKey(ctx).getOrNull()
            addSession(SessionMode.LINUX)
        }.onFailure { setupError = "Linux repair failed: ${it.message}" }
    }

    LaunchedEffect(keyImportMessage) {
        if (keyImportMessage?.startsWith("Key imported") == true) {
            Bootstrap.ensureIdentity(ctx)
                .onSuccess { publicKey = it }
                .onFailure { setupError = "Couldn't prepare imported key: ${it.message}" }
        }
    }

    fun connect(profile: SshProfile) {
        connectionsOpen = false
        if (Userland.installedDistro(ctx) == null) {
            setupError = "Install Ubuntu first so Pocket Shell has a verified SSH client."
            distroPickerOpen = true
            return
        }
        // Start SSH as the PTY process's command. A fixed delay could type the
        // command into an unready shell, or steal focus after a tab switch.
        val tabId = TabIdentity.create()
        val holder = service.newSession(SessionMode.LINUX, tabId = tabId, connectionProfile = profile)
        holder.label.value = profile.name
        selectedSessionId = holder.id
        termViewRef.value?.let { view ->
            view.attachSession(holder.session)
            view.onScreenUpdated()
            viewClient.showKeyboard()
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            onThemes = { settingsOpen = false; themeOpen = true },
            onLinux = { settingsOpen = false; linuxManageOpen = true },
            onKeys = { settingsOpen = false; keysDialogOpen = true },
            onConnections = { settingsOpen = false; connectionsOpen = true },
            onWorkspacePreferencesChanged = {
                fontPoints = WorkspacePrefs.fontSize(ctx)
                screenShouldStayOn = WorkspacePrefs.keepScreenOn(ctx)
                service.refreshPowerPolicy()
            },
            initialKey = Secrets.apiKey(ctx),
            initialModel = Secrets.model(ctx),
            initialBase = Secrets.baseUrl(ctx),
            saveError = settingsSaveError ?: Secrets.storageError ?: Secrets.migrationNotice,
            saving = settingsSaving,
            onSave = { key, model, base ->
                settingsSaving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { Secrets.saveConfiguration(ctx, key, model, base) }
                    settingsSaving = false
                    if (result.isSuccess) {
                        settingsSaveError = null
                        settingsOpen = false
                        copilotOpen = true
                    } else settingsSaveError = Secrets.storageError
                }
            },
            onDismiss = { settingsOpen = false },
            // Re-negotiate the IME contract immediately so the keyboard on screen picks the
            // new mode up, instead of only the next one the user opens.
            onKeyboardModeChanged = { viewClient.refreshIme() },
        )
    }

    if (linuxManageOpen) {
        LinuxManageDialog(
            distro = Userland.installedDistro(ctx),
            hasLinuxFiles = Distro.ALL.any { Userland.rootfsDir(ctx, it).exists() },
            healthMessage = linuxHealthMessage,
            healthBusy = linuxHealthBusy,
            onCheck = {
                linuxHealthBusy = true
                linuxHealthMessage = "Testing the Linux toolchain…"
                scope.launch {
                    Bootstrap.healthCheck(ctx)
                        .onSuccess { linuxHealthMessage = "Ready — ${it.lineSequence().firstOrNull().orEmpty()}" }
                        .onFailure { linuxHealthMessage = "Self-test failed — ${it.message}" }
                    linuxHealthBusy = false
                }
            },
            onRepair = {
                val distro = Userland.installedDistro(ctx)
                if (distro == null) {
                    linuxHealthMessage = "Linux is not installed."
                } else {
                    linuxHealthBusy = true
                    linuxHealthMessage = "Repairing Linux…"
                    scope.launch {
                        Bootstrap.repair(ctx, distro) { msg ->
                            scope.launch(Dispatchers.Main) { linuxHealthMessage = msg }
                        }.onSuccess {
                            publicKey = SshKeyStore.publicKey(ctx).getOrNull()
                            linuxHealthMessage = "Ready — complete toolchain and SSH identity verified."
                        }.onFailure { linuxHealthMessage = "Repair failed — ${it.message}" }
                        linuxHealthBusy = false
                    }
                }
            },
            onUninstall = { uninstallLinux() },
            onDismiss = { linuxManageOpen = false },
        )
    }

    if (themeOpen) {
        ThemePickerDialog(
            currentId = currentTheme.id,
            onPick = { setTheme(it) },
            onDismiss = { themeOpen = false },
        )
    }

    if (keysDialogOpen) {
        ExtraKeysDialog(
            current = extraKeys,
            onPickPreset = { preset ->
                ExtraKeysLayouts.savePreset(ctx, preset)
                extraKeys = preset.keys
            },
            onChange = { keys ->
                if (keys.isNotEmpty()) {
                    ExtraKeysLayouts.saveCustom(ctx, keys)
                    extraKeys = keys
                }
            },
            onDismiss = { keysDialogOpen = false },
        )
    }

    if (connectionsOpen) {
        ConnectionLibraryDialog(
            keyImportMessage = keyImportMessage,
            publicKey = publicKey,
            onImportKey = onImportKey,
            onCopyPublicKey = {
                publicKey?.let { Clip.copy(it) }
            },
            onConnect = { connect(it) },
            onDismiss = { connectionsOpen = false },
        )
    }

    fun captureOutput() {
        val state = service.workbench
        val holder = sessions.firstOrNull { it.id == state.targetId }
        if (holder == null) { state.error = "The original tab is no longer open."; return }
        state.outputLabel = holder.displayName
        state.outputLines = holder.session.emulator?.screen?.transcriptText.orEmpty().split('\n')
        state.matchIndex = 0
        state.error = null
        DiagnosticLog.record(ctx, "output_search", "snapshot_captured", tabId = holder.tabId)
    }

    if (service.workbench.page != null) {
        WorkbenchDialog(service,
            onConnect = ::connect,
            onJobOutput = { job ->
                runCatching {
                    val holder = service.newSession(SessionMode.LINUX, startupCommand = "exec " + RemoteJobs.command(job.profile, RemoteJobs.outputScript(job), interactive = true), customName = job.name + " output")
                    service.selectedSessionId.value = holder.id
                    service.workbench.close()
                }.onFailure { service.workbench.error = it.message ?: "Could not open job output." }
            },
            onResumeWorkspace = { profile, workspace ->
                if (Userland.installedDistro(ctx) == null) {
                    service.workbench.error = "Install Linux in Settings before opening a remote workspace."
                } else runCatching { service.resumeWorkspace(profile, workspace) }
                    .onSuccess { holder ->
                        termViewRef.value?.let { view ->
                            view.attachSession(holder.session)
                            view.onScreenUpdated()
                            viewClient.showKeyboard()
                        }
                    }.onFailure { service.workbench.error = it.message ?: "The workspace could not be opened." }
            },
            onRefreshOutput = ::captureOutput,
            onInsert = { command ->
                val state = service.workbench
                val target = sessions.firstOrNull { it.id == state.targetId }
                if (target == null || selectedSessionId != target.id) {
                    state.error = "Select the original live tab before inserting."
                } else {
                    ReviewedCommand.insert(target, state.targetId, command, run = false)
                        .onSuccess {
                            state.using = null
                            state.close()
                            DiagnosticLog.record(ctx, "commands", "inserted", tabId = target.tabId)
                            viewClient.showKeyboard()
                        }.onFailure { state.error = it.message ?: "The command was not inserted. Your draft is kept." }
                }
            },
            onAction = { action ->
                val state = service.workbench
                when (action) {
                    "Search output" -> {
                        state.targetId = selectedSessionId
                        state.page = WorkbenchState.Page.OUTPUT
                        captureOutput()
                    }
                    "Saved commands" -> { state.page = WorkbenchState.Page.COMMANDS; state.error = null }
                    "Connection check" -> { state.page = WorkbenchState.Page.DIAGNOSIS; state.error = null }
                    "Remote jobs" -> {
                        if (service.jobs.profile == null) service.jobs.profile = sessions.firstOrNull { it.id == selectedSessionId }?.connectionProfile
                            ?: SshShortcuts.profile(SshShortcuts.machines.first { it.id == "agent-prod" })
                        state.page = WorkbenchState.Page.JOBS; state.error = null
                    }
                    "Remote files" -> {
                        if (service.files.profile == null) sessions.firstOrNull { it.id == selectedSessionId }?.connectionProfile?.let(service.files::choose)
                        state.page = WorkbenchState.Page.FILES; state.error = null
                    }
                    "Remote workspaces" -> {
                        if (state.workspaceProfile == null) state.workspaceProfile = sessions.firstOrNull { it.id == selectedSessionId }?.connectionProfile
                        state.page = WorkbenchState.Page.WORKSPACES; state.error = null
                    }
                    else -> {
                        state.close()
                        when (action) {
                            "Connections" -> connectionsOpen = true
                            "New tab" -> addLinux()
                            "Text / voice" -> textInputSessionId = selectedSessionId
                            "AI Copilot" -> copilotOpen = true
                            "Themes" -> themeOpen = true
                            "Settings" -> settingsOpen = true
                        }
                    }
                }
            })
    }

    if (sessions.isEmpty()) {
        SetupHome(
            installed = Userland.installedDistro(ctx) != null,
            onSettings = { settingsOpen = true },
            onSetup = { distroPickerOpen = true },
            onRepair = {
                val distro = Userland.installedDistro(ctx) ?: return@SetupHome
                setupError = null
                setupStatus = "Repairing Linux…"
                scope.launch {
                    val result = Bootstrap.repair(ctx, distro) { msg ->
                        scope.launch(Dispatchers.Main) { setupStatus = msg }
                    }
                    setupStatus = null
                    result.onSuccess {
                        publicKey = SshKeyStore.publicKey(ctx).getOrNull()
                        addSession(SessionMode.LINUX)
                    }.onFailure { setupError = it.message ?: "Repair failed." }
                }
            },
        )
        if (setupStatus != null || setupError != null) {
            SetupOverlay(status = setupStatus, error = setupError, onDismiss = { setupError = null })
        }
        if (distroPickerOpen) {
            DistroPickerDialog(
                onPick = { installDistro(it) },
                onDismiss = { distroPickerOpen = false },
                setupRequired = false,
            )
        }
        return
    }

    val active = sessions[activeIndex.coerceIn(0, sessions.size - 1)]

    fun sendKey(code: Int) { viewClient.sendKey(code) }
    fun sendCtrl(cp: Int) { termViewRef.value?.inputCodePoint(cp, true, false); ctrlMenuOpen = false }
    fun sendAlt(cp: Int) { termViewRef.value?.inputCodePoint(cp, false, true); altMenuOpen = false }
    fun sendLiteral(ch: Char) { termViewRef.value?.inputCodePoint(if (viewClient.shiftDown) ch.uppercaseChar().code else ch.code, false, false) }

    fun terminalText(): String? =
        runCatching { TerminalSnapshot.tail(active.session, maxChars = 4000) }.getOrNull()

    fun askAi(mode: AiCopilot.Mode) {
        if (aiBusy) return
        if (!Secrets.hasApiKey(ctx)) { settingsOpen = true; return }
        val prompt = if (mode == AiCopilot.Mode.COMMAND) aiInput.trim()
            else "Explain the recent output and any error, and suggest a fix if applicable."
        if (mode == AiCopilot.Mode.COMMAND && prompt.isEmpty()) return
        service.requestCopilot(active, prompt, terminalText(), mode)
    }

    fun insertToTerminal(cmd: String, run: Boolean) {
        val result = ReviewedCommand.insert(active, aiOriginId, cmd, run)
        if (result.isFailure) {
            aiError = result.exceptionOrNull()?.message
            return
        }
        aiResult = null
        copilotOpen = false
        termViewRef.value?.let { it.requestFocus(); viewClient.showKeyboard() }
    }

    fun showSession(holder: TermSession) {
        selectedSessionId = holder.id
        termViewRef.value?.let { it.attachSession(holder.session); it.onScreenUpdated() }
    }
    fun tabAction(holder: TermSession, action: TabAction) {
        if (holder !in sessions) return
        runCatching {
            when (action) {
                TabAction.Rename -> renameSessionId = holder.id
                is TabAction.SetColor -> service.setSessionColor(holder,action.colorId)
                TabAction.Duplicate -> showSession(service.duplicateSession(holder))
                TabAction.Restart -> service.restartSession(holder)?.let(::showSession)
                TabAction.MoveLeft -> service.moveSession(holder,-1)
                TabAction.MoveRight -> service.moveSession(holder,1)
                TabAction.CopyOutput -> Clip.copy(TabOutput.forClipboard(holder.session.emulator?.screen?.transcriptText))
                TabAction.ClearScrollback -> { holder.session.emulator?.screen?.clearTranscript(); termViewRef.value?.let(TerminalInteraction::redraw) }
                TabAction.ResetTerminal -> { if (holder.session.emulator != null) holder.session.reset(); termViewRef.value?.let(TerminalInteraction::redraw) }
                TabAction.CloseOthers -> { service.closeOtherSessions(holder); showSession(holder) }
                TabAction.Close -> closeTargetId = holder.id
            }
        }.onFailure { setupError = it.message ?: "The tab action failed." }
    }
    val renameTarget = sessions.firstOrNull { it.id == renameSessionId }
    if (renameTarget != null) RenameTabDialog(
        customName = renameTarget.customName.value,
        automaticTitle = renameTarget.label.value,
        onSave = { service.renameSession(renameTarget,it); renameSessionId = null },
        onDismiss = { renameSessionId = null },
    )

    val expandedWorkspace = LocalConfiguration.current.screenWidthDp >= 700
    val controls: @Composable (Boolean) -> Unit = { sidebar ->
        TabRail(
            sessions = sessions,
            sidebar = sidebar,
            activeIndex = activeIndex,
            onSelect = { selectedSessionId = sessions[it].id; textInputSessionId = null },
            onClose = { closeTargetId = sessions.getOrNull(it)?.id },
            onTabAction = ::tabAction,
            onMenuOpened = { viewClient.hideKeyboard() },
            onAdd = { addLinux() },
            onConnect = { viewClient.hideKeyboard(); connectionsOpen = true },
            onTheme = { themeOpen = true },
            onCopilot = { copilotOpen = !copilotOpen },
            onSettings = { settingsOpen = true },
        )
    }
    Row(modifier = Modifier.fillMaxSize()) {
      if (expandedWorkspace) Box(Modifier.width(220.dp).fillMaxHeight()) { controls(true) }
      Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
        if (!expandedWorkspace) controls(false)

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RailBg).padding(horizontal = 4.dp)) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { c ->
                    TerminalViewport(c).apply { terminal.apply {
                        setTerminalViewClient(viewClient)
                        viewClient.view = this
                        this.keepScreenOn = screenShouldStayOn
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(fontPx) // creates the renderer; must precede setTypeface
                        setTypeface(mono)
                        attachSession(active.session)
                        onScreenUpdated()
                        termViewRef.value = this
                        post { viewClient.showKeyboard() }
                    } }
                },
                update = { container ->
                    val v = container.terminal
                    v.keepScreenOn = screenShouldStayOn
                    viewClient.updateFontSize(fontPx)
                    v.setBackgroundColor(terminalBackground)
                    if (v.currentSession !== active.session) {
                        v.attachSession(active.session)
                        v.onScreenUpdated()
                    }
                },
            )

            if (setupStatus != null || setupError != null) {
                SetupOverlay(status = setupStatus, error = setupError, onDismiss = { setupError = null })
            }
        }

        if (!active.alive.value) {
            Row(Modifier.fillMaxWidth().background(RailSurfaceAlt).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Session ended", color = RailDimText, modifier = Modifier.weight(1f))
                if (active.reconnectCommand != null) TextButton(onClick = {
                    runCatching { service.reconnectSession(active) }
                        .onSuccess { holder ->
                            selectedSessionId = holder.id
                            termViewRef.value?.let { it.attachSession(holder.session); it.onScreenUpdated() }
                        }.onFailure { setupError = it.message ?: "Could not reconnect this tab." }
                }) { Text("Reconnect this tab") }
            }
        }

        if (copilotOpen) {
          androidx.compose.ui.window.Dialog(onDismissRequest = { copilotOpen = false },
              properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
           BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().heightIn(max = maxHeight).clip(RoundedCornerShape(16.dp)).background(RailSurfaceAlt)) {
             Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("AI Copilot", color = RailPromptText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { copilotOpen = false }) { Text("Close AI") }
             }
            CopilotPanel(
                modifier = Modifier.weight(1f, fill = false),
                input = aiInput,
                onInput = { aiInput = it },
                busy = aiBusy,
                result = aiResult,
                error = aiError,
                isCommand = aiIsCommand,
                originLabel = sessions.firstOrNull { it.id == aiOriginId }?.displayName,
                onSend = { askAi(AiCopilot.Mode.COMMAND) },
                onExplain = { askAi(AiCopilot.Mode.EXPLAIN) },
                onInsert = { insertToTerminal(it, run = false) },
                onRun = { insertToTerminal(it, run = true) },
                onSettings = { copilotOpen = false; settingsOpen = true },
            )
            }
           }
          }
        }

        if (viewClient.composingText.isNotEmpty()) {
            Text(
                text = "Composing: ${viewClient.composingText}",
                modifier = Modifier.fillMaxWidth().background(RailSurfaceAlt).padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            Modifier.fillMaxWidth().background(RailSurfaceAlt).padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyChip("History ↑") { termViewRef.value?.let { TerminalInteraction.pageHistory(it, up = true) } }
                KeyChip("History ↓") { termViewRef.value?.let { TerminalInteraction.pageHistory(it, up = false) } }
                if (!terminalInAlternateBuffer) {
                    KeyChip("Live") { termViewRef.value?.let(TerminalInteraction::followOutput) }
                }
            }
            KeyChip("Actions") { viewClient.hideKeyboard(); service.openWorkbench() }
            KeyChip("Text / voice") {
                viewClient.clearStickyModifiers()
                textInputSessionId = active.id
            }
        }

        // Keep terminal controls available even when Android reports a hardware keyboard.
        AnimatedVisibility(visible = extraKeysOpen, enter = expandVertically(), exit = shrinkVertically()) {
            when {
                ctrlMenuOpen -> CtrlMenu(onKey = { cp -> sendCtrl(cp) }, onBack = { ctrlMenuOpen = false })
                altMenuOpen -> AltMenu(onKey = { cp -> sendAlt(cp) }, onBack = { altMenuOpen = false })
                else -> ExtraKeysRow(
                    keys = extraKeys,
                    onEsc = { sendKey(KeyEvent.KEYCODE_ESCAPE) },
                    onTab = { sendKey(KeyEvent.KEYCODE_TAB) },
                    onCtrl = { viewClient.toggleCtrl() },
                    onAlt = { viewClient.toggleAlt() },
                    onShift = { viewClient.toggleShift() },
                    ctrlActive = viewClient.ctrlDown,
                    altActive = viewClient.altDown,
                    shiftActive = viewClient.shiftDown,
                    onCtrlLongPress = { ctrlMenuOpen = true },
                    onAltLongPress = { altMenuOpen = true },
                    onLiteral = { ch -> sendLiteral(ch) },
                    onUp = { sendKey(KeyEvent.KEYCODE_DPAD_UP) },
                    onDown = { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                    onLeft = { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                    onRight = { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                    onHome = { sendKey(KeyEvent.KEYCODE_MOVE_HOME) },
                    onEnd = { sendKey(KeyEvent.KEYCODE_MOVE_END) },
                    onPgUp = { sendKey(KeyEvent.KEYCODE_PAGE_UP) },
                    onPgDown = { sendKey(KeyEvent.KEYCODE_PAGE_DOWN) },
                    onDel = { sendKey(KeyEvent.KEYCODE_FORWARD_DEL) },
                )
            }
        }
        ExtraKeysHandle(
            open = extraKeysOpen,
            onToggle = { extraKeysOpen = !extraKeysOpen; ctrlMenuOpen = false; altMenuOpen = false },
            onLongClick = { keysDialogOpen = true },
        )
    }
    }

    val textTarget = sessions.firstOrNull { it.id == textInputSessionId }
    val closeTarget = sessions.firstOrNull { it.id == closeTargetId }
    if (closeTarget != null) {
        AlertDialog(
            onDismissRequest = { closeTargetId = null },
            title = { Text("Close ${closeTarget.displayName}?") },
            text = { Text("This closes the tab and its local process. Any unsubmitted draft in this tab will be discarded. A remote tmux workspace may continue on its server.") },
            confirmButton = { TextButton(onClick = {
                closeTargetId = null
                closeSession(sessions.indexOf(closeTarget))
            }) { Text("Close tab") } },
            dismissButton = { TextButton(onClick = { closeTargetId = null }) { Text("Keep tab") } },
        )
    }
    if (textTarget != null) {
        TextInputDialog(
            value = textDrafts[textTarget.id].orEmpty(),
            error = service.reviewedTextErrors[textTarget.id],
            onValueChange = { textDrafts[textTarget.id] = it; service.reviewedTextErrors.remove(textTarget.id) },
            onInsert = {
                val text = TerminalInteraction.reviewedText(textDrafts[textTarget.id].orEmpty())
                if (textTarget.alive.value && textTarget.session.emulator != null) {
                    viewClient.clearStickyModifiers()
                    if (textTarget.session.tryPaste(text)) {
                        textDrafts.remove(textTarget.id)
                        service.reviewedTextErrors.remove(textTarget.id)
                        textInputSessionId = null
                        termViewRef.value?.let(TerminalInteraction::followOutput)
                        viewClient.showKeyboard()
                    } else service.reviewedTextErrors[textTarget.id] = "The terminal could not accept this text. Your draft is kept; try again."
                } else {
                    service.reviewedTextErrors[textTarget.id] = "This session has ended. Reconnect before inserting. Your draft is kept."
                }
            },
            onDismiss = { textInputSessionId = null; viewClient.showKeyboard() },
        )
    }

    if (distroPickerOpen) {
        DistroPickerDialog(
            onPick = { installDistro(it) },
            onDismiss = { distroPickerOpen = false },
            setupRequired = false,
        )
    }


}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRail(
    sidebar: Boolean = false,
    sessions: List<TermSession>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onTabAction: (TermSession, TabAction) -> Unit,
    onMenuOpened: () -> Unit,
    onAdd: () -> Unit,
    onConnect: () -> Unit,
    onTheme: () -> Unit,
    onCopilot: () -> Unit,
    onSettings: () -> Unit,
) {
    var menuFor by remember { mutableStateOf<Int?>(null) }
    var railSize by remember { mutableStateOf(IntSize.Zero) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tabs: @Composable () -> Unit = {
            sessions.forEachIndexed { i, holder ->
              key(holder.id) { Box {
                val label = holder.displayName
                val alive = holder.alive.value
                val accent = holder.accent()
                val active = i == activeIndex
                val tabVisibility = remember { BringIntoViewRequester() }
                var tabSize by remember { mutableStateOf(IntSize.Zero) }
                // Selection, reorder and folding can move the active tab outside the
                // viewport. Reveal it after layout; ordinary manual scrolling stays free.
                LaunchedEffect(active, i, sidebar, tabSize, railSize) {
                    if (active && tabSize != IntSize.Zero && railSize != IntSize.Zero) {
                        withFrameNanos { }
                        tabVisibility.bringIntoView()
                        DiagnosticLog.record(context, "workspace", "active_tab_revealed", tabId = holder.tabId)
                    }
                }
                Column(
                    modifier = Modifier
                        .bringIntoViewRequester(tabVisibility)
                        .onSizeChanged { tabSize = it }
                        .then(if (sidebar) Modifier.fillMaxWidth() else Modifier.width(IntrinsicSize.Max))
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) RailBg else RailSurface)
                        .combinedClickable(onClick = { onSelect(i) }, onLongClickLabel = "Tab options",
                            onLongClick = { menuFor = holder.id; onMenuOpened() }),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp),
                    ) {
                        Text("●", color = if (alive) accent else RailDimText, fontSize = 9.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            color = if (active) RailPromptText else RailAccentDim,
                            fontFamily = RailMono,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (sidebar) Modifier.weight(1f) else Modifier.widthIn(max = 168.dp),
                        )
                        if (active) {
                            Box(Modifier.size(48.dp).semantics { contentDescription = "Tab options for $label" }
                                .clip(RoundedCornerShape(8.dp)).clickable { menuFor = holder.id; onMenuOpened() },
                                contentAlignment = Alignment.Center) { Text("⋯", color = RailAccentDim, fontSize = 20.sp) }
                            Box(Modifier.size(48.dp).semantics { contentDescription = "Close tab $label" }
                                .clip(RoundedCornerShape(8.dp)).clickable { onClose(i) },
                                contentAlignment = Alignment.Center) {
                                Text("×", color = RailDimText, fontSize = 20.sp)
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(2.dp)
                            .background(if (active) accent else Color.Transparent),
                    )
                }
                TabMenu(expanded = menuFor == holder.id, holder = holder, index = i, count = sessions.size,
                    onDismiss = { menuFor = null }, onAction = { onTabAction(holder,it) })
              } }
            }
    }
    Column(Modifier.fillMaxWidth().then(if (sidebar) Modifier.fillMaxHeight() else Modifier)
        .onSizeChanged { railSize = it }.background(RailSurfaceAlt)) {
        if (sidebar) {
            Text("WORKSPACES", color = RailDimText, fontSize = 11.sp, modifier = Modifier.padding(14.dp))
            Column(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Session tabs" }.verticalScroll(rememberScrollState()).padding(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { tabs() }
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RailAction("Connect",onConnect,Modifier.fillMaxWidth())
                RailAction("New tab",onAdd,Modifier.fillMaxWidth())
                RailAction("AI",onCopilot,Modifier.fillMaxWidth())
                RailAction("Theme",onTheme,Modifier.fillMaxWidth())
                RailAction("⚙",onSettings,Modifier.fillMaxWidth().semantics { contentDescription = "Settings" })
            }
        } else {
            Row(Modifier.fillMaxWidth().semantics { contentDescription = "Session tabs" }.horizontalScroll(rememberScrollState()).padding(6.dp,6.dp,6.dp,3.dp),horizontalArrangement = Arrangement.spacedBy(3.dp)) { tabs() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp,vertical = 4.dp),horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                RailAction("Connect",onConnect,Modifier.weight(1.3f))
                RailAction("New tab",onAdd,Modifier.weight(1.2f))
                RailAction("AI",onCopilot,Modifier.weight(.75f))
                RailAction("Theme",onTheme,Modifier.weight(1f))
                RailAction("⚙",onSettings,Modifier.width(48.dp).semantics { contentDescription = "Settings" })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RailSurface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = RailAccentDim, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun SetupOverlay(status: String?, error: String?, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RailBg.copy(alpha = 0.97f))
            .clickable(enabled = error != null) { onDismiss() }
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🐧", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        if (error != null) {
            Text(
                "Couldn't set up Linux",
                color = RailPromptText,
                fontFamily = RailMono,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(error, color = RailOutText, fontFamily = RailMono, fontSize = 12.sp)
            Spacer(Modifier.height(18.dp))
            Text("Tap to dismiss", color = RailAccentDim, fontFamily = RailMono, fontSize = 11.sp)
        } else {
            Text(
                status ?: "Working…",
                color = RailPromptText,
                fontFamily = RailMono,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(color = RailAccent, trackColor = RailKeyChip)
            Spacer(Modifier.height(14.dp))
            Text(
                "One-time download, sha256-verified. Then the package manager, python, git, ssh all work.",
                color = RailDimText,
                fontFamily = RailMono,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun SetupHome(installed: Boolean, onSetup: () -> Unit, onRepair: () -> Unit, onSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(RailBg).padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PS", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 42.sp)
        Spacer(Modifier.height(18.dp))
        Text(
            if (installed) "Linux needs repair" else "Build your Pocket Shell",
            color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (installed) {
                "Pocket Shell will verify and restore the complete Bash, SSH, Git, Python, Node, editor, and tmux toolchain before opening a terminal."
            } else {
                "A real Ubuntu environment with Bash, SSH, Git, Python, Node, editors, tmux, phone storage, and an app-managed SSH identity. No Android toybox shell."
            },
            color = RailDimText, fontFamily = RailMono, fontSize = 12.sp, lineHeight = 18.sp,
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(RailAccent.copy(alpha = .2f))
                .clickable(onClick = if (installed) onRepair else onSetup).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (installed) "Repair & verify Linux" else "Set up Ubuntu",
                color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
            )
        }
        TextButton(onClick = onSettings) { Text("Settings and diagnostics") }
    }
}

// Scrollable so every key stays reachable on narrow phones even as the row
// grows — everything a real shell/ssh session needs, not just the basics:
// nav (ESC/TAB/arrows/Home/End/PgUp/PgDn/Del), the two modifier menus (CTRL
// combos double as line-editing shortcuts; ALT combos are readline word-jump),
// and the punctuation shell commands and ssh flags/paths lean on most
// (path/pipe separators, flag dash, negation, home-dir tilde, port colon).
// Which keys appear, and in what order, comes from [keys] — see
// ExtraKeysLayouts for the presets and persistence.
@Composable
private fun ExtraKeysRow(
    keys: List<ExtraKey>,
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onShift: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    shiftActive: Boolean,
    onCtrlLongPress: () -> Unit,
    onAltLongPress: () -> Unit,
    onLiteral: (Char) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onHome: () -> Unit,
    onEnd: () -> Unit,
    onPgUp: () -> Unit,
    onPgDown: () -> Unit,
    onDel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { key ->
            val literal = key.literal
            if (literal != null) {
                KeyChip(key.label) { onLiteral(literal) }
            } else {
                when (key) {
                    ExtraKeysLayouts.ESC -> KeyChip(key.label, onClick = onEsc)
                    ExtraKeysLayouts.TAB -> KeyChip(key.label, onClick = onTab)
                    ExtraKeysLayouts.CTRL -> KeyChip(key.label, active = ctrlActive, onLongClick = onCtrlLongPress, onClick = onCtrl)
                    ExtraKeysLayouts.ALT -> KeyChip(key.label, active = altActive, onLongClick = onAltLongPress, onClick = onAlt)
                    ExtraKeysLayouts.SHIFT -> KeyChip(key.label, active = shiftActive, onClick = onShift)
                    ExtraKeysLayouts.LEFT -> KeyChip(key.label, onClick = onLeft)
                    ExtraKeysLayouts.DOWN -> KeyChip(key.label, onClick = onDown)
                    ExtraKeysLayouts.UP -> KeyChip(key.label, onClick = onUp)
                    ExtraKeysLayouts.RIGHT -> KeyChip(key.label, onClick = onRight)
                    ExtraKeysLayouts.HOME -> KeyChip(key.label, onClick = onHome)
                    ExtraKeysLayouts.END -> KeyChip(key.label, onClick = onEnd)
                    ExtraKeysLayouts.PGUP -> KeyChip(key.label, onClick = onPgUp)
                    ExtraKeysLayouts.PGDN -> KeyChip(key.label, onClick = onPgDown)
                    ExtraKeysLayouts.DEL -> KeyChip(key.label, onClick = onDel)
                }
            }
        }
    }
}

@Composable
private fun CtrlMenu(onKey: (Int) -> Unit, onBack: () -> Unit) {
    // C/D/Z = interrupt/EOF/suspend; L = clear; A/E = line start/end;
    // U/K/W = kill-to-start/kill-to-end/kill-word (readline line editing).
    val combos = listOf(
        "C" to 'c', "D" to 'd', "Z" to 'z', "L" to 'l',
        "A" to 'a', "E" to 'e', "U" to 'u', "K" to 'k', "W" to 'w', "R" to 'r',
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyChip("←", onClick = onBack)
        combos.forEach { (letter, ch) -> KeyChip("^$letter") { onKey(ch.code) } }
    }
}

@Composable
private fun AltMenu(onKey: (Int) -> Unit, onBack: () -> Unit) {
    // B/F = jump back/forward a word; D = delete word forward; . = last arg
    // of the previous command — all standard bash/zsh readline alt-combos.
    val combos = listOf("B" to 'b', "F" to 'f', "D" to 'd', "." to '.')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .horizontalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyChip("←", onClick = onBack)
        combos.forEach { (label, ch) -> KeyChip("M-$label") { onKey(ch.code) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyChip(label: String, modifier: Modifier = Modifier, active: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) RailAccent else RailKeyChip)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) RailOnAccent else RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExtraKeysHandle(open: Boolean, onToggle: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (open) RailAccentDim else RailBorder),
        )
    }
}
