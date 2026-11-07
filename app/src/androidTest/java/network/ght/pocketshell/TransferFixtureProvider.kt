package network.ght.pocketshell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Only packaged in the isolated instrumentation APK; exposes synthetic fixtures, never app files. */
class TransferFixtureProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val parts = uri.pathSegments
        require(parts.size == 2 && parts[0].matches(Regex("[a-f0-9-]{36}")))
        require(parts[1] in setOf("upload α.bin", "download α.bin"))
        val root = File(requireNotNull(context).cacheDir, "transfer-fixtures/" + parts[0]).apply { mkdirs() }
        return File(root, parts[1])
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null } }.toTypedArray()) }
    }
    override fun getType(uri: Uri) = "application/octet-stream"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (file(uri).delete()) 1 else 0
}
