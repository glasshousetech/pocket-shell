package network.ght.pocketshell

import android.content.Context

/**
 * One chip in the extra-keys row. [literal] keys just type a character; the
 * rest map to key events (ESC/TAB/arrows/…) or open the CTRL/ALT combo menus
 * — the wiring lives in MainActivity's ExtraKeysRow.
 */
data class ExtraKey(
    val id: String,
    val label: String,
    val literal: Char? = null,
)

/**
 * Configurable extra-keys layouts. Ships preset rows (the full default, a
 * minimal nav row, an SSH/tmux-oriented row) and lets the user reorder or
 * toggle individual keys; a customized row is persisted as an ordered,
 * comma-separated list of key ids in plain SharedPreferences (not secret),
 * same file as the terminal theme.
 *
 * [ExtraKeysLayouts.DEFAULT] is exactly the row that was hardcoded before
 * this setting existed, so nothing changes for existing users.
 */
object ExtraKeysLayouts {

    // Key catalog. The DEFAULT preset's order below is the historical
    // hardcoded order — do not reorder it.
    val ESC = ExtraKey("esc", "ESC")
    val TAB = ExtraKey("tab", "TAB")
    val CTRL = ExtraKey("ctrl", "CTRL")
    val ALT = ExtraKey("alt", "ALT")
    val LEFT = ExtraKey("left", "←")
    val DOWN = ExtraKey("down", "↓")
    val UP = ExtraKey("up", "↑")
    val RIGHT = ExtraKey("right", "→")
    val HOME = ExtraKey("home", "HOME")
    val END = ExtraKey("end", "END")
    val PGUP = ExtraKey("pgup", "PGUP")
    val PGDN = ExtraKey("pgdn", "PGDN")
    val DEL = ExtraKey("del", "DEL")

    /** Every key that can appear in the row, in default order. */
    val ALL: List<ExtraKey> = listOf(
        ESC, TAB, CTRL, ALT,
        LEFT, DOWN, UP, RIGHT,
        HOME, END, PGUP, PGDN, DEL,
        ExtraKey("dash", "-", '-'),
        ExtraKey("underscore", "_", '_'),
        ExtraKey("tilde", "~", '~'),
        ExtraKey("slash", "/", '/'),
        ExtraKey("pipe", "|", '|'),
        ExtraKey("colon", ":", ':'),
        ExtraKey("dot", ".", '.'),
        ExtraKey("star", "*", '*'),
        ExtraKey("equals", "=", '='),
        ExtraKey("dquote", "\"", '"'),
        ExtraKey("squote", "'", '\''),
        ExtraKey("dollar", "$", '$'),
    )

    data class Preset(val id: String, val label: String, val blurb: String, val keys: List<ExtraKey>)

    /** The original hardcoded row — the default for existing installs. */
    val DEFAULT = Preset("default", "Full (default)", "Everything: nav, paging, and shell punctuation.", ALL)

    /** Just the essentials — ESC/TAB, modifiers, arrows. */
    val MINIMAL = Preset(
        "minimal", "Minimal", "ESC, TAB, CTRL, ALT, and arrows only.",
        listOf(ESC, TAB, CTRL, ALT, LEFT, DOWN, UP, RIGHT),
    )

    /** Tuned for ssh + tmux: scrollback paging and the keys remote shells lean on. */
    val SSH_TMUX = Preset(
        "ssh_tmux", "SSH & tmux", "Nav + paging, pipe, tilde, dash, slash.",
        listOf(
            ESC, TAB, CTRL, ALT,
            LEFT, DOWN, UP, RIGHT,
            PGUP, PGDN,
            ALL.first { it.id == "pipe" },
            ALL.first { it.id == "tilde" },
            ALL.first { it.id == "dash" },
            ALL.first { it.id == "slash" },
        ),
    )

    val PRESETS: List<Preset> = listOf(DEFAULT, MINIMAL, SSH_TMUX)

    private const val PREFS = "pocketshell_prefs"
    private const val KEY_PRESET = "extra_keys_preset"
    private const val KEY_CUSTOM = "extra_keys_custom"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The row to render: the user's customized order if present, else the
     * saved preset, else [DEFAULT]. Unknown/stale ids in storage are dropped.
     */
    fun saved(context: Context): List<ExtraKey> {
        val p = prefs(context)
        decode(p.getString(KEY_CUSTOM, null))?.let { return it }
        val preset = PRESETS.firstOrNull { it.id == p.getString(KEY_PRESET, DEFAULT.id) } ?: DEFAULT
        return preset.keys
    }

    /** Selects a preset and clears any customization. */
    fun savePreset(context: Context, preset: Preset) {
        prefs(context).edit()
            .putString(KEY_PRESET, preset.id)
            .remove(KEY_CUSTOM)
            .apply()
    }

    /** Persists a user-reordered/toggled row. */
    fun saveCustom(context: Context, keys: List<ExtraKey>) {
        prefs(context).edit().putString(KEY_CUSTOM, encode(keys)).apply()
    }

    /** True when [keys] is exactly a preset's row (used to show the ✓). */
    fun matchesPreset(keys: List<ExtraKey>, preset: Preset): Boolean =
        keys.map { it.id } == preset.keys.map { it.id }

    internal fun encode(keys: List<ExtraKey>): String = keys.joinToString(",") { it.id }

    /** Parses a stored id list; null when absent, blank, or holding no known key. */
    internal fun decode(value: String?): List<ExtraKey>? {
        if (value.isNullOrBlank()) return null
        val keys = value.split(",").mapNotNull { id -> ALL.firstOrNull { it.id == id } }
        return keys.ifEmpty { null }
    }
}
