package network.ght.pocketshell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Remote commands survive the phone process. This service observes persisted server results. */
class JobsWorkbench(
    private val context: Context, private val scope: CoroutineScope, private val onActivityChanged: () -> Unit = {},
) : AutoCloseable {
    var jobs by mutableStateOf<List<RemoteJob>>(emptyList())
    var loaded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var monitoring by mutableStateOf(false)
    var profile by mutableStateOf<SshProfile?>(null)
    var name by mutableStateOf("")
    var command by mutableStateOf("")
    var directory by mutableStateOf("")
    var review by mutableStateOf<RemoteJob?>(null)
    var editorOpen by mutableStateOf(true)
    var checkSettingsOpen by mutableStateOf(false)
    private val preferences = context.getSharedPreferences("remote_jobs", Context.MODE_PRIVATE)
    var timeoutInput by mutableStateOf(preferences.getLong("timeout", 120).toString())
    var intervalInput by mutableStateOf(preferences.getLong("interval", 10).toString())
    private var timeoutSeconds = preferences.getLong("timeout", 120).coerceAtLeast(1)
    private var intervalSeconds = preferences.getLong("interval", 10).coerceAtLeast(1)
    private val storage = Mutex()
    private val hosts = mutableMapOf<SshProfile, Mutex>()
    private var active = 0
    private var watcher: Job? = null
    private var disposed = false
    private var paused = false
    internal var available: () -> Boolean = { Userland.installedDistro(context) != null }
    internal var request: suspend (SshProfile, String, Long) -> GuestResult = { host, script, timeout ->
        GuestCommand.run(context, RemoteJobs.command(host, script, timeout), timeout)
    }

    init {
        scope.launch {
            try {
                storage.withLock { jobs = withContext(Dispatchers.IO) { JobStore.load(context) } }
                editorOpen = jobs.isEmpty()
                loaded = true
                ensureWatcher()
            } catch (failure: Exception) {
                error = "Saved jobs could not be read. Existing records were kept."
                DiagnosticLog.record(context, "remote_jobs", "load_failed", failure.javaClass.simpleName)
            }
        }
    }
    private fun hostGate(profile: SshProfile) = hosts.getOrPut(profile) { Mutex() }
    private suspend fun create(job: RemoteJob) = storage.withLock {
        jobs = withContext(Dispatchers.IO) { JobStore.create(context, job) }
    }
    private suspend fun update(id: String, change: (RemoteJob) -> RemoteJob) = storage.withLock {
        jobs = withContext(Dispatchers.IO) { JobStore.update(context, id, change) }
    }
    fun applySettings() {
        val timeout = timeoutInput.toLongOrNull()
        val interval = intervalInput.toLongOrNull()
        if (timeout == null || interval == null || timeout <= 0 || interval <= 0 || interval > Long.MAX_VALUE / 1000) {
            error = "Enter positive timeout and check-interval values in seconds."; return
        }
        if (!preferences.edit().putLong("timeout", timeout).putLong("interval", interval).commit()) {
            error = "Job settings could not be saved."; return
        }
        timeoutSeconds = timeout; intervalSeconds = interval; error = null
        ensureWatcher()
    }
    fun prepare() {
        error = null
        review = runCatching {
            val host = requireNotNull(profile) { "Choose a Linux host." }
            RemoteJob(UUID.randomUUID().toString(), name, host, command, directory, System.currentTimeMillis()).validate().also {
                RemoteJobs.command(host, RemoteJobs.launchScript(it), timeoutSeconds)
            }
        }.getOrElse { error = it.message ?: "Check the job details."; null }
    }
    private fun operation(event: String, block: suspend () -> Unit) {
        active++; busy = true; onActivityChanged()
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                error = failure.message ?: "The job operation could not be confirmed."
                DiagnosticLog.record(context, "remote_jobs", event + "_failed", failure.javaClass.simpleName)
            } finally {
                active--; busy = active > 0
                if (!disposed) { ensureWatcher(); onActivityChanged() }
            }
        }
    }
    fun launch(job: RemoteJob) {
        if (!loaded) { error = "Saved jobs must be readable before starting new work."; return }
        review = null
        operation("launch") {
            check(!paused) { "Linux removal is in progress. The job has not been submitted." }
            // Store immutable identity and exact command before the first remote write.
            val existing = jobs.firstOrNull { it.id == job.id }
            if (existing == null) create(job.validate())
            editorOpen = false
            hostGate(job.profile).withLock {
                val current = jobs.first { it.id == job.id }
                try {
                    check(!paused) { "Linux removal is in progress. Refresh this job after Linux is available." }
                    val result = request(current.profile, RemoteJobs.launchScript(current), timeoutSeconds)
                    val observation = RemoteJobs.parse(result, listOf(current.id))[current.id]!!
                    accept(current.id, observation)
                    error = null
                    DiagnosticLog.record(context, "remote_jobs", "launch_observed")
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) {
                    update(current.id) { it.copy(connectionError = "Launch was not confirmed. Refresh status or retry this same job identity; do not assume it failed to start.") }
                    throw failure
                }
            }
        }
    }
    fun refresh() {
        if (!loaded) return
        operation("refresh") { refreshJobs(jobs.map { it.id }.toSet()) }
    }
    private suspend fun refreshJobs(ids: Set<String>) = coroutineScope {
        jobs.filter { it.id in ids }.groupBy { it.profile }.map { (host, selected) ->
            async {
                hostGate(host).withLock {
                    val targetIds = selected.map { it.id }
                    try {
                        check(!paused) { "Linux removal is in progress." }
                        val observations = RemoteJobs.parse(request(host, RemoteJobs.statusScript(targetIds), timeoutSeconds), targetIds)
                        observations.forEach { (id, result) -> accept(id, result) }
                        DiagnosticLog.record(context, "remote_jobs", "status_observed")
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (failure: Exception) {
                        selected.forEach { selectedJob ->
                            update(selectedJob.id) { it.copy(connectionError =
                                "Connection check failed. The displayed result is the last verified observation; remote work may still be running.") }
                        }
                        DiagnosticLog.record(context, "remote_jobs", "status_unavailable", failure.javaClass.simpleName)
                    }
                }
            }
        }.awaitAll()
        Unit
    }
    private suspend fun accept(id: String, observation: JobObservation) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        update(job.id) { it.copy(observation = observation, connectionError = null) }
        val current = jobs.first { it.id == id }
        if (current.watching && observation.resultKey != null && current.notifiedResult != observation.resultKey && runCatching { notifyResult(current) }.getOrElse {
                error = "The result was saved, but its notification could not be posted."
                DiagnosticLog.record(context, "remote_jobs", "notification_failed", it.javaClass.simpleName)
                false
            }) {
            update(current.id) { it.copy(notifiedResult = observation.resultKey) }
        }
    }
    fun setWatching(job: RemoteJob, watching: Boolean) {
        operation("watch") {
            update(job.id) { it.copy(watching = watching) }
        }
    }
    private fun ensureWatcher() {
        if (disposed || paused || !loaded || watcher?.isActive == true) return
        if (jobs.none { it.watching && it.observation?.terminal != true } || !available()) return
        watcher = scope.launch {
            monitoring = true; onActivityChanged()
            var normalExit = false
            try {
                while (isActive && available()) {
                    val ids = jobs.filter { it.watching && it.observation?.terminal != true }.map { it.id }.toSet()
                    if (ids.isEmpty()) break
                    refreshJobs(ids)
                    if (jobs.none { it.watching && it.observation?.terminal != true }) break
                    delay(intervalSeconds * 1000)
                }
                normalExit = true
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                error = "Job monitoring paused because its state could not be saved. Existing records were kept."
                DiagnosticLog.record(context, "remote_jobs", "monitoring_failed", failure.javaClass.simpleName)
            } finally {
                monitoring = false; watcher = null
                if (!disposed) { onActivityChanged(); if (normalExit) ensureWatcher() }
            }
        }
    }
    private fun notifyResult(job: RemoteJob): Boolean {
        val result = job.observation ?: return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Remote job results", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).setAction(OPEN_JOBS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = PendingIntent.getActivity(context, 2001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = when (result.state) {
            "SUCCEEDED" -> "Finished successfully on " + job.profile.name
            "FAILED" -> "Exited with code " + result.exit + " on " + job.profile.name
            else -> "The job process ended without a confirmed exit result on " + job.profile.name
        }
        manager.notify(job.id, 2001, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal).setContentTitle(job.name).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build())
        DiagnosticLog.record(context, "remote_jobs", "result_notification_posted")
        return true
    }
    suspend fun withoutRequests(block: suspend () -> Result<Unit>): Result<Unit> {
        paused = true
        try {
            watcher?.cancelAndJoin()
            hosts.values.toList().forEach { gate -> gate.withLock { } }
            return block()
        } finally { paused = false; ensureWatcher() }
    }
    override fun close() { disposed = true; watcher?.cancel() }
    companion object {
        const val CHANNEL = "pocketshell_remote_jobs"
        const val OPEN_JOBS = "network.ght.pocketshell.OPEN_REMOTE_JOBS"
    }
}
