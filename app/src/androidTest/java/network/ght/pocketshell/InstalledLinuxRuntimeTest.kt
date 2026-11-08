package network.ght.pocketshell

import android.net.ConnectivityManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Explicit acceptance on an installed Linux environment; excluded from the empty-state CI suite. */
class InstalledLinuxRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun runLinux(command: String, bind: File? = null): String {
        val distro = requireNotNull(Userland.installedDistro(context)) { "Install Linux before this acceptance check." }
        val args = Userland.prootExecArgs(context, distro, command).toMutableList()
        if (bind != null) args.addAll(1, listOf("-b", bind.absolutePath + ":/root/.pocketshell-runtime-probe"))
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment().clear()
        Userland.prootEnv(context).forEach { value ->
            val separator = value.indexOf('=')
            builder.environment()[value.substring(0, separator)] = value.substring(separator + 1)
        }
        val process = builder.start()
        var output = ""
        val reader = thread { output = process.inputStream.bufferedReader().use { it.readText() } }
        try {
            assertTrue("Linux command timed out", process.waitFor(120, TimeUnit.SECONDS))
            reader.join(5000)
            assertFalse("Linux output reader did not finish", reader.isAlive)
            assertEquals(output, 0, process.exitValue())
            return output
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @Test fun actualAppSandboxPreservesLinuxSyscallBehavior() {
        assumeTrue(Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        val probe = File(context.cacheDir, "runtime-syscalls-x86_64")
        try {
            instrumentation.context.assets.open("runtime-syscalls-x86_64").use { input ->
                probe.outputStream().use { input.copyTo(it) }
            }
            assertTrue(probe.setExecutable(true, true))
            val output = runLinux("/root/.pocketshell-runtime-probe", probe)
            assertTrue(output, output.contains("POCKETSHELL_SYSCALLS_OK 42"))
        } finally { probe.delete() }
    }

    @Test fun installedToolchainUsesAndroidDnsAndResolvesRealHosts() = runBlocking {
        val distro = requireNotNull(Userland.installedDistro(context))
        LinuxDns.refresh(context)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val expected = manager.activeNetwork?.let { manager.getLinkProperties(it)?.dnsServers }.orEmpty()
            .mapNotNull { it.hostAddress }.distinct()
        assertTrue("An online Android network is required", expected.isNotEmpty())
        val actual = File(Userland.rootfsDir(context, distro), "etc/resolv.conf").readLines()
            .filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ") }
        assertEquals(expected, actual)
        val output = runLinux("getent ahostsv4 archive.ubuntu.com && printf 'POCKETSHELL_DNS_OK\\n'")
        assertTrue(output, output.contains("POCKETSHELL_DNS_OK"))
        Bootstrap.healthCheck(context).getOrThrow()
        Unit
    }
}
