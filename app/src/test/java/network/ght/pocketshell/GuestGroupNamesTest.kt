package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GuestGroupNamesTest {
    @Test fun commandArgumentsArePassedWithoutRequoting() {
        val command = arrayOf("/bin/sh", "-c", "printf '%s' \"\$1\"; exit 37", "name", "é🙂\n\$HOME")
        val wrapped = GuestGroupNames.wrap(command, "exec \"\$@\"", 26)
        assertArrayEquals(command, wrapped.drop(5).toTypedArray())
        assertEquals("exec \"\$@\"", wrapped[2])
        assertEquals("26", wrapped[4])
    }

    @Test fun malformedRecordsNeverBecomeExportedTerminalContent() {
        val invalid = listOf(
            "password=not-a-real-password",
            "v1\tnot-a-time\t26\t17\t1077\tregistered\t0",
            "v1\t2026-11-07T00:00:00Z\t26\t17\t1077\tprivate-command\t0",
            "v1\t2026-11-07T00:00:00Z\t26\t17\t1077\tfailed\t999",
            "v1\t2026-11-07T00:00:00Z\t26\t17\t1077\tregistered\t0\nextra"
        )
        invalid.forEach { assertNull(GuestGroupNames.parse(it)) }
    }

    @Test fun interruptedSuccessAndFailureStatesStayDistinct() {
        for (state in listOf("registering", "registered", "failed", "enumeration_failed")) {
            val event = GuestGroupNames.parse("v1\t2026-11-07T00:00:00Z\t26\t17\t1077\t" + state + "\t1\n")
            assertNotNull(event)
            assertEquals(state, event!!.state)
            assertEquals(1077, event.groupId.toInt())
        }
    }

    @Test fun missingDiagnosticsAreEmptyWithoutInventingSuccess() {
        val dir = Files.createTempDirectory("pocketshell-groups-test").toFile()
        try { assertTrue(GuestGroupNames.readEvents(dir).isEmpty()) }
        finally { dir.deleteRecursively() }
    }

    @Test fun malformedStoredRecordIsReportedInsteadOfSilentlyDiscarded() {
        val root = Files.createTempDirectory("pocketshell-groups-test").toFile()
        try {
            val dir = File(root, "var/lib/pocketshell/group-map").apply { mkdirs() }
            File(dir, "1077").writeText("untrusted-terminal-text")
            assertThrows(IllegalArgumentException::class.java) { GuestGroupNames.readEvents(root) }
        } finally { root.deleteRecursively() }
    }

    @Test fun diagnosticsDirectoryFailureIsNotAnEmptySuccess() {
        val root = Files.createTempDirectory("pocketshell-groups-test").toFile()
        try {
            val file = File(root, "var/lib/pocketshell/group-map")
            requireNotNull(file.parentFile).mkdirs()
            file.writeText("file instead of directory")
            assertThrows(java.io.IOException::class.java) { GuestGroupNames.readEvents(root) }
        } finally { root.deleteRecursively() }
    }

    @Test fun actualGuestRepairRecordsAreReadableWhenFixtureIsProvided() {
        val configuredFixture = System.getProperty("pocketshell.guestFixture")
        org.junit.Assume.assumeNotNull(configuredFixture)
        val fixture = requireNotNull(configuredFixture)
        for (distro in listOf("rootfs", "alpine")) {
            val events = GuestGroupNames.readEvents(File(fixture, distro))
            assertEquals(setOf("1077", "3003", "9997", "20725", "50725"), events.map { it.groupId }.toSet())
            assertTrue(events.all { it.state == "registered" && it.exitCode == 0 && it.build == 26 })
        }
    }
}
