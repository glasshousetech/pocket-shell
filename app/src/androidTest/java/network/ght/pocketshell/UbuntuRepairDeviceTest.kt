package network.ght.pocketshell

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.concurrent.TimeUnit

/** Read-only diagnostics against the installed Linux environment. */
class UbuntuRepairDeviceTest {
    @Test
    fun inspectNodeFilesystem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val distro = requireNotNull(Userland.installedDistro(context))
        val command = "set -eu; ls -l /usr/bin/npm /usr/share/nodejs/npm/bin/npm-cli.js; " +
            "readlink -f /usr/bin/npm; " +
            "node -e 'const fs=require(\"fs\"); for(const p of [\"/usr/bin/npm\",\"/usr/share/nodejs/npm/bin/npm-cli.js\"]){console.log(p,fs.realpathSync(p),fs.statSync(p).size)}'; " +
            "dpkg --audit; npm --version"
        val pb = ProcessBuilder(*Userland.prootExecArgs(context, distro, command))
        pb.environment().clear()
        Userland.prootEnv(context).forEach { kv ->
            val (key, value) = kv.split("=", limit = 2)
            pb.environment()[key] = value
        }
        val process = pb.redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.append(it).append('\n') }
            }
        }.apply { isDaemon = true; start() }
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Node diagnostic timed out" }
        reader.join(2_000)
        assertEquals(output.toString(), 0, process.exitValue())
    }
}
