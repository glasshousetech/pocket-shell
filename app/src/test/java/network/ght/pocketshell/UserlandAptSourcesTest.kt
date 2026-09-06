package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserlandAptSourcesTest {
    @Test
    fun `amd64 uses the Ubuntu archive`() {
        val sources = Userland.ubuntuAptSources("x86_64")

        assertTrue(sources.contains("URIs: http://archive.ubuntu.com/ubuntu"))
        assertTrue(sources.contains("Suites: noble noble-updates noble-security"))
        assertTrue(sources.contains("Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg"))
    }

    @Test
    fun `ARM uses the Ubuntu ports archive`() {
        assertEquals(
            "URIs: http://ports.ubuntu.com/ubuntu-ports",
            Userland.ubuntuAptSources("arm64-v8a").lineSequence().first { it.startsWith("URIs:") },
        )
        assertEquals(
            "URIs: http://ports.ubuntu.com/ubuntu-ports",
            Userland.ubuntuAptSources("armeabi-v7a").lineSequence().first { it.startsWith("URIs:") },
        )
    }

    @Test
    fun `Ubuntu apt runs recover an interrupted dpkg first`() {
        val prelude = Userland.ubuntuAptPrelude()

        assertTrue(prelude.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(prelude.contains("dpkg --configure -a"))
        assertTrue(prelude.trimEnd().endsWith(";"))
        assertTrue(Userland.ubuntuApt().startsWith("apt-get "))
    }
}
