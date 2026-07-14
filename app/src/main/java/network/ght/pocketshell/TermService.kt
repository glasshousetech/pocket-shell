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
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import com.termux.terminal.TerminalSession

/** One live terminal tab. State is observable so tab labels/dots update reactively. */
class TermSession(
    val id: Int,
    val label: MutableState<String>,
    val alive: MutableState<Boolean>,
    val session: TerminalSession,
    val mode: SessionMode,
)

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

    fun newSession(mode: SessionMode): TermSession {
        val id = nextId++
        val prefix = if (mode == SessionMode.LINUX) "linux" else "sh"
        val label = mutableStateOf("$prefix $id")
        val alive = mutableStateOf(true)
        lateinit var holder: TermSession
        val session = TermCore.newSession(
            this,
            mode,
            onRedraw = { s -> onRedraw?.invoke(s); TranscriptLogger.onRedraw(this, holder, s) },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { alive.value = false; refreshNotification() },
        )
        holder = TermSession(id, label, alive, session, mode)
        sessions.add(holder)
        acquireWakeLock()
        refreshNotification()
        persistSnapshot()
        return holder
    }

    fun closeSession(holder: TermSession) {
        runCatching { holder.session.finishIfRunning() }
        sessions.remove(holder)
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
            if (entry.mode == SessionMode.LINUX && Userland.installedDistro(this) == null) return@forEach
            runCatching { restoreSession(entry) }
                .onSuccess { restoredAny = true }
        }
        // Consume the snapshot only once something actually came back from it,
        // so a transient failure doesn't silently discard the user's history.
        if (restoredAny) SessionStore.clear(this)
    }

    private fun restoreSession(entry: SessionStore.Saved): TermSession {
        val id = nextId++
        val fallbackPrefix = if (entry.mode == SessionMode.LINUX) "linux" else "sh"
        val label = mutableStateOf(entry.title.ifBlank { "$fallbackPrefix $id" })
        val alive = mutableStateOf(true)
        lateinit var holder: TermSession
        val session = TermCore.newSession(
            this,
            entry.mode,
            cwd = entry.cwd,
            onRedraw = { s -> onRedraw?.invoke(s); TranscriptLogger.onRedraw(this, holder, s) },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { alive.value = false; refreshNotification() },
        )
        // Spawn now, at a default size — TerminalView.attachSession() later just
        // resizes this (reflowing the buffer) rather than respawning, since the
        // emulator already exists. That lets us inject the saved scrollback
        // before any real UI has attached.
        session.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        injectRestoredTranscript(session, entry.transcript)

        holder = TermSession(id, label, alive, session, entry.mode)
        sessions.add(holder)
        acquireWakeLock()
        return holder
    }

    /** Feeds saved scrollback text through the emulator as if it were program output. */
    private fun injectRestoredTranscript(session: TerminalSession, transcript: String) {
        if (transcript.isBlank()) return
        // transcriptText only ever contains rendered characters (no escape codes),
        // but it joins rows with bare '\n'; the emulator needs '\r\n' to actually
        // return to column 0, or replayed lines drift diagonally across the screen.
        val normalized = transcript.replace("\r\n", "\n").replace("\n", "\r\n")
        val marker = "[2m── session restored (best-effort history — not a live process) ──[0m\r\n\r\n"
        val text = "$normalized\r\n$marker"
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
                title = holder.label.value,
                cwd = if (holder.mode == SessionMode.SYSTEM) {
                    runCatching { holder.session.cwd }.getOrNull()
                } else {
                    null // proot's own -w /root always wins; host cwd isn't meaningful.
                },
                transcript = runCatching {
                    holder.session.emulator?.screen?.transcriptText
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
        val n = sessions.size
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Pocket Shell")
            .setContentText(if (n == 1) "1 session running" else "$n sessions running")
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

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
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
