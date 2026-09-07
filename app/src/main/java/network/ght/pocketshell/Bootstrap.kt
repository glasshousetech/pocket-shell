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
import java.util.concurrent.TimeUnit

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

                // SSH is the product's primary workflow, not an optional extra.
                // Do not mark a rootfs ready unless the core shell and ssh
                // client were installed and actually execute successfully.
                onStatus("Installing shell and SSH…")
                provisionCore(context, distro)
                onStatus("Installing developer toolkit…")
                provisionToolkit(context, distro)

                onStatus("Creating SSH identity…")
                ensureIdentitySync(context, distro)

                onStatus("Verifying complete toolchain…")
                verifyCore(context, distro)

                val version = if (distro == Distro.Alpine) Userland.ALPINE_VERSION else Userland.UBUNTU_VERSION
                Userland.installedMarker(context, distro).writeText("${distro.id} $version schema=${Userland.SETUP_SCHEMA}\n")
                Result.success(Unit)
            } catch (t: Throwable) {
                runCatching { root.deleteRecursively() }
                Result.failure(t)
            }
        }

    /** Upgrades an older/broken rootfs in place without deleting keys or home data. */
    suspend fun repair(context: Context, distro: Distro, onStatus: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(rootfsDirExists(context, distro)) { "Linux is not installed." }
                onStatus("Repairing SSH and shell…")
                configure(Userland.rootfsDir(context, distro), distro)
                provisionCore(context, distro)
                onStatus("Refreshing developer toolkit…")
                provisionToolkit(context, distro)
                onStatus("Checking SSH identity…")
                ensureIdentitySync(context, distro)
                onStatus("Running health check…")
                verifyCore(context, distro)
                val version = if (distro == Distro.Alpine) Userland.ALPINE_VERSION else Userland.UBUNTU_VERSION
                Userland.installedMarker(context, distro).writeText("${distro.id} $version schema=${Userland.SETUP_SCHEMA}\n")
            }
        }

    /** Ensures a self-contained keypair exists and returns its public key. */
    suspend fun ensureIdentity(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val distro = Userland.installedDistro(context) ?: error("Linux is not installed.")
            ensureIdentitySync(context, distro)
            SshKeyStore.publicKey(context).getOrThrow()
        }
    }

    /** Executes the same gate used at install time without modifying the rootfs. */
    suspend fun healthCheck(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val distro = Userland.installedDistro(context) ?: error("Linux is not installed.")
            verifyCore(context, distro)
        }
    }

    private fun rootfsDirExists(context: Context, distro: Distro): Boolean =
        File(Userland.rootfsDir(context, distro), "bin/sh").exists()

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

    private fun provisionCore(context: Context, distro: Distro) {
        val command = when (distro) {
            Distro.Alpine -> "apk update && apk add --no-cache bash openssh-client ca-certificates curl tmux"
            Distro.Ubuntu -> "${Userland.ubuntuAptPrelude()}${Userland.ubuntuApt()} update && " +
                "${Userland.ubuntuApt()} install -y --no-install-recommends bash openssh-client ca-certificates curl tmux"
        }
        runGuest(context, distro, command, 240)
    }

    private fun provisionToolkit(context: Context, distro: Distro) {
        val command = when (distro) {
            Distro.Alpine -> "apk add --no-cache python3 py3-pip git wget vim nano jq rsync zip unzip tar gzip coreutils findutils grep sed less nodejs npm build-base procps"
            Distro.Ubuntu -> "${Userland.ubuntuAptPrelude()}${Userland.ubuntuApt()} install -y --no-install-recommends " +
                "python3 python3-pip git wget vim nano jq rsync zip unzip tar gzip coreutils findutils grep sed less " +
                "nodejs npm build-essential procps && rm -rf /var/lib/apt/lists/*"
        }
        runGuest(context, distro, command, 420)
    }

    private fun ensureIdentitySync(context: Context, distro: Distro) {
        runGuest(
            context,
            distro,
            "set -eu; mkdir -p /root/.ssh; chmod 700 /root/.ssh; " +
                "if [ ! -s /root/.ssh/id_ed25519 ]; then " +
                "ssh-keygen -q -t ed25519 -N '' -C 'pocket-shell' -f /root/.ssh/id_ed25519; " +
                "elif [ ! -s /root/.ssh/id_ed25519.pub ]; then " +
                "ssh-keygen -y -f /root/.ssh/id_ed25519 > /root/.ssh/id_ed25519.pub; fi; " +
                "chmod 600 /root/.ssh/id_ed25519; chmod 644 /root/.ssh/id_ed25519.pub",
            45,
        )
    }

    private fun verifyCore(context: Context, distro: Distro): String {
        return runGuest(
            context,
            distro,
            "set -eu; " +
                "for c in bash ssh ssh-keygen tmux git python3 node npm vim nano curl wget jq rsync tar gzip ps; do " +
                "command -v \"\$c\" >/dev/null || { echo \"missing:\$c\"; exit 12; }; done; " +
                "test -s /root/.ssh/id_ed25519; test -s /root/.ssh/id_ed25519.pub; " +
                "printf 'READY '; bash --version | head -1; ssh -V; tmux -V; git --version; python3 --version; node --version; npm --version",
            45,
        )
    }

    /** Runs a bounded guest command and drains output without defeating the timeout. */
    private fun runGuest(context: Context, distro: Distro, command: String, timeoutSeconds: Long): String {
        val pb = ProcessBuilder(*Userland.prootExecArgs(context, distro, command))
        pb.environment().clear()
        Userland.prootEnv(context).forEach { kv ->
            val (k, v) = kv.split("=", limit = 2)
            pb.environment()[k] = v
        }
        pb.redirectErrorStream(true)
        val proc = pb.start()
        // Keep the *tail* of the guest output: apt/dpkg print thousands of
        // progress lines and the failure is always at the end.
        val tail = GuestOutputTail(maxChars = 24_000)
        val reader = Thread {
            proc.inputStream.bufferedReader().useLines { lines -> lines.forEach(tail::append) }
        }.apply { isDaemon = true; start() }
        val output = tail
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            reader.join(2_000)
            throw IllegalStateException("Linux setup timed out after ${timeoutSeconds}s. Check the connection and retry.")
        }
        reader.join(2_000)
        if (proc.exitValue() != 0) {
            throw IllegalStateException("Linux setup exited ${proc.exitValue()}: ${output.toString().takeLast(2_500)}")
        }
        return output.toString()
    }

    /** Minimal working network + package config inside the guest. */
    private fun configure(root: File, distro: Distro) {
        File(root, "etc").mkdirs()
        // Google DNS (we deliberately avoid Cloudflare on the GHT stack).
        File(root, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(root, "etc/profile.d").mkdirs()
        File(root, "root/.ssh").apply { mkdirs(); Os.chmod(absolutePath, 448) }
        File(root, "tmp").apply { mkdirs(); Os.chmod(absolutePath, 1023) } // 01777
        val sshConfig = File(root, "root/.ssh/config")
        if (!sshConfig.exists()) {
            sshConfig.writeText(
                "Host *\n  ServerAliveInterval 30\n  ServerAliveCountMax 3\n" +
                    "  TCPKeepAlive yes\n  IdentitiesOnly yes\n  IdentityFile ~/.ssh/id_ed25519\n"
            )
        }
        Os.chmod(sshConfig.absolutePath, 384)

        when (distro) {
            Distro.Alpine -> {
                File(root, "etc/apk").mkdirs()
                File(root, "etc/apk/repositories").writeText(
                    "https://dl-cdn.alpinelinux.org/alpine/v3.20/main\n" +
                        "https://dl-cdn.alpinelinux.org/alpine/v3.20/community\n"
                )
                // A clean prompt + welcome for interactive login shells.
                File(root, "etc/profile.d/00-pocketshell.sh").writeText(
                    "export PS1='pocket:\\w\\$ '\n" +
                        "alias ll='ls -la'\n" +
                        "alias cls='clear'\n" +
                        "[ -d /sdcard ] && alias downloads='cd /sdcard/Download'\n" +
                        "[ -f /etc/pocketshell-welcomed ] || { echo 'Pocket Shell is ready. Tap Connect for a one-touch SSH + tmux workspace.'; echo 'Shared phone files appear at /sdcard after enabling Storage in Settings.'; touch /etc/pocketshell-welcomed; }\n"
                )
            }

            Distro.Ubuntu -> {
                // Ubuntu Base is deliberately bare: it has the archive signing
                // key but no configured APT source. Without this, every repair
                // reaches apt-get with zero package repositories and fails.
                val abi = Userland.supportedAbi(distro)
                    ?: error("No Ubuntu APT source is available for this device architecture.")
                File(root, "etc/apt/sources.list.d").mkdirs()
                File(root, "etc/apt/sources.list.d/pocketshell.sources")
                    .writeText(Userland.ubuntuAptSources(abi))
                File(root, "etc/profile.d/00-pocketshell.sh").writeText(
                    "export PS1='pocket:\\w\\$ '\n" +
                        "alias ll='ls -la'\n" +
                        "alias cls='clear'\n" +
                        "[ -d /sdcard ] && alias downloads='cd /sdcard/Download'\n" +
                        "[ -f /etc/pocketshell-welcomed ] || { echo 'Pocket Shell is ready. Tap Connect for a one-touch SSH + tmux workspace.'; echo 'Shared phone files appear at /sdcard after enabling Storage in Settings.'; touch /etc/pocketshell-welcomed; }\n"
                )
            }
        }
    }
}
