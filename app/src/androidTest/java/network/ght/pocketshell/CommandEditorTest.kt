package network.ght.pocketshell

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CommandEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun emptyAndSentenceBoundaryNeverRequestAutomaticCapitalization() {
        instrumentation.runOnMainSync {
            val editor = CommandEditText(instrumentation.targetContext)
            val caps = InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            for (text in listOf("", "first. ", "first\n", "echo ")) {
                editor.setText(text)
                editor.setSelection(text.length)
                val info = EditorInfo()
                val connection = editor.onCreateInputConnection(info)!!
                assertEquals(0, info.inputType and caps)
                assertEquals(0, info.initialCapsMode)
                assertEquals(0, connection.getCursorCapsMode(caps))
                assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
                connection.closeConnection()
            }
        }
    }

    @Test fun compositionCommitManualCaseUnicodeAndCursorEditingStayLiteral() {
        val expected = "echo 'MiXeD café 🚀'"
        instrumentation.runOnMainSync {
            val editor = CommandEditText(instrumentation.targetContext)
            editor.setText("")
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingText("echo 'MiXeD ca", 1))
            assertTrue(connection.setComposingText(expected, 1))
            assertTrue(connection.finishComposingText())
            assertEquals(expected, editor.text.toString())
            assertTrue(connection.setSelection(0, 4))
            assertTrue(connection.commitText("printf", 1))
            assertEquals("printf 'MiXeD café 🚀'", editor.text.toString())
            assertTrue(connection.commitText("\nNEXT", 1))
            assertTrue(editor.text.toString().contains("NEXT"))
            connection.closeConnection()
        }
        val logs = DiagnosticLog.read(instrumentation.targetContext)
        assertTrue(logs.contains("literal_editor_connection"))
        assertFalse(logs.contains(expected))
    }
}
