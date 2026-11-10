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
                assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                assertEquals(0, info.initialCapsMode)
                assertNotEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN)
                assertEquals(0, connection.getCursorCapsMode(caps))
                assertNotEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
                assertEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
                connection.closeConnection()
            }
        }
    }

    @Test fun reconnectingKeyboardPreservesExactDraftSelectionAndExplicitCase() {
        instrumentation.runOnMainSync {
            val editor = CommandEditText(instrumentation.targetContext)
            val draft = "printf 'MiXeD café 🚀'"
            editor.setText(draft)
            editor.setSelection(8, 13)
            repeat(3) {
                assertEquals(draft, editor.text.toString())
                assertEquals(8, editor.selectionStart)
                assertEquals(13, editor.selectionEnd)
                assertNull(editor.transformationMethod)
                val info = EditorInfo()
                val connection = editor.onCreateInputConnection(info)!!
                assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL,
                    info.inputType and InputType.TYPE_MASK_VARIATION)
                assertEquals(0, info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
                connection.closeConnection()
            }
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.commitText("UPPER", 1))
            assertEquals("printf 'UPPER café 🚀'", editor.text.toString())
            connection.closeConnection()
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
