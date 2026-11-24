package network.ght.pocketshell

/**
 * Bounded, line-oriented buffer that keeps the most recent guest output.
 * Older lines are dropped once [maxChars] is exceeded so the error surfaced to
 * the user (and to logs) is the end of the run, where apt/dpkg report why they
 * stopped, rather than the first screenful of progress noise.
 */
class GuestOutputTail(private val maxChars: Int) {
    private val lines = ArrayDeque<String>()
    private var chars = 0

    @Synchronized
    fun append(line: String) {
        lines.addLast(line)
        chars += line.length + 1
        while (chars > maxChars && lines.size > 1) {
            chars -= lines.removeFirst().length + 1
        }
    }

    /** Return pipe failures to the command owner; an interrupted reader must not crash the app. */
    fun drain(input: java.io.InputStream): java.io.IOException? = try {
        input.bufferedReader().useLines { lines -> lines.forEach(::append) }
        null
    } catch (failure: java.io.IOException) {
        failure
    }

    @Synchronized
    override fun toString(): String =
        if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
}
