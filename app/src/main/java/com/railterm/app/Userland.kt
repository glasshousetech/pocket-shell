package com.railterm.app

import android.content.Context
import android.os.Build
import java.io.File

/**
 * The Linux userland: a proot-mounted Alpine rootfs that gives real `apk`,
 * `python`, `git`, `ssh`, etc. on a non-rooted phone.
 *
 * proot + its loaders ship as native libs (the only place Android lets an app
 * execute a binary — the app-data dir is W^X). The Alpine rootfs is downloaded
 * on first use and pinned by sha256. See [Bootstrap] for install.
 */
object Userland {

    const val ALPINE_VERSION = "3.20.10"

    data class Rootfs(val url: String, val sha256: String)

    private fun alpineUrl(arch: String) =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$arch/" +
            "alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz"

    /** Android ABI -> pinned Alpine minirootfs (matches the bundled proot ABI). */
    private val ROOTFS: Map<String, Rootfs> = mapOf(
        "arm64-v8a" to Rootfs(alpineUrl("aarch64"), "61ac877fdbcee6914731bc22a4ed5668ea3470f201f97a7078931c48b71bbeec"),
        "armeabi-v7a" to Rootfs(alpineUrl("armv7"), "bb903f7e47b4991c91ebf5677bc8f66df5d8e8c24a9c093ba072222243c83b75"),
        "x86_64" to Rootfs(alpineUrl("x86_64"), "0d44a414245f043ae56872a23762e6c9bee585da2cbe4aa81cbd0331eaf53223"),
        "x86" to Rootfs(alpineUrl("x86"), "7a80c1cfc1a8ca542c0b553fc3d42b962ad72531ba28850aecc8473f3472dd7a"),
    )

    /** First device ABI we have both a proot binary and a rootfs for. */
    fun supportedAbi(): String? = Build.SUPPORTED_ABIS.firstOrNull { ROOTFS.containsKey(it) }
    fun rootfsFor(abi: String): Rootfs? = ROOTFS[abi]

    fun rootfsDir(context: Context): File = File(context.filesDir, "alpine")
    fun installedMarker(context: Context): File = File(rootfsDir(context), ".railterm_installed")
    fun isInstalled(context: Context): Boolean = installedMarker(context).exists()

    // Executables live in the read-only native lib dir, extracted at install time.
    private fun nativeLibDir(context: Context): String = context.applicationInfo.nativeLibraryDir
    fun prootBin(context: Context): File = File(nativeLibDir(context), "libproot.so")
    fun loader(context: Context): File = File(nativeLibDir(context), "libproot-loader.so")
    fun loader32(context: Context): File = File(nativeLibDir(context), "libproot-loader32.so")

    fun isAvailable(context: Context): Boolean = supportedAbi() != null && prootBin(context).exists()

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

    /** argv that boots an interactive Alpine login shell inside the rootfs. */
    fun prootArgs(context: Context): Array<String> {
        val root = rootfsDir(context).absolutePath
        val proot = prootBin(context).absolutePath
        return arrayOf(
            proot,
            "--link2symlink",   // emulate hardlinks apk relies on
            "--kill-on-exit",
            "-r", root,
            "-0",               // present as root so apk/package installs work
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
            "/bin/sh", "-l",
        )
    }
}
