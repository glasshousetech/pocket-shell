package network.ght.pocketshell

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Owned by the terminal service: rotation cannot change an in-flight transfer's host or path. */
class FilesWorkbench(private val context: Context, private val scope: CoroutineScope, private val onActivityChanged: () -> Unit = {}) : AutoCloseable {
    data class Destination(val profile: SshProfile, val directory: SftpClient.Path, val timeout: Long)
    data class Download(val file: File, val name: String, val sha256: String)
    var profile by mutableStateOf<SshProfile?>(null)
    var directory by mutableStateOf<SftpClient.Path?>(null)
    var pathInput by mutableStateOf(".")
    var timeout by mutableStateOf("120")
    var entries by mutableStateOf<List<SftpClient.Entry>?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("Choose a host to browse its files.")
    var error by mutableStateOf<String?>(null)
    var progress by mutableLongStateOf(0L)
    var transfers by mutableStateOf<List<FileTransfer>>(emptyList())
    var receiptError by mutableStateOf<String?>(null)
    var preparedDownload by mutableStateOf<Download?>(null)
    var uploadDestination: Destination? = null
    var exportDownload: Download? = null
    internal var transport: (Context, SshProfile, Long) -> SftpClient = FileTransport::open
    private val gate = Mutex()
    private val main = Handler(Looper.getMainLooper())
    private var connection: SftpClient? = null
    private var connectionProfile: SshProfile? = null
    private var connectionTimeout: Long? = null
    private var queued = 0
    private var lastProgress = 0L
    @Volatile private var disposed = false

