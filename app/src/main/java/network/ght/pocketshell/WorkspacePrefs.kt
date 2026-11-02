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
}
