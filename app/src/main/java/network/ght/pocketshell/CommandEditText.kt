package network.ght.pocketshell

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/** Literal command text, with normal Unicode composition and keyboard dictation. */
class CommandEditText(context: Context) : EditText(context) {
    init {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        gravity = Gravity.TOP or Gravity.START
        setTextIsSelectable(false)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val original = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.initialCapsMode = 0
        DiagnosticLog.record(context, "input", "literal_editor_connection")
        return object : InputConnectionWrapper(original, false) {
            // IMEs can ask for sentence caps even when inputType requests none.
            // Answer for this command editor; never rewrite committed characters.
            override fun getCursorCapsMode(reqModes: Int): Int = 0
        }
    }
}
