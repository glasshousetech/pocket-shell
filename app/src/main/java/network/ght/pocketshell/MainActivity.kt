package network.ght.pocketshell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import network.ght.pocketshell.ui.*
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val serviceState = mutableStateOf<TermService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            serviceState.value = (binder as? TermService.LocalBinder)?.service
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Clip.init(this)
        requestNotificationPermission()

        // Start + bind the foreground service that owns the terminal sessions, so
        // they survive backgrounding and Activity recreation.
        val intent = Intent(this, TermService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // Real frosted-glass behind the window (Kali-style translucent chrome),
        // not just a tinted overlay. Older devices still get the plain see-through
        // wallpaper from the theme, just without the blur.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 80 }
        }

        setContent {
            PocketShellTheme {
                Surface(color = Color.Transparent) {
                    val service = serviceState.value
                    if (service == null) Splash() else PocketShellApp(service)
                }
            }
        }
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
private fun PocketShellApp(service: TermService) {
    val configuration = LocalConfiguration.current // recomposes on config change
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val fontPx = remember { with(density) { 13.sp.toPx() }.toInt().coerceAtLeast(18) }
    val mono = remember {
        runCatching { ResourcesCompat.getFont(ctx, R.font.jbm_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    // Load the persisted terminal color theme before any session/emulator is
    // created, so the very first session already renders with it.
    var currentThemeId by remember { mutableStateOf(TermThemes.saved(ctx).id) }
    remember { TerminalColors.COLOR_SCHEME.updateWith(TermThemes.byId(currentThemeId).toProperties()) }

    // Sessions live in the foreground service (survive backgrounding).
    val sessions = service.sessions
    var activeIndex by remember { mutableIntStateOf(0) }
    var extraKeysOpen by remember { mutableStateOf(false) }
    var ctrlMenuOpen by remember { mutableStateOf(false) }
    var altMenuOpen by remember { mutableStateOf(false) }

    val termViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val viewClient = remember { RailViewClient(ctx, fontPx) }
    val scope = rememberCoroutineScope()
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var distroPickerOpen by remember { mutableStateOf(false) }
    var linuxManageOpen by remember { mutableStateOf(false) }
    var themeOpen by remember { mutableStateOf(false) }

    // AI copilot state
    var copilotOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var aiInput by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<String?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiIsCommand by remember { mutableStateOf(true) }

    // Repaint the visible terminal when its session's screen changes.
    LaunchedEffect(Unit) {
        service.onRedraw = { s -> termViewRef.value?.let { if (it.currentSession === s) it.onScreenUpdated() } }
    }

    fun addSession(mode: SessionMode) {
        val holder = service.newSession(mode)
        activeIndex = sessions.indexOf(holder).coerceAtLeast(0)
        termViewRef.value?.let {
            it.attachSession(holder.session)
            it.onScreenUpdated()
            viewClient.showKeyboard()
        }
    }

    // Prefer the installed Linux distro over the bare Android shell whenever we
    // have to conjure a session with no other signal to go on (cold start, or
    // "always keep one tab open" after closing the last one) — SYSTEM's PATH is
    // just /system/bin, so a user who already installed Alpine/Ubuntu should
    // land in the real userland, not the crippled toybox shell, by default.
    fun defaultMode(): SessionMode =
        if (Userland.installedDistro(ctx) != null) SessionMode.LINUX else SessionMode.SYSTEM

    fun closeSession(index: Int) {
        val holder = sessions.getOrNull(index) ?: return
        service.closeSession(holder)
        if (sessions.isEmpty()) { addSession(defaultMode()); return }
        activeIndex = activeIndex.coerceIn(0, sessions.size - 1)
        termViewRef.value?.let {
            it.attachSession(sessions[activeIndex].session)
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
            result.onSuccess { addSession(SessionMode.LINUX) }
                .onFailure { setupError = it.message ?: "Install failed." }
        }
    }

    fun uninstallLinux() {
        Userland.uninstall(ctx)
        linuxManageOpen = false
    }

    fun setTheme(theme: TermTheme) {
        TermThemes.apply(theme, sessions.map { it.session }) { termViewRef.value?.onScreenUpdated() }
        TermThemes.save(ctx, theme)
        currentThemeId = theme.id
        themeOpen = false
    }

    LaunchedEffect(Unit) { if (sessions.isEmpty()) addSession(defaultMode()) }
    if (sessions.isEmpty()) return

    val active = sessions[activeIndex.coerceIn(0, sessions.size - 1)]

    // Reflects whether a hardware keyboard is actually attached right now.
    val hardwareKeyboardAttached = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    fun sendKey(code: Int) { termViewRef.value?.handleKeyCode(code, 0) }
    fun sendCtrl(cp: Int) { termViewRef.value?.inputCodePoint(cp, true, false); ctrlMenuOpen = false }
    fun sendAlt(cp: Int) { termViewRef.value?.inputCodePoint(cp, false, true); altMenuOpen = false }
    fun sendLiteral(ch: Char) { termViewRef.value?.inputCodePoint(ch.code, false, false) }

    fun terminalText(): String? =
        runCatching { active.session.emulator?.screen?.transcriptText }.getOrNull()

    fun askAi(mode: AiCopilot.Mode) {
        if (!Secrets.hasApiKey(ctx)) { settingsOpen = true; return }
        val prompt = if (mode == AiCopilot.Mode.COMMAND) aiInput.trim()
            else "Explain the recent output and any error, and suggest a fix if applicable."
        if (mode == AiCopilot.Mode.COMMAND && prompt.isEmpty()) return
        aiError = null; aiResult = null; aiBusy = true
        aiIsCommand = mode == AiCopilot.Mode.COMMAND
        val termText = terminalText()
        scope.launch {
            val res = AiCopilot.ask(ctx, prompt, termText, mode)
            aiBusy = false
            res.onSuccess { aiResult = it; if (aiIsCommand) aiInput = "" }
                .onFailure { aiError = it.message ?: "Request failed." }
        }
    }

    fun insertToTerminal(cmd: String, run: Boolean) {
        val text = cmd.trim()
        active.session.write(if (run && !text.endsWith("\n")) "$text\n" else text)
        aiResult = null
        copilotOpen = false
        termViewRef.value?.let { it.requestFocus(); viewClient.showKeyboard() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRail(
            sessions = sessions.map { it.label.value to it.alive.value },
            activeIndex = activeIndex,
            onSelect = { activeIndex = it },
            onClose = { closeSession(it) },
            onAdd = { addSession(SessionMode.SYSTEM) },
            onAddLinux = { addLinux() },
            onLinuxManage = { linuxManageOpen = true },
            onTheme = { themeOpen = true },
            onCopilot = { copilotOpen = !copilotOpen },
            onSettings = { settingsOpen = true },
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(RailBg)) {
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { c ->
                    TerminalView(c, null).apply {
                        setTerminalViewClient(viewClient)
                        viewClient.view = this
                        keepScreenOn = true
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setTextSize(fontPx) // creates the renderer; must precede setTypeface
                        setTypeface(mono)
                        attachSession(active.session)
                        onScreenUpdated()
                        termViewRef.value = this
                        post { viewClient.showKeyboard() }
                    }
                },
                update = { v ->
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

        if (copilotOpen) {
            CopilotPanel(
                input = aiInput,
                onInput = { aiInput = it },
                busy = aiBusy,
                result = aiResult,
                error = aiError,
                isCommand = aiIsCommand,
                onSend = { askAi(AiCopilot.Mode.COMMAND) },
                onExplain = { askAi(AiCopilot.Mode.EXPLAIN) },
                onInsert = { insertToTerminal(it, run = false) },
                onRun = { insertToTerminal(it, run = true) },
                onSettings = { settingsOpen = true },
            )
        }

        if (!hardwareKeyboardAttached) {
            AnimatedVisibility(visible = extraKeysOpen, enter = expandVertically(), exit = shrinkVertically()) {
                when {
                    ctrlMenuOpen -> CtrlMenu(onKey = { cp -> sendCtrl(cp) }, onBack = { ctrlMenuOpen = false })
                    altMenuOpen -> AltMenu(onKey = { cp -> sendAlt(cp) }, onBack = { altMenuOpen = false })
                    else -> ExtraKeysRow(
                        onEsc = { sendKey(KeyEvent.KEYCODE_ESCAPE) },
                        onTab = { sendKey(KeyEvent.KEYCODE_TAB) },
                        onCtrl = { ctrlMenuOpen = true },
                        onAlt = { altMenuOpen = true },
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
            )
        }
    }

    if (settingsOpen) {
        SettingsDialog(
            initialKey = Secrets.apiKey(ctx),
            initialModel = Secrets.model(ctx),
            initialBase = Secrets.baseUrl(ctx),
            onSave = { key, model, base ->
                Secrets.setApiKey(ctx, key)
                Secrets.setModel(ctx, model)
                Secrets.setBaseUrl(ctx, base)
                settingsOpen = false
                copilotOpen = true
            },
            onDismiss = { settingsOpen = false },
        )
    }

    if (distroPickerOpen) {
        DistroPickerDialog(
            onPick = { installDistro(it) },
            onDismiss = { distroPickerOpen = false },
        )
    }

    if (linuxManageOpen) {
        LinuxManageDialog(
            distro = Userland.installedDistro(ctx),
            onUninstall = { uninstallLinux() },
            onDismiss = { linuxManageOpen = false },
        )
    }

    if (themeOpen) {
        ThemePickerDialog(
            currentId = currentThemeId,
            onPick = { setTheme(it) },
            onDismiss = { themeOpen = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRail(
    sessions: List<Pair<String, Boolean>>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onAdd: () -> Unit,
    onAddLinux: () -> Unit,
    onLinuxManage: () -> Unit,
    onTheme: () -> Unit,
    onCopilot: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .horizontalScroll(rememberScrollState())
            .padding(6.dp, 6.dp, 6.dp, 0.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sessions.forEachIndexed { i, (label, alive) ->
            val active = i == activeIndex
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(if (active) RailBg else RailSurface)
                    .clickable { onSelect(i) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(
                        "●",
                        color = if (alive) RailAccent else RailDimText,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(end = 5.dp),
                    )
                    Text(
                        label,
                        color = if (active) RailPromptText else RailAccentDim,
                        fontFamily = RailMono,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        fontSize = 12.sp,
                    )
                    if (active) {
                        Text(
                            "✕",
                            color = RailDimText,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onClose(i) }
                                .padding(horizontal = 3.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (active) RailAccent else Color.Transparent),
                )
            }
        }
        Text(
            "+",
            color = RailAccentDim,
            fontFamily = RailMono,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .clickable { onAdd() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
        // New Linux session — opens the distro picker on first use, then just
        // opens a session. Long-press to manage/uninstall the installed distro.
        Text(
            "🐧",
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .combinedClickable(onClick = { onAddLinux() }, onLongClick = { onLinuxManage() })
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        // Terminal color theme picker.
        Text(
            "🎨",
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .clickable { onTheme() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        // AI copilot toggle.
        Text(
            "✨",
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .clickable { onCopilot() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        // Settings.
        Text(
            "⚙",
            color = RailAccentDim,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .clickable { onSettings() }
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
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

// Scrollable so every key stays reachable on narrow phones even as the row
// grows — everything a real shell/ssh session needs, not just the basics:
// nav (ESC/TAB/arrows/Home/End/PgUp/PgDn/Del), the two modifier menus (CTRL
// combos double as line-editing shortcuts; ALT combos are readline word-jump),
// and the punctuation shell commands and ssh flags/paths lean on most
// (path/pipe separators, flag dash, negation, home-dir tilde, port colon).
@Composable
private fun ExtraKeysRow(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
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
        KeyChip("ESC", onClick = onEsc)
        KeyChip("TAB", onClick = onTab)
        KeyChip("CTRL", onClick = onCtrl)
        KeyChip("ALT", onClick = onAlt)
        KeyChip("←", onClick = onLeft)
        KeyChip("↓", onClick = onDown)
        KeyChip("↑", onClick = onUp)
        KeyChip("→", onClick = onRight)
        KeyChip("HOME", onClick = onHome)
        KeyChip("END", onClick = onEnd)
        KeyChip("PGUP", onClick = onPgUp)
        KeyChip("PGDN", onClick = onPgDown)
        KeyChip("DEL", onClick = onDel)
        KeyChip("-") { onLiteral('-') }
        KeyChip("_") { onLiteral('_') }
        KeyChip("~") { onLiteral('~') }
        KeyChip("/") { onLiteral('/') }
        KeyChip("|") { onLiteral('|') }
        KeyChip(":") { onLiteral(':') }
        KeyChip(".") { onLiteral('.') }
        KeyChip("*") { onLiteral('*') }
        KeyChip("=") { onLiteral('=') }
        KeyChip("\"") { onLiteral('"') }
        KeyChip("'") { onLiteral('\'') }
        KeyChip("$") { onLiteral('$') }
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

@Composable
private fun KeyChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(RailKeyChip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
    }
}

@Composable
private fun ExtraKeysHandle(open: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (open) RailAccentDim else Color(0xFF3A4368)),
        )
    }
}
