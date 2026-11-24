package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random

class SftpBehaviorTest {
    private fun server(root: File): Process {
        val executable = listOf("/usr/lib/openssh/sftp-server", "/usr/libexec/openssh/sftp-server").map(::File).firstOrNull { it.canExecute() }
            ?: error("Install openssh-sftp-server for real file-transfer verification.")
        return ProcessBuilder(executable.path, "-d", root.absolutePath).start()
    }
    private fun profile() = SshProfile("Test", "example.test", "connor", 22, "", "")

    @Test fun realServerRoundTripKeepsExactNamesBytesAndConflicts() {
        val root = Files.createTempDirectory("pocketshell-sftp-").toFile()
        try {
            SftpClient(server(root), 10).use { client ->
                val directory = client.canonical(SftpClient.Path.text("."))
                val bytes = ByteArray(2 * 1024 * 1024 + 37).also { Random(42).nextBytes(it) }
                val name = SftpClient.Path.of(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + " ' α\n.bin".toByteArray())
                val path = directory.child(name)
                var acknowledged = 0L
                assertEquals(bytes.size.toLong(), client.upload(ByteArrayInputStream(bytes), path) { acknowledged = it })
                assertEquals(bytes.size.toLong(), acknowledged)
                assertTrue(client.list(directory).any { it.name == name })
                val output = ByteArrayOutputStream()
                assertEquals(bytes.size.toLong(), client.download(path, output))
                assertArrayEquals(bytes, output.toByteArray())
                try { client.upload(ByteArrayInputStream(byteArrayOf(1)), path); fail("Exclusive upload must refuse an existing file") }
                catch (_: SftpClient.StatusException) { }
                assertEquals(bytes.size.toLong(), client.stat(path).size)
                val occupied = directory.child(SftpClient.Path.text("occupied"))
                client.upload(ByteArrayInputStream("keep me".toByteArray()), occupied)
                try { client.rename(path, occupied); fail("RENAME must not overwrite") }
                catch (_: SftpClient.StatusException) { }
                val preserved = ByteArrayOutputStream()
                client.download(occupied, preserved)
                assertEquals("keep me", preserved.toString("UTF-8"))
                client.mkdir(directory.child(SftpClient.Path.text("new folder")))
                assertTrue(client.stat(directory.child(SftpClient.Path.text("new folder"))).directory)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun uploadReceiptSurvivesConflictsReplacementAndLostRenameReply() {
        val root = Files.createTempDirectory("pocketshell-transfer-").toFile()
        val local = Files.createTempFile("pocketshell-source-", ".bin").toFile()
        local.writeBytes(ByteArray(175003).also { Random(9).nextBytes(it) })
        try {
            SftpClient(server(root), 10).use { client ->
                val target = client.canonical(SftpClient.Path.text(".")).child(SftpClient.Path.text("result ' α.bin"))
                File(root, target.display.substringAfterLast('/')).writeText("old contents")
                var receipt: FileTransfer? = null
                val transfers = SftpTransfers(client) { receipt = it }
                val prepared = FileTransfer.prepare(profile(), target, local)
                val conflict = transfers.upload(prepared, local)
                assertEquals(FileTransfer.Phase.CONFLICT, conflict.phase)
                assertEquals("old contents", File(root, "result ' α.bin").readText())
                assertEquals(prepared.sha256, client.hash(prepared.staged))
                val result = transfers.commit(conflict, true)
                assertEquals(FileTransfer.Phase.COMPLETE, result.phase)
                assertArrayEquals(local.readBytes(), File(root, "result ' α.bin").readBytes())
                assertEquals("old contents", File(root, prepared.backup.display.substringAfterLast('/')).readText())
                assertNull(client.statOrNull(prepared.staged))

                // Durable last phase says COMMITTING when the network loses its success reply.
                val uncertain = result.copy(phase = FileTransfer.Phase.COMMITTING)
                val recovered = transfers.reconcile(uncertain)
                assertEquals(FileTransfer.Phase.COMPLETE, recovered.phase)
                assertEquals(FileTransfer.Phase.COMPLETE, receipt!!.phase)

                val another = FileTransfer.prepare(profile(), target, local)
                val secondConflict = transfers.upload(another, local)
                val kept = transfers.keepBoth(secondConflict, "second copy.bin")
                assertEquals(FileTransfer.Phase.COMPLETE, kept.phase)
                assertArrayEquals(local.readBytes(), File(root, "second copy.bin").readBytes())
                assertArrayEquals(local.readBytes(), File(root, "result ' α.bin").readBytes())
            }
        } finally { root.deleteRecursively(); local.delete() }
    }

    @Test fun interruptedStageAndDiskJournalFailureNeverClaimCompletion() {
        val root = Files.createTempDirectory("pocketshell-interrupt-").toFile()
        val local = Files.createTempFile("pocketshell-source-", ".txt").toFile().apply { writeText("actual complete contents") }
        try {
            SftpClient(server(root), 10).use { client ->
                val target = client.canonical(SftpClient.Path.text(".")).child(SftpClient.Path.text("original"))
                val prepared = FileTransfer.prepare(profile(), target, local)
                File(root, "original").writeText("keep")
                client.upload(ByteArrayInputStream("partial".toByteArray()), prepared.staged)
                val transfers = SftpTransfers(client) { }
                assertEquals(FileTransfer.Phase.FAILED, transfers.reconcile(prepared.copy(phase = FileTransfer.Phase.UPLOADING)).phase)
                assertEquals("keep", File(root, "original").readText())
                val other = FileTransfer.prepare(profile(), target, local)
                try {
                    SftpTransfers(client) { throw java.io.IOException("disk full") }.upload(other, local)
                    fail("An unwritable receipt must stop before any remote write")
                } catch (_: java.io.IOException) { }
                assertNull(client.statOrNull(other.staged))
                assertEquals("keep", File(root, "original").readText())
            }
        } finally { root.deleteRecursively(); local.delete() }
    }

    @Test fun transportsSelectActualSubsystemWithoutTerminalOrUnknownCommands() {
        val direct = FileTransport.command(profile(), 400)
        assertTrue(direct.contains("ssh -T -s "))
        assertTrue(direct.contains("StrictHostKeyChecking=yes"))
        File("build/workspaces").mkdirs()
        File("build/workspaces/relay-sftp.sh").writeText(FileTransport.relayScript("agent-prod", 120))
        val relay = FileTransport.command(profile().copy(remoteCommand = "~/.local/bin/ght-hop agent-prod"), 400)
        assertTrue(relay.contains("ght-agents"))
        assertTrue(relay.contains(" -s -- "))
        assertFalse(relay.contains("tmux"))
        try { FileTransport.command(profile().copy(remoteCommand = "arbitrary command"), 400); fail("Unsupported routing must be explicit") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun disconnectDuringUploadLeavesOriginalAndRecoverablePartialReceipt() {
        val root = Files.createTempDirectory("pocketshell-drop-").toFile()
        val local = Files.createTempFile("pocketshell-large-", ".bin").toFile()
        local.outputStream().use { output -> repeat(512) { output.write(ByteArray(32768) { (it % 253).toByte() }) } }
        try {
            val process = server(root)
            var receipt: FileTransfer? = null
            SftpClient(process, 10).use { client ->
                val target = client.canonical(SftpClient.Path.text(".")).child(SftpClient.Path.text("original"))
                File(root, "original").writeText("original bytes")
                val item = FileTransfer.prepare(profile(), target, local)
                try {
                    SftpTransfers(client) { receipt = it }.upload(item, local) { process.destroyForcibly() }
                    fail("A disconnected transfer cannot report completion")
                } catch (_: Exception) { }
                assertEquals(FileTransfer.Phase.UNCERTAIN, receipt!!.phase)
                assertEquals("original bytes", File(root, "original").readText())
            }
            SftpClient(server(root), 10).use { client ->
                val result = SftpTransfers(client) { receipt = it }.reconcile(receipt!!)
                assertEquals(FileTransfer.Phase.FAILED, result.phase)
                assertEquals("original bytes", File(root, "original").readText())
            }
        } finally { root.deleteRecursively(); local.delete() }
    }

    @Test fun concurrentDestinationWriteAfterBackupIsPreservedAndReportedUncertain() {
        val root = Files.createTempDirectory("pocketshell-race-").toFile()
        val local = Files.createTempFile("pocketshell-new-", ".txt").toFile().apply { writeText("new data") }
        try {
            SftpClient(server(root), 10).use { client ->
                val target = client.canonical(SftpClient.Path.text(".")).child(SftpClient.Path.text("shared"))
                File(root, "shared").writeText("old data")
                var receipt: FileTransfer? = null
                var competingWriter = false
                val engine = SftpTransfers(client) { item ->
                    receipt = item
                    if (competingWriter && item.phase == FileTransfer.Phase.COMMITTING) File(root, "shared").writeText("other writer")
                }
                val item = FileTransfer.prepare(profile(), target, local)
                val conflict = engine.upload(item, local)
                competingWriter = true
                try { engine.commit(conflict, true); fail("A competing destination must not be overwritten") }
                catch (_: Exception) { }
                assertEquals(FileTransfer.Phase.UNCERTAIN, receipt!!.phase)
                assertEquals("other writer", File(root, "shared").readText())
                assertEquals("old data", File(root, item.backup.display.substringAfterLast('/')).readText())
                assertEquals("new data", File(root, item.staged.display.substringAfterLast('/')).readText())
                assertEquals(FileTransfer.Phase.CONFLICT, engine.reconcile(receipt!!).phase)
            }
        } finally { root.deleteRecursively(); local.delete() }
    }
}
