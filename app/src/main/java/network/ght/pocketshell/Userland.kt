package network.ght.pocketshell

import android.content.Context
import android.os.Build
import java.io.File

/**
 * A distro the picker can offer. Only one may be installed at a time — picking
 * a second one requires uninstalling the first (see [Userland.uninstall]).
 */
sealed class Distro(val id: String, val label: String, val dirName: String) {
    object Alpine : Distro("alpine", "Alpine Linux", "alpine")
    object Ubuntu : Distro("ubuntu", "Ubuntu", "ubuntu")

    companion object {
        val ALL: List<Distro> = listOf(Alpine, Ubuntu)
        fun byId(id: String): Distro? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * The Linux userland: a proot-mounted rootfs (Alpine or Ubuntu) that gives real
 * `apk`/`apt`, `python`, `git`, `ssh`, etc. on a non-rooted phone.
 *
 * proot + its loaders ship as native libs (the only place Android lets an app
 * execute a binary — the app-data dir is W^X). Rootfs tarballs are downloaded
 * on first use and pinned by sha256. See [Bootstrap] for install.
 */
object Userland {

    const val ALPINE_VERSION = "3.20.10"
    const val UBUNTU_VERSION = "24.04.4"

    data class Rootfs(val url: String, val sha256: String)

    private fun alpineUrl(arch: String) =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$arch/" +
            "alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"

    // Canonical's official "Ubuntu Base" tarballs (cdimage.ubuntu.com) — the same
    // kind of source Termux's own proot-distro tool uses for its Ubuntu plugin.
    // The sha256 values below were independently recomputed from the downloaded
    // tarballs (not just copied from the published SHA256SUMS), matching the
    // Alpine pinning discipline.
    private fun ubuntuUrl(arch: String) =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" +
            "ubuntu-base-$UBUNTU_VERSION-base-$arch.tar.gz"

    /** Android ABI -> pinned Alpine minirootfs (matches the bundled proot ABI). */
    private val ALPINE_ROOTFS: Map<String, Rootfs> = mapOf(
        "arm64-v8a" to Rootfs(alpineUrl("aarch64"), "61ac877fdbcee6914731bc22a4ed5668ea3470f201f97a7078931c48b71bbeec"),
        "armeabi-v7a" to Rootfs(alpineUrl("armv7"), "bb903f7e47b4991c91ebf5677bc8f66df5d8e8c24a9c093ba072222243c83b75"),
        "x86_64" to Rootfs(alpineUrl("x86_64"), "0d44a414245f043ae56872a23762e6c9bee585da2cbe4aa81cbd0331eaf53223"),
        "x86" to Rootfs(alpineUrl("x86"), "7a80c1cfc1a8ca542c0b553fc3d42b962ad72531ba28850aecc8473f3472dd7a"),
    )

    /**
     * Android ABI -> pinned Ubuntu Base rootfs. No official 32-bit x86 (i386)
     * ubuntu-base tarball is published, so that ABI is intentionally absent —
     * devices on it fall back to Alpine.
     */
    private val UBUNTU_ROOTFS: Map<String, Rootfs> = mapOf(
        "arm64-v8a" to Rootfs(ubuntuUrl("arm64"), "04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2"),
        "armeabi-v7a" to Rootfs(ubuntuUrl("armhf"), "991520b47f6586f38a78505cf016e300b6191bb8ff86a0723481ec23a37ab7f4"),
        "x86_64" to Rootfs(ubuntuUrl("amd64"), "c1e67ef7b17a6300e136118bd1dc04725009cb376c1aad10abcf8cd453628d58"),
    )

    private fun rootfsMap(distro: Distro): Map<String, Rootfs> = when (distro) {
        Distro.Alpine -> ALPINE_ROOTFS
        Distro.Ubuntu -> UBUNTU_ROOTFS
    }

    /** First device ABI we have both a proot binary and a rootfs for, for this distro. */
    fun supportedAbi(distro: Distro): String? = Build.SUPPORTED_ABIS.firstOrNull { rootfsMap(distro).containsKey(it) }
    fun rootfsFor(distro: Distro, abi: String): Rootfs? = rootfsMap(distro)[abi]

    fun rootfsDir(context: Context, distro: Distro): File = File(context.filesDir, distro.dirName)
    fun installedMarker(context: Context, distro: Distro): File = File(rootfsDir(context, distro), ".pocketshell_installed")
    fun isInstalled(context: Context, distro: Distro): Boolean = installedMarker(context, distro).exists()

    /** Whichever distro is currently installed, if any (only one at a time). */
    fun installedDistro(context: Context): Distro? = Distro.ALL.firstOrNull { isInstalled(context, it) }

    // Executables live in the read-only native lib dir, extracted at install time.
    private fun nativeLibDir(context: Context): String = context.applicationInfo.nativeLibraryDir
    fun prootBin(context: Context): File = File(nativeLibDir(context), "libproot.so")
    fun loader(context: Context): File = File(nativeLibDir(context), "libproot-loader.so")
    fun loader32(context: Context): File = File(nativeLibDir(context), "libproot-loader32.so")

    fun isAvailable(context: Context, distro: Distro): Boolean =
        supportedAbi(distro) != null && prootBin(context).exists()

    /** Host-side env for the proot process itself. */
    fun prootEnv(context: Context): Array<String> {
        val tmp = File(context.cacheDir, "proot").apply { mkdirs() }
        val env = mutableListOf(
            "PROOT_LOADER=${loader(context).absolutePath}",
            "PROOT_TMP_DIR=${tmp.absolutePath}",
            // NB: do NOT set PROOT_NO_SECCOMP. Forcing proot to ptrace every
            // syscall makes poll()/fork() return ENOSYS on modern kernels
            // (verified on the android-14 emulator). Seccomp fast-path is
            // correct here and matches Termux's default.
            "HOME=${context.filesDir.absolutePath}",
            "TERM=xterm-256color",
        )
        if (loader32(context).exists()) env.add("PROOT_LOADER_32=${loader32(context).absolutePath}")
        return env.toTypedArray()
    }

    /** Shared proot argv (bind mounts + guest env) common to interactive and one-shot runs. */
    private fun baseArgs(context: Context, distro: Distro): Array<String> {
        val root = rootfsDir(context, distro).absolutePath
        val proot = prootBin(context).absolutePath
        return arrayOf(
            proot,
            "--link2symlink",   // emulate hardlinks apk/dpkg rely on
            "--kill-on-exit",
            "-r", root,
            "-0",               // present as root so apk/apt package installs work
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/dev/urandom:/dev/random",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
    }

    /** argv that boots an interactive login shell inside [distro]'s rootfs. */
    fun prootArgs(context: Context, distro: Distro): Array<String> =
        baseArgs(context, distro) + arrayOf("/bin/sh", "-l")

    /** argv that runs [command] non-interactively inside [distro]'s rootfs (no PTY; used for provisioning). */
    fun prootExecArgs(context: Context, distro: Distro, command: String): Array<String> =
        baseArgs(context, distro) + arrayOf("/bin/sh", "-c", command)

    /** Removes every installed distro's rootfs so the picker starts fresh. */
    fun uninstall(context: Context) {
        Distro.ALL.forEach { runCatching { rootfsDir(context, it).deleteRecursively() } }
    }
}
