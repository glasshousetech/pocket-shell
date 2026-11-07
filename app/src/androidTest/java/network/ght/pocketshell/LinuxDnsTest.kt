package network.ght.pocketshell

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LinuxDnsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(block: (File) -> Unit) {
        val root = File(context.cacheDir, "dns-test-" + java.util.UUID.randomUUID())
        File(root, "etc").mkdirs()
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun address(ip: String) = InetAddress.getByName(ip)

    @Test fun networkChangesReplaceLegacyDnsAndOfflinePreservesLastRoute() = fixture { root ->
        val file = File(root, "etc/resolv.conf")
        file.writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        LinuxDns.writeRoot(context, root, listOf(address("10.0.2.3")))
        assertTrue(file.readText().contains("nameserver 10.0.2.3\n"))
        assertFalse(file.readText().contains("8.8.8.8"))
        LinuxDns.writeRoot(context, root, listOf(address("100.64.0.1"), address("2001:db8::53")))
        val beforeOffline = file.readText()
        assertTrue(beforeOffline.contains("nameserver 100.64.0.1\n"))
        assertEquals(2, beforeOffline.lineSequence().count { it.startsWith("nameserver ") })
        LinuxDns.writeRoot(context, root, emptyList())
        assertEquals(beforeOffline, file.readText())
    }

    @Test fun concurrentUpdatesLeaveCompleteConfigAndNeverRecreateRemovedRoot() = fixture { root ->
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..20).map { i -> pool.submit { LinuxDns.writeRoot(context, root, listOf(address("10.0.0.$i"))) } }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val lines = File(root, "etc/resolv.conf").readLines()
            assertEquals(2, lines.size)
            assertTrue((1..20).any { lines[1] == "nameserver 10.0.0.$it" })
            synchronized(SshKeyStore.identityLock) { assertTrue(root.deleteRecursively()) }
            LinuxDns.writeRoot(context, root, listOf(address("10.0.0.21")))
            assertFalse(root.exists())
        } finally { pool.shutdownNow() }
    }
}
