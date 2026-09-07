package network.ght.pocketshell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestOutputTailTest {
    @Test
    fun `keeps everything while under the cap`() {
        val tail = GuestOutputTail(maxChars = 100)
        tail.append("one")
        tail.append("two")
        assertEquals("one\ntwo\n", tail.toString())
    }

    @Test
    fun `drops the oldest lines first once over the cap`() {
        val tail = GuestOutputTail(maxChars = 40)
        repeat(20) { tail.append("Setting up package-$it ...") }
        tail.append("E: dpkg was interrupted")
        val text = tail.toString()
        assertTrue(text.endsWith("E: dpkg was interrupted\n"))
        assertFalse(text.contains("package-0 "))
        assertTrue(text.length <= 40 + "E: dpkg was interrupted".length + 1)
    }

    @Test
    fun `always retains at least the last line even if it exceeds the cap`() {
        val tail = GuestOutputTail(maxChars = 5)
        tail.append("a very long final line")
        assertEquals("a very long final line\n", tail.toString())
    }
}