    init {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { TransferStore.load(context) } }
                .onSuccess { transfers = it }
                .onFailure { receiptError = "Transfer receipts could not be read. Existing records were kept." }
        }
    }
    private fun seconds(): Long = timeout.toLongOrNull()?.takeIf { it > 0 } ?: error("Enter a positive timeout in seconds.")
    fun choose(host: SshProfile) {
        profile = host; directory = null; entries = null; pathInput = "."
        error = null; status = "Ready to open " + host.name + "."
    }
    private fun client(host: SshProfile, seconds: Long): SftpClient {
        check(!disposed) { "The file workspace has closed." }
        if (connectionProfile != host || connectionTimeout != seconds || connection?.closed != false) {
            connection?.close()
            connection = transport(context, host, seconds)
            connectionProfile = host; connectionTimeout = seconds
        }
        return requireNotNull(connection)
    }
    private fun operation(event: String, body: suspend () -> Unit) {
        queued++; busy = true; onActivityChanged(); error = null; progress = 0
        scope.launch {
            try {
                gate.withLock {
                    DiagnosticLog.record(context, "files", event + "_started")
                    body()
                    DiagnosticLog.record(context, "files", event + "_finished")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                error = when (failure) {
                    is SftpClient.StatusException, is IllegalArgumentException, is IllegalStateException -> failure.message
                    else -> "The file operation was not confirmed. Check the connection and any transfer receipt before retrying."
                }
                DiagnosticLog.record(context, "files", event + "_failed", failure.javaClass.simpleName)
            } finally {
                queued--; busy = queued > 0; if (!disposed) onActivityChanged()
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { TransferStore.load(context) }
                }.onSuccess { transfers = it }.onFailure { receiptError = "Transfer receipts could not be read; existing records were kept." }
            }
        }
    }
    private fun report(bytes: Long) {
        val now = System.nanoTime()
        if (now - lastProgress > 100_000_000) {
            lastProgress = now
            main.post { if (!disposed) progress = bytes }
        }
    }
    fun browse(path: SftpClient.Path? = null) {
        val host = profile ?: run { error = "Choose a host first."; return }
        val seconds = runCatching { seconds() }.getOrElse { error = it.message; return }
        val requested = path ?: runCatching { SftpClient.Path.text(pathInput) }.getOrElse { error = "Enter a remote path."; return }
        operation("browse") {
            status = "Reading " + host.name + "…"
            val result = withContext(Dispatchers.IO) {
                val remote = client(host, seconds)
                val actual = remote.canonical(requested)
                actual to remote.list(actual).sortedWith(compareBy<SftpClient.Entry> { !it.attributes.directory }.thenBy { it.name.display.lowercase() })
            }
            profile = host; directory = result.first; pathInput = result.first.display; entries = result.second
            status = result.second.size.toString() + " items on " + host.name
        }
    }
    fun prepareUpload(): Boolean {
        val host = profile ?: return false
        val path = directory ?: return false
        val seconds = runCatching { seconds() }.getOrElse { error = it.message; return false }
        if (receiptError != null) { error = receiptError; return false }
        uploadDestination = Destination(host, path, seconds)
        return true
    }
    fun upload(uri: Uri?) {
        val destination = uploadDestination
        uploadDestination = null
        if (uri == null || destination == null) return
        operation("upload") {
            status = "Preparing selected file…"
            val result = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: error("The file provider did not supply a filename.")
                val target = destination.directory.child(SftpClient.Path.text(name))
                val staging = File(context.noBackupFilesDir, "file-transfer-cache").apply { check(mkdirs() || isDirectory) }
                val local = File(staging, UUID.randomUUID().toString())
                try {
                    context.contentResolver.openInputStream(uri)?.use { input -> local.outputStream().use { input.copyTo(it, 65536) } }
                        ?: error("The selected file could not be opened.")
                    val item = FileTransfer.prepare(destination.profile, target, local)
                    TransferStore.save(context, item)
                    SftpTransfers(client(destination.profile, destination.timeout)) { TransferStore.save(context, it) }
                        .upload(item, local, ::report)
                } finally { local.delete() }
            }
            progress = result.size
            status = result.message
            if (result.phase == FileTransfer.Phase.COMPLETE) refreshAfterWrite(destination.profile, destination.directory, destination.timeout)
        }
    }
    private suspend fun refreshAfterWrite(host: SshProfile, path: SftpClient.Path, seconds: Long) {
        if (profile != host || directory != path) return
        // A successful upload stays successful if the subsequent listing is unavailable.
        withContext(Dispatchers.IO) { runCatching { client(host, seconds).list(path) } }
            .onSuccess { entries = it.sortedWith(compareBy<SftpClient.Entry> { !it.attributes.directory }.thenBy { it.name.display.lowercase() }) }
            .onFailure { error = "The transfer finished, but this folder needs a refresh." }
    }
    fun resolve(item: FileTransfer, action: String, newName: String = "") {
        val seconds = runCatching { seconds() }.getOrElse { error = it.message; return }
        operation("transfer_" + action) {
            val result = withContext(Dispatchers.IO) {
                val fresh = TransferStore.load(context).firstOrNull { it.id == item.id } ?: error("This transfer receipt is missing.")
                val engine = SftpTransfers(client(fresh.profile, seconds)) { TransferStore.save(context, it) }
                when (action) {
                    "check" -> engine.reconcile(fresh)
                    "replace" -> engine.commit(fresh, true)
                    "finish" -> engine.commit(fresh, false)
                    "keep_both" -> engine.keepBoth(fresh, newName)
                    else -> error("Unknown transfer action.")
                }
            }
            status = result.message
            if (result.phase == FileTransfer.Phase.COMPLETE) refreshAfterWrite(result.profile, result.target.parent(), seconds)
        }
    }
    fun download(entry: SftpClient.Entry) {
        val host = profile ?: return
        val path = directory?.child(entry.name) ?: return
        val seconds = runCatching { seconds() }.getOrElse { error = it.message; return }
        operation("download") {
            status = "Downloading " + entry.name.display + "…"
            val ready = withContext(Dispatchers.IO) {
                val folder = File(context.noBackupFilesDir, "file-download-cache").apply { check(mkdirs() || isDirectory) }
                val local = File(folder, UUID.randomUUID().toString())
                try {
                    val checksum = SftpTransfers(client(host, seconds)) { TransferStore.save(context, it) }.download(path, local, ::report)
                    Download(local, entry.name.display, checksum)
                } catch (failure: Exception) { local.delete(); throw failure }
            }
            preparedDownload?.file?.delete()
            preparedDownload = ready
            progress = ready.file.length()
            status = "Downloaded and checksum verified. Save a copy to your phone."
        }
    }
    fun prepareExport(): String? {
        exportDownload = preparedDownload
        return exportDownload?.name
    }
    fun export(uri: Uri?) {
        val download = exportDownload
        exportDownload = null
        if (uri == null || download == null) return
        operation("save_download") {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { output -> download.file.inputStream().use { it.copyTo(output, 65536) } }
                    ?: error("The destination could not be opened.")
                val digest = MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = ByteArray(65536)
                    while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                } ?: error("The saved file could not be read back for verification.")
                check(digest.digest().joinToString("") { "%02x".format(it) } == download.sha256) { "The saved file checksum differs. The verified download is still available." }
            }
            status = "Saved to your phone and checksum verified."
        }
    }
    fun newFolder(name: String) {
        val host = profile ?: return
        val parent = directory ?: return
        val seconds = runCatching { seconds() }.getOrElse { error = it.message; return }
        val path = runCatching { parent.child(SftpClient.Path.text(name)) }.getOrElse { error = it.message; return }
        operation("mkdir") {
            withContext(Dispatchers.IO) {
                val remote = client(host, seconds)
                remote.mkdir(path)
                check(remote.stat(path).directory) { "The new directory could not be verified." }
            }
            status = "Folder created."
            refreshAfterWrite(host, parent, seconds)
        }
    }
    suspend fun withoutTransport(block: suspend () -> Result<Unit>): Result<Unit> = gate.withLock {
        withContext(Dispatchers.IO) { connection?.close(); connection = null }
        entries = null; directory = null
        block()
    }
    override fun close() {
        disposed = true; main.removeCallbacksAndMessages(null)
        connection?.close()
        preparedDownload?.file?.delete()
    }
}
