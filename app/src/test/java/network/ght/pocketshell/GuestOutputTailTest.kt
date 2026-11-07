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
    @Test
    fun interruptedSubprocessOutputReachesCallerWithoutUncaughtReaderCrash() {
        val expected = java.io.InterruptedIOException("closed fixture pipe")
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw expected
        }
        val result = java.util.concurrent.atomic.AtomicReference<java.io.IOException?>()
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val reader = Thread { result.set(GuestOutputTail(100).drain(broken)) }
        reader.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> uncaught.set(failure) }
        reader.start()
        reader.join(5_000)
        assertFalse(reader.isAlive)
        assertEquals(expected, result.get())
        assertEquals(null, uncaught.get())
    }

    @Test
    fun normalSubprocessOutputRemainsCompleteAndReportsNoReadFailure() {
        val tail = GuestOutputTail(100)
        assertEquals(null, tail.drain("ready\ncomplete\n".byteInputStream()))
        assertEquals("ready\ncomplete\n", tail.toString())
    }
}
