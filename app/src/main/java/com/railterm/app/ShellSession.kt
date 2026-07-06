package com.railterm.app

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * Backs one terminal tab with a real child process (the device's own shell —
 * no root, no bundled userland yet; that's the next milestone). Good enough
 * to prove the UI drives a genuine process, not a mock.
 */
class ShellSession(private val scope: CoroutineScope) {
    val output = mutableStateOf("")
    val isAlive = mutableStateOf(false)

    private var process: Process? = null
    private var stdin: OutputStream? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            try {
                val proc = ProcessBuilder("/system/bin/sh", "-i")
                    .redirectErrorStream(true)
                    .start()
                process = proc
                stdin = proc.outputStream
                withContext(Dispatchers.Main) { isAlive.value = true }

                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val buf = CharArray(1024)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    withContext(Dispatchers.Main) { output.value += chunk }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { output.value += "\n[railterm] couldn't start shell: ${e.message}\n" }
            } finally {
                withContext(Dispatchers.Main) { isAlive.value = false }
            }
        }
    }

    /** Submits a line of input as if typed + Enter. */
    fun sendLine(text: String) {
        writeRaw((text + "\n").toByteArray())
    }

    /** Sends a raw control byte, e.g. 0x03 for Ctrl+C, 0x1B for Esc. */
    fun sendControlByte(byte: Int) {
        writeRaw(byteArrayOf(byte.toByte()))
    }

    /** Sends raw text verbatim, e.g. an ANSI escape sequence like "[A" for Up. */
    fun sendEscape(seq: String) {
        writeRaw(seq.toByteArray())
    }

    private fun writeRaw(bytes: ByteArray) {
        val out = stdin ?: return
        scope.launch(Dispatchers.IO) {
            try {
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // process already gone; UI reflects this via isAlive
            }
        }
    }

    fun destroy() {
        process?.destroy()
    }
}
