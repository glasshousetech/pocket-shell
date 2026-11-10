package network.ght.pocketshell

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RemoteWorkspacePersistenceTest {
    @Test fun exactIdentityAndFullRetainedOutputSurviveAndInvalidIdentityCannotRejoin() {
        assumeTrue("Isolated emulator only", android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SessionStore.load(context) // Serialize with any earlier queued writes.
        val file = File(context.noBackupFilesDir, "sessions_snapshot.json")
        val previous = file.takeIf { it.exists() }?.readBytes()
        try {
            SessionStore.clear(context)
            val workspace = RemoteWorkspace("\$7", 1760000010, "Running work", 2, 1, 1234, 1760000000)
            val history = "retained output\n".repeat(1500)
            val profile = SshProfile("Fixture host", "example.test", "connor")
            SessionStore.save(context, listOf(SessionStore.Saved(SessionMode.LINUX, "Fixture", null, history,
                connectionProfile = profile, remoteWorkspace = workspace)))
            val restored = SessionStore.load(context).single()
            assertEquals(history, restored.transcript)
            assertTrue(workspace.sameProcess(checkNotNull(restored.remoteWorkspace)))
            assertEquals(profile, restored.connectionProfile)
            assertNull(restored.restoreNotice)

            val root = JSONObject(file.readText())
            root.getJSONArray("sessions").getJSONObject(0).getJSONObject("remoteWorkspace").put("serverPid", -1)
            file.writeText(root.toString())
            val invalid = SessionStore.load(context).single()
            assertEquals(history, invalid.transcript)
            assertNull(invalid.remoteWorkspace)
            assertNull(invalid.connectionProfile)
            assertNull(invalid.startupCommand)
            assertTrue(checkNotNull(invalid.restoreNotice).contains("History was kept"))

            root.put("version", 999)
            file.writeText(root.toString())
            val future = file.readBytes()
            SessionStore.save(context, listOf(restored))
            assertTrue(SessionStore.load(context).isEmpty())
            assertArrayEquals("A newer-format snapshot must not be overwritten", future, file.readBytes())
        } finally {
            SessionStore.load(context)
            if (previous == null) file.delete() else file.writeBytes(previous)
        }
    }
}
