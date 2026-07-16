package network.ght.pocketshell

import android.app.Application

class PocketShellApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
