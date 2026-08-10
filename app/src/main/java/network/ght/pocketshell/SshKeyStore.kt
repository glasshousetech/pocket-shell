package network.ght.pocketshell

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File

/** Imports an SSH private key directly into Linux-private app storage. */
object SshKeyStore {
    private const val MAX_KEY_BYTES = 64 * 1024

    fun import(context: Context, uri: Uri): Result<String> = runCatching {
        val distro = Userland.installedDistro(context)
            ?: error("Install Ubuntu before importing an SSH key.")
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (out.size() <= MAX_KEY_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            val data = out.toByteArray()
            require(data.size <= MAX_KEY_BYTES) { "That file is too large to be an SSH private key." }
            data
        } ?: error("The selected file could not be opened.")
        val text = bytes.toString(Charsets.UTF_8).trim()
        require(
            text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----") ||
                text.startsWith("-----BEGIN RSA PRIVATE KEY-----") ||
                text.startsWith("-----BEGIN EC PRIVATE KEY-----") ||
                text.startsWith("-----BEGIN PRIVATE KEY-----")
        ) { "Select a private key in OpenSSH or PEM format." }

        val sshDir = File(Userland.rootfsDir(context, distro), "root/.ssh").apply { mkdirs() }
        Os.chmod(sshDir.absolutePath, 448) // 0700
        val target = File(sshDir, "id_ed25519")
        val temp = File(sshDir, ".id_ed25519.import")
        temp.writeBytes(bytes)
        Os.chmod(temp.absolutePath, 384) // 0600
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            Os.chmod(target.absolutePath, 384)
            temp.delete()
        }
        "Key imported securely to ~/.ssh/id_ed25519"
    }
}
