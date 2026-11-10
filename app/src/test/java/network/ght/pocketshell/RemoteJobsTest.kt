package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

class RemoteJobsTest {
    private fun cleanup(home: File) {
        File(home, ".local/state/pocketshell/jobs").listFiles().orEmpty().forEach { directory ->
            val metadata = File(directory, "running").takeIf { it.isFile }?.readText()?.trim()?.split(" ") ?: return@forEach
            val pid = metadata.firstOrNull()?.toLongOrNull() ?: return@forEach
            val commandLine = File("/proc/" + pid + "/cmdline").takeIf { it.isFile }?.readText().orEmpty()
            val ticks = File("/proc/" + pid + "/stat").takeIf { it.isFile }?.readText()?.substringAfterLast(") ")?.split(" ")?.getOrNull(19)
            if (commandLine.contains(directory.absolutePath + "/runner.sh") && ticks == metadata.getOrNull(2)) {
                val group = File("/proc/" + pid + "/stat").readText().substringAfterLast(") ").split(" ")[2]
                if (group == pid.toString()) ProcessBuilder("/bin/kill", "-KILL", "--", "-" + pid).start().waitFor()
            }
        }
        home.deleteRecursively()
    }
    private fun job(command: String, home: File) = RemoteJob(UUID.randomUUID().toString(), "Verification job",
        SshProfile("Fixture", "example.test", "connor", 22, "", ""), command, home.absolutePath, System.currentTimeMillis())
    private fun execute(home: File, script: String): GuestResult {
        val process = ProcessBuilder("sh", "-c", script).apply { environment()["HOME"] = home.absolutePath }.redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Fixture command did not finish" }
        return GuestResult(process.exitValue(), text)
    }
    private fun observation(home: File, job: RemoteJob) =
        RemoteJobs.parse(execute(home, RemoteJobs.statusScript(listOf(job.id))), listOf(job.id))[job.id]!!
    private fun await(home: File, job: RemoteJob, state: String): JobObservation {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val actual = observation(home, job)
            if (actual.state == state) return actual
            Thread.sleep(20)
        }
        error("Expected " + state + ", got " + observation(home, job))
    }

    @Test fun realDetachedJobSurvivesLauncherAndDuplicateSubmissionRunsOnlyOnce() {
        val home = Files.createTempDirectory("pocketshell-jobs-").toFile()
        try {
            val marker = File(home, "allow-finish")
            val job = job("printf 'literal %s\\n' \"it is safe\"; printf x >> count; while [ ! -f allow-finish ]; do sleep 0.02; done; printf 'finished\\n'", home)
            val first = execute(home, RemoteJobs.launchScript(job))
            assertEquals(first.output, 0, first.exitCode)
            val running = await(home, job, "RUNNING")
            assertFalse(running.terminal)
            val second = execute(home, RemoteJobs.launchScript(job))
            assertEquals(second.output, 0, second.exitCode)
            assertEquals("x", File(home, "count").readText())
            marker.writeText("go")
            val done = await(home, job, "SUCCEEDED")
            assertEquals(0, done.exit)
            assertTrue(done.finished > 0)
            assertEquals("literal it is safe\nfinished\n", File(done.remoteDirectory, "output.log").readText())
            assertEquals("x", File(home, "count").readText())
            // A fresh status request recovers the same result after client state is discarded.
            assertEquals(done.copy(checked = 1), observation(home, job).copy(checked = 1))
        } finally { cleanup(home) }
    }

    @Test fun failureExitAndPidReuseAreNotReportedAsSuccessfulOrRunning() {
        val home = Files.createTempDirectory("pocketshell-jobs-failed-").toFile()
        try {
            val failed = job("printf 'actual failure\\n'; exit 7", home)
            assertEquals(0, execute(home, RemoteJobs.launchScript(failed)).exitCode)
            val result = await(home, failed, "FAILED")
            assertEquals(7, result.exit)
            assertEquals("actual failure\n", File(result.remoteDirectory, "output.log").readText())

            val stale = job("unused", home)
            val folder = File(home, ".local/state/pocketshell/jobs/" + stale.id).apply { mkdirs() }
            val boot = File("/proc/sys/kernel/random/boot_id").readText().trim()
            val pid = File("/proc/self/stat").readText().substringBefore(" ").toLong()
            File(folder, "running").writeText(pid.toString() + " " + boot + " 1 1760000000\n")
            File(folder, "ready").writeText("1")
            assertEquals("INTERRUPTED", observation(home, stale).state)
            assertNull(observation(home, stale).exit)
            File(folder, "running").delete()
            assertEquals("STARTING", observation(home, stale).state)
            File(folder, "ready").delete()
            assertEquals("PREPARING", observation(home, stale).state)
        } finally { cleanup(home) }
    }

    @Test fun concurrentSameIdentityDoesNotDuplicateCommandAndPartialResponseCannotReplaceState() {
        val home = Files.createTempDirectory("pocketshell-jobs-race-").toFile()
        try {
            val job = job("printf x >> count", home)
            val script = RemoteJobs.launchScript(job)
            val calls = (1..4).map {
                ProcessBuilder("sh", "-c", script).apply { environment()["HOME"] = home.absolutePath }
                    .redirectErrorStream(true).redirectOutput(File(home, "caller-" + it)).start()
            }
            calls.forEach { assertTrue(it.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, it.exitValue()) }
            await(home, job, "SUCCEEDED")
            assertEquals("x", File(home, "count").readText())
            val complete = execute(home, RemoteJobs.statusScript(listOf(job.id)))
            try { RemoteJobs.parse(complete.copy(output = complete.output.substringBefore("__POCKET_JOBS_END__")), listOf(job.id)); fail("Truncated protocol must fail") }
            catch (_: IllegalStateException) { }
        } finally { cleanup(home) }
    }

    @Test fun outputAndRelayCommandsPreserveExactShellQuoting() {
        val home = Files.createTempDirectory("pocketshell-jobs-quotes-").toFile()
        try {
            val command = "printf '%s\\n' 'apostrophe' \"literal \\\$(not-executed)\""
            val job = job(command, home)
            val script = RemoteJobs.launchScript(job)
            assertEquals(0, execute(home, script).exitCode)
            val done = await(home, job, "SUCCEEDED")
            assertTrue(File(done.remoteDirectory, "output.log").readText().contains("literal \$(not-executed)"))
            val output = RemoteJobs.outputScript(job)
            assertTrue(output.contains(job.id))
            assertTrue(RemoteJobs.command(job.profile.copy(remoteCommand = "~/.local/bin/ght-hop agent-prod"), script).contains("ght-hop agent-prod"))
            File("build/workspaces").mkdirs()
            val relayJob = RemoteJob(UUID.randomUUID().toString(), "Relay verification", job.profile.copy(remoteCommand = "~/.local/bin/ght-hop agent-prod"), "printf '%s\\n' 'POCKET_RELAY_JOB_OK' 'literal $(printf untouched)'", System.getProperty("user.dir"), System.currentTimeMillis())
            File("build/workspaces/jobs-relay-id.txt").writeText(relayJob.id)
            File("build/workspaces/jobs-relay-command.txt").writeText(RemoteJobs.command(relayJob.profile, RemoteJobs.launchScript(relayJob)))
            File("build/workspaces/jobs-relay-status.txt").writeText(RemoteJobs.command(relayJob.profile, RemoteJobs.statusScript(listOf(relayJob.id))))
        } finally { cleanup(home) }
    }
}
