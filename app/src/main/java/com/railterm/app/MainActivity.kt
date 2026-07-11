package com.railterm.app

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.railterm.app.ui.*
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private class Session(
    val id: Int,
    val label: MutableState<String>,
    val alive: MutableState<Boolean>,
    val session: TerminalSession,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Clip.init(this)

        // Real frosted-glass behind the window (Kali-style translucent chrome),
        // not just a tinted overlay. Older devices still get the plain see-through
        // wallpaper from the theme, just without the blur.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 80 }
        }

        setContent {
            RailtermTheme {
                Surface(color = Color.Transparent) {
                    RailtermApp()
                }
            }
        }
    }
}

@Composable
private fun RailtermApp() {
    val configuration = LocalConfiguration.current // recomposes on config change
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val fontPx = remember { with(density) { 13.sp.toPx() }.toInt().coerceAtLeast(18) }
    val mono = remember {
        runCatching { ResourcesCompat.getFont(ctx, R.font.jbm_regular) }.getOrNull() ?: Typeface.MONOSPACE
    }

    val sessions = remember { mutableStateListOf<Session>() }
    var activeIndex by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(1) }
    var extraKeysOpen by remember { mutableStateOf(false) }
    var ctrlMenuOpen by remember { mutableStateOf(false) }

    val termViewRef = remember { mutableStateOf<TerminalView?>(null) }
    val viewClient = remember { RailViewClient(ctx, fontPx) }
    val scope = rememberCoroutineScope()
    var setupStatus by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    fun addSession(mode: SessionMode) {
        val id = nextId++
        val prefix = if (mode == SessionMode.ALPINE) "linux" else "sh"
        val label = mutableStateOf("$prefix $id")
        val alive = mutableStateOf(true)
        val session = TermCore.newSession(
            ctx,
            mode,
            onRedraw = { s -> termViewRef.value?.let { if (it.currentSession === s) it.onScreenUpdated() } },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { alive.value = false },
        )
        sessions.add(Session(id, label, alive, session))
        activeIndex = sessions.size - 1
        termViewRef.value?.let {
            it.attachSession(session)
            it.onScreenUpdated()
            viewClient.showKeyboard()
        }
    }

    // First tap on Linux installs the Alpine userland (once), then opens a session.
    fun addLinux() {
        if (Userland.isInstalled(ctx)) { addSession(SessionMode.ALPINE); return }
        if (!Userland.isAvailable(ctx)) { setupError = "Linux isn't available for this device's CPU."; return }
        setupError = null
        setupStatus = "Preparing Alpine Linux…"
        scope.launch {
            val result = Bootstrap.install(ctx) { msg ->
                scope.launch(Dispatchers.Main) { setupStatus = msg }
            }
            setupStatus = null
            result.onSuccess { addSession(SessionMode.ALPINE) }
                .onFailure { setupError = it.message ?: "Install failed." }
        }
    }

    LaunchedEffect(Unit) { if (sessions.isEmpty()) addSession(SessionMode.SYSTEM) }
    if (sessions.isEmpty()) return

    val active = sessions[activeIndex.coerceIn(0, sessions.size - 1)]

    // Reflects whether a hardware keyboard is actually attached right now.
    val hardwareKeyboardAttached = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    fun sendKey(code: Int) { termViewRef.value?.handleKeyCode(code, 0) }
    fun sendCtrl(cp: Int) { termViewRef.value?.inputCodePoint(cp, true, false); ctrlMenuOpen = false }
    fun sendLiteral(ch: Char) { termViewRef.value?.inputCodePoint(ch.code, false, false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRail(
            sessions = sessions.map { it.label.value to it.alive.value },
            activeIndex = activeIndex,
            onSelect = { activeIndex = it },
            onAdd = { addSession(SessionMode.SYSTEM) },
            onAddLinux = { addLinux() },
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

        if (!hardwareKeyboardAttached) {
            AnimatedVisibility(visible = extraKeysOpen, enter = expandVertically(), exit = shrinkVertically()) {
                if (ctrlMenuOpen) {
                    CtrlMenu(onKey = { cp -> sendCtrl(cp) })
                } else {
                    ExtraKeysRow(
                        onEsc = { sendKey(KeyEvent.KEYCODE_ESCAPE) },
                        onTab = { sendKey(KeyEvent.KEYCODE_TAB) },
                        onCtrl = { ctrlMenuOpen = true },
                        onLiteral = { ch -> sendLiteral(ch) },
                        onUp = { sendKey(KeyEvent.KEYCODE_DPAD_UP) },
                        onDown = { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                        onLeft = { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                        onRight = { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                    )
                }
            }
            ExtraKeysHandle(
                open = extraKeysOpen,
                onToggle = { extraKeysOpen = !extraKeysOpen; ctrlMenuOpen = false },
            )
        }
    }
}

@Composable
private fun TabRail(
    sessions: List<Pair<String, Boolean>>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onAddLinux: () -> Unit,
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
        // New Linux (Alpine) session — installs the userland on first use.
        Text(
            "🐧",
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(RailSurface)
                .clickable { onAddLinux() }
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
                "One-time download (~3 MB). Then apk, python, git, ssh all work.",
                color = RailDimText,
                fontFamily = RailMono,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ExtraKeysRow(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrl: () -> Unit,
    onLiteral: (Char) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KeyChip("ESC", Modifier.weight(1f), onEsc)
        KeyChip("TAB", Modifier.weight(1f), onTab)
        KeyChip("CTRL", Modifier.weight(1f), onCtrl)
        KeyChip("|", Modifier.weight(1f)) { onLiteral('|') }
        KeyChip("/", Modifier.weight(1f)) { onLiteral('/') }
        KeyChip("↑", Modifier.weight(1f), onUp)
        KeyChip("↓", Modifier.weight(1f), onDown)
        KeyChip("←", Modifier.weight(1f), onLeft)
        KeyChip("→", Modifier.weight(1f), onRight)
    }
}

@Composable
private fun CtrlMenu(onKey: (Int) -> Unit) {
    val combos = listOf("C" to 'c', "D" to 'd', "Z" to 'z', "L" to 'l', "A" to 'a', "E" to 'e', "R" to 'r')
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        combos.forEach { (letter, ch) ->
            KeyChip("^$letter", Modifier.weight(1f)) { onKey(ch.code) }
        }
    }
}

@Composable
private fun KeyChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(RailKeyChip)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
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
