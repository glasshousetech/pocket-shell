package com.railterm.app

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
import android.os.IBinder
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
)

/**
 * Foreground service that OWNS the terminal sessions. This is what makes Railterm
 * a real terminal: shells (and long-running commands) survive the app being
 * backgrounded or the Activity being recreated, because Android won't reap a
 * foreground-service process. A partial wake lock keeps work alive with the
 * screen off; a persistent notification shows session count + an Exit action.
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
        val session = TermCore.newSession(
            this,
            mode,
            onRedraw = { s -> onRedraw?.invoke(s) },
            onTitle = { s -> s.title?.takeIf { it.isNotBlank() }?.let { label.value = it } },
            onFinished = { alive.value = false; refreshNotification() },
        )
        val holder = TermSession(id, label, alive, session)
        sessions.add(holder)
        acquireWakeLock()
        refreshNotification()
        return holder
    }

    fun closeSession(holder: TermSession) {
        runCatching { holder.session.finishIfRunning() }
        sessions.remove(holder)
        // The UI always keeps at least one session open, so we don't self-stop here;
        // the user exits explicitly via the notification's Exit action.
        refreshNotification()
    }

    private fun shutdown() {
        onRedraw = null
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
            .setContentTitle("Railterm")
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "railterm:sessions").apply {
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
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "railterm_sessions"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.railterm.app.STOP"
    }
}
