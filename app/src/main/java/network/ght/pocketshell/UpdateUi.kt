package network.ght.pocketshell

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.ght.pocketshell.ui.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screen-side companion to [AppUpdater]: checks while the app is open (at launch, on return after 5 minutes,
 * every 30 minutes) and exposes state to Compose. The hourly background job lives in [UpdateWorker].
 */
object UpdateCoordinator {
    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object Current : Status
        data object StoreManaged : Status
        data class Ready(val name: String) : Status
        data class NeedsPermission(val name: String) : Status
        data class Installing(val name: String) : Status
        data class Waiting(val name: String) : Status
        data class Deferred(val name: String) : Status
        data class Failed(val reason: String) : Status
    }

    enum class PromptKind { READY, PERMISSION }
    data class Prompt(val kind: PromptKind, val name: String)

    private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    private const val RETURN_INTERVAL_MS = 5 * 60 * 1000L

    val status = mutableStateOf<Status>(Status.Idle)
    val prompt = mutableStateOf<Prompt?>(null)
    val note = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val checking = AtomicBoolean(false)
    private var loop: Job? = null
    private var announcedCode = -1
    @Volatile private var lastCheckAt = 0L

    /** Activity onStart: launch and every return to the app. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        AppUpdater.log(app, "info", "updater-started", null)
        AppUpdater.schedule(app)
        loop?.cancel()
        loop = scope.launch {
            if (System.currentTimeMillis() - lastCheckAt > RETURN_INTERVAL_MS) check(app, interactive = false)
            while (true) { delay(CHECK_INTERVAL_MS); check(app, interactive = false) }
        }
    }

    /** Activity onStop: the 30 minute timer only runs while the app is open. */
    fun stop() { loop?.cancel(); loop = null }

    fun checkNow(ctx: Context) = scope.launch { check(ctx.applicationContext, interactive = true) }
    fun installNow(ctx: Context) = scope.launch { check(ctx.applicationContext, interactive = true, installNow = true) }

    private suspend fun check(app: Context, interactive: Boolean, installNow: Boolean = false) {
        if (!checking.compareAndSet(false, true)) return
        lastCheckAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) { if (interactive) status.value = Status.Checking }
        val outcome = withContext(Dispatchers.IO) { AppUpdater.run(app, interactive, installNow) }
        withContext(Dispatchers.Main) {
            checking.set(false)
            val name = outcome.versionName ?: ""
            when (outcome.state) {
                AppUpdater.State.CURRENT -> { status.value = Status.Current; prompt.value = null }
                AppUpdater.State.STORE_MANAGED -> status.value = Status.StoreManaged
                AppUpdater.State.DISABLED -> if (status.value == Status.Checking) status.value = Status.Idle
                AppUpdater.State.FAILED -> if (interactive) status.value = Status.Failed(outcome.detail ?: "Check your connection and try again.")
                    else if (status.value == Status.Checking) status.value = Status.Idle
                AppUpdater.State.INSTALLING -> status.value = Status.Installing(name)
                AppUpdater.State.DEFERRED_SESSIONS -> status.value = Status.Deferred(name)
                AppUpdater.State.NEEDS_PERMISSION -> {
                    status.value = Status.NeedsPermission(name)
                    if (interactive || announcedCode != outcome.versionCode) {
                        announcedCode = outcome.versionCode; prompt.value = Prompt(PromptKind.PERMISSION, name)
                    }
                }
                AppUpdater.State.READY, AppUpdater.State.WAITING_FOR_CONFIRMATION -> {
                    status.value = if (outcome.state == AppUpdater.State.READY) Status.Ready(name) else Status.Waiting(name)
                    if (interactive || announcedCode != outcome.versionCode) {
                        announcedCode = outcome.versionCode; prompt.value = Prompt(PromptKind.READY, name)
                    }
                }
            }
        }
    }

    /** Share the structured update log (JSON lines) through the Android share sheet. */
    fun shareLog(ctx: Context) {
        val source = AppUpdater.logFile(ctx)
        if (!source.exists()) { note.value = "No update log yet."; return }
        try {
            val dir = File(ctx.cacheDir, "attachments").also { it.mkdirs() }
            val copy = File(dir, "pocketshell-${BuildConfig.VERSION_NAME}-updates.log")
            source.copyTo(copy, overwrite = true)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", copy)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Pocket Shell update log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AppUpdater.log(ctx, "info", "log-exported", null)
            ctx.startActivity(Intent.createChooser(send, "Share update log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            AppUpdater.log(ctx, "warn", "log-export-failed", e.javaClass.simpleName)
            note.value = "The update log could not be shared."
        }
    }
}

/** Hosts the two update dialogs. Placed once at the root of the activity's content. */
@Composable
fun UpdatePrompts() {
    val ctx = LocalContext.current
    val prompt by UpdateCoordinator.prompt
    val p = prompt ?: return
    val dismiss = { UpdateCoordinator.prompt.value = null }
    val live = TermService.hasLiveWork()
    when (p.kind) {
        UpdateCoordinator.PromptKind.READY -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Pocket Shell ${p.name} is ready") },
            text = {
                Text(
                    if (live) "The update is downloaded and verified. Your terminal sessions are still running, so it installs by itself once they have ended and you have left the app."
                    else "The update is downloaded and verified. Restart now to finish, or keep working and it installs on its own a couple of minutes after you leave the app.",
                )
            },
            confirmButton = {
                if (!live) TextButton(onClick = { dismiss(); UpdateCoordinator.installNow(ctx) }) { Text("Restart now") }
                else TextButton(onClick = dismiss) { Text("OK") }
            },
            dismissButton = { if (!live) TextButton(onClick = dismiss) { Text("Later") } },
        )
        UpdateCoordinator.PromptKind.PERMISSION -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Let Pocket Shell update itself") },
            text = {
                Text(
                    "Pocket Shell ${p.name} is downloaded and verified. Android needs a one-time permission so Pocket Shell can install its own updates. " +
                        "Turn on Allow from this source, then come back. After that, updates install automatically. Pocket Shell works fine without it.",
                )
            },
            confirmButton = { TextButton(onClick = { dismiss(); (ctx.findActivity() as? MainActivity)?.openInstallPermission() }) { Text("Open setting") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Not now") } },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is android.content.ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Settings > Updates. */
@Composable
internal fun UpdatesSection() {
    val ctx = LocalContext.current
    val status by UpdateCoordinator.status
    val note by UpdateCoordinator.note
    var auto by remember { mutableStateOf(AppUpdater.autoEnabled(ctx)) }
    val direct = BuildConfig.DIRECT_UPDATES
    var canInstall by remember { mutableStateOf(AppUpdater.canInstall(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) canInstall = AppUpdater.canInstall(ctx) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text("Updates", color = RailPromptText, fontFamily = RailMono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Text("Pocket Shell v${BuildConfig.VERSION_NAME}", color = RailDimText, fontFamily = RailMono, fontSize = 11.sp)
    if (!direct) {
        Text("Google Play updates this copy of Pocket Shell.", color = RailDimText, fontFamily = RailMono, fontSize = 11.sp)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = auto,
            onCheckedChange = { auto = it; AppUpdater.setAutoEnabled(ctx, it) },
            colors = SwitchDefaults.colors(checkedTrackColor = RailAccent, checkedThumbColor = RailPromptText),
        )
        Text(
            "Automatic updates. Pocket Shell checks its home server, downloads updates in the app and installs them after you leave it, never while a terminal session is running.",
            color = RailDimText, fontFamily = RailMono, fontSize = 10.sp, lineHeight = 15.sp,
        )
    }
    val line: Pair<String, Boolean>? = when (val s = status) {
        UpdateCoordinator.Status.Idle -> null
        UpdateCoordinator.Status.Checking -> "Checking…" to false
        UpdateCoordinator.Status.Current -> "You're up to date." to false
        UpdateCoordinator.Status.StoreManaged -> "Google Play manages updates for this copy." to false
        is UpdateCoordinator.Status.Ready -> "v${s.name} is downloaded and verified. It installs after you leave the app." to true
        is UpdateCoordinator.Status.NeedsPermission -> "v${s.name} is downloaded. Allow installs from Pocket Shell to finish." to true
        is UpdateCoordinator.Status.Installing -> "Installing v${s.name}. The app restarts for a moment." to true
        is UpdateCoordinator.Status.Waiting -> "v${s.name} is waiting for your confirmation in Android's installer." to true
        is UpdateCoordinator.Status.Deferred -> "v${s.name} is downloaded. It installs after your terminal sessions end." to true
        is UpdateCoordinator.Status.Failed -> "Couldn't update: ${s.reason}" to false
    }
    line?.let { (text, accent) -> Text(text, color = if (accent) RailAccent else RailAccentDim, fontFamily = RailMono, fontSize = 11.sp, lineHeight = 16.sp) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AiChip("Check for updates", { UpdateCoordinator.checkNow(ctx) }, Modifier.fillMaxWidth())
        if (!canInstall) {
            AiChip("Allow Pocket Shell to install updates", { (ctx.findActivity() as? MainActivity)?.openInstallPermission() }, Modifier.fillMaxWidth(), accent = true)
            Text("Optional one-time Android permission (Install unknown apps). Without it you confirm each update in Android's installer.", color = RailDimText, fontSize = 11.sp)
        } else {
            Text("Install permission granted.", color = RailDimText, fontFamily = RailMono, fontSize = 11.sp)
        }
        AiChip("Share update log", { UpdateCoordinator.shareLog(ctx) }, Modifier.fillMaxWidth())
        note?.let { Text(it, color = RailDimText, fontSize = 11.sp) }
    }
}
