package network.ght.pocketshell

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
 * Installs a Linux userland: download (sha256-pinned) -> extract -> configure.
 * Idempotent and self-cleaning; a failed install wipes the partial rootfs so the
 * next attempt starts clean.
 */
object Bootstrap {

    suspend fun install(context: Context, distro: Distro, onStatus: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            val abi = Userland.supportedAbi(distro)
                ?: return@withContext Result.failure(
                    IllegalStateException("No ${distro.label} build for this CPU (${Build.SUPPORTED_ABIS.joinToString()}).")
                )
            if (!Userland.prootBin(context).exists()) {
                return@withContext Result.failure(IllegalStateException("proot binary missing for $abi."))
            }
            val rf = Userland.rootfsFor(distro, abi)!!
            val root = Userland.rootfsDir(context, distro)
            try {
                if (root.exists()) root.deleteRecursively()
                root.mkdirs()

                val tarFile = File(context.cacheDir, "${distro.id}-rootfs.tar.gz")
                onStatus("Downloading ${distro.label} ($abi)…")
                val sha = download(rf.url, tarFile) { pct -> onStatus("Downloading ${distro.label}… $pct%") }
                if (!sha.equals(rf.sha256, ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalStateException("Download corrupted (checksum mismatch). Try again.")
                    )
                }

                onStatus("Unpacking root filesystem…")
                extract(root, tarFile)
                tarFile.delete()

                onStatus("Configuring…")
                configure(root, distro)

                // Best-effort: preinstall ssh/git/python so the terminal is
                // immediately useful (e.g. sshing to a droplet) without a manual
                // `apk add`/`apt install` first. Never fails the whole install —
                // a flaky network here still leaves a good, usable rootfs; the
                // welcome message covers the manual fallback.
                onStatus("Installing ssh, git, python…")
                runCatching { provision(context, distro) }

                val version = if (distro == Distro.Alpine) Userland.ALPINE_VERSION else Userland.UBUNTU_VERSION
                Userland.installedMarker(context, distro).writeText("${distro.id} $version\n")
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

    /** Runs a one-shot, non-interactive provisioning command inside the fresh rootfs via proot. */
    private fun provision(context: Context, distro: Distro) {
        val command = when (distro) {
            Distro.Alpine -> "apk update && apk add --no-cache openssh-client python3 git ca-certificates"
            Distro.Ubuntu -> "export DEBIAN_FRONTEND=noninteractive && apt-get update && " +
                "apt-get install -y --no-install-recommends openssh-client python3 git ca-certificates && " +
                "rm -rf /var/lib/apt/lists/*"
        }
        val pb = ProcessBuilder(*Userland.prootExecArgs(context, distro, command))
        pb.environment().clear()
        Userland.prootEnv(context).forEach { kv ->
            val (k, v) = kv.split("=", limit = 2)
            pb.environment()[k] = v
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("Provisioning timed out")
        }
        if (proc.exitValue() != 0) {
            throw IllegalStateException("Provisioning exited ${proc.exitValue()}: ${output.take(500)}")
        }
    }

    /** Minimal working network + package config inside the guest. */
    private fun configure(root: File, distro: Distro) {
        File(root, "etc").mkdirs()
        // Google DNS (we deliberately avoid Cloudflare on the GHT stack).
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/profile.d").mkdirs()

        when (distro) {
            Distro.Alpine -> {
                File(root, "etc/apk").mkdirs()
                File(root, "etc/apk/repositories").writeText(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/v3.20/community\n"
                )
                // A clean prompt + welcome for interactive login shells.
                File(root, "etc/profile.d/00-pocketshell.sh").writeText(
                    "export PS1='alpine:\\w\\$ '\n" +
                        "alias ll='ls -la'\n" +
                        "[ -f /etc/pocketshell-welcomed ] || { echo 'Alpine Linux on Pocket Shell. ssh/git/python are ready — try: ssh user@host'; touch /etc/pocketshell-welcomed; }\n"
                )
            }

            Distro.Ubuntu -> {
                // The official ubuntu-base tarball already ships a working
                // /etc/apt/sources.list (archive.ubuntu.com / ports.ubuntu.com,
                // picked correctly per architecture) — left untouched rather
                // than guessing at a mirror URL ourselves.
                File(root, "etc/profile.d/00-pocketshell.sh").writeText(
                    "export PS1='ubuntu:\\w\\$ '\n" +
                        "alias ll='ls -la'\n" +
                        "[ -f /etc/pocketshell-welcomed ] || { echo 'Ubuntu on Pocket Shell. ssh/git/python are ready — try: ssh user@host'; touch /etc/pocketshell-welcomed; }\n"
                )
            }
        }
    }
}
