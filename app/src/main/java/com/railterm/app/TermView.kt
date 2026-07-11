package com.railterm.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/** App-wide clipboard access; initialized once from the Activity. */
object Clip {
    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }

    private fun manager(): ClipboardManager? =
        appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun copy(text: String) {
        if (text.isEmpty()) return
        manager()?.setPrimaryClip(ClipData.newPlainText("railterm", text))
    }

    fun paste(): String =
        manager()?.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()
}

/**
 * Drives one TerminalView: input focus, pinch-to-zoom font sizing, and the
 * modifier-key contract the engine reads. Sticky Ctrl/Alt let the extra-keys
 * row compose combos (press CTRL, then a letter) without a hardware keyboard.
 */
class RailViewClient(
    private val context: Context,
    private val defaultFontPx: Int,
) : TerminalViewClient {

    var view: TerminalView? = null

    private var fontSizePx: Int = defaultFontPx
    private val minFontPx = (defaultFontPx * 0.55f).roundToInt()
    private val maxFontPx = (defaultFontPx * 2.2f).roundToInt()

    /** Sticky modifiers toggled by the extra-keys row. Consumed after one key. */
    var ctrlDown = false
    var altDown = false
    var shiftDown = false
    var fnDown = false

    fun clearStickyModifiers() {
        ctrlDown = false; altDown = false; shiftDown = false; fnDown = false
    }

    fun showKeyboard() {
        val v = view ?: return
        v.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onScale(scale: Float): Float {
        // Pinch outside a small dead-zone re-sizes the font, Termux-style.
        if (scale < 0.9f || scale > 1.1f) {
            val newSize = (fontSizePx * scale).roundToInt().coerceIn(minFontPx, maxFontPx)
            if (newSize != fontSizePx) {
                fontSizePx = newSize
                view?.setTextSize(newSize)
            }
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true

    // terminal-view drives the entire selection UX itself once we don't block
    // it (see onLongPress below): long-press expands to a word, drag handles
    // extend/shrink the range, and a floating ActionMode toolbar with
    // Copy/Paste/More appears automatically. We only add a one-time nudge the
    // very first time a selection starts, since the ActionMode toolbar isn't
    // obvious on a first run.
    override fun copyModeChanged(copyMode: Boolean) {
        if (!copyMode) return
        val prefs = context.getSharedPreferences("railterm_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEN_SELECTION_HINT, false)) return
        prefs.edit().putBoolean(SEEN_SELECTION_HINT, true).apply()
        Toast.makeText(context, "Selected — drag the handles to adjust, then tap Copy", Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    // Returning false lets TerminalView run its own default long-press
    // handling (TerminalView#onLongPress -> startTextSelectionMode), which is
    // where word-selection + handles + the Copy toolbar come from. Returning
    // true here would suppress all of that, so we deliberately don't.
    override fun onLongPress(e: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = ctrlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = shiftDown
    override fun readFnKey(): Boolean = fnDown

    override fun onCodePoint(codePoint: Int, ctrlDownFromEvent: Boolean, session: TerminalSession): Boolean {
        // A real character was committed; a one-shot sticky modifier is now spent.
        if (ctrlDown || altDown || shiftDown || fnDown) clearStickyModifiers()
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    private companion object {
        const val TAG = "Railterm"
        const val SEEN_SELECTION_HINT = "seen_selection_hint"
    }
}
