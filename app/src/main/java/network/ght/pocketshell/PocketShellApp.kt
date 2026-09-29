package network.ght.pocketshell

import android.app.Application

class PocketShellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        com.termux.terminal.TerminalColors.COLOR_SCHEME.updateWith(TermThemes.saved(this).toProperties())
    }
}
