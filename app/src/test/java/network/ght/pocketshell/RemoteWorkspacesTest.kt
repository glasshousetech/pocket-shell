package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

class RemoteWorkspacesTest {
    private val profile = SshProfile("Test", "example.test", "connor")

    @Test fun interruptedAndInvalidListsDoNotBecomeSuccessfulEmptyResults() {
        val incomplete = GuestResult(0, "__POCKET_WORKSPACES_V2__\n")
        assertTrue(runCatching { RemoteWorkspaces.parse(incomplete, profile) }.isFailure)
        assertTrue(runCatching { RemoteWorkspaces.parse(GuestResult(255, ""), profile) }.isFailure)
        assertTrue(runCatching { RemoteWorkspace("$(touch bad)", 1, "bad", 1, 0, 123, 1).validate() }.isFailure)
        assertTrue(RemoteWorkspaces.parse(GuestResult(0, "__POCKET_WORKSPACES_V2__\n__POCKET_WORKSPACES_END__\n"), profile).isEmpty())
    }

    @Test fun handoffPreservesTheExactTargetWithoutDetachingOtherClients() {
        val workspace = RemoteWorkspace("\$12", 1760000000, "My coding session", 2, 1, 321, 1759999990)
        val script = RemoteWorkspaces.attachScript(workspace)
        assertTrue(script.contains("1760000000"))
        assertTrue(script.contains("attach-session -t '\$12'"))
        assertFalse(script.contains("attach-session -d"))
        val portable = RemoteWorkspaces.attachCommand(profile, workspace, portable = true)
        File("build/workspaces").mkdirs()
        File("build/workspaces/portable-command.txt").writeText(portable, Charsets.UTF_8)
        File("build/workspaces/portable-script.sh").writeText(script, Charsets.UTF_8)
        assertFalse(portable.contains("id_ed25519"))
        assertTrue(portable.contains("StrictHostKeyChecking=yes"))
        val hop = profile.copy(remoteCommand = "~/.local/bin/ght-hop mob")
        assertTrue(runCatching { RemoteWorkspaces.attachCommand(hop, workspace) }.isFailure)
    }

