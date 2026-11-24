package network.ght.pocketshell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One release advertised by the home server feed. */
data class UpdateRelease(val code: Int, val name: String, val apk: String, val sha256: String, val bytes: Long)

/** Parsing and trust rules for dl.ght.network/pocketshell-latest.json. Pure, so it is unit tested. */
object UpdateFeed {
    const val APP_KEY = "pocketshell"
    const val FEED_URL = "https://dl.ght.network/$APP_KEY-latest.json"

    fun parse(json: String, appKey: String = APP_KEY): UpdateRelease {
        val latest = JSONObject(json)
        val code = latest.getInt("versionCode")
        val raw = latest.optString("apkUrl", "").ifEmpty { latest.getString("url") }
        if (!isTrustedApkUrl(raw, appKey)) throw IOException("Untrusted update location")
        val sha = latest.optString("sha256", "").lowercase(Locale.ROOT)
        if (!Regex("[0-9a-f]{64}").matches(sha)) throw IOException("Update metadata has no SHA-256")
        return UpdateRelease(code, latest.optString("versionName", code.toString()), raw, sha, latest.optLong("bytes", -1))
    }

    /** Only https://dl.ght.network/apps/<key>/... or /<key>-... ending in .apk. */
    fun isTrustedApkUrl(raw: String, appKey: String = APP_KEY): Boolean {
        val uri = try { URI(raw) } catch (_: Exception) { return false }
        val path = uri.path ?: return false
        val rawPath = uri.rawPath ?: return false
        return uri.scheme == "https" && uri.host == "dl.ght.network" && uri.userInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawQuery == null && uri.rawFragment == null &&
            (path.startsWith("/apps/$appKey/") || path.startsWith("/$appKey-")) &&
            path.endsWith(".apk") && !path.contains("..") && !rawPath.contains("%2e", ignoreCase = true) &&
            !rawPath.contains("%2f", ignoreCase = true)
    }
}

/**
 * In-app updater. It asks the home server (dl.ght.network) for the newest release, downloads the APK
 * inside the app, verifies size, SHA-256, package name, version and signing certificate, then installs it
 * through PackageInstaller as a self-update. With "Install unknown apps" allowed for Pocket Shell Android
 * installs it without a prompt. Updates are never handed to a web browser, and never installed while a
 * terminal session is live or the app is on screen (unless the person chose Restart now and nothing is live).
 */
object AppUpdater {
    enum class State { DISABLED, STORE_MANAGED, CURRENT, READY, NEEDS_PERMISSION, INSTALLING, WAITING_FOR_CONFIRMATION, DEFERRED_SESSIONS, FAILED }

    data class Outcome(val state: State, val versionName: String? = null, val versionCode: Int = 0, val detail: String? = null)

    const val PREFS = "ght-app-updates"
    const val ACTION_RESULT = "network.ght.pocketshell.APP_UPDATE_RESULT"
    const val EXTRA_NAME = "versionName"
    const val EXTRA_CODE = "versionCode"
    private const val CHANNEL_ACTION = "app-updates"
    private const val CHANNEL_DONE = "app-updates-done"
    private const val NOTE_ACTION = 7101
    private const val NOTE_DONE = 7102
    private const val PERIODIC = "ght-app-update-periodic"
    private const val ON_LEAVE = "ght-app-update-on-leave"
    private val LOCK = Any()

    @Volatile private var visibleActivities = 0
    @Volatile private var inSystemFlow = false

    // ---- foreground tracking: never restart the app under someone who is using it ----

    fun activityStarted(c: Context) {
        visibleActivities++
        inSystemFlow = false
        runCatching { WorkManager.getInstance(c).cancelUniqueWork(ON_LEAVE) }
    }

