package network.ght.pocketshell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import network.ght.pocketshell.term.TerminalView
import network.ght.pocketshell.term.TerminalViewClient
import kotlin.math.roundToInt

/**
 * How much of the soft keyboard the terminal asks for.
 *
 * Upstream Termux declares the terminal as a password-style field so no IME ever autocorrects a
 * command. Every mainstream keyboard — Microsoft SwiftKey, Gboard, Samsung — reacts by hiding its
 * whole toolbar row, and that row is where voice typing, the clipboard and emoji live. Full mode
 * declares an ordinary text field with suggestions switched off instead: the row comes back and
 * autocorrect stays off. Terminal-safe mode restores the upstream behaviour for anyone whose
 * keyboard misbehaves in a text field.
 */
object KeyboardPrefs {
    private const val KEY_FULL_IME = "full_ime_features"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun fullImeFeatures(context: Context): Boolean = prefs(context).getBoolean(KEY_FULL_IME, true)

    fun setFullImeFeatures(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FULL_IME, value).apply()
    }
}

internal const val PREFS_FILE = "pocketshell_prefs"

/** App-wide clipboard access; initialized once from the Activity. */
object Clip {
    private var appContext: Context? = null
    fun init(context: Context) { appContext = context.applicationContext }

    private fun manager(): ClipboardManager? =
        appContext?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun copy(text: String) {
        if (text.isEmpty()) return
        manager()?.setPrimaryClip(ClipData.newPlainText("pocketshell", text))
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
    var ctrlDown by mutableStateOf(false)
    var altDown by mutableStateOf(false)
    var shiftDown by mutableStateOf(false)
    var fnDown by mutableStateOf(false)

    fun clearStickyModifiers() {
        ctrlDown = false; altDown = false; shiftDown = false; fnDown = false
    }

    fun toggleCtrl() { ctrlDown = !ctrlDown }
    fun toggleAlt() { altDown = !altDown }
    fun toggleShift() { shiftDown = !shiftDown }

    fun sendKey(keyCode: Int): Boolean {
        val v = view ?: return false
        if (v.currentSession?.emulator == null) return false
        var modifiers = 0
        if (ctrlDown) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (altDown) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (shiftDown) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        val handled = TerminalInteraction.sendKey(v, keyCode, modifiers)
        if (handled) {
            clearStickyModifiers()
            TerminalInteraction.followOutput(v)
        }
        return handled
    }

    private var keyboardFocusListener: ViewTreeObserver.OnWindowFocusChangeListener? = null

    private fun clearKeyboardFocusRequest() {
        keyboardFocusListener?.let { listener ->
            view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(listener)
        }
        keyboardFocusListener = null
    }

    /**
     * Re-negotiate the IME contract after the keyboard-mode setting changes, so the keyboard
     * already on screen picks it up instead of only the next one.
     */
    fun refreshIme() {
        view?.refreshImeConfiguration()
    }

    fun showKeyboard() {
        val v = view ?: return
        clearKeyboardFocusRequest()
        v.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        fun showAfterFocusDispatch() {
            // Android connects the IME after dispatching window-focus listeners.
            v.post {
                if (v.isAttachedToWindow && v.hasFocus() && v.hasWindowFocus()) {
                    imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        if (v.hasWindowFocus()) {
            showAfterFocusDispatch()
        } else {
            // A dialog still owns the window during its Insert/Close callback.
            // Show the keyboard when Android returns focus, not after an arbitrary delay.
            keyboardFocusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                if (hasFocus) {
                    clearKeyboardFocusRequest()
                    showAfterFocusDispatch()
                }
            }
            v.viewTreeObserver.addOnWindowFocusChangeListener(keyboardFocusListener)
        }
    }

    fun hideKeyboard() {
        clearKeyboardFocusRequest()
        val v = view ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
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

    // Read live rather than cached: the setting can change while a session is open, and
    // TerminalView asks again on every restartInput().
    override fun shouldAllowFullImeFeatures(): Boolean = KeyboardPrefs.fullImeFeatures(context)
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
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(SEEN_SELECTION_HINT, false)) return
        prefs.edit().putBoolean(SEEN_SELECTION_HINT, true).apply()
        Toast.makeText(context, "Selected — drag the handles to adjust, then tap Copy", Toast.LENGTH_LONG).show()
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        val v = view ?: return false
        // Let the engine process hardware modifiers, AltGr, and character composition.
        // Its special-key path does not call onCodePoint, so consume our one-shot
        // state here only when KeyHandler actually has an escape sequence.
        if (KeyEvent.isModifierKey(keyCode)) return false
        TerminalInteraction.followOutput(v)
        if (ctrlDown || altDown || shiftDown || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            var modifiers = 0
            if (ctrlDown || e.isCtrlPressed) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
            if (altDown || e.isAltPressed) modifiers = modifiers or KeyHandler.KEYMOD_ALT
            if (shiftDown || e.isShiftPressed) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
            if (e.isNumLockOn) modifiers = modifiers or KeyHandler.KEYMOD_NUM_LOCK
            if (!e.isFunctionPressed && TerminalInteraction.sendKey(v, keyCode, modifiers)) {
                clearStickyModifiers()
                return true
            }
        }
        return false
    }
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
        view?.let(TerminalInteraction::followOutput)
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
        const val TAG = "PocketShell"
        const val SEEN_SELECTION_HINT = "seen_selection_hint"
    }
}
