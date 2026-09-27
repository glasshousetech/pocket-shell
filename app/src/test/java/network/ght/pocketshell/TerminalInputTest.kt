package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test

class TerminalInputTest {
    @Test fun reviewedDictationCannotSubmitCommandsOrEscapeSequences() {
        assertEquals("echo one echo two[31m", TerminalInteraction.reviewedText("echo one\r\necho two\u001b[31m\u0000"))
        assertEquals("  café 👋  ", TerminalInteraction.reviewedText("  café 👋  "))
    }

    @Test fun sshStartupIsAnArgumentNotDelayedTerminalTyping() {
        val command = "ssh example.test 'tmux new-session -A -s agents'"
        assertArrayEquals(arrayOf("/bin/bash", "-lc", command), Userland.interactiveShellArgs("/bin/bash", command))
        assertArrayEquals(arrayOf("/bin/bash", "-l"), Userland.interactiveShellArgs("/bin/bash", null))
    }
}
