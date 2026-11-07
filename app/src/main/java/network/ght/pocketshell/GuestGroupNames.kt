package network.ght.pocketshell

import java.io.File
import java.time.Instant

/** Guest startup wrapper and strict decoding of its small, per-group diagnostic records. */
internal object GuestGroupNames {
    fun wrap(command: Array<String>, script: String, build: Int): Array<String> {
        require(command.isNotEmpty())
        require(build > 0)
        return arrayOf("/bin/sh", "-c", script, "pocketshell-group-map", build.toString()) + command
    }

    data class Event(val at: String, val build: Int, val process: Long,
        val groupId: String, val state: String, val exitCode: Int)

    fun readEvents(root: File): List<Event> {
        val directory = File(root, "var/lib/pocketshell/group-map")
        if (!directory.exists()) return emptyList()
        val files = directory.listFiles() ?: throw java.io.IOException("Group diagnostics unavailable")
        return files.filter { it.isFile && it.name.matches(Regex("[0-9]+|process")) }
            .map { file -> requireNotNull(parse(file.readText())) { "Invalid group diagnostic record" } }
            .sortedWith(compareBy<Event> { it.at }.thenBy { it.groupId })
    }

    internal fun parse(record: String): Event? = runCatching {
        val fields = record.trimEnd('\n', '\r').split('\t')
        require(fields.size == 7 && fields[0] == "v1")
        Instant.parse(fields[1])
        val build = fields[2].toInt().also { require(it > 0) }
        val process = fields[3].toLong().also { require(it > 0) }
        require(fields[4].matches(Regex("[0-9]+|process")))
        require(fields[5] in setOf("registering", "registered", "failed", "enumeration_failed"))
        val code = fields[6].toInt().also { require(it in 0..255) }
        Event(fields[1], build, process, fields[4], fields[5], code)
    }.getOrNull()
}
