package network.ght.pocketshell

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** A normal keyboard with literal case, composition and reviewed command editing. */
class CommandEditText(context: Context) : EditText(context) {
    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = null
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        gravity = Gravity.TOP or Gravity.START
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && hasFocus()) {
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val original = super.onCreateInputConnection(outAttrs) ?: return null
        // The editor still wraps and retains pasted line breaks. Its IME contract is
        // one command: SwiftKey 9.13 capitalizes empty multiline fields despite caps=0.
        outAttrs.inputType = outAttrs.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv()
        outAttrs.initialCapsMode = 0
        DiagnosticLog.record(context, "input", "literal_editor_connection")
        return object : InputConnectionWrapper(original, false) {
            override fun getCursorCapsMode(reqModes: Int): Int = 0
            // SwiftKey still re-capitalizes the word it is composing when it commits it.
            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
                super.setComposingText(keepTypedCase(text), newCursorPosition)
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
                super.commitText(keepTypedCase(text), newCursorPosition)
        }
    }

    private var caseKeptLogged = false

    /** A keyboard replacing its composing word may not change the case of letters already typed. */
    private fun keepTypedCase(text: CharSequence): CharSequence {
        val content = editableText ?: return text
        val a = BaseInputConnection.getComposingSpanStart(content)
        val b = BaseInputConnection.getComposingSpanEnd(content)
        if (a < 0 || b < 0 || a == b) return text
        val kept = TypedCase.keep(content.subSequence(minOf(a, b), maxOf(a, b)), text)
        if (kept.contentEquals(text)) return text
        if (!caseKeptLogged) {
            caseKeptLogged = true
            DiagnosticLog.record(context, "input", "keyboard_case_kept")
        }
        return kept
    }
}
