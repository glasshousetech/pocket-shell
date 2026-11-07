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

/** Command typing and composed/voice input have different keyboard contracts. */
class CommandEditText(context: Context) : EditText(context) {
    var voiceMode: Boolean = false
        private set

    init {
        inputType = inputFlags(false)
        transformationMethod = null
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        gravity = Gravity.TOP or Gravity.START
    }

    private fun inputFlags(voice: Boolean): Int = InputType.TYPE_CLASS_TEXT or
        InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
        if (voice) InputType.TYPE_TEXT_VARIATION_NORMAL else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

    fun setVoiceMode(enabled: Boolean) {
        if (voiceMode == enabled) return
        // Keep the reviewed draft and cursor while ending the previous IME composition.
        BaseInputConnection.removeComposingSpans(text)
        voiceMode = enabled
        setRawInputType(inputFlags(enabled))
        transformationMethod = null
        if (isAttachedToWindow && hasFocus()) {
            context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }
        DiagnosticLog.record(context, "input", if (enabled) "editor_voice_mode" else "editor_code_mode")
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
        }
    }
}
