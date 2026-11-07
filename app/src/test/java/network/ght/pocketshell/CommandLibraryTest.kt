package network.ght.pocketshell

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CommandLibraryTest {
    @Test fun parametersBecomeLiteralShellArguments() {
        val values = listOf("hello world", "it's literal", "$(printf injected)", "; echo injected", "", "日本語 🌙", "-n")
        values.forEach { value ->
            val command = CommandTemplate.render("printf '%s' {{value}}", mapOf("value" to value))
            // Exercise the UTF-8 input path used by a terminal. A C-locale JVM
            // cannot encode Unicode OS argv even though shell input supports it.
            val process = ProcessBuilder("/bin/sh").start()
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(command + "\n") }
            assertEquals(value, process.inputStream.bufferedReader(Charsets.UTF_8).readText())
            assertEquals(0, process.waitFor())
        }
    }

    @Test fun repeatedParametersStayConsistentAndQuoted() {
        assertEquals(listOf("name", "path"), CommandTemplate.fields("printf '%s %s %s' {{name}} {{path}} {{name}}"))
        assertEquals("echo 'a' 'a'", CommandTemplate.render("echo {{name}} {{name}}", mapOf("name" to "a")))
    }

    @Test fun embeddedQuoteContextsAndMalformedFieldsAreRejectedBeforeInsertion() {
        listOf("echo '{{name}}'", "echo \"{{name}}\"", "echo \\{{name}}", "echo {{bad name}}", "echo {{missing", "echo }}", "echo one\necho two")
            .forEach { template -> assertThrows(IllegalArgumentException::class.java) { CommandTemplate.fields(template) } }
        assertThrows(IllegalArgumentException::class.java) { CommandTemplate.render("echo {{value}}", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { CommandTemplate.render("echo {{value}}", mapOf("value" to "one\rtwo")) }
    }

    @Test fun ordinaryShellSyntaxIsPreserved() {
        val template = "printf '%s' {{path}} | sed 's/a/b/' && echo done"
        assertEquals("printf '%s' '/tmp/a b' | sed 's/a/b/' && echo done",
            CommandTemplate.render(template, mapOf("path" to "/tmp/a b")))
    }

    @Test fun searchReturnsEveryMatchAndKeepsOriginalText() {
        val lines = List(350) { "alpha ALPHA 日本語" }
        val all = OutputSearch.find(lines, "alpha")
        assertEquals(700, all.size)
        assertEquals(349, all.last().line)
        assertEquals("ALPHA", all.last().text.substring(all.last().start, all.last().end))
        assertEquals(350, OutputSearch.find(lines, "alpha", true).size)
        assertEquals(350, OutputSearch.find(lines, "日本語").size)
        assertTrue(OutputSearch.find(lines, "").isEmpty())
        assertTrue(OutputSearch.find(lines, "not present").isEmpty())
    }
}
