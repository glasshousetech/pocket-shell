package network.ght.pocketshell

import android.content.Context
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import java.util.Properties

/** Platform-neutral RGB roles shared with the other GHT programs. */
data class ThemeUiColors(
    val surface: Long, val surfaceAlt: Long, val control: Long,
    val text: Long, val muted: Long, val accent: Long, val onAccent: Long,
    val border: Long, val error: Long, val success: Long, val warning: Long, val info: Long,
)

/** A complete app palette with a matching 16-color ANSI terminal palette. */
data class TermTheme(
    val id: String,
    val label: String,
    val description: String,
    val dark: Boolean,
    val tags: List<String>,
    val background: Long,
    val foreground: Long,
    val cursor: Long,
    val ansi: List<Long>,
    val ui: ThemeUiColors,
) {
    val oled: Boolean get() = dark && background == 0L

    fun toProperties(): Properties = Properties().apply {
        setProperty("background", hex(background))
        setProperty("foreground", hex(foreground))
        setProperty("cursor", hex(cursor))
        ansi.forEachIndexed { i, c -> setProperty("color$i", hex(c)) }
    }

    fun matches(query: String): Boolean = query.trim().let { q ->
        q.isEmpty() || listOf(label, description, tags.joinToString(" ")).any { it.contains(q, ignoreCase = true) }
    }

    private fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)
}

object TermThemes {
    val ALL: List<TermTheme> = ThemeCatalog.all
    // Keep historical preference IDs and terminal ANSI values.
    val DEFAULT: TermTheme = ALL.first { it.id == "default" }
    fun byId(id: String): TermTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    private const val PREFS = "pocketshell_prefs"
    private const val KEY_THEME = "terminal_theme"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun saved(context: Context): TermTheme = byId(prefs(context).getString(KEY_THEME, DEFAULT.id) ?: DEFAULT.id)
    fun save(context: Context, theme: TermTheme) {
        prefs(context).edit().putString(KEY_THEME, theme.id).apply()
    }

    /** Recolor existing emulators in place; never reconnect or replace a PTY. */
    fun apply(theme: TermTheme, sessions: List<TerminalSession>, redraw: () -> Unit) {
        TerminalColors.COLOR_SCHEME.updateWith(theme.toProperties())
        sessions.forEach { it.emulator?.mColors?.reset() }
        redraw()
    }
}
