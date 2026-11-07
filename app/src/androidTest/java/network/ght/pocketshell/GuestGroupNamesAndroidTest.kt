package network.ght.pocketshell

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.terminal.TerminalSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** Real Android/proot regression using a pinned minimal guest in an isolated app-owned directory. */
@RunWith(AndroidJUnit4::class)
class GuestGroupNamesAndroidTest {
    @Test
    fun inheritedGroupsResolveBeforeCommandsAndPtyStartup() = verifyGuest(Distro.Ubuntu)

    @Test
    fun alpineGroupsAndCompleteToolkitInstallWork() = verifyGuest(Distro.Alpine)

    private fun verifyGuest(distro: Distro) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val realContext = instrumentation.targetContext
        val fixture = File(realContext.cacheDir, "group-name-test-" + UUID.randomUUID()).apply { mkdirs() }
        check(fixture.canonicalFile.parentFile == realContext.cacheDir.canonicalFile)
        val context = object : ContextWrapper(realContext) {
            override fun getFilesDir() = File(fixture, "files").apply { mkdirs() }
            override fun getCacheDir() = File(fixture, "cache").apply { mkdirs() }
            override fun getApplicationContext(): Context = this
        }
        var terminal: TerminalSession? = null
        try {
            val abi = requireNotNull(Userland.supportedAbi(distro))
            val descriptor = requireNotNull(Userland.rootfsFor(distro, abi))
            val archive = File(context.cacheDir, "rootfs.tar.gz")
            val digest = MessageDigest.getInstance("SHA-256")
            val connection = URL(descriptor.url).openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            assertEquals(descriptor.sha256, digest.digest().joinToString("") { "%02x".format(it) })
            val root = Userland.rootfsDir(context, distro).apply { mkdirs() }
            // Reuse the shipped extractor, without provisioning or touching the user's installed guest.
            Bootstrap::class.java.getDeclaredMethod("extract", File::class.java, File::class.java)
                .apply { isAccessible = true }.invoke(Bootstrap, root, archive)
            Userland.installedMarker(context, distro).writeText("isolated regression fixture\n")
            val originalGroups = File(root, "etc/group").readText()

            val wrapped = Userland.prootExecArgs(context, distro, "printf 'IDS='; id -G; groups")
            val wrapperMarker = wrapped.indexOf("pocketshell-group-map")
            check(wrapperMarker >= 3)
            // Same packaged proot/root/env, bypassing only the repair to demonstrate the original failure.
            val baseline = execute(context, wrapped.take(wrapperMarker - 3).toTypedArray() +
                arrayOf("/bin/sh", "-c", "printf 'IDS='; id -G; groups"))
            if (distro == Distro.Ubuntu) assertTrue("Expected the missing Android group names: " + baseline.output,
                baseline.output.contains("cannot find name for group ID"))
            println("GROUP_NAME_BASELINE code=" + baseline.code + " output=" + baseline.output.replace("\n", "\\n"))
            val numericGroups = baseline.output.lineSequence().first { it.startsWith("IDS=") }

            if (abi == "x86_64") {
                val probe = File(root, "tmp/pocketshell-syscalls-probe")
                instrumentation.context.assets.open("runtime-syscalls-x86_64").use { input ->
                    probe.outputStream().use { input.copyTo(it) }
                }
                assertTrue("Owned native probe must be executable", probe.setExecutable(true))
                val syscalls = execute(context, wrapped.take(wrapperMarker - 3).toTypedArray() +
                    arrayOf("/tmp/pocketshell-syscalls-probe"))
                println("GROUP_NAME_SYSCALLS code=" + syscalls.code + " output=" + syscalls.output.replace("\n", "\\n"))
                assertEquals("Native compatibility probe: " + syscalls.output, 0, syscalls.code)
                assertTrue(syscalls.output, syscalls.output.contains("POCKETSHELL_SYSCALLS_OK"))
            }

            val fixed = execute(context, Userland.prootExecArgs(context, distro,
                "printf 'IDS='; id -G; groups; printf 'owned-storage-probe' > /root/.group-test; cat /root/.group-test"))
            println("GROUP_NAME_FIXED code=" + fixed.code + " output=" + fixed.output.replace("\n", "\\n"))
            if (fixed.code != 0) {
                val base = wrapped.take(wrapperMarker - 3).toTypedArray()
                val probes = listOf(
                    "printf 'REDIRECT_BEFORE\n'; id -Gn >/dev/null 2>&1; printf 'REDIRECT_AFTER=%s\n' \"$?\"",
                    "printf 'MKDIR_BEFORE\n'; mkdir -p /var/lib/pocketshell/group-map; printf 'MKDIR_AFTER=%s\n' \"$?\""
                )
                probes.forEachIndexed { index, command ->
                    val result = execute(context, base + arrayOf("/bin/sh", "-xc", command))
                    println("GROUP_NAME_PROBE " + index + " code=" + result.code + " output=" + result.output.replace("\n", "\\n"))
                }
                val traceArgs = wrapped.clone().apply { this[wrapperMarker - 2] = "-xc" }
                val trace = execute(context, traceArgs)
                println("GROUP_NAME_TRACE code=" + trace.code + " output=" + trace.output.replace("\n", "\\n"))
                File(root, "var/lib/pocketshell/group-map").listFiles().orEmpty().filter { it.isFile }.forEach {
                    println("GROUP_NAME_RECORD " + it.name + "=" + it.readText().replace("\n", "\\n"))
                }
            }
            assertEquals("Guest exit=" + fixed.code + " output=" + fixed.output, 0, fixed.code)
            assertFalse(fixed.output, fixed.output.contains("cannot find name for group ID"))
            assertTrue(fixed.output, fixed.output.lineSequence().any { it == numericGroups })
            assertTrue(fixed.output, fixed.output.contains("owned-storage-probe"))
            assertTrue(File(root, "etc/group").readText().startsWith(originalGroups))
            val mappedGroups = File(root, "etc/group").readText()

            val repeated = execute(context, Userland.prootExecArgs(context, distro,
                "cat /root/.group-test; printf '\\n'; id -Gn; exit 37"))
            assertEquals(repeated.output, 37, repeated.code)
            assertFalse(repeated.output, repeated.output.contains("cannot find name for group ID"))
            assertEquals(mappedGroups, File(root, "etc/group").readText())

            val finished = CountDownLatch(1)
            val transcript = AtomicReference("")
            instrumentation.runOnMainSync {
                terminal = TermCore.newSession(context, SessionMode.LINUX,
                    startupCommand = "test -t 0 && printf 'GROUP_PTY_READY\\n'; groups; printf 'GROUP_PTY_DONE\\n'",
                    onRedraw = { transcript.set(it.emulator?.screen?.transcriptText.orEmpty()) },
                    onTitle = {},
                    onFinished = {
                        transcript.set(it.emulator?.screen?.transcriptText.orEmpty())
                        finished.countDown()
                    })
                terminal!!.updateSize(120, 40)
            }
            assertTrue("Owned PTY did not finish", finished.await(90, TimeUnit.SECONDS))
            assertTrue(transcript.get(), transcript.get().contains("GROUP_PTY_READY"))
            assertTrue(transcript.get(), transcript.get().contains("GROUP_PTY_DONE"))
            assertFalse(transcript.get(), transcript.get().contains("cannot find name for group ID"))

            val events = GuestGroupNames.readEvents(root)
            assertTrue("Repair records missing", events.isNotEmpty())
            assertTrue(events.toString(), events.all { it.state == "registered" && it.exitCode == 0 })
            val exported = DiagnosticLog.read(context)
            assertTrue(exported, exported.contains("android_group_registered"))
            assertFalse(exported, exported.contains("owned-storage-probe"))
            if (distro == Distro.Alpine) {
                val scriptField = Userland::class.java.getDeclaredField("groupStartupScript").apply { isAccessible = true }
                val originalScript = scriptField.get(Userland) as String
                try {
                    scriptField.set(Userland, "set -x\n" + originalScript)
                    val installed = runBlocking {
                        Bootstrap.repair(context, distro) { println("ALPINE_INSTALL_STATUS " + it) }
                    }
                    println("ALPINE_INSTALL_RESULT success=" + installed.isSuccess + " error=" + installed.exceptionOrNull()?.message)
                    assertTrue("Alpine toolchain: " + installed.exceptionOrNull()?.message, installed.isSuccess)
                    val health = runBlocking { Bootstrap.healthCheck(context) }
                    assertTrue("Installed toolchain health: " + health.exceptionOrNull()?.message, health.isSuccess)
                    println("ALPINE_INSTALL_HEALTH " + health.getOrThrow())
                } finally {
                    scriptField.set(Userland, originalScript)
                }
            }
            println("GROUP_NAME_FIXTURE abi=" + abi + " numeric=" + numericGroups +
                " registered=" + events.size + " pty=true repeatedExit=" + repeated.code)
            println("GROUP_NAME_BASELINE " + baseline.output.replace("\n", "\\n"))
            println("GROUP_NAME_FIXED " + fixed.output.replace("\n", "\\n"))
        } finally {
            instrumentation.runOnMainSync { terminal?.finishIfRunning() }
            // Files.walkFileTree does not follow guest symlinks outside this owned fixture.
            Files.walkFileTree(fixture.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private data class Result(val code: Int, val output: String)

    private fun execute(context: Context, args: Array<String>): Result {
        val builder = ProcessBuilder(*args).redirectErrorStream(true)
        builder.environment().clear()
        Userland.prootEnv(context).forEach {
            val pair = it.split("=", limit = 2)
            builder.environment()[pair[0]] = pair[1]
        }
        val process = builder.start()
        val output = AtomicReference("")
        val reader = thread(name = "group-regression-output", isDaemon = true) {
            output.set(process.inputStream.bufferedReader().use { it.readText() })
        }
        try {
            assertTrue("Owned guest command timed out", process.waitFor(90, TimeUnit.SECONDS))
            reader.join(5_000)
            assertFalse("Guest output reader did not finish", reader.isAlive)
            return Result(process.exitValue(), output.get())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.join(5_000)
        }
    }
}
