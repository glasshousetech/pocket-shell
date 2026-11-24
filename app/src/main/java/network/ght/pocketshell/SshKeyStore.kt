package network.ght.pocketshell

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/** Imports an SSH private key directly into Linux-private app storage. */
object SshKeyStore {
    private const val MAX_KEY_BYTES = 64 * 1024
    internal val identityLock = Any()

    fun import(context: Context, uri: Uri): Result<String> = runCatching {
      synchronized(identityLock) {
        DiagnosticLog.record(context, "ssh", "key_import_started")
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
        // Only early rejection: ssh-keygen below must parse the full key before replacement.
        val type = listOf("OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "PRIVATE KEY")
            .firstOrNull { text.startsWith("-----BEGIN $it-----") && text.endsWith("-----END $it-----") }
            ?: error("Select a private key in OpenSSH or PEM format. Your existing identity was kept.")
        val encoded = text.removePrefix("-----BEGIN $type-----").removeSuffix("-----END $type-----")
            .filterNot(Char::isWhitespace)
        require(runCatching { Base64.getDecoder().decode(encoded).isNotEmpty() }.getOrDefault(false)) {
            "That private key is malformed. Your existing identity was kept."
        }

        val sshDir = File(Userland.rootfsDir(context, distro), "root/.ssh").apply { mkdirs() }
        Os.chmod(sshDir.absolutePath, 448) // 0700
        val target = File(sshDir, "id_ed25519")
        val publicTarget = File(sshDir, "id_ed25519.pub")
        val temp = File(sshDir, ".import-${UUID.randomUUID()}")
        val publicTemp = File(sshDir, "${temp.name}.pub")
        try {
            check(temp.createNewFile())
            Os.chmod(temp.absolutePath, 384)
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            val publicKey = Bootstrap.deriveImportedPublicKey(context, distro, temp.name)
            publicTemp.writeText("$publicKey\n")
            Os.chmod(publicTemp.absolutePath, 420)
            // Missing public metadata is recoverable; mismatched metadata is not safe.
            check(!publicTarget.exists() || publicTarget.delete()) { "Could not replace public identity metadata; your private key was kept." }
            Os.rename(temp.absolutePath, target.absolutePath) // Atomic, no truncation fallback.
            val publicReady = runCatching { Os.rename(publicTemp.absolutePath, publicTarget.absolutePath) }.isSuccess
            DiagnosticLog.record(context, "ssh", if (publicReady) "key_import_committed" else "key_import_public_pending")
            if (publicReady) "Key imported securely to ~/.ssh/id_ed25519"
            else "Key imported securely; public key preparation will retry when you connect."
        } finally {
            temp.delete()
            publicTemp.delete()
            bytes.fill(0)
        }
      }
    }.onFailure { DiagnosticLog.record(context, "ssh", "key_import_rejected", it.javaClass.simpleName) }

    internal fun isPublicKey(key: String) =
        key.startsWith("ssh-ed25519 ") || key.startsWith("ssh-rsa ") || key.startsWith("ecdsa-")

    fun publicKey(context: Context): Result<String> = runCatching {
        val distro = Userland.installedDistro(context) ?: error("Linux is not installed.")
        val key = File(Userland.rootfsDir(context, distro), "root/.ssh/id_ed25519.pub")
            .takeIf { it.isFile }?.readText()?.trim()
            ?: error("No public key is available yet.")
        require(key.startsWith("ssh-ed25519 ") || key.startsWith("ssh-rsa ") || key.startsWith("ecdsa-")) {
            "The generated public key is invalid."
        }
        key
    }
}
