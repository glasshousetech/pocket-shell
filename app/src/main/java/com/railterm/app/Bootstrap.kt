package com.railterm.app

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Installs the Alpine userland: download (sha256-pinned) -> extract -> configure.
 * Idempotent and self-cleaning; a failed install wipes the partial rootfs so the
 * next attempt starts clean.
 */
object Bootstrap {

    suspend fun install(context: Context, onStatus: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val abi = Userland.supportedAbi()
                ?: return@withContext Result.failure(
                    IllegalStateException("No Linux build for this CPU (${Build.SUPPORTED_ABIS.joinToString()}).")
                )
            if (!Userland.prootBin(context).exists()) {
                return@withContext Result.failure(IllegalStateException("proot binary missing for $abi."))
            }
            val rf = Userland.rootfsFor(abi)!!
            val root = Userland.rootfsDir(context)
            try {
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()

                val tarFile = File(context.cacheDir, "alpine-rootfs.tar.gz")
                onStatus("Downloading Alpine Linux ($abi)…")
                val sha = download(rf.url, tarFile) { pct -> onStatus("Downloading Alpine Linux… $pct%") }
                if (!sha.equals(rf.sha256, ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalStateException("Download corrupted (checksum mismatch). Try again.")
                    )
                }

                onStatus("Unpacking root filesystem…")
                extract(root, tarFile)
                tarFile.delete()

                onStatus("Configuring…")
                configure(root)

                Userland.installedMarker(context).writeText("alpine ${Userland.ALPINE_VERSION}\n")
                Result.success(Unit)
            } catch (t: Throwable) {
                runCatching { root.deleteRecursively() }
                Result.failure(t)
            }
        }

    /** Streams [url] to [dest] while computing its SHA-256; returns the hex digest. */
    private fun download(url: String, dest: File, onProgress: (Int) -> Unit): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        conn.connect()
        val total = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(64 * 1024)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    read += n
                    if (total > 0) {
                        val pct = ((read * 100) / total).toInt()
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
        }
        conn.disconnect()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Extracts a gzipped tar into [root], preserving symlinks, hardlinks, and modes. */
    private fun extract(root: File, tarFile: File) {
        val rootCanon = root.canonicalPath
        TarArchiveInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(tarFile)))).use { tar ->
            var next: TarArchiveEntry? = tar.nextEntry as TarArchiveEntry?
            while (next != null) {
                val entry = next // stable non-null binding (avoids smart-cast-in-closure)
                val out = File(root, entry.name)
                // Guard against path traversal (../ entries).
                val canon = out.canonicalPath
                if (canon != rootCanon && !canon.startsWith(rootCanon + File.separator)) {
                    next = tar.nextEntry as TarArchiveEntry?
                    continue
                }
                when {
                    entry.isDirectory -> out.mkdirs()

                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        out.delete()
                        Os.symlink(entry.linkName, out.absolutePath)
                    }

                    entry.isLink -> { // hardlink to an already-extracted file
                        out.parentFile?.mkdirs()
                        out.delete()
                        val target = File(root, entry.linkName)
                        runCatching { Os.link(target.absolutePath, out.absolutePath) }
                            .onFailure { target.copyTo(out, overwrite = true) }
                    }

                    else -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                        runCatching { Os.chmod(out.absolutePath, entry.mode and 0xFFF) }
                    }
                }
                next = tar.nextEntry as TarArchiveEntry?
            }
        }
    }

    /** Minimal working network + package config inside the guest. */
    private fun configure(root: File) {
        File(root, "etc").mkdirs()
        // Google DNS (we deliberately avoid Cloudflare on the GHT stack).
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/apk").mkdirs()
        File(root, "etc/apk/repositories").writeText(
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.20/community\n"
        )
        // A clean prompt + welcome for interactive login shells.
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/00-railterm.sh").writeText(
            "export PS1='alpine:\\w\\$ '\n" +
                "alias ll='ls -la'\n" +
                "[ -f /etc/railterm-welcomed ] || { echo 'Alpine Linux on Railterm. Try: apk add python3 git'; touch /etc/railterm-welcomed; }\n"
        )
    }
}
