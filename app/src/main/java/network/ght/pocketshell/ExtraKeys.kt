package network.ght.pocketshell

import android.content.Context

/**
 * One chip in the extra-keys row. [literal] keys just type a character; the
 * rest map to key events (ESC/TAB/arrows/…), open the CTRL/ALT combo menus, or
 * run a terminal tool (history paging, Actions, Text / voice) — the wiring
 * lives in MainActivity's ExtraKeysRow.
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
 * Presets include one-shot modifiers. Explicitly customized rows keep their saved order.
 */
object ExtraKeysLayouts {

    // Key catalog. Preserve the established order when adding controls.
    val ESC = ExtraKey("esc", "ESC")
    val TAB = ExtraKey("tab", "TAB")
    val CTRL = ExtraKey("ctrl", "CTRL")
    val ALT = ExtraKey("alt", "ALT")
    val SHIFT = ExtraKey("shift", "SHIFT")
    val LEFT = ExtraKey("left", "←")
    val DOWN = ExtraKey("down", "↓")
    val UP = ExtraKey("up", "↑")
    val RIGHT = ExtraKey("right", "→")
    val HOME = ExtraKey("home", "HOME")
    val END = ExtraKey("end", "END")
    val PGUP = ExtraKey("pgup", "PGUP")
    val PGDN = ExtraKey("pgdn", "PGDN")
    val DEL = ExtraKey("del", "DEL")
    val ENTER = ExtraKey("enter", "ENTER")

    // Terminal tools. These were a fixed row above the keys; they now sit at the end of the
    // collapsible row, where they can be reordered or removed like any other key.
    val HISTORY_UP = ExtraKey("history_up", "History ↑")
    val HISTORY_DOWN = ExtraKey("history_down", "History ↓")
    val LIVE = ExtraKey("live", "Live")
    val ACTIONS = ExtraKey("actions", "Actions")
    val TEXT_VOICE = ExtraKey("text_voice", "Text / voice")
    val TOOLS: List<ExtraKey> = listOf(HISTORY_UP, HISTORY_DOWN, LIVE, ACTIONS, TEXT_VOICE)

    /** Every key that can appear in the row, in default order. */
    val ALL: List<ExtraKey> = listOf(
        ESC, TAB, ENTER, CTRL, ALT, SHIFT,
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
    ) + TOOLS

    data class Preset(val id: String, val label: String, val blurb: String, val keys: List<ExtraKey>)

    /** Full row, including one-shot CTRL / ALT / SHIFT. */
    val DEFAULT = Preset("default", "Full (default)", "Everything: nav, paging, and shell punctuation.", ALL)

    /** Just the essentials — ESC/TAB, modifiers, arrows. */
    val MINIMAL = Preset(
        "minimal", "Minimal", "ESC, TAB, ENTER, CTRL, ALT, SHIFT, and arrows only.",
        listOf(ESC, TAB, ENTER, CTRL, ALT, SHIFT, LEFT, DOWN, UP, RIGHT) + TOOLS,
    )

    /** Tuned for ssh + tmux: scrollback paging and the keys remote shells lean on. */
    val SSH_TMUX = Preset(
        "ssh_tmux", "SSH & tmux", "Nav + paging, pipe, tilde, dash, slash.",
        listOf(
            ESC, TAB, ENTER, CTRL, ALT, SHIFT,
            LEFT, DOWN, UP, RIGHT,
            PGUP, PGDN,
            ALL.first { it.id == "pipe" },
            ALL.first { it.id == "tilde" },
            ALL.first { it.id == "dash" },
            ALL.first { it.id == "slash" },
        ) + TOOLS,
    )

    val PRESETS: List<Preset> = listOf(DEFAULT, MINIMAL, SSH_TMUX)

    private const val PREFS = "pocketshell_prefs"
    private const val KEY_PRESET = "extra_keys_preset"
    private const val KEY_CUSTOM = "extra_keys_custom"
    private const val KEY_ENTER_ADDED = "extra_keys_enter_added"
    private const val KEY_TOOLS_ADDED = "extra_keys_tools_added"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The row to render: the user's customized order if present, else the
     * saved preset, else [DEFAULT]. Unknown/stale ids in storage are dropped.
     */
    fun saved(context: Context): List<ExtraKey> {
        val p = prefs(context)
        decode(p.getString(KEY_CUSTOM, null))?.let { custom ->
            // ENTER and the terminal tools arrived after rows could be customized. Add each once
            // (ENTER next to TAB, the tools at the very end); removing them later sticks.
            val enterDone = p.getBoolean(KEY_ENTER_ADDED, false)
            val toolsDone = p.getBoolean(KEY_TOOLS_ADDED, false)
            if (enterDone && toolsDone) return custom
            val upgraded = (if (enterDone) custom else withEnter(custom)).let { if (toolsDone) it else withTools(it) }
            p.edit().putString(KEY_CUSTOM, encode(upgraded))
                .putBoolean(KEY_ENTER_ADDED, true).putBoolean(KEY_TOOLS_ADDED, true).apply()
            return upgraded
        }
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
        prefs(context).edit().putString(KEY_CUSTOM, encode(keys))
            .putBoolean(KEY_ENTER_ADDED, true).putBoolean(KEY_TOOLS_ADDED, true).apply()
    }

    /** [keys] with ENTER after TAB (or after ESC, else first) unless it is already there. */
    internal fun withEnter(keys: List<ExtraKey>): List<ExtraKey> {
        if (ENTER in keys) return keys
        val anchor = keys.indexOf(TAB).takeIf { it >= 0 } ?: keys.indexOf(ESC)
        return keys.toMutableList().apply { add(anchor + 1, ENTER) }
    }

    /** [keys] followed by any terminal tool it does not already hold, in catalog order. */
    internal fun withTools(keys: List<ExtraKey>): List<ExtraKey> = keys + TOOLS.filter { it !in keys }

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
