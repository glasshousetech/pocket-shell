package network.ght.pocketshell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Hourly (and install-on-leave) background run: downloads and installs updates while the app is not on screen. */
class UpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AppUpdater.run(applicationContext, interactive = false, installWhileVisible = false)
        return Result.success()
    }
}

/** Receives PackageInstaller results for in-app updates. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val name = intent.getStringExtra(AppUpdater.EXTRA_NAME) ?: ""
        val code = intent.getIntExtra(AppUpdater.EXTRA_CODE, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                // Ask once per version: remember it so the hourly job does not repeat the prompt.
                AppUpdater.prefs(c).edit().putInt("confirmCode", code).apply()
                AppUpdater.log(c, "info", "install-needs-confirmation", name)
                if (confirm == null) return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (AppUpdater.isVisible()) {
                    try { c.startActivity(confirm); return } catch (e: Exception) {
                        AppUpdater.log(c, "warn", "confirm-launch-failed", e.javaClass.simpleName)
                    }
                }
                AppUpdater.notifyConfirm(c, name, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.log(c, "info", "install-succeeded", name)
            else -> {
                val reason = reason(status, message)
                AppUpdater.prefs(c).edit().remove("installingCode").putString("lastError", reason).putLong("lastErrorAt", System.currentTimeMillis()).apply()
                AppUpdater.log(c, "error", "install-failed:$status", reason)
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED) AppUpdater.notifyFailure(c, name, reason)
            }
        }
    }

    internal fun reason(status: Int, message: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked the install. On Samsung phones, check Settings > Security and privacy > Auto Blocker."
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "The update conflicts with the installed copy."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "The update is not compatible with this phone."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The update file was rejected as invalid."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update."
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
            else -> "The update could not be installed."
        }
        return if (message.isNullOrEmpty()) base else "$base ($message)"
    }
}

/** Runs in the new version right after Android replaces the app. WorkManager re-arms its own schedule after reboots. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) AppUpdater.onUpdated(c)
    }
}
