package network.ght.pocketshell

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.AtomicFile
import java.io.File
import java.net.InetAddress

/** Keep Linux on Android's actual DNS route, including Wi-Fi, cellular and VPN changes. */
object LinuxDns {
    private val registrationLock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private const val HEADER = "# Managed by Pocket Shell from the active Android network.\n"

    fun start(context: Context) = synchronized(registrationLock) {
        if (callback != null) return@synchronized
        val app = context.applicationContext
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return@synchronized
        val listener = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            override fun onAvailable(network: Network) { current = network }
            override fun onLost(network: Network) { if (current == network) current = null }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network != current) return
                // Use the supplied snapshot: querying LinkProperties from a callback can race.
                runCatching {
                    Distro.ALL.forEach { writeRoot(app, Userland.rootfsDir(app, it), properties.dnsServers) }
                }.onFailure { DiagnosticLog.record(app, "dns", "network_refresh_failed", it.javaClass.simpleName) }
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(listener)
            callback = listener
        }.onFailure { DiagnosticLog.record(app, "dns", "monitor_registration_failed", it.javaClass.simpleName) }
    }

    /** Refresh before provisioning or launching a new Linux process, including after an upgrade. */
    fun refresh(context: Context, root: File? = null) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val addresses = runCatching {
            manager?.activeNetwork?.let { manager.getLinkProperties(it)?.dnsServers }.orEmpty()
        }.onFailure { DiagnosticLog.record(context, "dns", "network_snapshot_failed", it.javaClass.simpleName) }
            .getOrDefault(emptyList())
        if (root != null) writeRoot(context, root, addresses)
        else Distro.ALL.forEach { writeRoot(context, Userland.rootfsDir(context, it), addresses) }
    }

    internal fun writeRoot(context: Context, root: File, addresses: List<InetAddress>) =
        synchronized(SshKeyStore.identityLock) {
            val directory = File(root, "etc")
            // An offline callback must never recreate a Linux environment the user removed.
            if (!directory.isDirectory) return@synchronized
            val file = File(directory, "resolv.conf")
            val existing = if (file.isFile) file.readText() else ""
            val servers = addresses.mapNotNull { it.hostAddress }.distinct()
            // Keep the last usable route while offline; the next network callback refreshes it.
            if (servers.isEmpty() && existing.lineSequence().any { it.trimStart().startsWith("nameserver ") })
                return@synchronized
            val selected = servers.ifEmpty { listOf("8.8.8.8", "8.8.4.4") }
            val content = HEADER + selected.joinToString("", transform = { "nameserver $it\n" })
            if (existing == content) return@synchronized
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(content.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            DiagnosticLog.record(context, "dns", if (servers.isEmpty()) "fallback_configured" else "network_configured")
        }
}
