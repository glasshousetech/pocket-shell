package network.ght.pocketshell

import java.util.UUID

data class SavedCommand(val id: String = UUID.randomUUID().toString(), val name: String, val template: String)

/** Values are arguments, never pieces of shell syntax. Templates remain user-authored commands. */
object CommandTemplate {
    private val field = Regex("\\{\\{([A-Za-z_][A-Za-z0-9_]*)\\}\\}")

    fun fields(template: String): List<String> {
        require(template.isNotBlank()) { "Enter a command." }
        require(template.none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }) {
            "Use one command line. Separate commands with shell operators; Enter is sent only from the terminal."
        }
        var quote: Char? = null
        var escaped = false
        var index = 0
        val names = linkedSetOf<String>()
        while (index < template.length) {
            val match = field.matchAt(template, index)
            if (match != null) {
                require(quote == null && !escaped) { "Put parameters outside quotes: use {{name}}. Values are quoted for you." }
                names.add(match.groupValues[1])
                index = match.range.last + 1
                continue
            }
            val ch = template[index]
            if (escaped) escaped = false
            else if (ch == '\\' && quote != '\'') escaped = true
            else if (quote == ch) quote = null
            else if (quote == null && (ch == '\'' || ch == '"')) quote = ch
            if (template.startsWith("{{", index) || template.startsWith("}}", index)) {
                throw IllegalArgumentException("Use parameters such as {{service}}, with letters, numbers and underscores.")
            }
            index++
        }
        return names.toList()
    }

    fun render(template: String, values: Map<String, String>): String {
        val names = fields(template)
        require(names.all { values.containsKey(it) }) { "Fill in every parameter before inserting." }
        names.forEach { name ->
            require(values.getValue(name).none { Character.isISOControl(it) || it == '\u2028' || it == '\u2029' }) {
                "Parameter values cannot contain line breaks or control characters."
            }
        }
        return field.replace(template) { quoteArgument(values.getValue(it.groupValues[1])) }
    }

    fun validate(command: SavedCommand) {
        UUID.fromString(command.id)
        require(command.name.isNotBlank()) { "Give this command a name." }
        fields(command.template)
    }

    fun quoteArgument(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

data class OutputMatch(val line: Int, val text: String, val start: Int, val end: Int)

/** Searches an explicit snapshot. No terminal input, implicit logging, regex cost or result cap. */
object OutputSearch {
    fun find(lines: List<String>, query: String, matchCase: Boolean = false): List<OutputMatch> {
        if (query.isEmpty()) return emptyList()
        val matches = ArrayList<OutputMatch>()
        lines.forEachIndexed { line, text ->
            var from = 0
            while (from <= text.length - query.length) {
                val at = text.indexOf(query, from, ignoreCase = !matchCase)
                if (at < 0) break
                matches.add(OutputMatch(line, text, at, at + query.length))
                from = at + query.length
            }
        }
        return matches
    }
}
