package com.railterm.app

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.railterm.app.ui.*

private data class Session(val id: Int, val label: String, val shell: ShellSession)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real frosted-glass behind the window (Kali-style translucent terminal),
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
    val scope = rememberCoroutineScope()
    val sessions = remember { mutableStateListOf<Session>() }
    var activeIndex by remember { mutableIntStateOf(0) }
    var nextId by remember { mutableIntStateOf(1) }
    var extraKeysOpen by remember { mutableStateOf(false) }
    var ctrlMenuOpen by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateMapOf<Int, String>() }

    fun addSession() {
        val id = nextId++
        val shell = ShellSession(scope)
        shell.start()
        sessions.add(Session(id, "sh $id", shell))
        activeIndex = sessions.size - 1
    }

    LaunchedEffect(Unit) { if (sessions.isEmpty()) addSession() }

    // Real fix, not a demo toggle: reflects whether a hardware keyboard is
    // actually attached and exposed right now.
    val config = LocalConfiguration.current
    val hardwareKeyboardAttached = config.keyboard == Configuration.KEYBOARD_QWERTY &&
        config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

    if (sessions.isEmpty()) return

    val active = sessions[activeIndex.coerceIn(0, sessions.size - 1)]

    Column(modifier = Modifier.fillMaxSize()) {
        TabRail(
            sessions = sessions.map { it.label to it.shell.isAlive.value },
            activeIndex = activeIndex,
            onSelect = { activeIndex = it },
            onAdd = { addSession() },
        )

        Crossfade(targetState = active.id, modifier = Modifier.weight(1f), label = "session") { id ->
            val shown = sessions.firstOrNull { it.id == id } ?: active
            TerminalBody(text = shown.shell.output.value, modifier = Modifier.fillMaxSize())
        }

        if (!hardwareKeyboardAttached) {
            AnimatedVisibility(visible = extraKeysOpen, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    if (ctrlMenuOpen) {
                        CtrlMenu(onKey = { byte ->
                            active.shell.sendControlByte(byte)
                            ctrlMenuOpen = false
                        })
                    } else {
                        ExtraKeysRow(
                            onEsc = { active.shell.sendControlByte(0x1B) },
                            onTab = { active.shell.sendControlByte(0x09) },
                            onCtrl = { ctrlMenuOpen = true },
                            onLiteral = { ch -> drafts[active.id] = (drafts[active.id] ?: "") + ch },
                            onArrow = { seq -> active.shell.sendEscape("$seq") },
                        )
                    }
                }
            }
            ExtraKeysHandle(
                open = extraKeysOpen,
                onToggle = { extraKeysOpen = !extraKeysOpen; ctrlMenuOpen = false },
            )
        }

        InputBar(
            value = drafts[active.id] ?: "",
            onValueChange = { drafts[active.id] = it },
            onSubmit = {
                active.shell.sendLine(drafts[active.id] ?: "")
                drafts[active.id] = ""
            },
        )
    }
}

@Composable
private fun TabRail(
    sessions: List<Pair<String, Boolean>>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
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
    }
}

@Composable
private fun TerminalBody(text: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) { scrollState.animateScrollTo(scrollState.maxValue) }

    val shown = text.ifEmpty { "starting shell…" }
    val priorLines = shown.substringBeforeLast("\n", "")
    val liveLine = if (priorLines.isEmpty()) shown else shown.substringAfterLast("\n")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(RailBg)
            .verticalScroll(scrollState)
            .padding(16.dp, 12.dp),
    ) {
        if (priorLines.isNotEmpty()) {
            Text(
                text = priorLines,
                color = RailOutText,
                fontFamily = RailMono,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = liveLine,
                color = RailOutText,
                fontFamily = RailMono,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            BlinkingCursor(RailPromptText)
        }
    }
}

@Composable
private fun BlinkingCursor(color: Color) {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(600), repeatMode = RepeatMode.Reverse),
        label = "cursorAlpha",
    )
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .width(7.dp)
            .height(15.dp)
            .background(color.copy(alpha = alpha)),
    )
}

@Composable
private fun ExtraKeysRow(
    onEsc: () -> Unit,
    onTab: () -> Unit,
    onCtrl: () -> Unit,
    onLiteral: (String) -> Unit,
    onArrow: (String) -> Unit,
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
        KeyChip("|", Modifier.weight(1f)) { onLiteral("|") }
        KeyChip("-", Modifier.weight(1f)) { onLiteral("-") }
        KeyChip("←", Modifier.weight(1f)) { onArrow("[D") }
        KeyChip("→", Modifier.weight(1f)) { onArrow("[C") }
    }
}

@Composable
private fun CtrlMenu(onKey: (Int) -> Unit) {
    val combos = listOf("C" to 0x03, "D" to 0x04, "Z" to 0x1A, "L" to 0x0C, "A" to 0x01, "E" to 0x05)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(RailSurfaceAlt)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        combos.forEach { (letter, byte) ->
            KeyChip("^$letter", Modifier.weight(1f)) { onKey(byte) }
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

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(RailAccentDim.copy(alpha = 0.25f)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RailSurface)
                .padding(12.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", color = RailAccent, fontFamily = RailMono, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = RailMono,
                    fontSize = 14.sp,
                color = RailPromptText,
            ),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
            ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            )
        }
    }
}
