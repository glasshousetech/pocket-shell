package network.ght.pocketshell

import android.content.Context

object WorkspacePrefs {
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    fun fontSize(context: Context) = prefs(context).getInt("terminal_font_sp", 13).coerceIn(10,28)
    fun setFontSize(context: Context, value: Int) { prefs(context).edit().putInt("terminal_font_sp",value.coerceIn(10,28)).apply() }
    fun keepScreenOn(context: Context) = prefs(context).getBoolean("keep_screen_on",true)
    fun setKeepScreenOn(context: Context, value: Boolean) { prefs(context).edit().putBoolean("keep_screen_on",value).apply() }
    fun backgroundWork(context: Context) = prefs(context).getBoolean("background_work",true)
    fun setBackgroundWork(context: Context, value: Boolean) { prefs(context).edit().putBoolean("background_work",value).apply() }
    /** The workspace menu (tab sidebar on wide screens, tab and action rows on phones) is folded away. */
    fun workspaceCollapsed(context: Context) = prefs(context).getBoolean("workspace_collapsed",false)
    fun setWorkspaceCollapsed(context: Context, value: Boolean) { prefs(context).edit().putBoolean("workspace_collapsed",value).apply() }

    // Adjustable sizes. Every bound here has a layout reason, stated beside it.
    const val SIDEBAR_DEFAULT_DP = 220
    /** Narrower than this and the active tab's name has no room beside its two 48 dp buttons; the collapse button gives the slim rail instead. */
    const val SIDEBAR_MIN_DP = 160
    /** However wide the menu is dragged, the terminal keeps this much width so a prompt stays usable. */
    const val TERMINAL_MIN_DP = 240
    fun sidebarMaxDp(screenWidthDp: Int) = (screenWidthDp - TERMINAL_MIN_DP).coerceAtLeast(SIDEBAR_MIN_DP)
    /** Saved width of the open workspace menu on wide screens. Callers clamp it to the current screen with [sidebarMaxDp]. */
    fun sidebarWidth(context: Context) = prefs(context).getInt("workspace_sidebar_dp", SIDEBAR_DEFAULT_DP).coerceAtLeast(SIDEBAR_MIN_DP)
    fun setSidebarWidth(context: Context, value: Int) { prefs(context).edit().putInt("workspace_sidebar_dp", value.coerceAtLeast(SIDEBAR_MIN_DP)).apply() }

    const val KEY_SIZE_DEFAULT_DP = 48
    /** Below 32 dp a fingertip covers neighbouring keys; above 96 dp two keys no longer fit across a folded phone. */
    const val KEY_SIZE_MIN_DP = 32
    const val KEY_SIZE_MAX_DP = 96
    /** Side length of the keys in the collapsible toolbar. */
    fun toolbarKeySize(context: Context) = prefs(context).getInt("toolbar_key_dp", KEY_SIZE_DEFAULT_DP).coerceIn(KEY_SIZE_MIN_DP, KEY_SIZE_MAX_DP)
    fun setToolbarKeySize(context: Context, value: Int) { prefs(context).edit().putInt("toolbar_key_dp", value.coerceIn(KEY_SIZE_MIN_DP, KEY_SIZE_MAX_DP)).apply() }
    /** Whether the collapsible toolbar was left open. It now holds Actions and Text / voice, so the choice is remembered. */
    fun toolbarOpen(context: Context) = prefs(context).getBoolean("toolbar_open", true)
    fun setToolbarOpen(context: Context, value: Boolean) { prefs(context).edit().putBoolean("toolbar_open", value).apply() }
}
