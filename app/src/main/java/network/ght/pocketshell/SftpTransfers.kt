package network.ght.pocketshell

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Durable phase is written BEFORE each consequential request. Reconnect inspects, never replays it. */
data class FileTransfer(
    val id: String,
    val profile: SshProfile,
    val target: SftpClient.Path,
    val staged: SftpClient.Path,
    val backup: SftpClient.Path,
    val sha256: String,
    val size: Long,
    val phase: Phase,
    val message: String = "",
) {
    enum class Phase { PREPARED, UPLOADING, VERIFYING, READY, CONFLICT, BACKING_UP, COMMITTING, COMPLETE, UNCERTAIN, FAILED }
    fun validate(): FileTransfer {
        require(UUID.fromString(id).toString() == id && sha256.matches(Regex("[a-f0-9]{64}")) && size >= 0)
        require(target != staged && target != backup && staged != backup)
        require(target.parent() == staged.parent() && target.parent() == backup.parent())
        require(staged == target.parent().child(SftpClient.Path.text(".pocketshell-" + id + ".upload")))
        require(backup == target.parent().child(SftpClient.Path.text(".pocketshell-" + id + ".backup")))
        profile.command()
        return this
    }
    companion object {
        fun prepare(profile: SshProfile, target: SftpClient.Path, file: File): FileTransfer {
            val id = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return FileTransfer(id, profile, target,
                target.parent().child(SftpClient.Path.text(".pocketshell-" + id + ".upload")),
                target.parent().child(SftpClient.Path.text(".pocketshell-" + id + ".backup")),
                hash.digest().joinToString("") { "%02x".format(it) }, file.length(), Phase.PREPARED).validate()
        }
    }
}

class SftpTransfers(private val client: SftpClient, private val persist: (FileTransfer) -> Unit) {
    private fun save(item: FileTransfer, phase: FileTransfer.Phase, message: String = "") =
        item.copy(phase = phase, message = message).also { persist(it) }
    private fun matches(path: SftpClient.Path, item: FileTransfer): Boolean {
        val attrs = client.statOrNull(path) ?: return false
        return attrs.regular && attrs.size == item.size && client.hash(path) == item.sha256
    }

    fun upload(item: FileTransfer, source: File, progress: (Long) -> Unit = {}): FileTransfer {
        item.validate()
        require(item.phase == FileTransfer.Phase.PREPARED) { "Inspect this transfer before attempting it again." }
        var current = save(item, FileTransfer.Phase.UPLOADING)
        try {
            source.inputStream().use { client.upload(it, item.staged, progress) }
            current = save(current, FileTransfer.Phase.VERIFYING)
            check(matches(item.staged, item)) { "The uploaded file did not match its saved checksum. The destination was not changed." }
            current = save(current, FileTransfer.Phase.READY)
            return commit(current, replaceWithBackup = false)
        } catch (error: Exception) {
            // No automatic cleanup: after a dropped reply the remote effect can still have happened.
            save(current, FileTransfer.Phase.UNCERTAIN, "Transfer interrupted. Check status before retrying; the destination and staged file may differ.")
            throw error
        }
    }

    fun commit(item: FileTransfer, replaceWithBackup: Boolean): FileTransfer {
        item.validate()
        require(item.phase in listOf(FileTransfer.Phase.READY, FileTransfer.Phase.CONFLICT)) { "Check the transfer before committing it." }
        var current = item
        try {
            check(matches(item.staged, item)) { "The staged file is missing or changed. The destination was not replaced." }
            val existing = client.statOrNull(item.target)
            if (existing != null) {
                if (!replaceWithBackup) return save(item, FileTransfer.Phase.CONFLICT, "A file already has this name. Keep both or replace it while retaining a backup.")
                require(existing.regular) { "The destination is not a regular file. Choose another name." }
                check(client.statOrNull(item.backup) == null) { "A backup already exists. Check this transfer before replacing anything." }
                current = save(current, FileTransfer.Phase.BACKING_UP)
                client.rename(item.target, item.backup) // Non-overwriting; concurrent files are never discarded.
            }
            current = save(current, FileTransfer.Phase.COMMITTING)
            client.rename(item.staged, item.target)
            check(matches(item.target, item)) { "The destination changed during verification. Inspect this transfer." }
            return save(current, FileTransfer.Phase.COMPLETE,
                if (client.statOrNull(item.backup) != null) "Uploaded and checksum verified. Previous file retained at " + item.backup.display else "Uploaded and checksum verified.")
        } catch (error: Exception) {
            save(current, FileTransfer.Phase.UNCERTAIN, "The last write was not confirmed. Check status; existing files and any backup were retained.")
            throw error
        }
    }

    fun keepBoth(item: FileTransfer, name: String): FileTransfer {
        require(item.phase in listOf(FileTransfer.Phase.CONFLICT, FileTransfer.Phase.READY))
        val destination = item.target.parent().child(SftpClient.Path.text(name))
        require(destination != item.staged && destination != item.backup)
        val changed = item.copy(target = destination, phase = FileTransfer.Phase.READY)
        persist(changed)
        return commit(changed, false)
    }

    /** Read-only recovery of an interrupted request. No status is inferred from a reservation alone. */
    fun reconcile(item: FileTransfer): FileTransfer {
        item.validate()
        val stage = client.statOrNull(item.staged)
        val destination = client.statOrNull(item.target)
        val backup = client.statOrNull(item.backup)
        if (stage == null && matches(item.target, item)) {
            return save(item, FileTransfer.Phase.COMPLETE,
                if (backup == null) "Destination checksum verified after reconnect." else "Destination checksum verified; previous file remains at " + item.backup.display)
        }
        if (stage != null && matches(item.staged, item)) {
            return save(item, if (destination == null) FileTransfer.Phase.READY else FileTransfer.Phase.CONFLICT,
                if (backup == null) "Staged file checksum verified. Ready for your next action." else
                    "Staged file verified. Backup retained at " + item.backup.display + "; review the destination before continuing.")
        }
        return save(item, FileTransfer.Phase.FAILED,
            "No complete staged upload was verified. Existing destination and backup were kept. Start a new transfer; partial file: " + item.staged.display)
    }

    fun download(source: SftpClient.Path, output: File, progress: (Long) -> Unit = {}): String {
        val before = client.stat(source)
        require(before.regular) { "Choose a regular file to download." }
        output.outputStream().use { client.download(source, it, progress) }
        val after = client.stat(source)
        check(before == after && after.size == output.length()) { "The source changed while downloading. Retry after it stops changing." }
        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(client.hash(source) == hash) { "The downloaded bytes do not match the current remote file. Retry the download." }
        return hash
    }
}