    @Test fun realTmuxDiscoveryAttachmentAndDetachKeepBothRemoteProcessesAlive() {
        val folder = Files.createTempDirectory("pocketshell-workspaces-").toFile()
        val socket = "pocketshell-test-" + UUID.randomUUID()
        var attachment: Process? = null
        var handoffAttachment: Process? = null
        val realTmux = ProcessBuilder("/bin/sh", "-c", "command -v tmux").start().let {
            val found = it.inputStream.bufferedReader().readText().trim()
            check(it.waitFor() == 0 && found.isNotBlank()) { "tmux is required for this behavior test." }
            found
        }
        val wrapper = File(folder, "tmux")
        wrapper.writeText("#!/bin/sh\nexec " + CommandTemplate.quoteArgument(realTmux) +
            " -L " + CommandTemplate.quoteArgument(socket) + " \"\$@\"\n")
        check(wrapper.setExecutable(true))
        fun builder(vararg args: String) = ProcessBuilder(*args).apply {
            environment()["PATH"] = folder.absolutePath + ":" + environment()["PATH"]
            environment()["TERM"] = "xterm-256color"
            environment()["LANG"] = "C.UTF-8"
            environment()["LC_ALL"] = "C.UTF-8"
        }
        fun command(vararg args: String): GuestResult {
            val process = builder(*args).redirectErrorStream(true).start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Owned tmux command did not finish.")
            }
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return GuestResult(process.exitValue(), output)
        }
        fun shell(script: String): GuestResult {
            val file = File(folder, "probe.sh").apply { writeText(script, Charsets.UTF_8) }
            return command("/bin/sh", file.absolutePath)
        }
        fun tmux(vararg args: String) = command(wrapper.absolutePath, *args)
        fun waitFor(label: String, expected: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < until) {
                if (expected()) return
                Thread.sleep(30)
            }
            fail(label + ": " + listOf("attach-output.txt", "handoff-output.txt").joinToString("\n") { File(folder, it).takeIf { f -> f.exists() }?.readText().orEmpty() })
        }
        try {
            assertTrue(RemoteWorkspaces.parse(shell(RemoteWorkspaces.listScript()), profile).isEmpty())
            assertEquals(0, shell("tmux new-session -d -s 'Workspace α' -x 80 -y 24").exitCode)
            assertEquals(0, tmux("new-session", "-d", "-s", "Sibling workspace", "-x", "80", "-y", "24").exitCode)
            val sessions = RemoteWorkspaces.parse(shell(RemoteWorkspaces.listScript()), profile)
            assertEquals(setOf("Workspace α", "Sibling workspace"), sessions.map { it.name }.toSet())
            val chosen = sessions.single { it.name == "Workspace α" }
            val sibling = sessions.single { it.name == "Sibling workspace" }
            fun pid(workspace: RemoteWorkspace) = tmux("display-message", "-p", "-t", workspace.id, "#{pane_pid}").output.trim()
            val originalPid = pid(chosen)
            val siblingPid = pid(sibling)
            assertTrue(originalPid.toLong() > 0)

            val attachFile = File(folder, "attach.sh").apply { writeText(RemoteWorkspaces.attachScript(chosen)) }
            attachment = builder("script", "-q", "-c", "/bin/sh " + CommandTemplate.quoteArgument(attachFile.absolutePath), "/dev/null")
                .redirectOutput(File(folder, "attach-output.txt")).redirectErrorStream(true).start()
            waitFor("The existing workspace did not acquire a client") {
                tmux("display-message", "-p", "-t", chosen.id, "#{session_attached}").output.trim() == "1"
            }
            assertEquals(originalPid, pid(chosen))
            assertEquals(siblingPid, pid(sibling))
            // Exercise the exact copied command through a real shell and PTY.
            // The SSH shim only substitutes the network hop; it executes the received remote argument.
            val sshShim = File(folder, "ssh").apply {
                writeText("#!/bin/sh\nfor argument do remote=\"\$argument\"; done\nexec /bin/sh -c \"\$remote\"\n")
                check(setExecutable(true))
            }
            val handoffFile = File(folder, "handoff.sh").apply {
                writeText(RemoteWorkspaces.attachCommand(profile, chosen, portable = true), Charsets.UTF_8)
            }
            handoffAttachment = builder("script", "-q", "-c", "/bin/sh " + CommandTemplate.quoteArgument(handoffFile.absolutePath), "/dev/null")
                .redirectOutput(File(folder, "handoff-output.txt")).redirectErrorStream(true).start()
            waitFor("PC handoff must attach alongside the original client") {
                tmux("display-message", "-p", "-t", chosen.id, "#{session_attached}").output.trim() == "2"
            }
            assertTrue(checkNotNull(attachment).isAlive)
            assertEquals(originalPid, pid(chosen))
            assertEquals(siblingPid, pid(sibling))
            checkNotNull(handoffAttachment).outputStream.apply { write(byteArrayOf(2, 'd'.code.toByte())); flush() }
            waitFor("Leaving the PC client must preserve the original client") {
                tmux("display-message", "-p", "-t", chosen.id, "#{session_attached}").output.trim() == "1"
            }
            val attached = checkNotNull(attachment)
            attached.outputStream.write(byteArrayOf(2, 'd'.code.toByte()))
            attached.outputStream.flush()
            waitFor("Detach did not release the client") {
                tmux("display-message", "-p", "-t", chosen.id, "#{session_attached}").output.trim() == "0"
            }
            assertEquals(originalPid, pid(chosen))
            assertEquals(siblingPid, pid(sibling))
            assertEquals(75, shell(RemoteWorkspaces.attachScript(chosen.copy(created = chosen.created + 1))).exitCode)
            assertEquals(75, shell(RemoteWorkspaces.attachScript(chosen.copy(serverPid = chosen.serverPid + 1))).exitCode)
            assertEquals(75, shell(RemoteWorkspaces.attachScript(chosen.copy(serverStarted = chosen.serverStarted + 1))).exitCode)
            assertEquals(originalPid, pid(chosen))
            assertEquals(siblingPid, pid(sibling))
        } finally {
            handoffAttachment?.destroyForcibly()
            attachment?.destroyForcibly()
            tmux("kill-server") // Only the private test socket created above.
            folder.deleteRecursively()
        }
    }
}
