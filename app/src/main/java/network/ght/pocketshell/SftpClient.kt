package network.ght.pocketshell

import java.io.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** SFTP v3 over an owned OpenSSH subsystem. No terminal parsing or second credential store. */
class SftpClient(
    private val process: Process,
    private val timeoutSeconds: Long = 120,
    // A corrupt packet must not allocate arbitrary memory. This is not a file/transfer size limit.
    private val maxPacketBytes: Int = 16 * 1024 * 1024,
) : Closeable {
    data class Attributes(val size: Long? = null, val permissions: Int? = null, val modified: Long? = null) {
        val directory get() = permissions?.and(0xf000) == 0x4000
        val regular get() = permissions?.and(0xf000) == 0x8000
    }
    class Path private constructor(private val value: ByteArray) {
        val bytes get() = value.copyOf()
        val display get() = String(value, Charsets.UTF_8)
        fun child(name: Path): Path {
            require(name.display != "." && name.display != ".." && name.value.isNotEmpty() && name.value.none { it == 47.toByte() || it == 0.toByte() }) { "Invalid file name." }
            return of(value + (if (value.lastOrNull() == 47.toByte()) byteArrayOf() else byteArrayOf(47)) + name.value)
        }
        fun parent(): Path {
            val end = value.dropLastWhile { it == 47.toByte() }.toByteArray().indexOfLast { it == 47.toByte() }
            return if (end <= 0) text("/") else of(value.copyOfRange(0, end))
        }
        override fun equals(other: Any?) = other is Path && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode()
        companion object {
            fun of(bytes: ByteArray): Path { require(bytes.isNotEmpty() && bytes.none { it == 0.toByte() }); return Path(bytes.copyOf()) }
            fun text(text: String) = of(text.toByteArray(Charsets.UTF_8))
        }
    }
    data class Entry(val name: Path, val attributes: Attributes)
    class StatusException(val status: Int) : IOException(when (status) {
        2 -> "The remote file no longer exists."
        3 -> "The server denied access to this file."
        4 -> "The server could not complete this file operation."
        8 -> "The server does not support this file operation."
        else -> "SFTP returned status " + status + "."
    })
    private data class Packet(val type: Int, val data: DataInputStream)
    private val output = DataOutputStream(BufferedOutputStream(process.outputStream))
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Packet>>()
    private val sequence = AtomicInteger(1)
    private val version = CompletableFuture<Map<String, String>>()
    @Volatile private var failure: Throwable? = null
    @Volatile var closed = false
        private set
    private val stderr = StringBuilder()
    private val errorReader = Thread({
        try {
            process.errorStream.bufferedReader().use { input ->
                val buffer = CharArray(2048)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    synchronized(stderr) { stderr.append(buffer, 0, n); if (stderr.length > 65536) stderr.delete(0, stderr.length - 65536) }
                }
            }
        } catch (_: IOException) { /* close() owns this stream */ }
    }, "pocketshell-sftp-errors").apply { isDaemon = true; start() }
    private val reader = Thread({
        try {
            DataInputStream(BufferedInputStream(process.inputStream)).use { input ->
                while (!closed) {
                    val size = input.readInt()
                    check(size in 5..maxPacketBytes) { "Invalid SFTP packet length (configured maximum: " + maxPacketBytes + " bytes)." }
                    val bytes = ByteArray(size)
                    input.readFully(bytes)
                    val data = DataInputStream(ByteArrayInputStream(bytes))
                    val type = data.readUnsignedByte()
                    if (type == 2) {
                        check(!version.isDone && data.readInt() == 3) { "This server does not offer SFTP version 3." }
                        val extensions = linkedMapOf<String, String>()
                        while (data.available() > 0) extensions[data.readString().toString(Charsets.UTF_8)] = data.readString().toString(Charsets.UTF_8)
                        version.complete(extensions)
                    } else {
                        val id = data.readInt()
                        val result = pending.remove(id) ?: throw IOException("Unexpected SFTP response identity.")
                        result.complete(Packet(type, data))
                    }
                }
            }
        } catch (error: Throwable) { fail(error) }
    }, "pocketshell-sftp-reader").apply { isDaemon = true; start() }

    val extensions: Map<String, String>
    init {
        require(timeoutSeconds > 0 && maxPacketBytes >= 65536)
        try {
            sendPacket(1) { writeInt(3) }
            extensions = await(version)
        } catch (error: Throwable) { close(); throw error }
    }
    fun stderrForClassification(): String = synchronized(stderr) { stderr.toString() }
    private fun fail(error: Throwable) {
        synchronized(this) {
            if (failure == null) failure = error
            closed = true
            version.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
        }
        process.destroy()
    }
    override fun close() {
        fail(IOException("SFTP connection closed."))
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        if (process.isAlive) process.destroyForcibly()
    }
    private fun <T> await(future: CompletableFuture<T>): T = try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        fail(error)
        throw IOException("SFTP reached its configured operation timeout. Check transfer status before retrying a write.", error)
    } catch (error: ExecutionException) { throw IOException("The SFTP connection ended before the operation was confirmed.", error.cause) }
      catch (error: InterruptedException) { Thread.currentThread().interrupt(); fail(error); throw IOException("SFTP operation interrupted; check its receipt.", error) }

    private fun sendPacket(type: Int, body: DataOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).use { data -> data.writeByte(type); data.body() } }.toByteArray()
        synchronized(output) { output.writeInt(bytes.size); output.write(bytes); output.flush() }
    }
    private fun request(type: Int, body: DataOutputStream.() -> Unit): CompletableFuture<Packet> {
        failure?.let { throw IOException("SFTP is disconnected. Reconnect to continue.", it) }
        val id = sequence.getAndIncrement()
        val result = CompletableFuture<Packet>()
        pending[id] = result
        try { sendPacket(type) { writeInt(id); body() } } catch (error: Throwable) { fail(error) }
        return result
    }
    private fun checked(packet: Packet, type: Int): DataInputStream {
        if (packet.type == 101) {
            val status = packet.data.readInt()
            if (status != 0) throw StatusException(status)
            check(type == 101) { "Unexpected empty SFTP response." }
        } else check(packet.type == type) { "Unexpected SFTP response type." }
        return packet.data
    }
    private fun response(type: Int, expected: Int, body: DataOutputStream.() -> Unit) = checked(await(request(type, body)), expected)
    private fun attrs(data: DataInputStream): Attributes {
        val flags = data.readInt()
        val size = if (flags and 1 != 0) data.readLong().also { check(it >= 0) } else null
        if (flags and 2 != 0) { data.readInt(); data.readInt() }
        val mode = if (flags and 4 != 0) data.readInt() else null
        val modified = if (flags and 8 != 0) { data.readInt(); data.readInt().toLong() and 0xffffffffL } else null
        if (flags and Int.MIN_VALUE != 0) repeat(data.readCount()) { data.readString(); data.readString() }
        return Attributes(size, mode, modified)
    }
    fun stat(path: Path, follow: Boolean = true): Attributes =
        attrs(response(if (follow) 17 else 7, 105) { writeString(path.bytes) })
    fun statOrNull(path: Path, follow: Boolean = false): Attributes? = try { stat(path, follow) }
        catch (e: StatusException) { if (e.status == 2) null else throw e }
    fun canonical(path: Path): Path {
        val data = response(16, 104) { writeString(path.bytes) }
        check(data.readInt() == 1)
        val result = Path.of(data.readString())
        data.readString(); attrs(data)
        return result
    }
    fun list(path: Path): List<Entry> {
        val handle = response(11, 102) { writeString(path.bytes) }.readString()
        val entries = mutableListOf<Entry>()
        try {
            while (true) {
                val packet = await(request(12) { writeString(handle) })
                if (packet.type == 101) {
                    val status = packet.data.readInt()
                    if (status == 1) break
                    throw StatusException(status)
                }
                val data = checked(packet, 104)
                repeat(data.readCount()) {
                    val name = Path.of(data.readString()); data.readString()
                    val attributes = attrs(data)
                    if (name.display != "." && name.display != "..") entries.add(Entry(name, attributes))
                }
            }
        } finally { closeHandle(handle) }
        return entries
    }
    private fun open(path: Path, flags: Int, permissions: Int? = null): ByteArray = response(3, 102) {
        writeString(path.bytes); writeInt(flags)
        writeInt(if (permissions == null) 0 else 4)
        if (permissions != null) writeInt(permissions)
    }.readString()
    private fun closeHandle(handle: ByteArray) { response(4, 101) { writeString(handle) } }

    /** Pipeline writes; progress counts acknowledged bytes. CLOSE and available fsync are checked. */
    fun upload(source: InputStream, path: Path, progress: (Long) -> Unit = {}): Long {
        val handle = open(path, 2 or 8 or 32, 384)
        var offset = 0L
        var confirmed = 0L
        val requests = ArrayDeque<Pair<CompletableFuture<Packet>, Int>>()
        try {
            val buffer = ByteArray(32768)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val start = offset
                requests.add(request(6) { writeString(handle); writeLong(start); writeString(buffer.copyOf(count)) } to count)
                offset += count
                if (requests.size >= 16) {
                    val (future, bytes) = requests.removeFirst()
                    checked(await(future), 101); confirmed += bytes; progress(confirmed)
                }
            }
            while (requests.isNotEmpty()) {
                val (future, bytes) = requests.removeFirst()
                checked(await(future), 101); confirmed += bytes; progress(confirmed)
            }
            if (extensions["fsync@openssh.com"] == "1") response(200, 101) { writeString("fsync@openssh.com".toByteArray()); writeString(handle) }
        } finally { closeHandle(handle) }
        return offset
    }
    fun download(path: Path, target: OutputStream, progress: (Long) -> Unit = {}): Long {
        val handle = open(path, 1)
        var offset = 0L
        fun readResult(packet: Packet): ByteArray? {
            if (packet.type == 101) {
                val status = packet.data.readInt()
                if (status == 1) return null
                throw StatusException(status)
            }
            return checked(packet, 103).readString().also { check(it.isNotEmpty()) { "The server returned an empty read without EOF." } }
        }
        try {
            val initial = attrs(response(8, 105) { writeString(handle) })
            if (initial.regular && initial.size != null) {
                // Sixteen in-flight 32KiB requests avoid one network round-trip per block.
                // Memory is independent of file size. Offsets, including the final short block, are exact.
                var requested = 0L
                val queue = ArrayDeque<Pair<CompletableFuture<Packet>, Int>>()
                while (offset < initial.size) {
                    while (queue.size < 16 && requested < initial.size) {
                        val start = requested
                        val length = minOf(32768L, initial.size - start).toInt()
                        queue.add(request(5) { writeString(handle); writeLong(start); writeInt(length) } to length)
                        requested += length
                    }
                    val (future, expected) = queue.removeFirst()
                    val bytes = readResult(await(future))
                    check(bytes != null && bytes.size == expected) { "The remote file changed during download. Retry after it stops changing." }
                    target.write(bytes); offset += bytes.size; progress(offset)
                }
                check(readResult(await(request(5) { writeString(handle); writeLong(offset); writeInt(1) })) == null) {
                    "The remote file grew during download. Retry after it stops changing."
                }
                check(initial == attrs(response(8, 105) { writeString(handle) })) { "The remote file changed during download." }
            } else {
                while (true) {
                    val start = offset
                    val bytes = readResult(await(request(5) { writeString(handle); writeLong(start); writeInt(32768) })) ?: break
                    target.write(bytes); offset += bytes.size; progress(offset)
                }
            }
        } finally { closeHandle(handle) }
        return offset
    }
    fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        download(path, object : OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()) }
            override fun write(bytes: ByteArray, off: Int, len: Int) { digest.update(bytes, off, len) }
        })
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /** Standard RENAME fails if destination exists; posix-rename would silently overwrite it. */
    fun rename(source: Path, target: Path) { response(18, 101) { writeString(source.bytes); writeString(target.bytes) } }
    fun mkdir(path: Path) { response(14, 101) { writeString(path.bytes); writeInt(4); writeInt(448) } }
    fun remove(path: Path) { response(13, 101) { writeString(path.bytes) } }
    private fun DataInputStream.readCount(): Int = readInt().also { check(it >= 0 && it <= available() / 4) { "Invalid SFTP item count." } }
    private fun DataInputStream.readString(): ByteArray {
        val size = readInt()
        check(size >= 0 && size <= available()) { "Invalid SFTP string length." }
        return ByteArray(size).also { readFully(it) }
    }
    private fun DataOutputStream.writeString(bytes: ByteArray) { writeInt(bytes.size); write(bytes) }
}