    /**
     * systemFlow = the app only stepped aside for a picker or settings screen it opened itself; never install then.
     * When the person really leaves with a verified update waiting, install it ~2 minutes later via a unique
     * OneTimeWork (the delay skips quick app switches; coming back cancels it).
     */
    fun activityStopped(c: Context, systemFlow: Boolean) {
        if (visibleActivities > 0) visibleActivities--
        inSystemFlow = systemFlow
        if (visibleActivities > 0 || systemFlow || !BuildConfig.DIRECT_UPDATES || !autoEnabled(c)) return
        if (prefs(c).getInt("readyCode", -1) <= BuildConfig.VERSION_CODE) return
        try {
            val request = OneTimeWorkRequest.Builder(UpdateWorker::class.java)
                .setInitialDelay(2, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(ON_LEAVE, ExistingWorkPolicy.REPLACE, request)
            log(c, "info", "install-on-leave-scheduled", prefs(c).getString("readyName", null))
        } catch (e: Exception) {
            log(c, "warn", "install-on-leave-failed", e.javaClass.simpleName)
        }
    }

    fun isVisible(): Boolean = visibleActivities > 0 || inSystemFlow

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean("auto", true)
    fun setAutoEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("auto", on).apply()
        log(c, "info", if (on) "auto-enabled" else "auto-disabled", null)
    }
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()
    fun permissionSettings(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))

    /** Hourly background check. WorkManager keeps it across reboots and app restarts. */
    fun schedule(c: Context) {
        if (!BuildConfig.DIRECT_UPDATES) return
        val request = PeriodicWorkRequest.Builder(UpdateWorker::class.java, 60, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Copies installed by Google Play are updated by Play; the in-app updater stands down for them. */
    fun storeManaged(c: Context): Boolean {
        if (!BuildConfig.DIRECT_UPDATES) return true
        return try {
            val installer = if (Build.VERSION.SDK_INT >= 30) {
                c.packageManager.getInstallSourceInfo(c.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION") c.packageManager.getInstallerPackageName(c.packageName)
            }
            installer == "com.android.vending"
        } catch (_: Exception) { false }
    }

    /**
     * Blocking; call off the main thread. interactive = the person pressed Check for updates.
     * installWhileVisible = the person pressed Restart now.
     */
    fun run(c: Context, interactive: Boolean, installWhileVisible: Boolean): Outcome = synchronized(LOCK) {
        val started = System.currentTimeMillis()
        try {
            if (storeManaged(c)) return Outcome(State.STORE_MANAGED, detail = "Google Play manages updates for this copy.")
            if (!interactive && !autoEnabled(c)) return Outcome(State.DISABLED)
            log(c, "info", if (interactive) "check-started:manual" else "check-started:auto", null)
            val release = fetch(c)
            if (release.code <= BuildConfig.VERSION_CODE) {
                cleanup(c, -1)
                prefs(c).edit().putLong("lastCheckedAt", System.currentTimeMillis()).remove("readyCode").remove("readyName").apply()
                log(c, "info", "current", "latest=${release.code}")
                return Outcome(State.CURRENT, release.name, release.code)
            }
            val apk = download(c, release)
            verify(c, apk, release)
            cleanup(c, release.code)
            prefs(c).edit().putLong("lastCheckedAt", System.currentTimeMillis())
                .putInt("readyCode", release.code).putString("readyName", release.name).apply()
            log(c, "info", "ready", "${release.name} in ${System.currentTimeMillis() - started}ms")
            if (!canInstall(c)) {
                log(c, "warn", "needs-install-permission", release.name)
                if (!isVisible() && prefs(c).getInt("permNotifiedCode", -1) != release.code) {
                    prefs(c).edit().putInt("permNotifiedCode", release.code).apply()
                    notifyPermission(c, release.name)
                }
                return Outcome(State.NEEDS_PERMISSION, release.name, release.code)
            }
            if (isVisible() && !installWhileVisible) return Outcome(State.READY, release.name, release.code)
            if (TermService.hasLiveWork()) {
                // Pocket Shell is an SSH terminal: restarting it would drop live sessions. Try again on the next run.
                log(c, "info", "install-deferred-live-session", release.name)
                return Outcome(State.DEFERRED_SESSIONS, release.name, release.code)
            }
            if (!interactive && !installWhileVisible && prefs(c).getInt("confirmCode", -1) == release.code) {
                // Android already asked the person to confirm this version; don't ask again every hour.
                return Outcome(State.WAITING_FOR_CONFIRMATION, release.name, release.code)
            }
            commit(c, apk, release)
            log(c, "info", "install-committed", release.name)
            Outcome(State.INSTALLING, release.name, release.code)
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            log(c, "error", "update-failed", reason)
            prefs(c).edit().putString("lastError", reason).putLong("lastErrorAt", System.currentTimeMillis()).apply()
            Outcome(State.FAILED, detail = reason)
        }
    }

    // ---- network ----

    private fun userAgent() = "GHTAndroidUpdater/${UpdateFeed.APP_KEY}/${BuildConfig.VERSION_NAME}"

    fun fetch(c: Context): UpdateRelease {
        val connection = URL(UpdateFeed.FEED_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 30_000; connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("User-Agent", userAgent())
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("Update server answered HTTP $status")
            val body = connection.inputStream.use { it.readBytes() }
            if (body.size > 262_144) throw IOException("Update metadata is too large")
            return UpdateFeed.parse(String(body, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    private fun updateDir(c: Context): File = File(c.noBackupFilesDir, "updates").also { it.mkdirs() }

    private fun download(c: Context, r: UpdateRelease): File {
        val target = File(updateDir(c), "${UpdateFeed.APP_KEY}-${r.code}.apk")
        if (target.isFile && r.sha256 == sha256(target)) { log(c, "info", "download-reused", r.name); return target }
        val part = File(updateDir(c), target.name + ".part")
        val connection = URL(r.apk).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000; connection.readTimeout = 60_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent())
        val started = System.currentTimeMillis()
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("Download answered HTTP $status")
            connection.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n); digest.update(buffer, 0, n); total += n
                    }
                    out.flush()
                }
            }
        } catch (e: Exception) { part.delete(); throw e } finally { connection.disconnect() }
        if (r.bytes > 0 && total != r.bytes) { part.delete(); throw IOException("Download size mismatch ($total of ${r.bytes})") }
        if (r.sha256 != hex(digest.digest())) { part.delete(); throw IOException("Download failed its SHA-256 check") }
        target.delete()
        if (!part.renameTo(target)) throw IOException("Could not store the downloaded update")
        log(c, "info", "downloaded", "${r.name} $total bytes in ${System.currentTimeMillis() - started}ms")
        return target
    }

    /** The APK must be this app, the advertised version, and signed by the same key as the installed copy. */
    private fun verify(c: Context, apk: File, r: UpdateRelease) {
        val pm = c.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw IOException("Downloaded update is not a readable APK")
        if (c.packageName != archive.packageName) throw IOException("Downloaded update is for another app")
        val archiveCode = PackageInfoCompat.getLongVersionCode(archive)
        if (archiveCode != r.code.toLong()) throw IOException("Downloaded update has version $archiveCode, expected ${r.code}")
        val installed = pm.getPackageInfo(c.packageName, flags)
        val mine = signers(installed)
        val theirs = signers(archive)
        if (mine.isEmpty() || theirs.isEmpty()) throw IOException("Signing certificate could not be read")
        if (theirs.none { it in mine }) throw IOException("Downloaded update is signed by a different key")
    }

    private fun signers(info: PackageInfo): Set<String> {
        val sigs: Array<Signature> = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory) ?: return emptySet()
        } else {
            @Suppress("DEPRECATION") info.signatures ?: return emptySet()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { hex(md.digest(it.toByteArray())) }.toSet()
    }

    // ---- install ----

    private fun commit(c: Context, apk: File, r: UpdateRelease) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        params.setSize(apk.length())
        params.setInstallReason(PackageManager.INSTALL_REASON_USER)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 262_144)
                        session.fsync(out)
                    }
                }
                val result = Intent(c, UpdateInstallReceiver::class.java).setAction(ACTION_RESULT)
                    .putExtra(EXTRA_NAME, r.name).putExtra(EXTRA_CODE, r.code)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(c, id, result, flags)
                prefs(c).edit().putInt("installingCode", r.code).putLong("installStartedAt", System.currentTimeMillis()).apply()
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    /** Called after Android replaced the app (MY_PACKAGE_REPLACED), in the new version's process. */
    fun onUpdated(c: Context) {
        val p = prefs(c)
        val ours = p.getInt("installingCode", -1) == BuildConfig.VERSION_CODE
        val took = System.currentTimeMillis() - p.getLong("installStartedAt", System.currentTimeMillis())
        p.edit().remove("installingCode").remove("installStartedAt").remove("readyCode").remove("readyName").remove("confirmCode")
            .remove("lastError").putInt("updatedTo", BuildConfig.VERSION_CODE).putLong("updatedAt", System.currentTimeMillis()).apply()
        cleanup(c, -1)
        log(c, "info", if (ours) "updated-in-app" else "updated-externally", BuildConfig.VERSION_NAME + if (ours) " install ${took}ms" else "")
        schedule(c)
        cancel(c, NOTE_ACTION)
        if (ours) notifyDone(c)
    }

    private fun cleanup(c: Context, keepCode: Int) {
        updateDir(c).listFiles()?.forEach { f ->
            if (keepCode < 0 || f.name != "${UpdateFeed.APP_KEY}-$keepCode.apk") f.delete()
        }
    }

    // ---- notifications (only when the person has to act, plus a quiet "updated" note) ----

    private fun channels(c: Context): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ACTION) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ACTION, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shown only when an update needs you, for example a one-time install permission."
            })
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Update installed", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note after the app updates itself."
            })
        }
        return nm
    }

    private fun appName(c: Context) = c.getString(R.string.app_name)

    private fun notifyPermission(c: Context, versionName: String) {
        val open = PendingIntent.getActivity(c, 7101, permissionSettings(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(c, NOTE_ACTION, CHANNEL_ACTION, "${appName(c)} $versionName is ready",
            "Allow ${appName(c)} to install its own updates. Android asks once; after that updates install automatically.", open)
    }

    fun notifyConfirm(c: Context, versionName: String, confirm: Intent) {
        val open = PendingIntent.getActivity(c, 7102, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        post(c, NOTE_ACTION, CHANNEL_ACTION, "Finish updating ${appName(c)}", "Tap to install ${appName(c)} $versionName.", open)
    }

    fun notifyFailure(c: Context, versionName: String, reason: String) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
        val open = launch?.let { PendingIntent.getActivity(c, 7103, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        post(c, NOTE_ACTION, CHANNEL_ACTION, "${appName(c)} $versionName could not install", reason, open)
    }

    private fun notifyDone(c: Context) {
        val launch = c.packageManager.getLaunchIntentForPackage(c.packageName)
        val open = launch?.let { PendingIntent.getActivity(c, 7104, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        post(c, NOTE_DONE, CHANNEL_DONE, "${appName(c)} updated", "Now on version ${BuildConfig.VERSION_NAME}.", open)
    }

    private fun post(c: Context, id: Int, channel: String, title: String, text: String, open: PendingIntent?) {
        try {
            val nm = channels(c)
            val b = Notification.Builder(c, channel).setSmallIcon(R.drawable.ic_update_notification)
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setOnlyAlertOnce(true)
            if (open != null) b.setContentIntent(open)
            nm.notify(id, b.build())
        } catch (e: Exception) { log(c, "warn", "notify-failed", e.javaClass.simpleName) }
    }

    private fun cancel(c: Context, id: Int) { runCatching { c.getSystemService(NotificationManager::class.java).cancel(id) } }

    // ---- diagnostics: files/logs/pocketshell-updates.log (JSON lines), shareable from Settings > Updates ----

    fun logFile(c: Context): File = File(File(c.filesDir, "logs"), "${UpdateFeed.APP_KEY}-updates.log")

    fun log(c: Context, level: String, event: String, detail: String?) {
        try {
            val file = logFile(c)
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > 1024 * 1024) {
                val old = File(file.parentFile, file.name + ".old"); old.delete(); file.renameTo(old)
            }
            val line = JSONObject()
                .put("at", Instant.now().toString()).put("level", level).put("app", UpdateFeed.APP_KEY)
                .put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                .put("component", "android-updater").put("event", event)
            if (detail != null) line.put("detail", if (detail.length > 300) detail.substring(0, 300) else detail)
            line.put("sdk", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            FileOutputStream(file, true).use { it.write((line.toString() + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {}
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val b = ByteArray(65_536)
            while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
}
