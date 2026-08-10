package network.ght.pocketshell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Direct shared-storage access for real CLI tools (git, rsync, editors, etc.). */
object StorageAccess {
    fun isGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Host path exposed inside Linux as /sdcard when permission is granted. */
    fun sharedRoot(context: Context): File? {
        if (!isGranted(context)) return null
        return Environment.getExternalStorageDirectory().takeIf { it.exists() && it.canRead() }
    }
}
