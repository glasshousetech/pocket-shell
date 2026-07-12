package network.ght.pocketshell

import android.content.Context
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import java.util.Properties

/**
 * A named 16-color ANSI terminal palette (+ default foreground/background/
 * cursor). Applied to the shared termux-app [TerminalColorScheme] and pushed
 * into every live session's [com.termux.terminal.TerminalEmulator.mColors].
 *
 * This only changes the *text* palette (ls colors, prompts, vim, htop, …) —
 * the app's frosted-glass backdrop (see ui/Theme.kt) is untouched, since
 * TerminalRenderer never paints a full-canvas fill for the "default"
 * background; it only draws a rect for cells whose background explicitly
 * differs from the current default (see TerminalRenderer#renderTextRun).
 */
data class TermTheme(
    val id: String,
    val label: String,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    /** 16 entries: color0 (black) .. color15 (bright white), real ANSI values. */
    val ansi: List<Long>,
) {
    fun toProperties(): Properties {
        val p = Properties()
        p.setProperty("background", hex(background))
        p.setProperty("foreground", hex(foreground))
        p.setProperty("cursor", hex(cursor))
        ansi.forEachIndexed { i, c -> p.setProperty("color$i", hex(c)) }
        return p
    }

    private fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)
}

object TermThemes {

    // Matches the termux-app library's own baked-in default xterm-based
    // palette (TerminalColorScheme.DEFAULT_COLORSCHEME) — i.e. "no theme
    // applied" against the Kali-style frosted-glass chrome. Kept explicit
    // (rather than just "don't call updateWith") so it can be re-selected
    // after trying another theme via the same code path.
    val DEFAULT = TermTheme(
        id = "default", label = "Pocket Shell (Kali)",
        background = 0x000000, foreground = 0xFFFFFF, cursor = 0xFFFFFF,
        ansi = listOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x6495ED, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        ),
    )

    // Official Dracula terminal palette (draculatheme.com/terminal).
    val DRACULA = TermTheme(
        id = "dracula", label = "Dracula",
        background = 0x282A36, foreground = 0xF8F8F2, cursor = 0xF8F8F0,
        ansi = listOf(
            0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
            0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
        ),
    )

    // Official Solarized Dark ANSI mapping (ethanschoonover.com/solarized).
    val SOLARIZED_DARK = TermTheme(
        id = "solarized_dark", label = "Solarized Dark",
        background = 0x002B36, foreground = 0x839496, cursor = 0x839496,
        ansi = listOf(
            0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
            0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
        ),
    )

    // Official Nord terminal palette (nordtheme.com/docs/terminal).
    val NORD = TermTheme(
        id = "nord", label = "Nord",
        background = 0x2E3440, foreground = 0xD8DEE9, cursor = 0xD8DEE9,
        ansi = listOf(
            0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
            0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4,
        ),
    )

    // Standard Gruvbox Dark terminal palette (morhetz/gruvbox).
    val GRUVBOX_DARK = TermTheme(
        id = "gruvbox_dark", label = "Gruvbox Dark",
        background = 0x282828, foreground = 0xEBDBB2, cursor = 0xEBDBB2,
        ansi = listOf(
            0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
            0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2,
        ),
    )

    val ALL: List<TermTheme> = listOf(DEFAULT, DRACULA, SOLARIZED_DARK, NORD, GRUVBOX_DARK)

    fun byId(id: String): TermTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    private const val PREFS = "pocketshell_prefs"
    private const val KEY_THEME = "terminal_theme"

    /** Theme is not secret — plain SharedPreferences, unlike Secrets.kt's API key. */
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saved(context: Context): TermTheme = byId(prefs(context).getString(KEY_THEME, DEFAULT.id) ?: DEFAULT.id)

    fun save(context: Context, theme: TermTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.id).apply()
    }

    /** Applies [theme] to the shared color scheme and every live session, then redraws. */
    fun apply(theme: TermTheme, sessions: List<TerminalSession>, redraw: () -> Unit) {
        TerminalColors.COLOR_SCHEME.updateWith(theme.toProperties())
        sessions.forEach { it.emulator?.mColors?.reset() }
        redraw()
    }
}
